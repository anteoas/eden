(ns eden.report)

(defmulti print-warning :type)

(defmethod print-warning :missing-key [{:keys [directive key template]}]
  (println (format "  Missing key: %s for directive %s in template %s"
                   key directive template)))

(defmethod print-warning :missing-render-template [{:keys [lang template parent]}]
  (println (format "  Missing template: %s (lang: %s) while rendering %s"
                   template lang parent)))

(defmethod print-warning :missing-page-content [{:keys [lang spec parent]}]
  (println (format "  Missing content: %s (lang: %s) while rendering %s"
                   (:data spec) lang parent)))

(defmethod print-warning :not-a-string [{:keys [form lang]}]
  (println (format "  Missing string value for template: '%s' (lang: %s)"
                   (pr-str form)
                   lang)))

(defmethod print-warning :missing-include-template [{:keys [directive template]}]
  (println (format "  Missing include template: %s for %s"
                   template directive)))

(defmethod print-warning :missing-template [{:keys [lang template]}]
  (println (format "  Missing template: %s (lang: %s). Create template or specify :template in content " template lang)))

(defmethod print-warning :default [{:keys [type] :as warning}]
  (println "Unknown warning:" type)
  (prn warning))

(defn print-build-report [ctx]
  (when-let [warnings (seq (into #{} (:warnings ctx)))]
    (println "\n⚠️ Warnings")
    (doseq [[content-key content-warnings] (sort-by first (group-by #(or (:parent %) (:content-key %)) warnings))]
      (println "\n" content-key)
      (doseq [warning content-warnings]
        (print-warning warning))))

  (when-let [error (:error ctx)]
    (println (format "\n❌ Build failed: %s" error))))
