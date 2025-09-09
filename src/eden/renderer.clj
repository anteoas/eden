(ns eden.renderer
  (:require [replicant.string :as rs]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [eden.site-generator3 :as sg]
            [eden.loader :as loader]))

#_(defn scan-for-sections
  "Scan expanded templates for :eden/render sections and build sections registry.
   Requires :expanded-templates in context."
  [{:keys [expanded-templates] :as ctx}]
  {:pre [(contains? ctx :expanded-templates)]}
  (let [sections (atom {})]
    ;; Walk each expanded template
    (doseq [[template-id template-data] expanded-templates]
      (walk/prewalk
       (fn [elem]
         (when (and (vector? elem)
                    (= :eden/render (first elem))
                    (map? (second elem))
                    (:section-id (second elem)))
           (let [spec (second elem)
                 section-id (:section-id spec)
                 data-key (or (:data spec) (:template spec))
                 new-section {:section-id section-id
                              :parent-template template-id
                              :data data-key}]
             ;; Store the section (data-key is the key, not section-id!)
             (swap! sections assoc data-key new-section)))
         elem)
       (:body template-data)))
    ;; Add sections to context (no duplicate detection or warnings)
    (assoc ctx :sections @sections)))

#_(defn expand-all-templates
  "Expand all templates once and cache the results.
   
   Returns context with :expanded-templates added, containing:
   - :eden/references - Set of page IDs referenced via :eden/link
   - :eden/includes - Set of template IDs referenced via :eden/include
   - :eden/renders - Set of template IDs referenced via :eden/render
   - :body - The expanded template body (for future use)
   
   This avoids redundant expansion work during dependency collection."
  [{:keys [templates] :as ctx}]
  (let [expanded-templates
        (reduce-kv
         (fn [acc template-id template]
           (let [expanded (sg/expand-template template {})
                 references (sg/collect-references expanded)
                 ;; Also collect :eden/include references
                 includes (atom #{})
                 ;; And collect :eden/render references
                 renders (atom #{})
                 _ (clojure.walk/prewalk
                    (fn [elem]
                      (cond
                        ;; Collect :eden/include
                        (and (vector? elem)
                             (= :eden/include (first elem))
                             (keyword? (second elem)))
                        (do (swap! includes conj (second elem))
                            elem)

                        ;; Collect :eden/render
                        (and (vector? elem)
                             (= :eden/render (first elem)))
                        (let [spec (second elem)]
                          (cond
                            ;; Simple keyword form
                            (keyword? spec)
                            (swap! renders conj spec)

                            ;; Map form with :template key
                            (and (map? spec) (:template spec))
                            (swap! renders conj (:template spec)))
                          elem)

                        :else elem))
                    template)]
             (assoc acc template-id
                    {:eden/references (set references)
                     :eden/includes @includes
                     :eden/renders @renders
                     :body expanded})))
         {}
         templates)]
    (assoc ctx :expanded-templates expanded-templates)))

#_(defn collect-dependencies-and-pages
  "Collect all templates that need to be processed and determine which ones should be rendered.
   
   Uses a breadth-first traversal to find all referenced templates via :eden/link, :eden/include, and :eden/render.
   
   Returns a map with:
   - :visited - All templates processed (pages + includes + renders + wrapper)
   - :pages-to-render - Only templates that should produce HTML files
   
   The wrapper template is always processed but never rendered.
   Includes and renders are processed but don't produce HTML files."
  [{:keys [expanded-templates content render-roots config]}]
  (let [wrapper (:wrapper config)
        ;; Start with render-roots and wrapper in processing queue
        initial-queue (if wrapper
                        (conj (vec render-roots) wrapper)
                        (vec render-roots))
        ;; Only render-roots start as pages (wrapper is not a page)
        initial-pages render-roots

        ;; Helper to check if a page exists in any language
        page-exists? (fn [page-id]
                       (or (contains? expanded-templates page-id)
                           ;; Check all language content maps
                           (some #(contains? % page-id) (vals content))))]
    (loop [processing-queue initial-queue
           visited #{}
           pages-to-render initial-pages]
      (if (empty? processing-queue)
        ;; Return both sets
        {:visited visited
         :pages-to-render pages-to-render}
        (let [current (first processing-queue)
              remaining-queue (rest processing-queue)]
          (if (contains? visited current)
            ;; Already processed this template, skip it
            (recur remaining-queue visited pages-to-render)
            ;; Process this template
            (let [;; Mark as visited
                  new-visited (conj visited current)
                  ;; Get the expanded template
                  expanded (get expanded-templates current)
                  ;; Get references, includes, and renders from the expanded template
                  references (or (:eden/references expanded) #{})
                  includes (or (:eden/includes expanded) #{})
                  renders (or (:eden/renders expanded) #{})
                  ;; Filter to only existing templates/pages
                  existing-refs (filter page-exists? references)
                  existing-includes (filter #(contains? expanded-templates %) includes)
                  existing-renders (filter #(contains? expanded-templates %) renders)
                  ;; Add unvisited references to queue and pages-to-render
                  refs-to-add (set/difference (set existing-refs) new-visited)
                  ;; Add unvisited includes and renders to queue only (not pages-to-render)
                  includes-to-add (set/difference (set existing-includes) new-visited)
                  renders-to-add (set/difference (set existing-renders) new-visited)]
              (recur (into (vec remaining-queue) (concat refs-to-add includes-to-add renders-to-add))
                     new-visited
                     (into pages-to-render refs-to-add)))))))))

#_(defn render-page
  "Render page HTML if content exists. Returns page with :html and any :warnings."
  [{:keys [config templates pages-registry strings page->url content sections build-constants]} page]
  (if-let [page-content (:content page)]
    (let [;; Collect warnings for this page
          warnings (atom [])

          ;; Determine template: explicit template, type field, or content-key
          explicit-template (:template page-content)
          template-name (or explicit-template
                            (:content-key page))

          ;; Add warning if we're defaulting to content-key
          _ (when (nil? explicit-template)
              (swap! warnings conj
                     {:type :defaulted-template
                      :content-key (:content-key page)
                      :defaulted-to template-name
                      :message (format "Page '%s' has no :template field, defaulting to '%s'"
                                       (:content-key page) template-name)}))

          ;; Convert to keyword if string
          template-key (if (string? template-name)
                         (keyword template-name)
                         template-name)
          ;; Try to get template, fall back to :default
          template (or (get templates template-key)
                       (get templates :default))
          wrapper (get templates (:wrapper config))
          ;; Build path vector from content-key (simplified for now)
          ;; In full implementation, this would be derived from site structure
          page-path (if (= (:content-key page) :landing)
                      []
                      [(:content-key page)])
          ;; Get strings for this language
          lang-strings (get strings (:lang-code page))
          ;; Get all content for this language as content-data
          ;; This allows :eden/render to access any content file's data
          lang-content (get content (:lang-code page) {})]
      (if template
        (let [;; Collect warnings from both process calls
              warn! (fn [warning] (swap! warnings conj warning))
              ;; Merge build-constants with page content under :data
              ;; Page content takes precedence over build-constants
              enriched-content {:data (merge build-constants page-content)
                                :build-constants build-constants ; Keep for directives to use
                                :lang (:lang-code page)
                                :path page-path
                                :pages pages-registry
                                :strings lang-strings
                                :content-data lang-content
                                :templates templates
                                :page->url page->url
                                :site-config config
                                :sections sections
                                :current-page-id (:content-key page)
                                :render-stack [[:content (:content-key page)]
                                               [:template template-key]]
                                :content-key (:content-key page)
                                :warn! warn!}
              page-html (sg/process template enriched-content)
              ;; Pass enriched content to wrapper with page-html as :body
              ;; Wrapper gets page content in :data plus the rendered body
              wrapper-context (-> enriched-content
                                  (assoc :body page-html) ; :body is a system field, not user data
                                  (sg/push-render-context :wrapper (:wrapper config)))
              wrapped-html (sg/process wrapper wrapper-context)]
          ;; Preserve all page data including slug
          (cond-> (assoc page
                         :html (str "<!DOCTYPE html>" (rs/render (sg/prepare-for-render wrapped-html)))
                         :slug (:slug page-content))
            (seq @warnings) (assoc :warnings @warnings)))
        ;; Template not found - add warning
        (assoc page
               :warnings (conj @warnings
                               {:type :missing-template
                                :template-name template-name
                                :content-key (:content-key page)}))))
    ;; No content - add error
    (assoc page
           :warnings [{:type :missing-content
                       :content-key (:content-key page)
                       :lang-code (:lang-code page)}])))

#_(defn render-all-pages
  "Render all pages and collect warnings"
  [{:keys [pages config templates page->url content build-constants] :as ctx}]
  ;; First build the page registry from all pages
  ;; Use resolve to avoid cyclic dependency
  (let [build-page-registry-fn (requiring-resolve 'eden.builder/build-page-registry)
        pages-registry (@build-page-registry-fn pages)
        ;; Load translation strings for all languages
        strings (reduce (fn [acc [lang-code _]]
                          (if-let [lang-strings (loader/load-translation-strings
                                                 (:root-path config)
                                                 lang-code)]
                            (assoc acc lang-code lang-strings)
                            acc))
                        {}
                        (:lang config))
        ;; Render all pages
        rendered-pages (mapv #(render-page {:config config
                                            :templates templates
                                            :pages-registry pages-registry
                                            :strings strings
                                            :page->url page->url
                                            :content content
                                            :build-constants build-constants
                                            :sections (:sections ctx)} %)
                             pages)
        ;; Collect warnings from individual page renders
        warnings (reduce (fn [acc page]
                           (if-let [w (:warnings page)]
                             (concat acc w)
                             acc))
                         []
                         rendered-pages)]
    (assoc ctx
           :pages rendered-pages
           :warnings (when (seq warnings)
                       {:page-warnings warnings}))))

#_(defn format-warnings
  "Format warnings for display. Takes the warnings map and returns formatted strings."
  [warnings]
  (let [messages (atom [])]
    ;; Format page-level warnings
    (when-let [warnings (:page-warnings warnings)]
      (doseq [w warnings]
        (case (:type w)
          :missing-template
          (swap! messages conj (str "WARNING: Template '" (:template-name w)
                                    "' not found for page '" (:content-key w) "'"))
          :missing-content
          (swap! messages conj (str "ERROR: No content for " (:content-key w)
                                    " in " (:lang-code w)))
          ;; Default
          (swap! messages conj (str "WARNING: " (pr-str w))))))

    ;; Format other warnings (currently only page-warnings exist)
    (doseq [[warning-type items] (dissoc warnings :page-warnings)]
      (doseq [item items]
        (case warning-type
          :missing-pages
          (swap! messages conj (str "WARNING: Missing page '" (:content-key item)
                                    "' referenced at " (:render-stack item)))
          :missing-templates
          (swap! messages conj (str "WARNING: Missing template '" (:template-id item)
                                    "' at " (:render-stack item)))
          :unconfigured-languages
          (swap! messages conj (str "WARNING: Unconfigured language '" item "'"))
          :ambiguous-links
          (swap! messages conj (str "WARNING: Ambiguous link '" (:link-id item)
                                    "' exists as both page and section"))
          :missing-keys
          (swap! messages conj (str "WARNING: Missing key '" (:key item)
                                    "' at " (:render-stack item)))
          ;; Default
          (swap! messages conj (str "WARNING: " warning-type " - " (pr-str item))))))
    @messages))

(defn render-page
  "Render a single page, discovering references dynamically"
  [{:keys [site-config templates content strings warn!] :as ctx} content-key]
  ;; Check all languages for this page
  (let [pages-by-lang (reduce (fn [acc [lang-code lang-content]]
                                (if-let [page-content (get lang-content content-key)]
                                  (assoc acc lang-code page-content)
                                  acc))
                              {}
                              content)]
    (if (empty? pages-by-lang)
      ;; Page doesn't exist in any language
      (warn! {:type :missing-content
              :content-key content-key
              :message (str "Page " content-key " not found in any language")})
      ;; Render page for each language it exists in
      (reduce (fn [results [lang page-content]]
                (let [ ;; Determine template
                      template-key (or (:template page-content) content-key)
                      template (get templates template-key)
                      wrapper (get templates (:wrapper site-config))

                      ;; Get strings for this language
                      lang-strings (get strings lang)
                      page-data (assoc page-content :lang lang)

                      ;; Build context for rendering
                      render-context (assoc ctx
                                            ;; TODO: do we need :lang in :data?
                                            :data page-data
                                            :lang lang
                                            :content-key content-key
                                            :strings lang-strings
                                            ;; All content for languge
                                            ;; Used as source for eden/each queries
                                            ;; Should we include every language here?
                                            :content-data (get content lang))]
                  (if (not template)
                    (do
                      (warn! {:type :missing-template
                              :template template-key
                              :content-key content-key
                              :lang lang})
                      results)
                    ;; Process template and wrapper
                    (let [processed-template (sg/process template render-context)
                          processed-page (if wrapper
                                           (sg/process wrapper (assoc render-context :body processed-template))
                                           processed-template)]
                      ;; Return page data with HTML containing placeholders
                      (conj results (assoc page-data :rendered/page processed-page))))))
              []
              pages-by-lang))))
