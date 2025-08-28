(ns eden.builder
  (:require [clojure.java.io :as io]
            [eden.renderer :as renderer]))

(defn build-page-registry
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

(defn- validate-pages
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

(defn- create-page-specs
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

(defn- load-page-content
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

(defn- load-all-content
  "Load content for all pages"
  [{:keys [pages] :as ctx}]
  (assoc ctx :pages (map #(load-page-content ctx %) pages)))

(defn- process-page-content
  "Process page content and add language info"
  [{:keys [default-lang]} page]
  (if-let [content (:content page)]
    (let [lang-code (:lang-code page)
          processed (assoc content
                           :lang lang-code
                           :lang-prefix (if (= lang-code default-lang) "" (str "/" (name lang-code))))]
      (assoc page :content processed))
    page))

(defn- process-all-content
  "Process content for all pages"
  [{:keys [pages default-lang] :as ctx}]
  (assoc ctx :pages (map #(process-page-content {:default-lang default-lang} %) pages)))

(defn- calculate-page-path
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

(defn- calculate-all-paths
  "Calculate paths for all pages"
  [{:keys [pages default-lang] :as ctx}]
  (assoc ctx :pages (map #(calculate-page-path {:default-lang default-lang} %) pages)))

(defn build-site
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

(defn write-output
  "Write HTML files to disk using the url->filepath strategy."
  [html-files output-dir url->filepath]
  (doseq [{:keys [path html lang-code content-key] :as page} html-files]
    (let [;; Build the input map for url->filepath
          page-data {:path path
                     :page {:content-key content-key
                            :lang-code lang-code
                            :slug (:slug page)}
                     :lang lang-code}
          ;; Get the filepath from the strategy function
          filepath (url->filepath page-data)
          output-file (io/file output-dir filepath)]
      (io/make-parents output-file)
      (spit output-file html)))
  (println (format "  Wrote %d HTML files" (count html-files))))
