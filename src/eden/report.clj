(ns eden.report)

(defmulti print-warning :type)

(defmethod print-warning :missing-key [{:keys [directive key template]}]
  (println (format "  Missing key: %s for directive %s in template %s" key directive template)))

(defmethod print-warning :default [{:keys [type] :as warning}]
  (println "Unknown warning:" type)
  (prn warning))

(defn print-build-report [ctx]
  (when-let [warnings (seq (into #{} (:warnings ctx)))]
    (println "\n⚠️ Warnings")
    (doseq [warning warnings]
      (print-warning warning)))

  (when-let [error (:error ctx)]
    (println (format "\n❌ Build failed: %s" error))))
