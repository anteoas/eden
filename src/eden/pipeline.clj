(ns eden.pipeline
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [eden.loader :as loader]
            [eden.builder :as builder]
            [eden.assets :as assets])
  (:import [java.io File]))

;; TODO: validate params
(defn init-context [& {:keys [site-edn output-dir mode]}]
  {:site-edn site-edn
   :output-dir output-dir
   :mode mode
   :timings []})

(defn run-step
  "Run a build step and accumulate results, warnings, and timing.
   Short-circuits if an error is present in the context."
  [ctx step-fn step-name]
  (if (:error ctx)
    ctx ;; Short-circuit on error
    (let [start (System/currentTimeMillis)
          {:keys [warnings error] :as result} 
          (try
            (step-fn ctx)
            (catch Exception e
              {:error {:ex e
                       :message (.getMessage e)
                       :step step-name}}))
          elapsed (- (System/currentTimeMillis) start)]
      (cond-> (merge 
               (update ctx :timings conj {:name step-name
                                          :elapsed-ms elapsed}) 
               (dissoc result :warnings :error))
        (seq warnings) (update :warnings into warnings)
        error (assoc :error error)))))

(defn pipeline* [initial-context & steps]
  (reduce (fn [ctx [step-name step-fn]]
            (run-step ctx step-fn step-name))
          initial-context
          steps))

(defmacro |> [initial-context & steps]
  `(pipeline* (init-context ~initial-context)
              ~@(map (fn [step]
                       `[~(keyword step) ~step])
                     steps)))

(defn load-step
  "Load site configuration and templates"
  [ctx]
  (loader/load-site-data ctx))

(defn build-step
  "Build HTML from templates and content"
  [ctx]
  (builder/build-site ctx))

(defn process-images-step
  "Process images if enabled"
  [{:keys [site-config] :as ctx}]
  (when (:image-processor site-config)
    (assets/process-images ctx)))

(defn bundle-assets-step
  "Bundle CSS and JS assets"
  [ctx]
  (assets/bundle-assets ctx))

(defn copy-static-step
  "Copy static assets (excluding CSS/JS which are bundled)"
  [{:keys [site-config]}]
  (let [root-path (:root-path site-config)
        output-path (:output-path site-config)
        assets (or (:assets site-config) "assets")
        assets-source (io/file root-path assets)
        assets-target (io/file output-path assets)]
    (when (.exists assets-source)
      {:static-files-copied
       (reduce (fn [files-copied file]
                 (if (and (File/.isFile file)
                          (not (re-matches #".*\.css$" (str file)))
                          (not (re-matches #".*\.js$" (str file))))
                   (let [source-path (.toPath assets-source)
                         file-path (File/.toPath file)
                         relative-path (.relativize source-path file-path)
                         target-file (io/file assets-target (.toString relative-path))]
                     (io/make-parents target-file)
                     (io/copy file target-file)
                     (conj files-copied (str target-file)))
                   files-copied))
               []
               (file-seq assets-source))})))

(defn write-output-step
  "Write HTML files to disk"
  [ctx]
  (builder/write-output ctx))

;; TODO: merge into process-images-step?
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
