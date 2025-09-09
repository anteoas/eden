(ns eden.builder
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [replicant.string :as rs]
            [eden.config :as config]
            [eden.renderer :as renderer]))

#_(defn build-page-registry
    "Build a registry of all pages from loaded content, organized by language"
    [pages]
    (reduce (fn [registry page]
              (if-let [content (:content page)]
                (let [page-id (:content-key page)
                      lang-code (:lang-code page)
                      slug (:slug content)
                      title (:title content)]
                  (if (and slug title)
                  ;; Include all content metadata in the registry, nested by language
                    (assoc-in registry [lang-code page-id] content)
                    (do
                      (println (str "WARNING: Page " page-id " (" lang-code ") missing required fields"))
                      (when-not slug (println "  - Missing :slug"))
                      (when-not title (println "  - Missing :title"))
                      registry)))
                registry))
            {}
            pages))

#_(defn- validate-pages
    "Validate that all pages have required fields"
    [pages]
    (let [invalid-pages (filter (fn [page]
                                  (let [content (:content page)]
                                    (or (nil? (:slug content))
                                        (nil? (:title content)))))
                                pages)]
      (when (seq invalid-pages)
        (println "ERROR: The following pages are missing required fields:")
        (doseq [page invalid-pages]
          (let [content (:content page)]
            (println (str "  - " (:content-key page) " (" (:lang-code page) ")")
                     (when-not (:slug content) " [missing :slug]")
                     (when-not (:title content) " [missing :title]"))))
        (throw (ex-info "Pages missing required fields" {:invalid-pages invalid-pages})))
      pages))

#_(defn- create-page-specs
    "Create page specifications for each page that needs to be built.

   A 'page spec' is a map describing a single HTML page to be generated, containing:
   - :content-key - The page identifier (e.g., :landing, :about, :products.logistics)
   - :lang-code   - The language for this page (e.g., :no, :en)
   - :is-index    - Whether this is the index/home page

   This function takes the set of pages that need to be rendered (from dependency
   collection) and expands it into individual page specs for each language combination.

   For example, if :pages-to-render contains #{:landing :about} and we have languages
   {:no {:default true} :en {}}, this creates 4 page specs:
   - {:content-key :landing :lang-code :no :is-index true}
   - {:content-key :landing :lang-code :en :is-index true}
   - {:content-key :about :lang-code :no :is-index false}
   - {:content-key :about :lang-code :en :is-index false}

   The index page (specified in config) gets special treatment with :is-index true."
    [{:keys [config pages-to-render] :as ctx}]
    (let [;; Get the index page from config
          index-page (:index config)
        ;; Get all configured languages
          lang-codes (keys (:lang config))
        ;; Create a page spec for each page × language combination
          page-specs (for [content-key pages-to-render
                           lang-code lang-codes]
                       {:content-key content-key
                        :lang-code lang-code
                        :is-index (= content-key index-page)})]
      (assoc ctx :pages page-specs)))

#_(defn- load-page-content
    "Load content for a specific page/language combination from pre-loaded content"
    [{:keys [content]} page]
    (let [lang-code (:lang-code page)
          content-key (:content-key page)
        ;; Get content from pre-loaded data
          page-content (get-in content [lang-code content-key])
        ;; Extract slug from content or use content-name as fallback
          slug (or (:slug page-content)
                   (name content-key))]
      (if page-content
        (assoc page
               :content page-content
               :slug slug)
      ;; No content found
        (do
          (println (str "WARNING: No content for " content-key " in " lang-code))
          page))))

#_(defn- load-all-content
    "Load content for all pages"
    [{:keys [pages] :as ctx}]
    (assoc ctx :pages (map #(load-page-content ctx %) pages)))

#_(defn- process-page-content
    "Process page content and add language info"
    [{:keys [default-lang]} page]
    (if-let [content (:content page)]
      (let [lang-code (:lang-code page)
            processed (assoc content
                             :lang lang-code
                             :lang-prefix (if (= lang-code default-lang) "" (str "/" (name lang-code))))]
        (assoc page :content processed))
      page))

#_(defn- process-all-content
    "Process content for all pages"
    [{:keys [pages default-lang] :as ctx}]
    (assoc ctx :pages (map #(process-page-content {:default-lang default-lang} %) pages)))

#_(defn- calculate-page-path
    "Calculate final URL path (without file extension)"
    [{:keys [default-lang]} page]
    (let [{:keys [lang-code is-index slug output-path]} page
        ;; Determine base path (no .html extension)
          base-path (cond
                      is-index "/"
                      output-path output-path ; Explicit path from old format
                      slug (str "/" slug)
                      :else (str "/" (name (:content-key page)))) ; Fallback
        ;; Add language prefix for non-default languages
        ;; Only skip prefix if BOTH: default language AND (index OR regular page)
          final-path (if (= lang-code default-lang)
                       base-path
                       (str "/" (name lang-code) base-path))]
      (assoc page :path final-path)))

#_(defn- calculate-all-paths
    "Calculate paths for all pages"
    [{:keys [pages default-lang] :as ctx}]
    (assoc ctx :pages (map #(calculate-page-path {:default-lang default-lang} %) pages)))

#_(defn build-site
    "Build static site using dependency-driven rendering.

   Data map should contain:
     :config - Site configuration with :render-roots
     :templates - Map of template-name -> template data/fn
     :content - All loaded content organized by language

   Returns map with :html-files and :warnings"
    [{:keys [config templates] :as ctx} _opts]
  ;; Validate wrapper template exists
    (when-not (get templates (:wrapper config))
      (throw (ex-info (str "Wrapper template '" (:wrapper config) "' not found")
                      {:wrapper (:wrapper config)
                       :available (keys templates)})))

  ;; Get render-roots from config
    (let [render-roots (or (:render-roots config)
                           #{(:index config)}) ; Fallback to just index if nothing specified

        ;; Phase 1: Analyze - expand templates, scan sections, collect dependencies
          analyzed-ctx (-> (renderer/expand-all-templates ctx)
                           (renderer/scan-for-sections))
          deps-result (renderer/collect-dependencies-and-pages
                       (assoc analyzed-ctx :render-roots render-roots))

        ;; Build pipeline with analyzed context
          result (-> analyzed-ctx
                     (assoc :pages-to-render (:pages-to-render deps-result))
                     create-page-specs
                     load-all-content
                     (update :pages validate-pages)
                     process-all-content
                     calculate-all-paths
                     (renderer/render-all-pages))]
      {:html-files (->> (:pages result)
                        (filter :html)
                        (map #(select-keys % [:path :html :slug :lang-code :content-key])))
       :warnings (:warnings result)}))

#_(defn resolve-links
  "Post-process rendered pages to resolve link placeholders and convert to HTML"
  [ctx {:keys [results sections warnings] :as state}]
  (let [;; Helper to generate URL for a page
        page->url (:page->url ctx)
        config (:config ctx)
        warnings-atom (atom warnings)

        make-url (fn [page-id lang-code]
                   (if-let [pages (get results page-id)]
                     (if-let [page (first (filter #(= (:lang-code %) lang-code) pages))]
                       (page->url {:slug (:slug page)
                                   :lang lang-code
                                   :site-config config})
                       (str "#no-lang-" (name lang-code) "-" (name page-id)))
                     (str "#no-page-" (name page-id))))

        ;; Helper to get page title
        get-title (fn [page-id lang-code]
                    (if-let [pages (get results page-id)]
                      (if-let [page (first (filter #(= (:lang-code %) lang-code) pages))]
                        (or (:title page) (name page-id))
                        (name page-id))
                      (name page-id)))

        ;; Resolve a content-key to either page URL or section anchor
        resolve-link (fn [content-key current-page-id lang-code]
                       (cond
                         ;; Check if it's a page first (pages take precedence)
                         (contains? results content-key)
                         (make-url content-key lang-code)

                         ;; Check if it's a section
                         (contains? sections content-key)
                         (let [section-parent (:parent (get sections content-key))]
                           (if (= section-parent current-page-id)
                             ;; Same page section
                             (str "#" (name content-key))
                             ;; Different page section
                             (str (make-url section-parent lang-code) "#" (name content-key))))

                         ;; Neither page nor section - broken link
                         :else
                         (do
                           (swap! warnings-atom conj {:type :broken-link
                                                      :from current-page-id
                                                      :to content-key})
                           (str "#broken-link-" (name content-key)))))

        ;; Resolve navigation helpers (parent, root)
        resolve-nav (fn [nav-type current-page-id lang-code]
                      (case nav-type
                        :parent
                        (let [path (if (= current-page-id (:index (:config ctx)))
                                     []
                                     (mapv keyword (str/split (name current-page-id) #"\.")))]
                          (cond
                            (empty? path) "#no-parent"
                            (= 1 (count path)) "/"
                            :else
                            (let [parent-id (if (= 2 (count path))
                                              (first path)
                                              (keyword (str/join "." (map name (butlast path)))))]
                              (make-url parent-id lang-code))))

                        :root "/"

                        "#unknown-nav"))

        ;; Process a single page's hiccup to resolve placeholders
        process-page (fn [page]
                       (let [lang-code (:lang-code page)
                             current-page-id (:content-key page)
                             resolve-node (fn resolve-node [node]
                                            (cond
                                              ;; Link href placeholder
                                              (and (map? node)
                                                   (= :eden.link.placeholder/href (:type node)))
                                              (cond
                                                (:content-key node)
                                                (resolve-link (:content-key node) current-page-id lang-code)

                                                (:nav node)
                                                (resolve-nav (:nav node) current-page-id lang-code)

                                                (:lang node)
                                                (make-url current-page-id (:lang node))

                                                :else "#unknown-link-type")

                                              ;; Link title placeholder
                                              (and (map? node)
                                                   (= :eden.link.placeholder/title (:type node)))
                                              (cond
                                                (:content-key node) (get-title (:content-key node) lang-code)
                                                (:nav node) (str (:nav node))
                                                :else "")

                                              ;; Link placeholder structure - return just the body
                                              (and (map? node)
                                                   (:eden/link-placeholder node))
                                              (:body node)

                                              ;; Recursive processing
                                              (vector? node) (mapv resolve-node node)
                                              (map? node) (into {} (map (fn [[k v]] [k (resolve-node v)]) node))
                                              :else node))
                             ;; Walk the hiccup tree
                             resolved-html (:html page)
                             resolved (walk/prewalk resolve-node resolved-html)]
                         (assoc page :html resolved)))]

    ;; Process all pages in results and update warnings
    (-> state
        (update :results
                (fn [results]
                  (into {}
                        (map (fn [[page-key pages]]
                               [page-key (map process-page pages)])
                             results))))
        (assoc :warnings @warnings-atom))))

(defn resolve-link
  ([ctx page-content]
   (resolve-link ctx page-content nil))
  ([{:keys [site-config] :as _ctx}
    {:keys [content-key slug] :as page-content}
    {:keys [] :as link}]
   ;; TODO: sections, nav
   (let [default-lang (config/find-default-language site-config)
         strategy (or (:page-url-strategy site-config) :default)
         lang (or (:lang link) (:lang page-content) default-lang)
         is-index? (= content-key (:index site-config))
         parts (str/split (name content-key) #"\.")
         base-path (if is-index?
                     "/"
                     (str "/" (str/join "/" (conj (or (butlast parts) [])
                                                  (or slug (last parts) )))))
         lang-path (if (= lang default-lang)
                     base-path
                     (str "/" (name lang) base-path))
         path (case strategy
                :with-extension (if is-index?
                                  lang-path
                                  (str lang-path ".html"))
                :default lang-path)]
     path)))

(defn resolve-links [ctx state]
  ;; TODO: sections
  (update state :rendered
          (fn [rendered]
            (into []
                  (map (fn [page-content]
                         (update
                          page-content
                          :rendered/page
                          (fn [page]
                            (walk/prewalk
                             (fn [elem]
                               (cond (and (map? elem)
                                          (contains? elem :type)
                                          (= "eden.link.placeholder" (namespace (:type elem))))
                                     (case (:type elem)
                                       :eden.link.placeholder/href (resolve-link ctx page-content elem)
                                       :eden.link.placeholder/title "TODO")
                                     :else elem))
                             page)))))
                  rendered))))

(defn format-build-output
  "Transform build-site-new output to match expected shape for write-output"
  [ctx state]
  (update state :rendered
          (fn [rendered]
            (into []
                  (map (fn [{:keys [rendered/page] :as page-content}]
                         (assoc page-content
                                :path (resolve-link ctx page-content)
                                :html/output (str "<!DOCTYPE html>" (rs/render page)))))
                  rendered))))

(defn build-site
  "build-site"
  [{:keys [site-config] :as ctx}]
  (let [initial-queue (into clojure.lang.PersistentQueue/EMPTY (:render-roots site-config))]
    (loop [render-queue initial-queue
           state {:attempted #{}
                  :rendered []
                  :sections {}
                  :references {}
                  :warnings []
                  :site-config site-config}]
      (if-let [page-id (peek render-queue)]
        (let [references (atom #{})
              sections (atom {})
              warnings (atom [])
              context (assoc ctx
                             :add-reference! #(do (swap! references conj %) nil)
                             :add-section! #(do (swap! sections assoc %1 %2) nil)
                             :warn! #(do (swap! warnings conj %) nil))
              rendered (renderer/render-page context page-id)

              ;; Pages that we have already attempted to render or are already queued
              attempted-or-in-queue (into #{} (concat (:attempted state) render-queue))

              ;; Add newly discovered references to queue
              updated-queue (into (pop render-queue) (remove attempted-or-in-queue @references))]

          (recur updated-queue
                 (-> state
                     (update :attempted conj page-id)
                     (update :rendered into rendered)
                     (update :sections merge @sections)
                     (update :references assoc page-id @references)
                     (update :warnings into @warnings))))
        ;; Queue empty - post-process to resolve link placeholders and format output
        (->> state
             (resolve-links ctx)
             (format-build-output ctx))))))

(defn write-output
  "Write HTML files to disk using the url->filepath strategy."
  [{:keys [rendered]
    {:keys [url->filepath]} :fns
    {:keys [output-path]} :site-config}]
  (doseq [{:keys [path html/output lang content-key] :as page} rendered]
    (let [;; Build the input map for url->filepath
          page-data {:path path
                     :page {:content-key content-key
                            :lang-code lang
                            :slug (:slug page)}
                     :lang lang}
          ;; Get the filepath from the strategy function
          filepath (url->filepath page-data)
          output-file (io/file output-path filepath)]
      (io/make-parents output-file)
      (spit output-file output))))
