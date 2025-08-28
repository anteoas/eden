(ns eden.pipeline
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [babashka.fs :as fs]
            [eden.loader :as loader]
            [eden.builder :as builder]
            [eden.assets :as assets]))

(defn run-step
  "Run a build step and accumulate results, warnings, and timing.
   Short-circuits if an error is present in the context."
  [ctx step-fn step-name]
  (if (:error ctx)
    ctx ;; Short-circuit on error
    (let [start (System/currentTimeMillis)
          {:keys [result warnings error]} (try
                                            (step-fn ctx)
                                            (catch Exception e
                                              {:error (.getMessage e)}))
          elapsed (- (System/currentTimeMillis) start)]
      (cond-> (update ctx :timings assoc step-name elapsed)
        result (assoc-in [:results step-name] result)
        warnings (update :warnings (fn [old-warnings]
                                     (merge-with (fn [old new]
                                                   (concat (or old []) (or new [])))
                                                 old-warnings
                                                 warnings)))
        error (assoc :error error)))))

(defn load-step
  "Load site configuration and templates"
  [{:keys [site-edn output-dir]}]
  {:result (loader/load-site-data site-edn output-dir)})

(defn build-html-step
  "Build HTML from templates and content"
  [ctx]
  (let [site-data (get-in ctx [:results :load])
        {:keys [html-files warnings]} (builder/build-site site-data {:verbose false})

        ;; Detect orphan content
        all-content (into #{}
                          (for [[_lang content-map] (:content site-data)
                                [content-key _] content-map]
                            content-key))
        rendered-pages (into #{} (map :content-key html-files))
        ;; Pages with warnings were attempted but failed
        pages-with-warnings (into #{} (map :content-key (:page-warnings warnings)))
        ;; Attempted = rendered + failed
        attempted-pages (set/union rendered-pages pages-with-warnings)
        ;; True orphans = content that was never attempted
        orphan-content (set/difference all-content attempted-pages)

        ;; Add orphan warning if any found
        warnings-with-orphans (if (seq orphan-content)
                                (assoc warnings :orphan-content orphan-content)
                                warnings)]
    {:result {:html-files html-files
              :all-content all-content
              :rendered-pages rendered-pages
              :orphan-content orphan-content}
     :warnings warnings-with-orphans}))

(defn process-images-step
  "Process images if enabled"
  [ctx]
  (let [html-files (get-in ctx [:results :build-html :html-files])
        site-data (get-in ctx [:results :load])
        config (:config site-data)
        root-path (:root-path config)]
    (if (:image-processor config)
      {:result (assets/process-images html-files [] root-path)}
      {:result html-files})))

(defn bundle-assets-step
  "Bundle CSS and JS assets"
  [ctx]
  (let [site-data (get-in ctx [:results :load])
        config (:config site-data)
        root-path (:root-path config)
        output-path (:output-path config)
        mode (:mode ctx)
        bundle-info (assets/bundle-assets root-path output-path mode)]
    {:result bundle-info}))

(defn copy-static-step
  "Copy static assets (excluding CSS/JS which are bundled)"
  [ctx]
  (let [site-data (get-in ctx [:results :load])
        config (:config site-data)
        root-path (:root-path config)
        output-path (:output-path config)
        assets-source (io/file root-path "assets")
        assets-target (io/file output-path "assets")
        copied-count (atom 0)]
    (when (.exists assets-source)
      (doseq [file (file-seq assets-source)]
        (when (and (.isFile ^java.io.File file)
                   (not (str/includes? (.getPath ^java.io.File file) "/css/"))
                   (not (str/includes? (.getPath ^java.io.File file) "/js/")))
          (let [source-path (.toPath assets-source)
                file-path (.toPath ^java.io.File file)
                relative-path (.relativize source-path file-path)
                target-file (io/file assets-target (.toString relative-path))]
            (io/make-parents target-file)
            (io/copy file target-file)
            (swap! copied-count inc)))))
    {:result {:copied @copied-count}}))

(defn write-output-step
  "Write HTML files to disk"
  [ctx]
  (let [site-data (get-in ctx [:results :load])
        html-files (or (get-in ctx [:results :process-images])
                       (get-in ctx [:results :build-html :html-files]))
        output-path (:output-path (:config site-data))
        url->filepath (:url->filepath site-data)]
    (builder/write-output html-files output-path url->filepath)
    {:result {:html-count (count html-files)}}))

(defn copy-processed-images-step
  "Copy processed images from temp to dist"
  [ctx]
  (let [site-data (get-in ctx [:results :load])
        config (:config site-data)
        output-path (:output-path config)]
    (if (:image-processor config)
      (let [temp-images (io/file ".temp/images")
            dist-images (io/file output-path "assets/images")]
        (when (.exists temp-images)
          (fs/copy-tree temp-images dist-images {:replace-existing true}))
        {:result {:copied true}})
      {:result {:copied false}})))
