(ns eden.mcp.simulator
  (:require [eden.pipeline :as pipeline]
            [eden.loader :as loader]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [markdown.core :as md]))

(defn create-injection-step
  "Creates a pipeline step that injects LLM content after load"
  [llm-changes]
  (fn [ctx]
    (let [loaded-data (get-in ctx [:results :load])
          modified-data (reduce
                         (fn [data {:keys [path content]}]
                           (try
                             (let [parts (str/split path #"/")
                                   lang (keyword (first parts))
                                   filename (last parts)
                                   content-key (keyword (str/replace filename #"\.(edn|md)$" ""))
                                   ;; Parse the content based on file type
                                   parsed-content (cond
                                                    (str/ends-with? path ".edn")
                                                    (edn/read-string content)

                                                    (str/ends-with? path ".md")
                                                    (let [parsed (loader/parse-markdown content)]
                                                      (-> parsed
                                                          (assoc :content/html
                                                                 (md/md-to-html-string
                                                                  (:markdown/content parsed)))
                                                          (dissoc :markdown/content)))

                                                    :else
                                                    {:content/html content})
                                   ;; Ensure required fields
                                   final-content (merge {:template :home
                                                         :title "Untitled"
                                                         :slug ""
                                                         :content-key content-key}
                                                        parsed-content)]
                               (assoc-in data [:content lang content-key] final-content))
                             (catch Exception e
                               (throw (ex-info (str "Failed to parse content for " path)
                                               {:path path
                                                :error (.getMessage e)}
                                               e)))))
                         loaded-data
                         llm-changes)]
      ;; Return the modified data directly (no :result wrapper)
      modified-data)))

(defn simulate-build
  "Simulate a build with injected LLM changes"
  [{:keys [site-edn output-dir path content]}]
  (let [warnings-atom (atom [])
        warn! (fn [category message]
                (swap! warnings-atom conj {:category category :message message}))]
    (try
      (let [initial-ctx {:site-edn site-edn
                         :output-dir (or output-dir "dist")
                         :mode :dev
                         :warnings {}
                         :timings {}
                         :results {}
                         :warn! warn!}
            ;; Run normal load step
            ctx-after-load (pipeline/run-step initial-ctx pipeline/load-step :load)
            ;; Inject LLM changes by modifying the loaded data directly
            injection-step (create-injection-step [{:path path :content content}])
            modified-load-data (injection-step ctx-after-load)
            ;; Parse the path to get content-key
            parts (str/split path #"/")
            lang (keyword (first parts))
            filename (last parts)
            content-key (keyword (str/replace filename #"\.(edn|md)$" ""))
            ;; Add content-key to render-roots so it gets built
            ;; The config is IN the modified-load-data, not above it
            modified-with-roots (if (get-in modified-load-data [:content lang content-key])
                                  (update-in modified-load-data [:config :render-roots]
                                             (fnil conj #{}) content-key)
                                  modified-load-data)
            ;; Replace the load result with our modified version
            ctx-with-changes (assoc-in ctx-after-load [:results :load] modified-with-roots)
            ;; Continue with rest of pipeline
            final-ctx (-> ctx-with-changes
                          (pipeline/run-step pipeline/build-html-step :build-html)
                          (pipeline/run-step pipeline/process-images-step :process-images)
                          (pipeline/run-step pipeline/bundle-assets-step :bundle-assets))]

        ;; Extract results - collect ALL warnings from context
        {:success? true
         :html-files (get-in final-ctx [:results :build-html :html-files])
         :warnings (vec (distinct (concat
                                   (map :message @warnings-atom)
                                   (map str (mapcat (fn [[_type warns]]
                                                      (if (sequential? warns)
                                                        warns
                                                        [warns]))
                                                    (:warnings final-ctx))))))
         :performance (:timings final-ctx)})

      (catch Exception e
        {:success? false
         :error (.getMessage e)
         :exception e}))))

(defn simulate-content-change
  "Simulates a content change and returns validation result with HTML preview"
  [{:keys [site-edn path content]}]
  (let [result (simulate-build {:site-edn site-edn :path path :content content})]
    (if (:success? result)
      (let [;; Parse path to find the right HTML file
            parts (str/split path #"/")
            lang (keyword (first parts))
            filename (last parts)
            content-key (keyword (str/replace filename #"\.(edn|md)$" ""))
            ;; Find the matching HTML
            matching-html (first (filter #(and (= (:lang-code %) lang)
                                               (= (:content-key %) content-key))
                                         (:html-files result)))]
        {:success? true
         :html (:html matching-html)
         :warnings (mapv str (:warnings result))
         :performance (:performance result)})
      result)))