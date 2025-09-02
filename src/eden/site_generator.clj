(ns eden.site-generator
  "Refactored site generator using multimethods for cleaner dispatch"
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; Record for raw HTML strings that shouldn't be escaped
(defrecord RawString [s]
  Object
  (toString [_] s))

;; Helper functions
(defn vector-of-vectors? [v]
  (and (vector? v)
       (every? vector? v)))

(defn eden-directive? [elem]
  (and (vector? elem)
       (keyword? (first elem))
       (namespace (first elem))
       (= "eden" (namespace (first elem)))))

(defn push-render-context
  "Push a new context onto the render stack"
  [context type id]
  (update context :render-stack (fnil conj []) [type id]))

(defn get-render-stack
  "Get the current render stack"
  [context]
  (:render-stack context []))

(defmulti expand-directive
  "Expand a directive to make references explicit.
   Returns the expanded element (possibly unchanged).

   For :eden/link directives, expands to a map format that makes
   page references explicit for dependency tracking.

   Other directives are returned unchanged."
  (fn [elem _context]
    (cond
      (and (vector? elem)
           (eden-directive? elem))
      (first elem)

      ;; Already expanded (map format)
      (and (map? elem)
           (contains? elem :eden/expanded))
      :already-expanded

      :else :default)))

(defmethod expand-directive :eden/link [elem _context]
  (let [[_ link-spec & body] elem
        ;; Normalize to map
        expanded-arg (if (keyword? link-spec)
                       {:content-key link-spec}
                       link-spec)]
    ;; Always expand to map format
    (cond-> {:eden/expanded :link
             :expanded-arg expanded-arg
             :body (vec body)}
      ;; Add references only if there's a page-id
      (:content-key expanded-arg)
      (assoc :eden/references #{(:content-key expanded-arg)}))))

(defmethod expand-directive :eden/render [elem _context]
  ;; For :eden/render, we don't need to track references during expansion
  ;; Just return it as-is to be processed during rendering
  {:element elem
   :references #{}})

(defmethod expand-directive :already-expanded [elem _context]
  elem)

(defmethod expand-directive :default [elem _context]
  ;; Everything else passes through unchanged
  elem)

(defn expand-template
  "Walk a template and expand all directives"
  [template context]
  (walk/postwalk
   (fn [elem]
     (expand-directive elem context))
   template))

(defn collect-references
  "Collect all page references from an expanded template"
  [expanded-template]
  (let [refs (atom #{})]
    (walk/prewalk
     (fn [elem]
       (when (and (map? elem) (:eden/references elem))
         (swap! refs into (:eden/references elem)))
       elem)
     expanded-template)
    @refs))

(defn parse-eden-each-args
  "Parse :eden/each arguments into collection-key, options-map, and template"
  [args]
  (let [collection-key (first args)
        remaining (rest args)
        template-idx (loop [idx 0
                            rem remaining]
                       (cond
                         (empty? rem) idx
                         (and (vector? (first rem))
                              (keyword? (first (first rem))))
                         idx
                         (keyword? (first rem))
                         (recur (+ idx 2) (drop 2 rem))
                         :else
                         (recur (inc idx) (rest rem))))
        options-list (take template-idx remaining)
        template (nth remaining template-idx nil)
        options-map (apply hash-map options-list)]
    {:collection-key collection-key
     :options options-map
     :template template}))

;; Multimethod dispatch
(defmulti process-element
  "Process a template element based on its type"
  (fn [elem _context]
    (cond
      ;; Not a vector
      (not (vector? elem)) :scalar

      ;; Empty vector
      (empty? elem) :empty

      ;; eden directives
      (and (keyword? (first elem))
           (namespace (first elem))
           (= "eden" (namespace (first elem))))
      (first elem)

      ;; Vector of vectors (multiple elements)
      (and (vector? (first elem))
           (keyword? (first (first elem))))
      :vector-of-vectors

      ;; Regular hiccup element
      (keyword? (first elem))
      :hiccup

      ;; Default
      :else :default)))

;; :eden/t - Translation directive
(defmethod process-element :eden/t [elem context]
  (let [[_ key-or-path & args] elem
        ;; Check if last arg is a map (interpolation) or string (default)
        has-interpolation? (and (seq args) (map? (last args)))
        has-default? (and (seq args) (not has-interpolation?) (string? (last args)))
        raw-interpolation (when has-interpolation? (last args))
        ;; Process interpolation values to handle [:eden/get ...] directives
        interpolation (when raw-interpolation
                        (into {}
                              (map (fn [[k v]]
                                     [k (if (and (vector? v) (eden-directive? v))
                                          (process-element v context)
                                          v)])
                                   raw-interpolation)))
        default-value (when has-default? (last args))
        ;; Get translation key
        trans-key (if (vector? key-or-path) key-or-path [key-or-path])
        ;; Look up in strings
        strings (:strings context)
        translation (get-in strings trans-key)]
    (cond
      ;; Found translation
      translation
      (if interpolation
        ;; Replace {{key}} with values from interpolation map
        (reduce (fn [text [k v]]
                  (str/replace text
                               (str "{{" (name k) "}}")
                               (str v)))
                translation
                interpolation)
        translation)

      ;; Has default
      has-default?
      default-value

      ;; Missing translation
      :else
      (do
        (when-let [warn! (:warn! context)]
          (warn! {:type :missing-translation
                  :key key-or-path
                  :lang (:lang context)
                  :location (get-render-stack context)}))
        (str "### " key-or-path " ###")))))

;; :eden/link - Smart linking directive
(defmethod process-element :eden/link [elem context]
  (let [[_ link-spec & body] elem
        ;; Process spec to support dynamic values like [:eden/get :content-key]
        processed-spec (process-element link-spec context)
        ;; Normalize to config map
        config (if (keyword? processed-spec)
                 {:content-key processed-spec}
                 processed-spec)
        {:keys [content-key nav]} config]

    ;; Add reference if it's a content page
    (when-let [add-reference! (:add-reference! context)]
      (when (and content-key (not nav))
        (add-reference! content-key)))

    ;; Create placeholders and process body
    (let [placeholder-href (assoc config :type :eden.link.placeholder/href)
          placeholder-title (assoc config :type :eden.link.placeholder/title)
          link-ctx (update context :data assoc
                           :link/href placeholder-href
                           :link/title placeholder-title)
          processed-body (if (= 1 (count body))
                           (process-element (first body) link-ctx)
                           (vec (map #(process-element % link-ctx) body)))]
      {:eden/link-placeholder config
       :body processed-body})))

;; Method implementations

(defmethod process-element :scalar [elem _context]
  elem)

(defmethod process-element :empty [elem _context]
  elem)

(defmethod process-element :vector-of-vectors [elem context]
  (vec (mapcat (fn [child]
                 (let [result (process-element child context)]
                   (if (and (eden-directive? child)
                            (vector? result)
                            (every? vector? result))
                     result
                     [result])))
               elem)))

(defmethod process-element :eden/body [_elem context]
  (let [body-content (if (map? context) (:body context) context)]
    (if (vector-of-vectors? body-content)
      (vec (mapcat (fn [child]
                     (let [result (process-element child context)]
                       (if (and (eden-directive? child)
                                (vector? result)
                                (every? vector? result))
                         result
                         [result])))
                   body-content))
      body-content)))

(defmethod process-element :eden/include [elem context]
  (let [templates (when (map? context) (:templates context))
        template-name (second elem)
        ;; Handle additional context override
        override-context (when (> (count elem) 2) (nth elem 2))
        ;; Push include to render stack
        updated-context (cond-> context
                          template-name (push-render-context :include template-name)
                          override-context (merge override-context))]
    (if-let [included (get templates template-name)]
      (process-element included updated-context)
      elem)))

(defmethod process-element :eden/if [elem context]
  (let [[_ condition then-branch else-branch] elem
        ;; Evaluate condition
        condition-value (cond
                          ;; Handle comparison operators
                          (and (vector? condition)
                               (#{:= :!= :< :> :<= :>=} (first condition)))
                          (let [[op a b] condition
                                val-a (process-element a context)
                                val-b (process-element b context)]
                            (case op
                              := (= val-a val-b)
                              :!= (not= val-a val-b)
                              :< (< val-a val-b)
                              :> (> val-a val-b)
                              :<= (<= val-a val-b)
                              :>= (>= val-a val-b)))

                          ;; Eden directive - process it
                          (and (vector? condition)
                               (keyword? (first condition))
                               (= "eden" (namespace (first condition))))
                          (process-element condition context)

                          ;; Simple keyword - look up in context
                          (keyword? condition)
                          (get-in context [:data condition])

                          ;; Vector path (non-Eden directive)
                          (and (vector? condition)
                               (keyword? (first condition)))
                          (get-in context (into [:data] condition))

                          ;; Direct value
                          :else condition)]
    (if condition-value
      (process-element then-branch context)
      (when else-branch
        (process-element else-branch context)))))

(defmethod process-element :eden/render [elem context]
  (let [[_ render-spec] elem
        ;; Process the render spec to support dynamic values like [:eden/get :template-id]
        processed-spec (process-element render-spec context)
        ;; Extract section-id if present (only from map specs) and process it
        section-id (when (map? render-spec)
                     (process-element (:section-id render-spec) context))
        ;; Determine data key and template
        [data-key template-id] (cond
                                 ;; Simple keyword form - look up template from data
                                 (keyword? processed-spec)
                                 (let [data (get-in context [:content-data processed-spec] {})]
                                   [processed-spec
                                    (or (:template data) processed-spec)])

                                 ;; Map form - use explicit template and data
                                 (map? processed-spec)
                                 ;; Process both :data and :template values to handle [:eden/get ...] directives
                                 (let [data-val (process-element (:data render-spec) context)
                                       template-val (if (:template render-spec)
                                                      (process-element (:template render-spec) context)
                                                      data-val)]
                                   [data-val template-val])

                                 ;; Fallback
                                 :else
                                 [nil nil])]

    ;; Validate data-key and template exist
    (cond
      ;; No valid data-key or template-id
      (or (nil? template-id)
          (and (not (keyword? template-id))
               (not (nil? template-id))))
      (do
        (when-let [warn! (:warn! context)]
          (warn! {:type :invalid-render-spec
                  :data-key data-key
                  :template-id template-id
                  :render-spec render-spec
                  :render-stack (get-render-stack context)}))
        ;; Return error placeholder instead of unchanged directive
        [:div.eden-render-error
         {:data-error "invalid-render-spec"
          :data-key (str data-key)
          :template-id (str template-id)}
         (str "<!-- Eden render error: Invalid render spec - data: "
              (pr-str data-key) " template: " (pr-str template-id) " -->")])

      ;; Template doesn't exist
      (nil? (get-in context [:templates template-id]))
      (do
        (when-let [warn! (:warn! context)]
          (warn! {:type :missing-template
                  :template-id template-id
                  :data-key data-key
                  :render-stack (get-render-stack context)}))
        ;; Return error placeholder
        [:div.eden-render-error
         {:data-error "missing-template"
          :template-id (str template-id)}
         (str "<!-- Eden render error: Missing template '" template-id "' -->")])

;; Valid template exists - process normally
      :else
      (let [;; Register section if present
            _ (when (and section-id (:add-section! context))
                ((:add-section! context) section-id {:parent (:content-key context)}))
            ;; Get data from content-data if data-key is specified
            content-data (when data-key
                           (get-in context [:content-data data-key] {}))
            ;; Merge build-constants with component's content under :data
            ;; Component data takes precedence over build-constants
            render-context (assoc context :data (merge (:build-constants context) content-data))
            ;; Process the template with merged context
            rendered-content (process-element (get-in context [:templates template-id]) render-context)]
        ;; Add section-id if present
        (if section-id
          (cond
            ;; Single hiccup element - add id to it
            (and (vector? rendered-content)
                 (keyword? (first rendered-content)))
            (let [[tag & rest-content] rendered-content
                  has-attrs? (and (seq rest-content) (map? (first rest-content)))
                  attrs (if has-attrs? (first rest-content) {})
                  children (if has-attrs? (rest rest-content) rest-content)]
              (vec (concat [tag (assoc attrs :id section-id)] children)))

            ;; Multiple elements or non-hiccup - wrap in div
            :else
            [:div {:id section-id} rendered-content])
          ;; No section-id, return as-is
          rendered-content)))))

(defmethod process-element :eden/get [elem context]
  (let [[_ key default-value] elem
        ;; Look in :data for all keys
        value (get-in context [:data key])
        first-key key
        is-content-html? (and (keyword? first-key)
                              (= "content" (namespace first-key))
                              (string? value))]
    (cond
      (nil? value)
      (if (= (count elem) 3) ; has default value
        default-value
        (let [key-str (name key)
              template-name (or (:current-template context) "unknown")
              page-id (or (:content-key context) "unknown")]
          ;; Collect warning if warn! function is available
          (when-let [warn! (:warn! context)]
            (warn! {:type :missing-key
                    :key key-str
                    :template template-name
                    :page page-id}))
          ;; Return a visible indicator for missing content
          [:span.missing-content (str "[:eden/get " key "]")]))

      is-content-html?
      [:span {:innerHTML value}]

      :else
      value)))

(defmethod process-element :eden/get-in [elem context]
  (let [path (second elem)
        default-value (when (> (count elem) 2)
                        (nth elem 2))
        has-default? (> (count elem) 2)
        value (if (empty? path)
                (:data context)
                (get-in context (into [:data] path) ::not-found))]
    (cond
      (not= value ::not-found)
      value

      has-default?
      default-value

      :else
      (str/join "." (map name path)))))

(defmethod process-element :eden/with
  [elem context]
  (let [[_ key-to-merge & body] elem
        value-to-merge (get-in context [:data key-to-merge])
        ;; Only merge into :data if value exists and is a map
        new-context (if (and value-to-merge (map? value-to-merge))
                      (update context :data merge value-to-merge)
                      context)]
    ;; Process body with potentially merged context
    ;; Handle multiple body elements properly
    (if (= 1 (count body))
      (process-element (first body) new-context)
      ;; When we have multiple body elements, process each and flatten if needed
      (vec (mapcat (fn [child]
                     (let [result (process-element child new-context)]
                       ;; Check if result needs flattening (e.g., from :eden/each)
                       (if (and (vector? result)
                                (not (keyword? (first result))))
                         result ; It's a vector of elements, use as-is for mapcat
                         [result]))) ; Single element, wrap for mapcat
                   body)))))

(defmethod process-element :eden/each [elem context]
  (let [{:keys [collection-key options template]} (parse-eden-each-args (rest elem))
        {:keys [limit order-by group-by where]} options

        ;; Common filter function
        filter-by-where (fn [coll]
                          (if where
                            (filterv (fn [item]
                                       (every? (fn [[k v]] (= (get item k) v))
                                               where))
                                     coll)
                            coll))

        ;; Common sort comparator builder
        make-comparator (fn [_ dir]
                          (let [desc? (= dir :desc)]
                            (fn [a b]
                              (let [result (compare a b)]
                                (if desc? (- result) result)))))

        ;; Get the collection based on source
        raw-collection (cond
                         (= :eden/all collection-key)
                         (when-let [content-data (:content-data context)]
                           (mapv (fn [[id data]]
                                   (assoc data :content-key id))
                                 content-data))

                         :else
                         (get-in context [:data collection-key]))

        ;; Apply filter if collection is sequential
        collection (if (sequential? raw-collection)
                     (filter-by-where raw-collection)
                     raw-collection)]

    (cond
      ;; No collection
      (nil? collection)
      []

      ;; Handle maps
      (map? collection)
      (let [entries (seq collection)
            sorted (if order-by
                     (let [order-specs (partition 2 order-by)]
                       (sort (fn [[k1 v1] [k2 v2]]
                               (loop [specs order-specs]
                                 (if-let [[field dir] (first specs)]
                                   (let [cmp (make-comparator field dir)
                                         result (case field
                                                  :eden.each/key (cmp k1 k2)
                                                  :eden.each/value (cmp v1 v2)
                                                  (cmp (get v1 field) (get v2 field)))]
                                     (if (zero? result)
                                       (recur (rest specs))
                                       result))
                                   0)))
                             entries))
                     entries)]
        (vec (map-indexed
              (fn [idx [k v]]
                (process-element template
                                 (update context :data merge
                                         (if (map? v) v {})
                                         {:eden.each/key k
                                          :eden.each/value (when-not (map? v) v)
                                          :eden.each/index idx})))
              (cond->> sorted
                limit (take limit)))))

      ;; Handle sequences with grouping
      (and (sequential? collection) group-by)
      (let [group-path (if (vector? group-by) group-by [group-by])
            groups (clojure.core/group-by #(get-in % group-path) collection)
            group-pairs (vec groups)
            sorted (if (and order-by (some #(= % :eden.each/group-key) order-by))
                     (let [order-specs (partition 2 order-by)]
                       (sort (fn [[k1 _] [k2 _]]
                               (loop [specs order-specs]
                                 (if-let [[field dir] (first specs)]
                                   (if (= field :eden.each/group-key)
                                     (let [cmp (make-comparator field dir)]
                                       (cmp k1 k2))
                                     (recur (rest specs)))
                                   0)))
                             group-pairs))
                     group-pairs)]
        (vec (mapcat
              (fn [[idx [group-key items]]]
                (let [indexed-items (map-indexed #(assoc %2 :eden.each/index %1) items)
                      result (process-element template
                                              (update context :data merge
                                                      (first items)
                                                      {:eden.each/group-key group-key
                                                       :eden.each/group-items indexed-items
                                                       :eden.each/index idx}))]
                  (if (and (vector? result) (not (keyword? (first result))))
                    result
                    [result])))
              (map-indexed vector (cond->> sorted
                                    limit (take limit))))))

      ;; Handle regular sequences  
      (sequential? collection)
      (let [sorted (if order-by
                     (let [order-specs (partition 2 order-by)]
                       (sort (fn [a b]
                               (loop [specs order-specs]
                                 (if-let [[field dir] (first specs)]
                                   (let [cmp (make-comparator field dir)
                                         result (cmp (get a field) (get b field))]
                                     (if (zero? result)
                                       (recur (rest specs))
                                       result))
                                   0)))
                             collection))
                     collection)]
        (vec (map-indexed
              (fn [idx item]
                (process-element template
                                 (update context :data merge
                                         (if (map? item) item {:eden.each/value item})
                                         {:eden.each/index idx})))
              (cond->> sorted
                limit (take limit)))))

      ;; Fallback
      :else
      [])))

(defmethod process-element :hiccup [elem context]
  (let [[tag & rest-elem] elem
        has-attrs? (and (seq rest-elem) (map? (first rest-elem)))
        attrs (when has-attrs? (first rest-elem))
        children (if has-attrs? (rest rest-elem) rest-elem)

        processed-attrs (when attrs
                          (reduce-kv (fn [m k v]
                                       (assoc m k (process-element v context)))
                                     {}
                                     attrs))

        processed-children (mapcat (fn [child]
                                     (let [result (process-element child context)]
                                       (cond
                                         ;; Result is a vector of vectors (e.g., from :eden/each or :eden/link with :eden/each)
                                         ;; Check if all elements are vectors (hiccup elements)
                                         (and (vector? result)
                                              (not (keyword? (first result)))
                                              (every? vector? result))
                                         result

                                         ;; sg/body with vector of vectors
                                         (and (vector? child)
                                              (= (first child) :eden/body)
                                              (vector-of-vectors? result))
                                         result

                                         :else
                                         [result])))
                                   children)]
    (if processed-attrs
      (into [tag processed-attrs] processed-children)
      (into [tag] processed-children))))

(defmethod process-element :default [elem _context]
  elem)

;; Public API - matches original
(defn process
  "Process template with content. If context has :warn! function, collects warnings."
  [base content]
  (cond
    ;; If content is not a map, wrap it in a minimal context
    (not (map? content))
    (process-element base {:body content})

    ;; Context already has warn! function, just process
    (:warn! content)
    (process-element base content)

    ;; Top-level call - add warning collection
    :else
    (let [warnings (atom [])
          warn! (fn [warning] (swap! warnings conj warning))
          context-with-warn (assoc content :warn! warn!)
          result (process-element base context-with-warn)]
      ;; Return result with any warnings collected
      (if (seq @warnings)
        {:result result :warnings @warnings}
        result))))

(defn prepare-for-render
  "Prepares a processed template for rendering with replicant.string.
   Converts RawString instances to use :innerHTML attribute."
  [elem]
  (walk/postwalk
   (fn [node]
     (cond
       ;; Convert RawString to a div with innerHTML
       (instance? RawString node)
       [:span {:innerHTML (.toString ^RawString node)}]

       ;; Leave everything else as-is
       :else node))
   elem))

;; Image URL extraction (unchanged)
(defn extract-image-urls
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

    (->> (re-seq url-pattern content)
         (map (fn [[_ url]]
                (let [[path query-string] (str/split url #"\?" 2)
                      params (parse-params query-string)]
                  (cond-> {:url url
                           :source-path path}
                    (not (:error params)) (merge (select-keys params [:width :height])
                                                 {:replace-url (generate-replace-url path params)})
                    (:error params) (assoc :error (:error params))))))
         vec)))
