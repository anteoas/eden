(ns eden.assets
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.process :as process]
            [clojure.data.json :as json]
            [eden.site-generator :as sg]
            [eden.image-processor :as img])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.io File]))

(defn process-images
  "Process images in HTML and CSS files based on query parameters."
  [html-files css-files root-path]
  (let [;; Extract image URLs from all HTML and CSS
        html-image-urls (mapcat #(sg/extract-image-urls (:html %)) html-files)
        css-image-urls (mapcat #(sg/extract-image-urls (slurp %)) css-files)
        all-image-urls (concat html-image-urls css-image-urls)

        ;; Ensure .temp/images directory exists
        temp-dir (io/file ".temp/images")
        _ (io/make-parents (io/file temp-dir "dummy.txt"))

        ;; Process each unique image
        _ (doall
           (for [img-data all-image-urls]
             (when-not (:error img-data)
               (let [;; Convert web path to file system path
                     source-path (str root-path (:source-path img-data))
                     output-dir ".temp/images"]
                 ;; Call image processor with consistent keys
                 (img/process-image (merge {:source-path source-path
                                            :output-dir output-dir}
                                           (select-keys img-data [:width :height])))))))

        ;; Build URL replacement map
        url-replacements (reduce (fn [m img-data]
                                   (if (:error img-data)
                                     m
                                     (assoc m (:url img-data) (:replace-url img-data))))
                                 {}
                                 all-image-urls)]

    ;; Replace URLs in HTML files
    (map (fn [html-file]
           (let [updated-html (reduce (fn [html [old-url new-url]]
                                        (str/replace html old-url new-url))
                                      (:html html-file)
                                      url-replacements)]
             (assoc html-file :html updated-html)))
         html-files)))

(defn- ensure-npm-setup
  "Ensure npm is set up in the site directory with esbuild"
  [site-dir]
  (let [package-json (io/file site-dir "package.json")
        node-modules (io/file site-dir "node_modules")]
    ;; Only install dependencies if package.json exists
    (when (and (.exists package-json)
               (not (.exists node-modules)))
      (println "  Installing npm dependencies...")
      (process/exec {:dir site-dir} "npm" "install")
      (println "  npm dependencies installed"))))

(defn- bundle-css
  "Bundle CSS with esbuild, or copy files if esbuild not available"
  [site-root output-dir mode]
  (let [css-dir (io/file site-root "assets" "css")
        css-files (when (.exists css-dir)
                    (seq (.listFiles css-dir (reify java.io.FilenameFilter
                                               (accept [_ _dir name]
                                                 (.endsWith name ".css"))))))]
    (when css-files
      (let [css-start (System/currentTimeMillis)
            out-dir (io/file output-dir "assets" "css")
            _ (io/make-parents (io/file out-dir "dummy"))
            ;; esbuild path is relative to site-root
            esbuild-path (io/file site-root "node_modules" ".bin" "esbuild")
            bundled-files (if (.exists esbuild-path)
                            ;; Use esbuild if available
                            (into []
                                  (mapcat (fn [css-file]
                                            (let [css-path (File/.getPath css-file)
                                                  out-file (io/file out-dir (File/.getName css-file))
                                                  args (into-array String
                                                                   (cond-> [(File/.getPath esbuild-path) css-path "--bundle"
                                                                            (str "--outfile=" (File/.getPath out-file))
                                                                            "--metafile=/dev/stdout"
                                                                            "--external:/assets/*"
                                                                            "--loader:.css=css"
                                                                            "--log-level=error"]
                                                                     (= mode :prod) (conj "--minify")))]
                                              (try
                                                ;; Use ProcessBuilder to capture stdout separately
                                                (let [pb (new ProcessBuilder ^"[Ljava.lang.String;" args)
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
                                                               {:file (File/.getName (io/file (str out-path)))
                                                                :size (:bytes out-info)
                                                                :type :css})
                                                             outputs))
                                                      ;; Fallback if no metadata
                                                      [{:file (File/.getName css-file)
                                                        :size (.length out-file)
                                                        :type :css}])
                                                    (do
                                                      (println (format "    CSS %s failed with exit code %d"
                                                                       (File/.getName css-file) exit-code))
                                                      [])))
                                                (catch Exception e
                                                  (println (format "    CSS %s failed: %s" (File/.getName css-file) (.getMessage e)))
                                                  []))))
                                          css-files))
                            ;; Otherwise just copy the files
                            (mapv (fn [css-file]
                                    (let [out-file (io/file out-dir (File/.getName css-file))]
                                      (io/copy css-file out-file)
                                      {:file (File/.getName css-file)
                                       :size (.length out-file)
                                       :type :css}))
                                  css-files))]
        {:elapsed (- (System/currentTimeMillis) css-start)
         :files bundled-files}))))

(defn- bundle-js
  "Bundle JavaScript with esbuild, or copy files if esbuild not available"
  [site-root output-dir mode]
  (let [js-dir (io/file site-root "assets" "js")
        js-files (when (.exists js-dir)
                   (seq (.listFiles js-dir (reify java.io.FilenameFilter
                                             (accept [_ _dir name]
                                               (.endsWith name ".js"))))))]
    (when js-files
      (let [js-start (System/currentTimeMillis)
            out-dir (io/file output-dir "assets" "js")
            _ (io/make-parents (io/file out-dir "dummy"))
            ;; esbuild path is relative to site-root
            esbuild-path (io/file site-root "node_modules" ".bin" "esbuild")
            bundled-files (if (.exists esbuild-path)
                            ;; Use esbuild if available
                            (into []
                                  (mapcat (fn [js-file]
                                            (let [js-path (File/.getPath js-file)
                                                  out-file (io/file out-dir (File/.getName js-file))
                                                  args (into-array String
                                                                   (cond-> [(File/.getPath esbuild-path) js-path "--bundle"
                                                                            (str "--outfile=" (File/.getPath out-file))
                                                                            "--metafile=/dev/stdout"
                                                                            "--format=iife"
                                                                            "--log-level=error"]
                                                                     (= mode :dev) (conj "--sourcemap")
                                                                     (= mode :prod) (conj "--minify")))]
                                              (try
                                                ;; Use ProcessBuilder to capture stdout separately
                                                (let [pb (new ProcessBuilder ^"[Ljava.lang.String;" args)
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
                                                               {:file (File/.getName (io/file (str out-path)))
                                                                :size (:bytes out-info)
                                                                :type :js})
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
  [site-root output-dir mode]
  ;; Only ensure npm setup if we're in a traditional Eden project structure
  ;; (with site/ subdirectory), not in a generated site
  (let [site-subdir (io/file site-root "site")]
    (when (.exists site-subdir)
      (ensure-npm-setup site-root)))
  (println "  Bundling assets:")
  (let [css-result (bundle-css site-root output-dir mode)
        js-result (bundle-js site-root output-dir mode)]
    ;; Print timing info
    (when css-result
      (println (format "    CSS: %dms" (:elapsed css-result))))
    (when js-result
      (println (format "    JS: %dms" (:elapsed js-result))))
    ;; Return bundle info for reporting
    {:css css-result
     :js js-result}))
