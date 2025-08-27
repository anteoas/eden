(ns eden.report
  (:require [replicant.string :as rs]
            [clojure.string :as str]
            [eden.loader :as loader]))

(defn- format-file-size
  "Format bytes into human-readable size"
  [bytes]
  (cond
    (< bytes 1024) (format "%db" bytes)
    (< bytes (* 1024 1024)) (format "%.1fkb" (/ bytes 1024.0))
    :else (format "%.1fmb" (/ bytes (* 1024.0 1024.0)))))

(defn generate-html-report
  "Generate an HTML build report for dev mode"
  [{:keys [timings warnings results error site-edn mode]}]
  (let [total-time (reduce + 0 (vals timings))
        html-count (get-in results [:write-output :html-count] 0)
        timestamp (java.time.LocalDateTime/now)
        status (if error "failed" "success")
        max-time (apply max 1 (vals timings))]
    (str "<!DOCTYPE html>"
         (rs/render
          [:html
           [:head
            [:title "Build Report"]
            [:meta {:charset "UTF-8"}]
            [:style "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; 
                     max-width: 1200px; margin: 0 auto; padding: 20px; background: #f5f5f5; }
              .header { background: white; padding: 20px; border-radius: 8px; margin-bottom: 20px; 
                       box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
              .status { display: inline-block; padding: 4px 12px; border-radius: 4px; 
                       font-weight: 600; font-size: 14px; }
              .status.success { background: #d4edda; color: #155724; }
              .status.failed { background: #f8d7da; color: #721c24; }
              .info { color: #666; margin: 5px 0; font-size: 14px; }
              .section { background: white; padding: 20px; border-radius: 8px; 
                        margin-bottom: 20px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
              h2 { margin-top: 0; color: #333; border-bottom: 2px solid #e9ecef; padding-bottom: 10px; }
              .timing-table { width: 100%; border-collapse: collapse; }
              .timing-table th { text-align: left; padding: 8px; background: #f8f9fa; 
                                border-bottom: 2px solid #dee2e6; }
              .timing-table td { padding: 8px; border-bottom: 1px solid #dee2e6; }
              .timing-bar { background: #007bff; height: 20px; border-radius: 3px; 
                           min-width: 2px; display: inline-block; }
              .warning { background: #fff3cd; border-left: 4px solid #ffc107; 
                        padding: 12px; margin: 10px 0; border-radius: 4px; }
              .warning-title { font-weight: 600; color: #856404; margin-bottom: 8px; }
              .warning-list { margin: 5px 0 5px 20px; color: #856404; }
              .error { background: #f8d7da; border-left: 4px solid #dc3545; 
                      padding: 12px; margin: 10px 0; border-radius: 4px; color: #721c24; }
              .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); 
                      gap: 15px; margin-top: 20px; }
              .stat-card { background: #f8f9fa; padding: 15px; border-radius: 6px; }
              .stat-value { font-size: 24px; font-weight: 600; color: #333; }
              .stat-label { color: #666; font-size: 14px; margin-top: 5px; }"]]
           [:body
            [:div.header
             [:h1 "Build Report"]
             [:span.status {:class status} (str/upper-case status)]
             [:div.info "Generated: " (str timestamp)]
             [:div.info "Site: " site-edn]
             [:div.info "Mode: " (name mode)]]

            (when error
              [:div.section
               [:h2 "❌ Build Error"]
               [:div.error error]])

            [:div.section
             [:h2 "⏱️ Build Performance"]
             [:table.timing-table
              [:thead
               [:tr
                [:th "Step"]
                [:th "Time (ms)"]
                [:th {:style "width: 50%"} "Timeline"]]]
              [:tbody
               (for [[step elapsed] (sort-by val > timings)]
                 [:tr
                  [:td (name step)]
                  [:td elapsed]
                  [:td
                   [:div.timing-bar
                    {:style (str "width: " (* 100 (/ elapsed max-time)) "%")}]]])
               [:tr {:style "font-weight: 600; background: #f8f9fa;"}
                [:td "Total"]
                [:td total-time]
                [:td]]]]]

            [:div.section
             [:h2 "📊 Build Statistics"]
             [:div.stats
              [:div.stat-card
               [:div.stat-value html-count]
               [:div.stat-label "HTML Files Generated"]]
              [:div.stat-card
               [:div.stat-value (count timings)]
               [:div.stat-label "Build Steps"]]
              [:div.stat-card
               [:div.stat-value (+ (count (:missing-keys warnings []))
                                   (count (:missing-pages warnings [])))]
               [:div.stat-label "Total Warnings"]]
              (when-let [copied (get-in results [:copy-static :copied])]
                [:div.stat-card
                 [:div.stat-value copied]
                 [:div.stat-label "Static Files Copied"]])]]

            (when (or (seq (:missing-keys warnings))
                      (seq (:missing-pages warnings)))
              [:div.section
               [:h2 "⚠️ Warnings"]

               (when (seq (:missing-keys warnings))
                 [:div.warning
                  [:div.warning-title "Missing Template Keys"]
                  (let [grouped (group-by :template (:missing-keys warnings))]
                    (for [[template keys] grouped]
                      [:div
                       [:strong "In " template ":"]
                       [:ul.warning-list
                        (for [{:keys [key page-id]} keys]
                          [:li key " (rendering " page-id ")"])]]))])

               (when (seq (:missing-pages warnings))
                 [:div.warning
                  [:div.warning-title "Missing Page References"]
                  [:ul.warning-list
                   (for [{:keys [page-id template]} (:missing-pages warnings)]
                     [:li "Page ID: " [:code page-id]
                      (when template (str " (referenced in " template ")"))])]])])

            [:div {:style "text-align: center; color: #999; padding: 20px; font-size: 12px;"}
             "Generated by Anteo Website Builder"]]]))))

(defn print-build-report
  "Print a formatted build report with timings and warnings"
  [{:keys [timings warnings results error] :as ctx}]
  (println "  Build steps:")

  ;; Print each step timing
  (doseq [[step elapsed] timings]
    (println (format "    %-20s %dms" (name step) elapsed)))

  ;; Print totals
  (println (format "  Total time: %dms" (reduce + (vals timings))))
  (when-let [html-count (get-in results [:write-output :html-count])]
    (println (format "  Generated %d HTML files" html-count)))

  ;; Print bundle info if available
  (when-let [bundle-info (get-in results [:bundle-assets])]
    (let [css-files (get-in bundle-info [:css :files])
          js-files (get-in bundle-info [:js :files])
          all-files (concat css-files js-files)]
      (when (seq all-files)
        (println "\n  Bundled assets:")
        (doseq [{:keys [file size type]} all-files]
          (println (format "    %s/%s  %s"
                           (name type)
                           file
                           (format-file-size size)))))))

  ;; Group warnings by type
  (let [page-warnings (:page-warnings warnings)
        warnings-by-type (group-by :type page-warnings)
        unconfigured-langs (:unconfigured-language warnings-by-type)
        ;; Get all warnings that are NOT unconfigured-language
        other-warnings (mapcat val (dissoc warnings-by-type :unconfigured-language))
        ;; Group unconfigured language warnings by language
        langs-by-lang (group-by :lang unconfigured-langs)]

    ;; Print unconfigured language warnings (grouped)
    (when (seq langs-by-lang)
      (println "\n⚠️  Language configuration issues:")
      (doseq [[lang lang-warnings] langs-by-lang]
        (let [first-warning (first lang-warnings)
              page-count (count lang-warnings)]
          ;; Use the helpful message from the first warning
          (if-let [message (:message first-warning)]
            (println (format "  - %s" message))
            (println (format "  - Language '%s' not configured (%d pages affected)"
                             (name lang) page-count)))
          ;; Don't list individual pages when there are many
          (when (<= page-count 5)
            (doseq [w lang-warnings]
              (println (format "      Page: %s" (:content-key w))))))))

    ;; Print other page-level warnings
    (when (seq other-warnings)
      (println "\n⚠️  Page warnings:")
      (doseq [w other-warnings]
        ;; Prefer the :message field if available
        (if-let [message (:message w)]
          (println (format "  - %s" message))
          ;; Fall back to type-specific formatting
          (case (:type w)
            :missing-template
            (println (format "  - Template '%s' not found for page '%s'"
                             (:template-name w) (:content-key w)))

            :defaulted-template
            (println (format "  - Page '%s' has no :template field, defaulting to '%s'"
                             (:content-key w) (:defaulted-to w)))

            :missing-content
            (println (format "  - No content for %s in %s"
                             (:content-key w) (:lang-code w)))

            :missing-key
            (println (format "  - Missing key '%s' in template %s (page: %s)"
                             (:key w) (:template w) (:page w)))

            :ambiguous-link
            (println (format "  - Ambiguous link '%s' exists as both page and section"
                             (:link-id w)))

            :missing-page
            (let [stack-str (when (seq (:render-stack w))
                              (->> (:render-stack w)
                                   (map (fn [[type id]]
                                          (str (name id)
                                               (when (not= type :content)
                                                 (str " (" (name type) ")")))))
                                   (str/join " → ")))]
              (if stack-str
                (println (format "  - Missing page '%s' in: %s" (:content-key w) stack-str))
                (println (format "  - Missing page '%s'" (:content-key w)))))

            :invalid-render-spec
            (let [stack-str (when (seq (:render-stack w))
                              (->> (:render-stack w)
                                   (map (fn [[type id]]
                                          (str (name id)
                                               (when (not= type :content)
                                                 (str " (" (name type) ")")))))
                                   (str/join " → ")))]
              (println (format "  - Invalid :eden/render spec with data=%s, template=%s%s"
                               (pr-str (:data-key w))
                               (pr-str (:template-id w))
                               (if stack-str (str " in: " stack-str) ""))))

            :missing-translation
            nil ; Will be handled separately below

            ;; Default
            (println (format "  - %s: %s" (:type w) (pr-str w))))))))

  ;; Print missing translations grouped by language
  (let [missing-translations (filter #(= :missing-translation (:type %))
                                     (:page-warnings warnings))]
    (when (seq missing-translations)
      (let [by-lang (group-by :lang missing-translations)
            config (get-in ctx [:results :load :config])
            root-path (:root-path config)]
        (println "\n⚠️  Missing translations:")
        (doseq [[lang translations] by-lang]
          (println (format "  Language: %s" (name lang)))
          (doseq [t translations]
            (println (format "    - %s" (:key t))))
          ;; Show where we looked for translations
          (when root-path
            (let [strings-path (loader/translation-file-path root-path lang)]
              (println (format "  Expected in: %s" strings-path))))))))

  ;; Print warnings (non-page warnings)
  (when (seq (:missing-keys warnings))
    (println "\n⚠️  Missing template keys:")
    (let [grouped (group-by :template (:missing-keys warnings))]
      (doseq [[template keys] grouped]
        (println (format "  In %s:" template))
        (doseq [{:keys [key content-key]} keys]
          (println (format "    - %s (rendering %s)" key content-key))))))

  (when (seq (:missing-pages warnings))
    (println "\n⚠️  Missing page references:")
    (doseq [{:keys [page-id render-stack]} (:missing-pages warnings)]
      (let [stack-str (when (seq render-stack)
                        (->> render-stack
                             (map (fn [[type id]]
                                    (str (name id)
                                         (when (not= type :content)
                                           (str " (" (name type) ")")))))
                             (str/join " → ")))]
        (println) ;; Add blank line before each warning for better readability
        (if stack-str
          (println (format "  - Page ID: %s\n    Found in: %s" page-id stack-str))
          (println (format "  - Page ID: %s" page-id))))))

  ;; Print orphan content warning
  (when-let [orphan-content (:orphan-content warnings)]
    (when (seq orphan-content)
      (println "\n📝 Found content files not linked from your site:")
      (doseq [content-key (sort orphan-content)]
        (println (format "  - content/%s.edn (or .md)" (name content-key))))
      (println "  To include them, add links from existing pages or add to :render-roots")))

  ;; Print error if any
  (when error
    (println (format "\n❌ Build failed: %s" error))))
