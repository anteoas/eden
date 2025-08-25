(ns eden.reflection-test
  "Test to ensure no reflection warnings in the codebase"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn find-clojure-files
  "Find all Clojure source files in src directory"
  []
  (let [src-dir (io/file "src")]
    (->> (file-seq src-dir)
         (filter #(.endsWith (.getName %) ".clj"))
         (map #(.getPath %)))))

(defn check-file-for-reflection
  "Load a file with reflection warnings enabled and capture any warnings"
  [file-path]
  (let [warnings (atom [])]
    (binding [*warn-on-reflection* true
              *err* (java.io.PrintWriter.
                     (proxy [java.io.Writer] []
                       (write
                         ([s]
                          (when (string? s)
                            (when (str/includes? s "Reflection warning")
                              (swap! warnings conj {:file file-path
                                                    :warning s}))))
                         ([cbuf off len]
                          (let [s (String. cbuf off len)]
                            (when (str/includes? s "Reflection warning")
                              (swap! warnings conj {:file file-path
                                                    :warning s})))))
                       (flush [])
                       (close [])))]
      (try
        ;; Load the file to trigger reflection warnings
        (load-file file-path)
        (catch Exception _
          ;; Some files might fail to load in isolation, that's ok
          ;; We're just checking for reflection warnings
          nil)))
    @warnings))

(deftest test-no-reflection-warnings
  (testing "No reflection warnings in source code"
    (let [all-files (find-clojure-files)
          all-warnings (atom [])]

      ;; Check each file
      (doseq [file all-files]
        (let [warnings (check-file-for-reflection file)]
          (when (seq warnings)
            (swap! all-warnings concat warnings))))

      ;; Report any warnings found
      (when (seq @all-warnings)
        (println "\nReflection warnings found:")
        (doseq [{:keys [file warning]} @all-warnings]
          (println (str "  " file ":"))
          (println (str "    " warning))))

      ;; Test fails if any warnings were found
      (is (empty? @all-warnings)
          (str "Found " (count @all-warnings) " reflection warnings")))))

;; Alternative: Check for reflection at compile time for specific namespaces
(deftest test-critical-namespaces-no-reflection
  (testing "Critical namespaces compile without reflection warnings"
    (let [warnings (atom nil)
          critical-namespaces '[eden.core
                                eden.builder
                                eden.pipeline
                                eden.renderer
                                eden.site-generator
                                eden.mcp]]
      (binding [*warn-on-reflection* true]
        ;; Capture warnings during require
        (let [warning-writer (java.io.StringWriter.)]
          (binding [*err* (java.io.PrintWriter. warning-writer)]
            (doseq [ns-sym critical-namespaces]
              ;; Force reload to trigger any warnings
              (require ns-sym :reload))
            (reset! warnings (str warning-writer)))))

      ;; Filter warnings to only show Eden namespace warnings
      (let [eden-warnings (->> (str/split-lines @warnings)
                               (filter #(and (str/includes? % "Reflection warning")
                                             (str/includes? % "eden/")))
                               (str/join "\n"))]

        (when (not (str/blank? eden-warnings))
          (println "\nReflection warnings in Eden namespaces:")
          (println eden-warnings))

        (is (str/blank? eden-warnings)
            "Eden namespaces should have no reflection warnings")))))
