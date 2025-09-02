(ns eden.site-generator2
  "Experimental site generator with macro-like directive evaluation.
   
   Key improvements:
   - Consistent evaluation semantics for all directives
   - Explicit control over what gets evaluated when
   - Better support for nested directives in arguments"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Core Evaluation Engine
;; ============================================================================

(defn eden-directive?
  "Check if element is an Eden directive"
  [elem]
  (and (vector? elem)
       (keyword? (first elem))
       (= "eden" (namespace (first elem)))))

(declare process)
(declare eval-expr)

(defn eval-expr
  "Recursively evaluate an expression, processing Eden directives.
   This is used for evaluating directive arguments."
  [expr context]
  (cond
    ;; Eden directive - process it
    (eden-directive? expr)
    (process expr context)

    ;; Map - evaluate all values (but not keys)
    (map? expr)
    (into {} (map (fn [[k v]] [k (eval-expr v context)]) expr))

    ;; Vector but not a directive - leave as-is (it's hiccup)
    (and (vector? expr) (not (eden-directive? expr)))
    expr

    ;; List/seq - evaluate elements
    (sequential? expr)
    (map #(eval-expr % context) expr)

    ;; Scalar values - return as-is
    :else expr))

;; ============================================================================
;; Directive Processing - Macro-like semantics
;; ============================================================================

(defmulti process-directive
  "Process directive with macro-like evaluation semantics.
   Each directive controls what gets evaluated and when."
  (fn [[directive & _] _context] directive))

;; ----------------------------------------------------------------------------
;; Data Access Directives
;; ----------------------------------------------------------------------------

(defmethod process-directive :eden/get [[_ key default-value] context]
  ;; Fully evaluate - get value from context data
  (let [value (get-in context [:data key])
        is-content-html? (and (keyword? key)
                              (= "content" (namespace key))
                              (string? value))]
    (cond
      (nil? value)
      (if (= (count [_ key default-value]) 3)
        default-value
        (do
          (when-let [warn! (:warn! context)]
            (warn! {:type :missing-key
                    :key key
                    :template (or (:current-template context) "unknown")
                    :page (:content-key context)}))
          [:span.missing-content (str "[:eden/get " key "]")]))

      is-content-html?
      [:span {:innerHTML value}]

      :else
      value)))

(defmethod process-directive :eden/get-in [[_ path default-value] context]
  ;; Fully evaluate - get nested value from context
  (let [has-default? (> (count [_ path default-value]) 2)
        value (if (empty? path)
                (:data context)
                (get-in context (into [:data] path) ::not-found))]
    (cond
      (not= value ::not-found) value
      has-default? default-value
      :else (str/join "." (map name path)))))

(defmethod process-directive :eden/config [[_ & path] context]
  ;; Access site configuration
  (let [;; Evaluate any directives in the path
        evaluated-path (mapv #(if (eden-directive? %)
                                (eval-expr % context)
                                %)
                             path)]
    (if (empty? evaluated-path)
      (:site-config context)
      (get-in (:site-config context) evaluated-path))))

;; ----------------------------------------------------------------------------
;; Control Flow Directives
;; ----------------------------------------------------------------------------

(defmethod process-directive :eden/if [[_ condition then-branch else-branch] context]
  ;; Evaluate condition, then evaluate appropriate branch
  (let [condition-value (cond
                          ;; Comparison operators
                          (and (vector? condition)
                               (#{:= :!= :< :> :<= :>=} (first condition)))
                          (let [[op a b] condition
                                val-a (eval-expr a context)
                                val-b (eval-expr b context)]
                            (case op
                              := (= val-a val-b)
                              :!= (not= val-a val-b)
                              :< (< val-a val-b)
                              :> (> val-a val-b)
                              :<= (<= val-a val-b)
                              :>= (>= val-a val-b)))

                          ;; Other expressions
                          :else (eval-expr condition context))]
    (if condition-value
      (process then-branch context)
      (when else-branch
        (process else-branch context)))))

(defmethod process-directive :eden/each [[_ collection-spec & body] context]
  ;; Parse options and template from body
  (let [;; Separate options from template (last element is always template)
        template (last body)
        options (butlast body)
        ;; Parse options into a map
        {:keys [where order-by limit group-by]}
        (apply hash-map options)

        ;; Get the collection
        raw-collection (cond
                         ;; Special :eden/all collection
                         (= :eden/all collection-spec)
                         (when-let [content-data (:content-data context)]
                           (mapv (fn [[id data]]
                                   (assoc data :content-key id))
                                 content-data))

                         ;; Regular keyword
                         (keyword? collection-spec)
                         (get-in context [:data collection-spec])

                         ;; Directive expression
                         :else
                         (eval-expr collection-spec context))

        ;; Apply :where filter if present
        collection (if (and where (sequential? raw-collection))
                     (filterv (fn [item]
                                (every? (fn [[k v]] (= (get item k) v))
                                        where))
                              raw-collection)
                     raw-collection)]

    (cond
      ;; No collection
      (nil? collection) []

      ;; Map iteration
      (map? collection)
      (vec (map-indexed
            (fn [idx [k v]]
              (let [item-context (-> context
                                     (assoc :data (merge (if (map? v) v {})
                                                         {:eden.each/key k
                                                          :eden.each/value v
                                                          :eden.each/index idx})))]
                (process template item-context)))
            (cond->> (seq collection)
              limit (take limit))))

      ;; Sequential iteration with grouping
      (and (sequential? collection) group-by)
      (let [groups (group-by #(get % group-by) collection)]
        (vec (mapcat
              (fn [[idx [group-key items]]]
                (let [result (process template
                                      (update context :data merge
                                              {:eden.each/group-key group-key
                                               :eden.each/group-items items
                                               :eden.each/index idx}))]
                  (if (and (vector? result) (not (keyword? (first result))))
                    result
                    [result])))
              (map-indexed vector (cond->> (seq groups)
                                    limit (take limit))))))

      ;; Regular sequential iteration
      (sequential? collection)
      (let [;; Apply sorting if specified
            sorted (if order-by
                     (let [[field dir] (if (vector? order-by) order-by [order-by :asc])
                           comparator (if (= dir :desc)
                                        #(compare (get %2 field) (get %1 field))
                                        #(compare (get %1 field) (get %2 field)))]
                       (sort comparator collection))
                     collection)]
        (vec (map-indexed
              (fn [idx item]
                (let [item-context (-> context
                                       (assoc :data (merge (if (map? item) item {:eden.each/value item})
                                                           {:eden.each/index idx})))]
                  (process template item-context)))
              (cond->> sorted
                limit (take limit)))))

      ;; Not a collection
      :else [])))

;; ----------------------------------------------------------------------------
;; Link and Reference Directives
;; ----------------------------------------------------------------------------

(defmethod process-directive :eden/link [[_ link-spec & body] context]
  ;; Evaluate the link spec (args) but not the body yet
  (let [;; Fully evaluate the spec to resolve any directives
        evaluated-spec (eval-expr link-spec context)
        ;; Normalize to config map
        config (if (keyword? evaluated-spec)
                 {:content-key evaluated-spec}
                 evaluated-spec)
        {:keys [content-key nav lang]} config]

    ;; Add reference if it's a content page
    (when-let [add-reference! (:add-reference! context)]
      (when (and content-key (not nav))
        (add-reference! content-key)))

    ;; Create placeholders and process body with them
    (let [placeholder-href (assoc config :type :eden.link.placeholder/href)
          placeholder-title (assoc config :type :eden.link.placeholder/title)
          link-ctx (update context :data assoc
                           :link/href placeholder-href
                           :link/title placeholder-title)
          ;; Process body with placeholders in context
          processed-body (if (= 1 (count body))
                           (process (first body) link-ctx)
                           (vec (map #(process % link-ctx) body)))]
      {:eden/link-placeholder config
       :body processed-body})))

;; ----------------------------------------------------------------------------
;; Translation Directive
;; ----------------------------------------------------------------------------

(defmethod process-directive :eden/t [[_ key-or-path interpolations] context]
  ;; Get translation string and interpolate
  (let [path (if (vector? key-or-path) key-or-path [key-or-path])
        template (get-in (:strings context) path)
        ;; Evaluate interpolation values
        values (when (map? interpolations)
                 (into {} (map (fn [[k v]]
                                 [k (eval-expr v context)])
                               interpolations)))]
    (if template
      (if values
        ;; Simple interpolation with {{key}} syntax
        (reduce (fn [s [k v]]
                  (str/replace s (str "{{" (name k) "}}") (str v)))
                template
                values)
        template)
      (do
        (when-let [warn! (:warn! context)]
          (warn! {:type :missing-translation
                  :key key-or-path
                  :lang (:lang context)}))
        (str "[" (str/join "." (map name path)) "]")))))

;; ----------------------------------------------------------------------------
;; Content Directives
;; ----------------------------------------------------------------------------

(defmethod process-directive :eden/body [[_] context]
  ;; Return the body content (usually markdown HTML)
  (or (get-in context [:data :content/html])
      (get-in context [:data :body])
      ""))

(defmethod process-directive :eden/render [[_ render-spec] context]
  ;; Evaluate the render spec and render the specified template
  (let [evaluated-spec (eval-expr render-spec context)
        ;; Extract data-key, template-id, and section-id
        [data-key template-id section-id]
        (cond
          (keyword? evaluated-spec)
          [evaluated-spec evaluated-spec nil]

          (map? evaluated-spec)
          [(:data evaluated-spec)
           (or (:template evaluated-spec) (:data evaluated-spec))
           (:section-id evaluated-spec)]

          :else [nil nil nil])]

    ;; Validate inputs
    (cond
      ;; No valid template-id
      (or (nil? template-id)
          (and (not (keyword? template-id))
               (not (nil? template-id))))
      (do
        (when-let [warn! (:warn! context)]
          (warn! {:type :invalid-render-spec
                  :data-key data-key
                  :template-id template-id
                  :render-spec render-spec}))
        [:div.eden-render-error
         {:data-error "invalid-render-spec"}
         (str "<!-- Invalid render spec: " (pr-str render-spec) " -->")])

      ;; Template doesn't exist
      (nil? (get-in context [:templates template-id]))
      (do
        (when-let [warn! (:warn! context)]
          (warn! {:type :missing-template
                  :template-id template-id
                  :data-key data-key}))
        [:div.eden-render-error
         {:data-error "missing-template"}
         (str "<!-- Missing template: " template-id " -->")])

      ;; Valid template exists
      :else
      (let [;; Register section if present
            _ (when (and section-id (:add-section! context))
                ((:add-section! context) section-id {:parent (:content-key context)}))
            ;; Get data from content-data if data-key is specified
            content-data (when data-key
                           (get-in context [:content-data data-key] {}))
            ;; Merge build-constants with component's content
            render-context (assoc context :data (merge (:build-constants context) content-data))
            ;; Process the template
            rendered nil;;(process template render-context)
            ]
        ;; Add section-id if present
        (if section-id
          (cond
            ;; Single hiccup element - add id to it
            (and (vector? rendered)
                 (keyword? (first rendered)))
            (let [[tag & rest-content] rendered
                  has-attrs? (and (seq rest-content) (map? (first rest-content)))
                  attrs (if has-attrs? (first rest-content) {})
                  children (if has-attrs? (rest rest-content) rest-content)]
              (vec (concat [tag (assoc attrs :id section-id)] children)))

            ;; Multiple elements or non-hiccup - wrap in div
            :else
            [:div {:id section-id} rendered])
          ;; No section-id, return as-is
          rendered)))))

;; ----------------------------------------------------------------------------
;; Default handler
;; ----------------------------------------------------------------------------

(defmethod process-directive :default [[directive & _] context]
  ;; Unknown directive - warn and return as-is
  (when-let [warn! (:warn! context)]
    (warn! {:type :unknown-directive
            :directive directive}))
  [:span.unknown-directive (str "[" directive " ...]")])

;; ============================================================================
;; Main Processing Function
;; ============================================================================

(defn process
  "Process an element (template or content) with the given context.
   Dispatches to appropriate handler based on element type."
  [elem context]
  (cond
    ;; Nil/empty
    (nil? elem) nil

    ;; Eden directive
    (eden-directive? elem)
    (process-directive elem context)

    ;; Regular hiccup element
    (and (vector? elem)
         (keyword? (first elem))
         (not (eden-directive? elem)))
    (let [[tag & content] elem
          has-attrs? (and (seq content) (map? (first content)))
          attrs (if has-attrs? (first content) {})
          children (if has-attrs? (rest content) content)
          ;; Process attributes
          processed-attrs (if has-attrs?
                            (into {} (map (fn [[k v]]
                                            [k (if (eden-directive? v)
                                                 (process v context)
                                                 v)])
                                          attrs))
                            {})
          ;; Process children
          processed-children (map #(process % context) children)]
      ;; Rebuild element
      (vec (concat [tag]
                   (when has-attrs? [processed-attrs])
                   processed-children)))

    ;; Collection of elements
    (sequential? elem)
    (vec (map #(process % context) elem))

    ;; Scalar values
    :else elem))
