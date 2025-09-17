(ns eden.assets
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.data.json :as json]
            [eden.image-processor :as img]
            [eden.esbuild :as esbuild])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.io File]))

(defn- extract-image-urls
  "Extract image URLs with query parameters from HTML or CSS."
  [content]
  (let [url-pattern #"(?:src=[\"']?|url\([\"']?)(/assets/images/[^\"')\s]+\?[^\"')\s]+)"
        parse-params (fn [query-string]
                       (let [params (java.net.URLDecoder/decode ^String query-string "UTF-8")
                             pairs (str/split params #"&")]
                         (reduce (fn [m pair]
                                   (let [[k v] (str/split pair #"=" 2)]
                                     (case k
                                       "size" (if-let [[_ w h] (re-matches #"^(\d+)x(.*)$" v)]
                                                (cond
                                                  (not (str/blank? h))
                                                  (if (re-matches #"\d+" h)
                                                    (assoc m :width (Long/parseLong w)
                                                           :height (Long/parseLong h))
                                                    (assoc m :error (str "Invalid height: " h)))
                                                  :else
                                                  (assoc m :width (Long/parseLong w)))
                                                (assoc m :error (str "Invalid size format: " v)))
                                       m)))
                                 {}
                                 pairs)))

        generate-replace-url (fn [path params]
                               (let [[base-path ext] (let [last-dot (.lastIndexOf ^String path ".")]
                                                       [(subs path 0 last-dot)
                                                        (subs path (inc last-dot))])
                                     {:keys [width height]} params
                                     size-suffix (cond
                                                   (and width height) (str "-" width "x" height)
                                                   width (str "-" width "x")
                                                   :else "")]
                                 (str base-path size-suffix "." ext)))]

    (into []
          (map (fn [[_ url]]
                 (let [[path query-string] (str/split url #"\?" 2)
                       params (parse-params query-string)]
                   (cond-> {:url url
                            :source-path path}
                     (not (:error params)) (merge (select-keys params [:width :height])
                                                  {:replace-url (generate-replace-url path params)})
                     (:error params) (assoc :error (:error params))))))
          (re-seq url-pattern content))))


(defn process-images
  "Process images in HTML and CSS files based on query parameters."
  [{:keys [site-config rendered css] :as _ctx}]
  (let [root-path (:root-path site-config)
        output-path (:output-path site-config)

        ;; Extract image URLs from all HTML and CSS
        html-image-urls (into #{} (mapcat #(extract-image-urls (:html/output %)) rendered))
        css-image-urls (into #{} (mapcat #(extract-image-urls (:content %)) css))
        all-image-urls (concat css-image-urls html-image-urls)

        image-results (mapv
                       (fn [img-data]
                         (if (:error img-data)
                           img-data
                           (let [opts (-> (select-keys img-data [:height :width])
                                          (assoc :source-path (str root-path (:source-path img-data))
                                                 :output-path (str output-path (:source-path img-data))))]
                             (merge opts (img/process-image opts)))))
                       all-image-urls)


        ;; Build URL replacement map
        url-replacements (reduce (fn [m img-data]
                                   (if (:error img-data)
                                     m
                                     (assoc m (:url img-data) (:replace-url img-data))))
                                 {}
                                 all-image-urls)]

    {:image-results image-results

     ;; Replace urls in HTML
     :rendered
     (mapv (fn [html-file]
             (let [updated-html (reduce (fn [html [old-url new-url]]
                                          (str/replace html old-url new-url))
                                        (:html/output html-file)
                                        url-replacements)]
               (assoc html-file :html/replaced updated-html)))
           rendered)

     ;; Replace urls in css
     :css
     (mapv (fn [css-file]
             (let [updated-css (reduce (fn [css [old-url new-url]]
                                         (str/replace css old-url new-url))
                                       (:content css-file)
                                       url-replacements)]
               (assoc css-file :replaced updated-css)))
           css)}))

(defn- copy-css
  "For now just copy css files over"
  [ctx]
  (when-let [css (seq (:css ctx))]
    (let [copy-start (System/currentTimeMillis)
          output-dir (io/file (:output-path (:site-config ctx)))
          files (mapv (fn [{:keys [content relative-path]}]
              (let [output-file (io/file output-dir relative-path)]
                (io/make-parents output-file)
                (io/copy content output-file)
                {:file (File/.getName output-file)
                 :path (str output-file)
                 :size (File/.length output-file)
                 :type :css}))
                      css)]
      {:elapsed (- (System/currentTimeMillis) copy-start)
       :files files})))

(defn- bundle-js
  "Bundle JavaScript with esbuild, or copy files if esbuild not available"
  [ctx]
  (let [site-root (-> ctx :site-config :root-path)
        assets-path (or (-> ctx :site-config :assets) "assets")
        js-dir (io/file site-root assets-path "js")
        js-files (when (and (File/.exists js-dir)
                            (File/.isDirectory js-dir))
                   (filter #(str/ends-with? % ".js") (file-seq js-dir)))]
    (when js-files
      (let [js-start (System/currentTimeMillis)
            output-path (-> ctx :site-config :output-path)
            mode (:mode ctx)
            out-dir (io/file output-path assets-path "js")
            _ (io/make-parents (io/file out-dir "."))
            ;; esbuild path is relative to site-root
            esbuild-path (io/file site-root "node_modules" ".bin" "esbuild")
            bundled-files (if (.exists esbuild-path)
                            ;; Use esbuild if available
                            (into []
                                  (mapcat (fn [js-file]
                                            (let [js-path (File/.getPath js-file)
                                                  out-file (io/file out-dir (File/.getName js-file))
                                                  args (into
                                                         [(File/.getPath esbuild-path) js-path]
                                                         (esbuild/args (cond-> {:bundle true
                                                                                :outfile (File/.getPath out-file)
                                                                                :metafile "/dev/stdout"
                                                                                :format "iife"
                                                                                :log-level "error"}
                                                                         (= mode :dev) (assoc :sourcemap true)
                                                                         (= mode :prod) (assoc :minify true))))]

                                              (try
                                                ;; Use ProcessBuilder to capture stdout separately
                                                ;; TODO: clojure.java.process
                                                (let [pb (new ProcessBuilder ^"[Ljava.lang.String;" (into-array String args))
                                                      _ (.redirectError pb ProcessBuilder$Redirect/DISCARD)
                                                      p (.start pb)
                                                      output (slurp (.getInputStream p))
                                                      exit-code (.waitFor p)]
                                                  (if (zero? exit-code)
                                                    ;; Parse JSON from stdout
                                                    (if (and output (not (str/blank? output)))
                                                      (let [meta-data (json/read-str output :key-fn keyword)
                                                            outputs (:outputs meta-data)]
                                                        (map (fn [[out-path out-info]]
                                                               (let [output-file (io/file (name out-path))]
                                                                 {:file (File/.getName output-file)
                                                                  :size (:bytes out-info)
                                                                  :path (File/.getAbsolutePath output-file)
                                                                  :type :js}))
                                                             outputs))
                                                      ;; Fallback if no metadata
                                                      [{:file (File/.getName js-file)
                                                        :size (.length out-file)
                                                        :type :js}])
                                                    (do
                                                      (println (format "    JS %s failed with exit code %d"
                                                                       (File/.getName js-file) exit-code))
                                                      [])))
                                                (catch Exception e
                                                  (println (format "    JS %s failed: %s" (File/.getName js-file) (.getMessage e)))
                                                  []))))
                                          js-files))
                            ;; Otherwise just copy the files
                            (mapv (fn [js-file]
                                    (let [out-file (io/file out-dir (File/.getName js-file))]
                                      (io/copy js-file out-file)
                                      {:file (File/.getName js-file)
                                       :size (.length out-file)
                                       :type :js}))
                                  js-files))]
        {:elapsed (- (System/currentTimeMillis) js-start)
         :files bundled-files}))))

(defn bundle-assets
  "Bundle all CSS and JS assets"
  [ctx]
  {:assets-output {:css (copy-css ctx)
                   :js (bundle-js ctx)}})


