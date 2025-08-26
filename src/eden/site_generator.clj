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
      (str "### " key-or-path " ###"))))

;; :eden/link - Smart linking directive
(defmethod process-element :eden/link [elem context]
  (let [[_ link-spec & body] elem
        ;; Process the link spec in the current context to support dynamic values
        ;; This allows [:eden/link [:eden/get :content-key] ...] to work in loops
        ;; NOTE: Map values are NOT recursively processed, so {:content-key [:eden/get :x]}
        ;; won't evaluate the inner :eden/get. Only simple expressions are supported.
        processed-spec (process-element link-spec context)
        ;; Normalize link spec
        link-config (if (keyword? processed-spec)
                      {:content-key processed-spec}
                      processed-spec)
        ;; Use current page if no content-key specified
        page-id (or (:content-key link-config)
                    (when (contains? link-config :lang)
                      (last (:path context))))
        ;; Check if requested language is configured
        requested-lang (:lang link-config)
        lang-configured? (or (nil? requested-lang)
                             (get-in context [:site-config :lang requested-lang]))
        ;; Override language if specified and configured
        context (if (and requested-lang lang-configured?)
                  (assoc context :lang requested-lang)
                  context)
        ;; Determine target and path
        target-info (cond
                      ;; Skip processing if language not configured
                      (and requested-lang (not lang-configured?))
                      (do
                        (when-let [warn! (:warn! context)]
                          (warn! {:type :unconfigured-language
                                  :lang requested-lang
                                  :content-key page-id
                                  :location (str "Template: " (get-in context [:render-stack 0 1] "unknown"))
                                  :message (str "Language '" (name requested-lang)
                                                "' referenced in :eden/link but not configured in site.edn. "
                                                "Add it to :lang configuration.")}))
                        nil)

                      ;; Link by content-key - check both pages and sections
                      page-id
                      (let [has-page? (get-in context [:pages page-id])
                            has-section? (get-in context [:sections page-id])
                            current-page-id (:current-page-id context)]
                        ;; Warn about ambiguity
                        (when (and has-page? has-section? (:warn! context))
                          ((:warn! context) {:type :ambiguous-link
                                             :link-id page-id
                                             :as-page (:slug has-page?)
                                             :as-section (let [{:keys [parent-template section-id]} has-section?]
                                                           (str (get-in context [:pages parent-template :slug]) "#" section-id))
                                             :resolved-to :page
                                             :location (get-render-stack context)}))
                        (cond
                          ;; Prefer standalone page
                          has-page?
                          {:page has-page?
                           :content-key page-id}

                          ;; Check sections
                          has-section?
                          (let [{:keys [parent-template section-id]} has-section?]
                            (if (= parent-template current-page-id)
                              ;; Same page - use hash link
                              {:section-link (str "#" section-id)}
                              ;; Different page - link to page with hash
                              (when-let [parent-page (get-in context [:pages parent-template])]
                                {:page parent-page
                                 :content-key parent-template
                                 :section-hash section-id})))

                          ;; Not found
                          :else nil))

                      ;; Navigation links
                      (= (:nav link-config) :parent)
                      (let [current-path (:path context)]
                        (cond
                          ;; No path or at root - no parent
                          (or (nil? current-path) (empty? current-path))
                          nil

                          ;; One level deep - parent is root
                          (= (count current-path) 1)
                          (when-let [page (or (get-in context [:pages :landing])
                                              (get-in context [:pages :home]))]
                            {:page page
                             :content-key :landing})

                          ;; Multiple levels - get parent
                          :else
                          (let [parent-path (vec (butlast current-path))
                                parent-id (last parent-path)]
                            (when-let [page (get-in context [:pages parent-id])]
                              {:page page
                               :content-key parent-id}))))

                      (= (:nav link-config) :root)
                      (when-let [page (or (get-in context [:pages :landing])
                                          (get-in context [:pages :home]))]
                        {:page page
                         :content-key :landing})

                      :else nil)]
    (if target-info
      (let [;; Handle different target types
            [url title] (cond
                          ;; Section on same page
                          (:section-link target-info)
                          [(:section-link target-info) ""]

                          ;; Page (possibly with section hash)
                          (:page target-info)
                          (let [page (:page target-info)
                                base-url (if-let [page->url (:page->url context)]
                                           (page->url {:slug (:slug page)
                                                       :lang (:lang context)
                                                       :site-config (:site-config context)})
                                           ;; Fallback to old URL generation
                                           (let [lang (:lang context)
                                                 is-default-lang (or (= lang :no) (nil? lang))
                                                 slug (:slug page)]
                                             (if is-default-lang
                                               (str "/" slug)
                                               (str "/" (name lang) "/" slug))))
                                ;; Add section hash if present
                                final-url (if-let [section-hash (:section-hash target-info)]
                                            (str base-url "#" section-hash)
                                            base-url)]
                            [final-url (:title page)])

                          :else
                          ["#" ""])
            ;; Merge link info into :data
            link-context (update context :data assoc
                                 :link/href url
                                 :link/title title)]
        ;; Process body with link context
        (if (= (count body) 1)
          (let [result (process-element (first body) link-context)]
            ;; If result is a vector of elements from :eden/each, return it as-is
            ;; to be flattened by parent context
            result)
          (vec (map #(process-element % link-context) body))))
      ;; No target found or language not configured
      (cond
        ;; Language not configured - skip rendering
        (and requested-lang (not lang-configured?))
        nil

        ;; Parent at root returns nil
        (and (= (:nav link-config) :parent)
             (or (nil? (:path context)) (empty? (:path context))))
        nil

        ;; Other cases: use placeholder
        :else
        (do
          ;; Record warning if content-key was specified and warnings atom exists
          (when (and (:warn! context) page-id)
            ((:warn! context) {:type :missing-page
                               :content-key page-id
                               :render-stack (get-render-stack context)}))
          ;; Process with placeholder values
          (when (seq body)
            (let [link-context (update context :data assoc
                                       :link/href "#"
                                       :link/title (if (keyword? processed-spec)
                                                     (name processed-spec)
                                                     (str processed-spec)))]
              (if (= (count body) 1)
                (process-element (first body) link-context)
                (vec (map #(process-element % link-context) body))))))))))

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
        ;; Evaluate condition - check if it's an sg directive first
        condition-value (cond
                          ;; sg directive - process it
                          (and (vector? condition)
                               (keyword? (first condition))
                               (= "eden" (namespace (first condition))))
                          (process-element condition context)

                          ;; Simple keyword - look up in context
                          (keyword? condition)
                          (get-in context [:data condition])

                          ;; Vector path (non-sg directive)
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
      (let [;; Get data from content-data if data-key is specified
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
      (->RawString value)

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
        limit (:limit options)
        order-by (:order-by options)
        group-by (:group-by options)
        where (:where options)
        ;; Determine the collection source
        collection (cond
                     ;; Special :eden/all keyword - get all content
                     (= :eden/all collection-key)
                     (when-let [content-data (:content-data context)]
                       ;; Convert content map to a vector of maps with :page-id
                       (let [content-vec (mapv (fn [[id data]]
                                                 (assoc data :content-key id))
                                               content-data)]
                         ;; Apply where filter if provided
                         (if where
                           (filterv (fn [item]
                                      ;; Check all where conditions (AND logic)
                                      (every? (fn [[k v]]
                                                (= (get item k) v))
                                              where))
                                    content-vec)
                           content-vec)))

                     ;; Regular collection from :data
                     :else
                     (let [coll (get-in context [:data collection-key])]
                       ;; Apply where filter if provided and collection is sequential
                       (if (and where (sequential? coll))
                         (filterv (fn [item]
                                    ;; Check all where conditions (AND logic)
                                    (every? (fn [[k v]]
                                              (= (get item k) v))
                                            where))
                                  coll)
                         coll)))]
    (cond
      ;; Handle maps
      (map? collection)
      (let [;; Convert map to sequence of [k v] pairs
            map-entries (seq collection)
            ;; Sort if order-by specified
            sorted-entries (if order-by
                             (let [order-specs (partition 2 order-by)
                                   comparators (map (fn [[field dir]]
                                                      (cond
                                                        ;; Sort by key
                                                        (= field :eden.each/key)
                                                        (if (= dir :desc)
                                                          #(compare (first %2) (first %1))
                                                          #(compare (first %1) (first %2)))
                                                        ;; Sort by value (if map) or by :eden.each/value
                                                        (= field :eden.each/value)
                                                        (if (= dir :desc)
                                                          #(compare (second %2) (second %1))
                                                          #(compare (second %1) (second %2)))
                                                        ;; Sort by field in value (if value is a map)
                                                        :else
                                                        (if (= dir :desc)
                                                          #(compare (get (second %2) field)
                                                                    (get (second %1) field))
                                                          #(compare (get (second %1) field)
                                                                    (get (second %2) field)))))
                                                    order-specs)]
                               (sort (fn [a b]
                                       (loop [comps comparators]
                                         (if (empty? comps)
                                           0
                                           (let [result ((first comps) a b)]
                                             (if (zero? result)
                                               (recur (rest comps))
                                               result)))))
                                     map-entries))
                             map-entries)
            ;; Apply limit
            limited-entries (if limit
                              (take limit sorted-entries)
                              sorted-entries)]
        ;; Process template for each entry with index
        (vec (map-indexed (fn [idx [k v]]
                            (let [;; Build context based on value type
                                  item-context (cond
                                                 ;; If value is a map, merge it into :data
                                                 (map? v)
                                                 (update context :data merge
                                                         v
                                                         {:eden.each/key k
                                                          :eden.each/index idx})
                                                 ;; Otherwise, add as minimal fields
                                                 :else
                                                 (update context :data merge
                                                         {:eden.each/key k
                                                          :eden.each/value v
                                                          :eden.each/index idx}))]
                              (process-element template item-context)))
                          limited-entries)))

      ;; Handle sequences (existing logic with index added)
      (sequential? collection)
      (if group-by
        ;; Group-by mode
        (let [;; Apply where filter before grouping if provided
              filtered-coll (if where
                              (filterv (fn [item]
                                         ;; Check all where conditions (AND logic)
                                         (every? (fn [[k v]]
                                                   (= (get item k) v))
                                                 where))
                                       collection)
                              collection)
              ;; Group items by the specified field
              group-path (if (vector? group-by) group-by [group-by])
              grouped (clojure.core/group-by #(get-in % group-path) filtered-coll)
              ;; Convert to vector of [group-key items] pairs to maintain order
              group-pairs (vec grouped)
              ;; Sort groups if order-by specified with :eden.each/group-key
              sorted-groups (if (and order-by
                                     (some #(= % :eden.each/group-key) order-by))
                              (let [order-specs (partition 2 order-by)
                                    comparators (map (fn [[field dir]]
                                                       (if (= field :eden.each/group-key)
                                                         (if (= dir :desc)
                                                           #(compare (first %2) (first %1))
                                                           #(compare (first %1) (first %2)))
                                                         (constantly 0)))
                                                     order-specs)]
                                (sort (fn [a b]
                                        (loop [comps comparators]
                                          (if (empty? comps)
                                            0
                                            (let [result ((first comps) a b)]
                                              (if (zero? result)
                                                (recur (rest comps))
                                                result)))))
                                      group-pairs))
                              group-pairs)
              ;; Apply limit to groups if specified
              limited-groups (if limit
                               (take limit sorted-groups)
                               sorted-groups)]
          ;; Process template for each group with index
          (vec (mapcat (fn [[idx [group-key items]]]
                         (let [;; Add items with their own indices for nested :eden/each
                               indexed-items (vec (map-indexed (fn [item-idx item]
                                                                 (assoc item :eden.each/index item-idx))
                                                               items))
                               ;; Merge first item plus group metadata into :data
                               group-context (update context :data merge
                                                     (first items)
                                                     {:eden.each/group-key group-key
                                                      :eden.each/group-items indexed-items
                                                      :eden.each/index idx})
                               ;; Process template returns a single element per group
                               ;; but we need to ensure it's wrapped properly for mapcat
                               result (process-element template group-context)]
                           (if (and (vector? result)
                                    (not (keyword? (first result))))
                             result ; It's already a vector of elements
                             [result]))) ; Wrap single element
                       (map-indexed vector limited-groups))))
        ;; Regular mode (no grouping) - with index
        (let [;; Apply where filter if provided
              filtered-coll (if where
                              (filterv (fn [item]
                                         ;; Check all where conditions (AND logic)
                                         (every? (fn [[k v]]
                                                   (= (get item k) v))
                                                 where))
                                       collection)
                              collection)
              sorted-coll (if order-by
                            (let [order-specs (partition 2 order-by)
                                  comparators (map (fn [[field dir]]
                                                     (if (= dir :desc)
                                                       #(compare (get %2 field) (get %1 field))
                                                       #(compare (get %1 field) (get %2 field))))
                                                   order-specs)]
                              (sort (fn [a b]
                                      (loop [comps comparators]
                                        (if (empty? comps)
                                          0
                                          (let [result ((first comps) a b)]
                                            (if (zero? result)
                                              (recur (rest comps))
                                              result)))))
                                    filtered-coll))
                            filtered-coll)
              limited-coll (if limit
                             (take limit sorted-coll)
                             sorted-coll)]
          ;; Add index to each item
          (vec (map-indexed (fn [idx item]
                              (let [item-context (if (map? item)
                                                   (update context :data merge
                                                           item
                                                           {:eden.each/index idx})
                                                   (update context :data merge
                                                           {:eden.each/value item
                                                            :eden.each/index idx}))]
                                (process-element template item-context)))
                            limited-coll))))

      ;; No collection
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
