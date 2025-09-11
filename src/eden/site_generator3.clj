(ns eden.site-generator3
  (:require [clojure.string :as str]))

(defn- hiccup?
  [elem]
  (and (vector? elem) (keyword? (first elem))))

(defn- hiccup-add-attributes [elem attribs]
  (if (hiccup? elem)
    (if (map? (second elem))
      (update elem 1 merge attribs)
      (into [(first elem) attribs] (rest elem)))
    elem))

(defn- vector-of-vectors? [elem]
  (and (vector? elem)
       (every? vector? elem)))

(defn- eden-directive?
  "Check if element is an Eden directive"
  [elem]
  (and (vector? elem)
       (keyword? (first elem))
       (= "eden" (namespace (first elem)))))

(defn- warn! [context msg]
  (when-let [w! (:warn! context)]
    (w! msg))
  nil)

(defn- add-reference! [context ref]
  (when-let [r! (:add-reference! context)]
    (r! ref))
  nil)

(defn- add-section! [context section-id]
  (when-let [s! (:add-section! context)]
    (s! section-id {:parent (:content-key context)}))
  nil)

(declare process)

(defmulti process-directive (fn [[directive & _] _context] directive))

(defmethod process-directive :eden/if [[_ condition then-branch else-branch] context]
  (let [condition-value (cond
                          (and (hiccup? condition)
                               (#{:= :!= :< :> :<= :>=} (first condition)))
                          (let [[op a b] condition
                                val-a (process a context)
                                val-b (process b context)]
                            (case op
                              := (= val-a val-b)
                              :!= (not= val-a val-b)
                              :> (> val-a val-b)
                              :< (< val-a val-b)
                              :>= (>= val-a val-b)
                              :<= (<= val-a val-b)))

                          :else (process condition context))]
    (if condition-value
      (process then-branch context)
      (when-not (nil? else-branch)
        (process else-branch context)))))

(defmethod process-directive :eden/get [[_ key default-value] context]
  (let [processed-key (process key context)
        value (get-in context [:data processed-key])]
    (cond
      (nil? value)
      (if default-value
        (process default-value context)
        (do (warn! context
                   {:type :missing-key
                    :key processed-key
                    ;; TODO: rendering stack
                    })
            [:span.missing-content (str "[:eden/get " processed-key "]")]))

      (= processed-key :html/content)
      [:span {:innerHTML value}]

      :else value)))

(defmethod process-directive :eden/get-in [[_ path default-value] context]
  (let [processed-path (process path context)
        value (get-in context (into [:data] processed-path))]
    (cond
      (nil? value)
      (if default-value
        (process default-value context)
        (do (warn! context
                   {:type :missing-path
                    :directive :eden/get-in
                    :path processed-path})
            [:span.missing-content (str "[:eden/get-in " processed-path "]")]))

      (= (last processed-path) :html/content)
      [:span {:innerHTML value}]

      :else value)))

(defmethod process-directive :eden/site-config [[_ & path] context]
  (let [processed-path (process path context)
        value (get-in (:site-config context) processed-path)]
    (when-not value
      (warn! context {:type :missing-config-key
                      :path processed-path}))
    (if value
      value
      [:span.missing-content (str "[:eden/config " path "]")])))

(defmethod process-directive :eden/each [[_ collection-spec & body] context]
  (let [[options template]
        (loop [remaining body
               opts []]
          (cond
            (empty? remaining)
            [opts []]

            (and (keyword? (first remaining))
                 (seq (rest remaining)))
            (recur (drop 2 remaining)
                   (into opts (take 2 remaining)))

            :else
            [opts remaining]))

        ;; TODO: warn on unsupported options
        {:keys [where order-by limit group-by]} (apply hash-map options)

        processed-collection-spec (process collection-spec context)

        raw-collection (cond
                         ;; Content collection
                         (= :eden/all processed-collection-spec)
                         (when-let [content-data (:content-data context)]
                           (mapv (fn [[id data]]
                                   (assoc data :content-key id))
                                 content-data))

                         (keyword? processed-collection-spec)
                         (let [result (get-in context [:data processed-collection-spec])]
                           (if result
                             result
                             (warn! context
                                    {:type :missing-collection-key
                                     :key processed-collection-spec})))

                         (coll? processed-collection-spec)
                         processed-collection-spec

                         :else
                         (warn! context
                                {:type :unsupported-eden-each-collection-spec
                                 :original-key collection-spec
                                 :context context
                                 :body body
                                 :key processed-collection-spec}))

        collection (if (and where (sequential? raw-collection))
                     (filterv (fn [item]
                                (every? (fn [[k v]]
                                          (= (get item k) v))
                                        where))
                              raw-collection)
                     raw-collection)

        comparator (if order-by
                     (let [[field dir] (if (vector? order-by) order-by [order-by :asc])]
                       (if (= dir :desc)
                         #(compare (get %2 field) (get %1 field))
                         #(compare (get %1 field) (get %2 field))))
                     compare)]
    (cond
      (nil? collection) []

      ;; map iteration
      (map? collection)
      (into [] (comp (map-indexed
                      (fn [idx [k v]]
                        (let [item-context (update context :data
                                                   merge
                                                   (when (map? v) v)
                                                   {:eden.each/key k
                                                    :eden.each/value v
                                                    :eden.each/index idx})]
                          (process template item-context))))
                     (mapcat (fn [elem]
                               (if (vector-of-vectors? elem)
                                 elem
                                 [elem]))))
            (cond->> (seq collection)
              limit (take limit)))

      ;; sequential iteration with grouping
      (and (sequential? collection) group-by)
      (let [group-fn (if (sequential? group-by)
                       (apply juxt (process group-by context))
                       group-by)
            grouped (clojure.core/group-by group-fn collection)]
        (into []
              (comp (map-indexed (fn [idx [group-key items]]
                                   (let [sorted (if order-by (sort comparator items) items)
                                         limited (if limit (take limit sorted) sorted)
                                         item-context (update context :data merge
                                                              {:eden.each/group-key group-key
                                                               :eden.each/group-items limited
                                                               :eden.each/index idx})]
                                     (process template item-context))))
                    (mapcat (fn [elem]
                              (if (vector-of-vectors? elem)
                                elem
                                [elem]))))
              grouped))

      ;; regular sequential iteration
      (sequential? collection)
      (let [sorted (if order-by
                     (sort comparator collection)
                     collection)]
        (into [] (comp (map-indexed (fn [idx item]
                                      (let [item-context (update context :data
                                                                 merge
                                                                 (if (map? item) item {:eden.each/value item})
                                                                 {:eden.each/index idx})]
                                        (process template item-context))))
                       (mapcat (fn [elem]
                                 (if (vector-of-vectors? elem)
                                   elem
                                   [elem]))))
              (cond->> sorted
                limit (take limit)))))))

(defmethod process-directive :eden/link [[_ link-spec & body] context]
  (let [processed-link-spec (process link-spec context)
        final-spec (if (keyword? processed-link-spec)
                     {:content-key processed-link-spec}
                     processed-link-spec)
        {:keys [content-key nav lang]} final-spec]

    (when (and content-key (not nav))
      (add-reference! context content-key))

    (let [lang (or lang (:lang context))
          link-element-context (cond-> final-spec
                                 lang (assoc :lang lang))
          link-context (assoc context :data
                              (assoc (:data context)
                                     :link/href (assoc link-element-context :type :eden.link.placeholder/href)
                                     :link/title (assoc link-element-context :type :eden.link.placeholder/title)))]
      (process body link-context))))

(defmethod process-directive :eden/render [[_ render-spec] context]
  (let [processed-render-spec (process render-spec context)
        final-spec (if (keyword? processed-render-spec)
                     {:data processed-render-spec}
                     processed-render-spec)

        content-key (:data final-spec)

        lang (or (:lang context)
                 (throw (ex-info "Language not set in context"
                                 {:context context
                                  :render-spec final-spec})))

        page-data (or (get-in context [:content lang content-key])
                      (warn! context
                             {:type :missing-page-content
                              :directive :eden/render
                              :lang lang
                              :spec final-spec}))

        template-key (or (:template final-spec)
                         (:template page-data)
                         content-key)

        template (or (get-in context [:templates template-key])
                     (and page-data
                          (warn! context
                                 {:type :missing-render-template
                                  :directive :eden/render
                                  :lang lang
                                  :template template-key
                                  :spec final-spec})))]

    (when-let [section-id (:section-id final-spec)]
      (add-section! context section-id))

    (if template
      (let [render-context (assoc context
                                  :data (assoc page-data :lang lang)
                                  :content-key content-key)
            results (process template render-context)
            section-slug (and (:section-id final-spec)
                              (or (:slug page-data) (and (keyword? content-key) (name content-key))))]
        (if (and (hiccup? results) section-slug)
          (hiccup-add-attributes results {:id section-slug})
          results))
      [:span.missing-content (str "[:eden/render " (:data final-spec) "]")])))

(defmethod process-directive :eden/with [[_ data-key & body] context]
  (let [processed-data-key (process data-key context)
        new-data (get-in context [:data processed-data-key])
        with-context (assoc context :data new-data)]

    (when-not new-data
      (warn! context {:type :with-directive-data-not-found
                      :data-key processed-data-key
                      :data (:data context)}))

    (process body with-context)))

(defmethod process-directive :eden/include [[_ template-name override-context] context]
  (let [processed-template-name (process template-name context)
        template (or (get-in context [:templates processed-template-name])
                     (warn! context
                            {:type :missing-include-template
                             :directive :eden/include
                             :template processed-template-name}))]
    (process template (update context :data merge override-context))))

(defmethod process-directive :eden/body [_ {:keys [body] :as context}]
  (when-not body
    (warn! context {:type :missing-body-in-context
                    :directive :eden/body
                    :content-key (:content-key context)}))
  body)

(defmethod process-directive :eden/t [[_ key-or-path interpolations-or-default-value] context]
  (let [processed-key-or-path (process key-or-path context)
        final-path (cond
                     (keyword? processed-key-or-path)
                     [processed-key-or-path]

                     (sequential? processed-key-or-path)
                     processed-key-or-path

                     :else (warn! context  {:type :invalid-key-or-path
                                            :path processed-key-or-path
                                            :directive :eden/t}))
        string-template (and final-path (get-in (:strings context) final-path))

        interpolations (when (map? interpolations-or-default-value)
                         (process interpolations-or-default-value context))

        default-value (when (string? interpolations-or-default-value)
                        interpolations-or-default-value)

        mappings (->> (re-seq #"\{\{([^\}]+)\}\}" (or string-template ""))
                      (map #(update % 1 (fn [value]
                                          (try
                                            (let [k (keyword value)]
                                              (get interpolations k))
                                            (catch Exception _))))))

        translated-string (and string-template
                               (reduce (fn [s [variable value]]
                                         (if (string? value)
                                           (str/replace s variable value)
                                           (do (warn! context {:type :not-a-string
                                                               :directive :eden/t
                                                               :value value
                                                               :template-variable variable
                                                               :template-string string-template
                                                               :form [:eden/t key-or-path interpolations-or-default-value]})
                                               s)))
                                       string-template mappings))]

    (or translated-string
        default-value
        [:span.missing-content (str "[:eden/t " processed-key-or-path "]" )])))

(defmethod process-directive :default [[directive & _] context]
  ;; magic values
  (if (#{:eden/all} directive)
    directive

    ;; Unknown directive - warn and return as-is
    (do (warn! context {:type :unknown-directive
                        :directive directive})
        [:span.unknown-directive (str "[" directive " ...]")])))

(defn process
  "Process"
  [elem context]
  (cond
    (nil? elem) nil

    (eden-directive? elem)
    (process-directive elem context)

    (sequential? elem)
    (into [] (mapcat (fn [e]
                       (let [result (process e context)]
                         (if (vector-of-vectors? result)
                           ;; returned multiple elements, flatten
                           result
                           ;; don't flatten
                           [result]))
                       )) elem)

    (map? elem)
    (into {}
          (map (fn [[k v]]
                 [(process k context)
                  (process v context)]))
          elem)

    :else elem))
