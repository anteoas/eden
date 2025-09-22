(ns eden.builder
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [replicant.string :as rs]
            [eden.site-generator :as sg]))

(defn- get-content [ctx {:keys [content-key lang]}]
  (and lang content-key
       (get-in ctx [:content lang content-key])))

(defn- create-url [{:keys [content-key index? slug section-id lang]}]
  (if index?
    (str "/"
         (when (keyword lang) (name lang))
         (when (keyword section-id) (str "#" (name section-id))))

    (let [parts (str/split (name content-key) #"\.")
          parent (butlast parts)
          id (when-let [base (or slug (last parts))]
               (if (keyword? section-id)
                 (str base "#" (name section-id))
                 base))
          lang-part (when (keyword? lang)
                      [(name lang)])
          url-parts (concat lang-part parent [id])]
      (str "/" (str/join "/" url-parts)))))

(comment

  (create-url {:content-key :about ;:foo.bar.baz
               :index? false
               :slug "yoyo"
               ;:lang :en
               ;:section-id :my-section-id
               }))

(defn- resolve-link [ctx state {:keys [content-key lang] :as link}]
  (let [section (get-in state [:sections content-key])
        content (get-content ctx link)
        parent-content (when section (get-content ctx (assoc link :content-key (:parent section))))
        url-content-key (or (:content-key parent-content) content-key)
        url-data (cond-> {:content-key url-content-key
                          :index? (= (-> ctx :site-config :index) url-content-key)
                          :slug (or (:slug parent-content) (:slug content))}
                   parent-content (assoc :section-id content-key)
                   (not= lang (:default-lang ctx)) (assoc :lang lang))]

    (create-url url-data)))

(defn- resolve-links [ctx state]
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
                                          (qualified-keyword? (:type elem))
                                          (= "eden.link.placeholder" (namespace (:type elem))))
                                     (let [link (apply merge (map #(select-keys % [:lang :content-key :nav]) [page-content elem]))]
                                       (case (:type elem)
                                         :eden.link.placeholder/href (resolve-link ctx state link)
                                         :eden.link.placeholder/title (or (:title (get-content ctx link))
                                                                          "TODO: missing!")))
                                     :else elem))
                             page)))))
                  rendered))))

(defn- format-build-output
  "Transform build-site-new output to match expected shape for write-output"
  [ctx state]
  (update state :rendered
          (fn [rendered]
            (into []
                  (map (fn [{:keys [rendered/page] :as page-content}]
                         (assoc page-content
                                :path (resolve-link ctx state (select-keys page-content [:lang :content-key]))
                                :html/output (str "<!DOCTYPE html>" (rs/render page)))))
                  rendered))))

(defn- render-page
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
                      ;; TODO: Footer
                      #_(warn! {:type :missing-template
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

(defn build-site
  "build-site"
  [{:keys [site-config valid-content-keys] :as ctx}]
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
                             :add-reference! (fn [ref]
                                               (when (valid-content-keys ref)
                                                 (swap! references conj ref))
                                               nil)
                             :add-section! #(do (swap! sections assoc %1 %2) nil)
                             :warn! #(do (swap! warnings conj %) nil))
              rendered (render-page context page-id)

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
  (doseq [{:keys [path html/output html/replaced lang content-key] :as page} rendered]
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
      (spit output-file (or replaced output)))))
