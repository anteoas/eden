(ns eden.site-generator-test.warning-examples
  "Example warnings for each type, used for:
   1. Visual inspection of report output
   2. Testing report formatting
   3. Documentation of warning structures
   
   All examples must validate against their schemas in warnings-test namespace.
   Run (print-all-examples) to see formatted output of all warning types."
  (:require [eden.site-generator-test.warnings-test :as wt]
            [eden.report :as report]
            [malli.core :as m]
            [malli.error :as me]))

(def warnings
  [{:type :missing-key
    :template :home
    :content-key :index
    :key :page-title
    :directive :eden/get}

   {:type :missing-path
    :directive :eden/get-in
    :path [:user :profile :avatar]}

   {:type :missing-config-key
    :path [:social :twitter :api-key]}

   {:type :missing-collection-key
    :key :products}

   {:type :unsupported-eden-each-collection-spec
    :original-key "invalid-string"
    :context {} ; Would have full context in real scenario
    :body [:div "..."]
    :key "invalid-string"}

   {:type :missing-page-content
    :directive :eden/render
    :lang :en
    :spec {:data :about-page}
    :parent :home
    :content-key :about-page}

   {:type :missing-render-template
    :directive :eden/render
    :lang :en
    :template :product-detail
    :spec {:data :product}
    :parent :shop
    :content-key :product}

   {:type :with-directive-data-not-found
    :data-key :user-preferences
    :data {:user-id 123 :user-name "John"}}

   {:type :missing-include-template
    :directive :eden/include
    :template :header-navigation
    :content-key :home}

   {:type :missing-body-in-context
    :directive :eden/body
    :content-key :blog-post}

   {:type :invalid-key-or-path
    :path "should-be-keyword-or-vector"
    :directive :eden/t}

   {:type :not-a-string
    :directive :eden/t
    :content-key :dashboard
    :lang :en
    :value 42
    :template-variable "{{count}}"
    :template-string "You have {{count}} messages"
    :form [:eden/t :message-count {:count 42}]}

   {:type :unknown-directive
    :directive :eden/custom-thing}

   {:type :missing-content
    :content-key :privacy-policy
    :message "Page :privacy-policy not found in any language"}])

(defn print-examples []
  (let [{:keys [valid errors]}
        (reduce (fn [agg w]
                  (if (m/validate wt/Warning w)
                    (update agg :valid conj w)
                    (update agg :errors conj (let [explained (m/explain wt/Warning w)]
                                               {:value w
                                                :explain explained
                                                :humanized (me/humanize explained)}))))
                {:valid [] :errors []}
                warnings)
        warnings-by-type (group-by :type valid)]

    (doseq [[type warnings] warnings-by-type]
      (println type "examples")
      (doseq [w warnings]
        (report/print-warning w)))

    (when (seq errors)
      (println "\nErrors:")
      (let [{missing true other false}
            (group-by #(= (-> % :explain :errors first :type) ::m/invalid-dispatch-value) errors)]
        (when (seq missing)
          (println "  Missing schemas")
          (doseq [missing-schema (into #{} (map (comp :type :value)) missing)]
            (println "    " missing-schema))
          (println))

        (when (seq other)
          (println "  Invalid warnings")
          (doseq [{:keys [value humanized]} other]
            (print "    ")
            (pr humanized)
            (print " - ")
            (prn value)
            (println))
          (println))))))

(comment
  (print-examples))
