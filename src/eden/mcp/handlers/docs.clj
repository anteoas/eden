(ns eden.mcp.handlers.docs
  "Documentation and discovery handlers for MCP"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [eden.loader :as loader]
            [eden.site-generator :as sg]
            [eden.mcp.api :as api])
  (:import [java.io File]))

(def directive-descriptions
  {:eden/get "Retrieves a value from the current context data"
   :eden/get-in "Retrieves nested values using a path vector"
   :eden/body "Insertion point for wrapped content in base templates"
   :eden/if "Conditional rendering based on data values"
   :eden/each "Iterates over collections with filtering and sorting"
   :eden/with "Merges data into context for child elements"
   :eden/link "Creates links to other pages with language support"
   :eden/render "Renders another template with data"
   :eden/include "Includes another template inline"
   :eden/t "Translation/internationalization support"})

(defn- analyze-template-structure
  "Walk template and collect usage information"
  [template]
  (let [used-fields (atom #{})
        used-directives (atom #{})
        fields-with-defaults (atom {})
        conditional-fields (atom #{})
        collection-fields (atom #{})]
    (walk/prewalk
     (fn [node]
       (when (sg/eden-directive? node)
         (let [[directive & args] node]
           (swap! used-directives conj directive)
           (case directive
             :eden/get
             (let [[field default] args]
               (swap! used-fields conj field)
               (when (> (count args) 1)
                 (swap! fields-with-defaults assoc field default)))

             :eden/get-in
             (when (and (vector? (first args)) (seq (first args)))
               (swap! used-fields conj (first (first args))))

             :eden/if
             (when (keyword? (first args))
               (swap! conditional-fields conj (first args))
               (swap! used-fields conj (first args)))

             :eden/each
             (let [collection-key (first args)]
               (when (and (keyword? collection-key)
                          (not= :eden/all collection-key))
                 (swap! collection-fields conj collection-key)
                 (swap! used-fields conj collection-key)))

             :eden/with
             (when (keyword? (first args))
               (swap! used-fields conj (first args)))

             :eden/link
             (when (and (keyword? (first args))
                        (not (#{:nav} (first args))))
               (swap! used-fields conj (first args)))

             nil)))
       node)
     template)
    {:fields @used-fields
     :directives @used-directives
     :defaults @fields-with-defaults
     :conditionals @conditional-fields
     :collections @collection-fields}))

(defn- format-template-analysis
  "Format template analysis as readable text"
  [template-name analysis]
  (let [{:keys [fields directives defaults conditionals collections]} analysis
        sorted-fields (sort fields)
        sorted-directives (sort directives)]
    (str "Template Analysis: " template-name "\n"
         "=" (apply str (repeat (+ 19 (count template-name)) "=")) "\n\n"

         "Directives Used:\n"
         (if (empty? directives)
           "  (none)\n"
           (str/join "\n"
                     (map #(str "  • " % " - " (get directive-descriptions % ""))
                          sorted-directives)))
         "\n\n"

         "Data Fields Used:\n"
         (if (empty? fields)
           "  (none)\n"
           (str/join "\n"
                     (map (fn [field]
                            (let [notes (cond-> []
                                          (contains? defaults field)
                                          (conj (str "default: " (pr-str (get defaults field))))
                                          (contains? conditionals field)
                                          (conj "conditional")
                                          (contains? collections field)
                                          (conj "collection"))]
                              (if (empty? notes)
                                (str "  • " field)
                                (str "  • " field " (" (str/join ", " notes) ")"))))
                          sorted-fields)))
         "\n\n"

         "Template Structure:\n"
         "  • Total directives: " (count directives) "\n"
         "  • Total fields: " (count fields) "\n"
         "  • Fields with defaults: " (count defaults) "\n"
         "  • Conditional fields: " (count conditionals) "\n"
         "  • Collection fields: " (count collections))))

(defn analyze-template
  "Analyze a template to extract metadata"
  [config {:keys [template]}]
  (try
    (let [site-root (-> (:site-edn config) io/file (.getParentFile))
          templates-dir (io/file site-root "templates")
          template-file (api/safe-resolve-path templates-dir (str template ".edn"))]
      (cond
        (nil? template-file)
        {:content [{:type "text"
                    :text "Error: Invalid template path"}]}

        (not (File/.exists template-file))
        {:content [{:type "text"
                    :text (str "Error: Template not found: " template)}]}

        :else
        (let [template-data (edn/read-string (slurp template-file))
              analysis (analyze-template-structure template-data)
              formatted (format-template-analysis template analysis)]
          {:content [{:type "text" :text formatted}]})))
    (catch Exception e
      {:content [{:type "text"
                  :text (str "Error: " (.getMessage e))}]})))

(defn list-directives
  "List all available Eden template directives"
  [_config _params]
  (let [sorted-directives (sort (keys directive-descriptions))
        formatted (str "Eden Template Directives\n"
                       "========================\n\n"
                       (str/join "\n\n"
                                 (map (fn [directive]
                                        (str "• " directive "\n"
                                             "  " (get directive-descriptions directive)
                                             "\n  Usage: "
                                             (case directive
                                               :eden/get "[:eden/get :field] or [:eden/get :field default-value]"
                                               :eden/get-in "[:eden/get-in [:path :to :field]]"
                                               :eden/body "[:eden/body]"
                                               :eden/if "[:eden/if :condition then-branch else-branch]"
                                               :eden/each "[:eden/each :collection [:li [:eden/get :item]]]"
                                               :eden/with "[:eden/with :data-to-merge body...]"
                                               :eden/link "[:eden/link :page-id link-body]"
                                               :eden/render "[:eden/render :template-or-data]"
                                               :eden/include "[:eden/include :template-name]"
                                               :eden/t "[:eden/t :translation-key]"
                                               "")))
                                      sorted-directives))
                       "\n\nFor detailed documentation, use: get-documentation {:topic \"template-directives\"}")]
    {:content [{:type "text" :text formatted}]}))

(defn get-site-config
  "Get current site configuration"
  [config _params]
  (try
    (let [site-file (io/file (:site-edn config))]
      (if (.exists site-file)
        (let [site-config (edn/read-string (slurp site-file))
              formatted (str "Site Configuration\n"
                             "==================\n\n"
                             "Templates:\n"
                             "  Wrapper: " (:wrapper site-config) "\n"
                             "  Index: " (:index site-config) "\n"
                             "\n"
                             "Render Roots:\n"
                             (str/join "\n" (map #(str "  • " %) (:render-roots site-config)))
                             "\n\n"
                             "Languages:\n"
                             (str/join "\n"
                                       (map (fn [[code config]]
                                              (str "  • " (name code) " - " (:name config)
                                                   (when (:default config) " (default)")))
                                            (:lang site-config)))
                             "\n\n"
                             "URL Configuration:\n"
                             "  Strategy: " (:url-strategy site-config) "\n"
                             "  Page URL Strategy: " (:page-url-strategy site-config) "\n"
                             "\n"
                             "Directories:\n"
                             "  Templates: " (or (:templates-dir site-config) "templates/") "\n"
                             "  Content: " (or (:content-dir site-config) "content/") "\n"
                             "  Assets: " (or (:assets-dir site-config) "assets/") "\n"
                             "  Output: " (or (:output-dir site-config) "dist/") "\n"
                             "\n"
                             "Full Configuration:\n"
                             (with-out-str (clojure.pprint/pprint site-config)))]
          {:content [{:type "text" :text formatted}]})
        {:error "Site configuration file not found"}))
    (catch Exception e
      {:error (.getMessage e)})))

(def documentation-topics
  {"template-directives"
   (str "Eden Template Directives Reference\n"
        "===================================\n\n"
        ":eden/get - Retrieve values from context\n"
        "  Syntax: [:eden/get key] or [:eden/get key default-value]\n"
        "  Example: [:h1 [:eden/get :title \"Untitled\"]]\n\n"
        ":eden/get-in - Retrieve nested values\n"
        "  Syntax: [:eden/get-in path] or [:eden/get-in path default]\n"
        "  Example: [:eden/get-in [:user :profile :name]]\n\n"
        ":eden/body - Insert wrapped content\n"
        "  Used in base templates to indicate where child content goes\n"
        "  Example: [:main [:eden/body]]\n\n"
        ":eden/if - Conditional rendering\n"
        "  Syntax: [:eden/if condition then-branch else-branch]\n"
        "  Example: [:eden/if :published [:span \"Published\"] [:span \"Draft\"]]\n\n"
        ":eden/each - Iterate over collections\n"
        "  Syntax: [:eden/each collection template]\n"
        "  Options: :where, :order-by, :limit, :group-by\n"
        "  Example: [:eden/each :posts [:article [:h2 [:eden/get :title]]]]\n\n"
        ":eden/with - Merge data into context\n"
        "  Syntax: [:eden/with data-key & body]\n"
        "  Example: [:eden/with :author [:span [:eden/get :name]]]\n\n"
        ":eden/link - Create links to pages\n"
        "  Syntax: [:eden/link page-id link-body]\n"
        "  Example: [:eden/link :about [:a {:href [:eden/get :link/href]} \"About\"]]\n\n"
        ":eden/render - Render another template\n"
        "  Syntax: [:eden/render template-or-data]\n"
        "  Example: [:eden/render :sidebar]\n\n"
        ":eden/include - Include template inline\n"
        "  Syntax: [:eden/include template-name]\n"
        "  Example: [:eden/include :header]\n\n"
        ":eden/t - Translations\n"
        "  Syntax: [:eden/t translation-key]\n"
        "  Example: [:eden/t :welcome-message]")

   "content-files"
   (str "Eden Content Files\n"
        "==================\n\n"
        "Eden content files are Clojure EDN or Markdown files that contain data.\n"
        "There's no fixed schema - you can include any fields your templates need.\n\n"
        "Common Fields (by convention):\n"
        "  :title - Often used for page titles\n"
        "  :slug - URL path segment (empty string \"\" for index pages)\n"
        "  :template - Which template to use for rendering\n"
        "  :content/html - HTML content (rendered as raw HTML)\n"
        "  :content/markdown - Markdown content\n\n"
        "File Formats:\n"
        "  .edn - Clojure data format\n"
        "    Example: {:title \"About\" :content/html \"<p>Hello</p>\"}\n\n"
        "  .md - Markdown with optional EDN frontmatter\n"
        "    Example:\n"
        "    ---\n"
        "    {:title \"Blog Post\" :template :blog}\n"
        "    ---\n"
        "    # Markdown content here\n\n"
        "Custom Fields:\n"
        "  Add any fields you want and access them with :eden/get\n"
        "  Examples: :author, :date, :tags, :image-url, etc.\n\n"
        "Templates determine what fields are needed - use analyze-template\n"
        "to see what fields a template expects.")

   "best-practices"
   (str "Eden Best Practices\n"
        "===================\n\n"
        "Templates:\n"
        "----------\n"
        "❌ NO hardcoded strings in templates\n"
        "   Bad:  [:a {:href \"...\"} \"Read more\"]\n"
        "   Good: [:a {:href \"...\"} [:eden/t :common/read-more]]\n\n"
        "✅ Use strings.edn for ALL user-facing text\n"
        "   Common strings like \"Read more\", \"Back\", \"Next\", \"Previous\"\n"
        "   should go in content/<lang>/strings.edn:\n"
        "   {:common/read-more \"Read more\"\n"
        "    :common/back \"Back\"\n"
        "    :nav/next \"Next\"}\n\n"
        "✅ Use semantic translation keys\n"
        "   Good: :nav/home, :form/submit, :error/not-found\n"
        "   Bad: :home-text, :submit-btn, :err404\n\n"
        "Content Files:\n"
        "--------------\n"
        "❌ Don't use :content/html in EDN files\n"
        "   If you have HTML content, use Markdown files instead!\n"
        "   EDN with :content/html is a clear sign the file should be .md\n\n"
        "✅ Use Markdown for content-heavy pages\n"
        "   blog-post.md with frontmatter is cleaner than\n"
        "   blog-post.edn with :content/html field\n\n"
        "✅ Use EDN for data-heavy content\n"
        "   Product listings, configuration, structured data\n\n"
        "Links:\n"
        "------\n"
        "✅ Always use :eden/link for internal links\n"
        "   Gets validation and correct language handling\n\n"
        "✅ Let :eden/link generate URLs\n"
        "   Don't hardcode paths - they change with language/strategy\n\n"
        "Data Organization:\n"
        "------------------\n"
        "✅ Use :eden/with to avoid repetition\n"
        "   Instead of [:eden/get-in [:product :title]]\n"
        "            [:eden/get-in [:product :price]]\n"
        "   Use [:eden/with :product ...]\n\n"
        "✅ Create reusable components with :eden/render\n"
        "   Don't copy-paste template code\n\n"
        "Development:\n"
        "------------\n"
        "✅ Test with missing data\n"
        "   Templates should handle optional fields gracefully\n\n"
        "✅ Check the build warnings\n"
        "   Eden reports missing pages, translations, etc.\n\n"
        "✅ Use analyze-template tool\n"
        "   Shows what fields a template expects")

   "image-processing"
   (str "Eden Image Processing\n"
        "=====================\n\n"
        "Enable in site.edn:\n"
        "  {:image-processor true}\n\n"
        "URL Query Parameters:\n"
        "  ?size=WIDTHxHEIGHT - Resize to exact dimensions\n"
        "  ?size=WIDTHx - Resize width, maintain aspect ratio\n"
        "  Note: Height-only not supported via URL\n\n"
        "HTML Examples:\n"
        "  <img src=\"/assets/images/hero.jpg?size=800x600\">\n"
        "  <img src=\"/assets/images/hero.jpg?size=800x\">\n\n"
        "CSS Examples:\n"
        "  background-image: url('/assets/images/bg.jpg?size=1920x');\n"
        "  background-image: url('/assets/images/thumb.jpg?size=200x200');\n\n"
        "Processing Details:\n"
        "  • High-quality scaling algorithm\n"
        "  • Aspect ratio always maintained\n"
        "  • Format preserved (JPG stays JPG, PNG stays PNG)\n"
        "  • Processed images cached in .temp/images/\n"
        "  • Missing images generate placeholders\n\n"
        "Output Names:\n"
        "  hero.jpg?size=800x → hero-800x.jpg\n"
        "  hero.jpg?size=800x600 → hero-800x600.jpg\n\n"
        "Placeholders:\n"
        "  When source images are missing, Eden generates placeholder\n"
        "  images showing the filename and requested dimensions.\n"
        "  This helps identify missing assets during development.")

   "quickstart"
   (str "Eden Quick Start\n"
        "================\n\n"
        "Initialize a new Eden site:\n"
        "  clj -Teden init\n\n"
        "This creates:\n"
        "  site.edn         - Site configuration\n"
        "  package.json     - NPM scripts\n"
        "  deps.edn         - Clojure dependencies\n"
        "  templates/       - Template files\n"
        "  content/en/      - English content\n"
        "  assets/          - CSS/JS/images\n\n"
        "Development:\n"
        "  npm run dev      - Start dev server with file watching\n\n"
        "Production:\n"
        "  npm run build    - Build static site to dist/\n"
        "  npm run clean    - Clean build artifacts\n\n"
        "Note: Eden uses npm scripts that call Clojure commands.\n"
        "The actual commands are in package.json.")

   "site-config"
   ;; Just serve the actual template with comments
   (slurp (io/resource "init-site/site.edn"))})

(defn get-documentation
  "Get documentation for a specific topic"
  [_config {:keys [topic]}]
  (if (or (nil? topic) (str/blank? topic))
    (let [topics (sort (keys documentation-topics))
          formatted (str "Available Documentation Topics\n"
                         "==============================\n\n"
                         (str/join "\n"
                                   (map #(str "• " % "\n  "
                                              (first (str/split-lines (get documentation-topics %))))
                                        topics))
                         "\n\nUsage: get-documentation {:topic \"<topic-name>\"}")]
      {:content [{:type "text" :text formatted}]})
    (if-let [doc (get documentation-topics topic)]
      {:content [{:type "text" :text doc}]}
      {:error (str "Unknown topic: " topic
                   ". Available topics: "
                   (str/join ", " (keys documentation-topics)))})))