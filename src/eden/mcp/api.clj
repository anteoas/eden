(ns eden.mcp.api
  "High-level API for MCP operations.
   Shared between MCP handlers to avoid duplication."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [eden.loader :as loader]
            [eden.pipeline :as pipeline]
            [eden.site-generator]
            [replicant.string]))

;; Content Operations

(defn list-content
  "List all content files with metadata.
   Options:
   - :language - filter by language code
   - :type - filter by content type
   - :template - filter by template name"
  [{:keys [site-edn output-dir]} {:keys [language type template]}]
  (let [;; Load all site data including content
        site-data (loader/load-site-data site-edn (or output-dir "dist"))
        all-content (:content site-data)]
    (->> all-content
         (mapcat (fn [[lang-code lang-content]]
                   (map (fn [[content-key content-data]]
                          {:path (str (name lang-code) "/"
                                      (str/replace (name content-key) #"\." "/")
                                      ".md")
                           :language (name lang-code)
                           :content-key content-key
                           :title (:title content-data)
                           :template (name (or (:template content-data) :page))
                           :type (:type content-data)
                           :metadata (dissoc content-data :content/html :markdown/content)})
                        lang-content)))
         (filter (fn [item]
                   (and (or (nil? language) (= (:language item) language))
                        (or (nil? type) (= (:type item) type))
                        (or (nil? template) (= (:template item) template)))))
         vec)))


(defn read-content
  "Read a specific content file."
  [{:keys [site-root]} {:keys [path]}]
  (try
    (let [content-dir (io/file site-root "content")
          content-file (io/file content-dir path)
          canonical-content (when content-dir (.getCanonicalPath content-dir))
          canonical-file (when content-file (.getCanonicalPath content-file))]
      (cond
        (not (.exists content-file))
        {:error (str "File not found: content/" path)}

        (not (and canonical-content
                  canonical-file
                  (str/starts-with? canonical-file canonical-content)))
        {:error "Invalid path - cannot access files outside content directory"}

        :else
        (let [content (slurp content-file)]
          (if (str/ends-with? path ".md")
            (let [parsed (loader/parse-markdown content)]
              {:path path
               :frontmatter (:metadata parsed)
               :content (:content parsed)})
            {:path path
             :content content}))))
    (catch Exception e
      {:error (str "Error reading file: " (.getMessage e))})))

(defn write-content
  "Write content to a file with proper frontmatter formatting."
  [{:keys [site-root]} {:keys [path content frontmatter]}]
  (let [content-file (io/file site-root "content" path)
        formatted (if (str/ends-with? path ".md")
                    (loader/format-frontmatter frontmatter content)
                    (pr-str (merge frontmatter {:content content})))]
    (fs/create-dirs (.getParentFile content-file))
    (spit content-file formatted)
    {:success true :path path}))

(defn delete-content
  "Delete a content file."
  [{:keys [site-root]} {:keys [path]}]
  (let [content-file (io/file site-root "content" path)]
    (when (.exists content-file)
      (fs/delete content-file)
      {:success true :path path})))

;; Template Operations

(defn list-templates
  "List all available templates."
  [{:keys [site-root]}]
  (let [templates-dir (if (instance? java.io.File site-root)
                        (io/file site-root "templates")
                        (io/file site-root "templates"))
        templates (when (.exists templates-dir)
                    (loader/load-templates templates-dir))]
    (->> templates
         (map (fn [[k _]]
                {:name (name k)
                 :path (str "templates/" (name k) ".edn")}))
         vec)))

(defn preview-template
  "Preview a template with sample data."
  [{:keys [site-root]} {:keys [template-name data]}]
  (let [templates-dir (if (instance? java.io.File site-root)
                        (io/file site-root "templates")
                        (io/file site-root "templates"))
        templates (loader/load-templates templates-dir)
        template (get templates (keyword template-name))]
    (when template
      (let [;; Convert :content to :content/html if needed
            normalized-data (if (and (:content data) (not (:content/html data)))
                              (-> data
                                  (assoc :content/html (:content data))
                                  (dissoc :content))
                              data)
            ;; Wrap data in :data key as expected by site-generator
            context {:data normalized-data}
            ;; Use site-generator to process the template
            result (eden.site-generator/process template context)
            ;; Extract the actual result (may be wrapped with warnings)
            final-result (if (map? result) (:result result) result)
            ;; Render to HTML string
            html (replicant.string/render
                  (eden.site-generator/prepare-for-render final-result))]
        {:html html}))))

;; Build Operations

(defn build-site
  "Trigger a site build."
  [{:keys [site-edn output-dir]} {:keys [mode]}]
  ;; Run the pipeline directly instead of calling eden.core
  (let [initial-ctx {:site-edn site-edn
                     :output-dir (or output-dir "dist")
                     :mode (or mode :prod)
                     :warnings {}
                     :timings {}
                     :results {}}
        final-ctx (-> initial-ctx
                      (pipeline/run-step pipeline/load-step :load)
                      (pipeline/run-step pipeline/build-html-step :build-html)
                      (pipeline/run-step pipeline/process-images-step :process-images)
                      (pipeline/run-step pipeline/bundle-assets-step :bundle-assets)
                      (pipeline/run-step pipeline/copy-static-step :copy-static)
                      (pipeline/run-step pipeline/write-output-step :write-output)
                      (pipeline/run-step pipeline/copy-processed-images-step :copy-processed-images))]
    {:success (not (:error final-ctx))
     :pages (count (get-in final-ctx [:results :build-html :html-files]))
     :warnings (vec (mapcat val (:warnings final-ctx)))}))

(defn get-build-status
  "Get the current build status."
  [_config]
  ;; This would need to track build state somewhere
  {:status "ready"})
