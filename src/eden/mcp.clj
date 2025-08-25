(ns eden.mcp
  "Integrated MCP server using clojure-mcp with embedded nREPL.
   Runs both MCP server and Eden in a single process."
  (:require [clojure-mcp.core :as mcp-core]
            [clojure-mcp.main :as mcp-main]
            [nrepl.server :as nrepl-server]
            [clojure.java.io :as io]))

(defonce nrepl-server-instance (atom nil))

(defn eden-template-directives-resource
  "Resource for Eden template directives documentation"
  []
  {:url "eden://template-directives"
   :name "Eden Template Directives"
   :description "Complete reference for Eden's template system directives"
   :mime-type "text/markdown"
   :resource-fn (fn [_ _ clj-result-k]
                  (clj-result-k [(str "# Eden Template Directives\n\n"
                                      "Eden templates use EDN-based directives as elements for dynamic content generation.\n\n"
                                      "## Core Directives\n\n"
                                      "### `:eden/get`\n"
                                      "Get a value from the context.\n"
                                      "```clojure\n"
                                      "[:eden/get :title]              ; Get title from data\n"
                                      "[:eden/get :site.title]         ; Nested access\n"
                                      "[:eden/get [:data :description]] ; Vector path\n"
                                      "```\n\n"
                                      "### `:eden/if`\n"
                                      "Conditional rendering based on truthiness.\n"
                                      "```clojure\n"
                                      "[:eden/if :show-content\n"
                                      "  [:div \"This shows if show-content is truthy\"]]\n"
                                      "```\n\n"
                                      "### `:eden/each`\n"
                                      "Iterate over collections.\n"
                                      "```clojure\n"
                                      "[:ul\n"
                                      "  [:eden/each :posts\n"
                                      "    [:li [:eden/get :title]]]]\n"
                                      "```\n\n"
                                      "### `:eden/with`\n"
                                      "Create local bindings.\n"
                                      "```clojure\n"
                                      "[:eden/with {:user :current-user}\n"
                                      "  [:p \"Welcome \" [:eden/get :user.name]]]\n"
                                      "```\n\n"
                                      "### `:eden/link`\n"
                                      "Create internal links with proper URL handling.\n"
                                      "```clojure\n"
                                      "[:eden/link :about \"About Us\"]        ; Link to about page\n"
                                      "[:eden/link [:blog :post-1] \"Post\"]   ; Link to nested page\n"
                                      "```\n\n"
                                      "### `:eden/body`\n"
                                      "Insert content passed to a wrapper template.\n"
                                      "```clojure\n"
                                      "[:main\n"
                                      "  [:eden/body]]  ; Content from wrapped template goes here\n"
                                      "```\n\n"
                                      "### `:eden/include`\n"
                                      "Include another template.\n"
                                      "```clojure\n"
                                      "[:eden/include :partials.header]\n"
                                      "[:eden/include :components.nav]\n"
                                      "```\n\n"
                                      "### `:eden/render`\n"
                                      "Render a page reference inline.\n"
                                      "```clojure\n"
                                      "[:eden/render :featured-post]  ; Render another page's content\n"
                                      "```\n\n"
                                      "### `:eden/t`\n"
                                      "Translate strings for i18n.\n"
                                      "```clojure\n"
                                      "[:eden/t :welcome-message]      ; Get translation\n"
                                      "[:eden/t :greeting {:name \"John\"}] ; With interpolation\n"
                                      "```\n\n"
                                      "## Directive Combinations\n"
                                      "Directives can be nested and combined:\n"
                                      "```clojure\n"
                                      "[:eden/if :posts\n"
                                      "  [:ul\n"
                                      "    [:eden/each :posts\n"
                                      "      [:eden/if :published\n"
                                      "        [:li\n"
                                      "          [:eden/link [:blog [:eden/get :slug]]\n"
                                      "            [:eden/get :title]]]]]]]\n"
                                      "```\n")]))})

(defn eden-content-structure-resource
  "Resource for Eden content structure documentation"
  []
  {:url "eden://content-structure"
   :name "Eden Content Structure"
   :description "How to structure content files and frontmatter in Eden"
   :mime-type "text/markdown"
   :resource-fn (fn [_ _ clj-result-k]
                  (clj-result-k [(str "# Eden Content Structure\n\n"
                                      "## File Organization\n"
                                      "```\n"
                                      "content/\n"
                                      "├── en/           # English content\n"
                                      "│   ├── index.md\n"
                                      "│   ├── about.md\n"
                                      "│   └── blog/\n"
                                      "│       ├── post-1.md\n"
                                      "│       └── post-2.md\n"
                                      "└── es/           # Spanish content\n"
                                      "    └── index.md\n"
                                      "```\n\n"
                                      "## Frontmatter Format\n"
                                      "Content files use EDN frontmatter separated by `---`:\n"
                                      "```clojure\n"
                                      "{:title \"Page Title\"\n"
                                      " :template :blog-post  ; Template to use\n"
                                      " :slug \"custom-url\"    ; Optional custom URL\n"
                                      " :date \"2024-01-15\"\n"
                                      " :author \"Jane Doe\"\n"
                                      " :tags [\"clojure\" \"web\"]\n"
                                      " :draft false}\n"
                                      "---\n"
                                      "# Markdown Content Here\n"
                                      "Regular markdown content...\n"
                                      "```\n\n"
                                      "## Common Frontmatter Fields\n"
                                      "- `:title` - Page title\n"
                                      "- `:template` - Template name (keyword)\n"
                                      "- `:slug` - Custom URL slug\n"
                                      "- `:date` - Publication date\n"
                                      "- `:draft` - Hide from production\n"
                                      "- `:tags` - List of tags\n"
                                      "- `:description` - SEO description\n"
                                      "- `:image` - Featured image path\n\n"
                                      "## Content Keys\n"
                                      "Content is accessed by key path:\n"
                                      "- `index.md` → `:index`\n"
                                      "- `about.md` → `:about`\n"
                                      "- `blog/post-1.md` → `:blog.post-1`\n\n"
                                      "## Language Support\n"
                                      "Multi-language sites use language codes:\n"
                                      "- `content/en/` - English\n"
                                      "- `content/es/` - Spanish\n"
                                      "- `content/fr/` - French\n"
                                      "Access: `:en.index`, `:es.index`, etc.\n")]))})

(defn eden-site-config-resource
  "Resource for Eden site configuration documentation"
  []
  {:url "eden://site-config"
   :name "Eden Site Configuration"
   :description "Guide to configuring site.edn and build options"
   :mime-type "text/markdown"
   :resource-fn (fn [_ _ clj-result-k]
                  (clj-result-k [(str "# Eden Site Configuration\n\n"
                                      "## site.edn Structure\n"
                                      "```clojure\n"
                                      "{:site-title \"My Eden Site\"\n"
                                      " :base-url \"https://example.com\"\n"
                                      " :languages [:en :es]  ; Supported languages\n"
                                      " :default-language :en\n"
                                      " :output-dir \"dist\"   ; Build output directory\n"
                                      " :theme {:primary-color \"#3B82F6\"\n"
                                      "         :font-family \"Inter, sans-serif\"}\n"
                                      " :plugins [\"eden-sitemap\" \"eden-rss\"]\n"
                                      " :image-processing\n"
                                      " {:sizes {:thumbnail [150 150]\n"
                                      "          :hero [1920 1080]\n"
                                      "          :card [600 400]}\n"
                                      "  :formats [:webp :jpg]\n"
                                      "  :quality 85}}\n"
                                      "```\n\n"
                                      "## Build Commands\n"
                                      "```clojure\n"
                                      ";; From REPL\n"
                                      "(eden.core/build :site-edn \"site.edn\" :mode :prod)\n"
                                      "(eden.core/dev :site-edn \"site.edn\")  ; Dev server\n\n"
                                      ";; From CLI\n"
                                      "clj -Teden build\n"
                                      "clj -Teden dev\n"
                                      "clj -Teden clean\n"
                                      "```\n\n"
                                      "## Image Processing\n"
                                      "Configure automatic image optimization:\n"
                                      "- Define size presets\n"
                                      "- Set output formats (webp, jpg, png)\n"
                                      "- Control quality settings\n"
                                      "- Images in content automatically processed\n\n"
                                      "## Development Mode\n"
                                      "Dev mode features:\n"
                                      "- Live reload with file watching\n"
                                      "- Browser-sync integration\n"
                                      "- Build performance metrics\n"
                                      "- Warning reports\n")]))})

(defn make-eden-prompts
  "Create Eden-specific prompts"
  [nrepl-client-atom working-dir]
  (concat
   ;; Include standard clojure-mcp prompts
   (mcp-main/make-prompts nrepl-client-atom working-dir)
   ;; Add Eden-specific prompts
   [{:name "eden_project_context"
     :description "Understand the Eden static site generator project context and common tasks"
     :arguments []
     :prompt-fn (fn [_ _ clj-result-k]
                  (clj-result-k
                   {:description "Eden Project Assistant"
                    :messages [{:role :user
                                :content (str "You are working with an Eden static site generator project.\n\n"
                                              "# What is Eden?\n"
                                              "Eden is a Clojure-based static site generator that uses:\n"
                                              "- EDN (Extensible Data Notation) for configuration and templates\n"
                                              "- Markdown files with EDN frontmatter for content\n"
                                              "- A directive-based template system (like :eden/get, :eden/each)\n"
                                              "- Multi-language support\n"
                                              "- Automatic image processing\n\n"
                                              "# Common Tasks\n"
                                              "1. **Content Management**\n"
                                              "   - Create/edit markdown files in content/ directory\n"
                                              "   - Use EDN frontmatter (before --- separator)\n"
                                              "   - Organize by language (content/en/, content/es/)\n\n"
                                              "2. **Template Work**\n"
                                              "   - Templates are in templates/ directory as .edn files\n"
                                              "   - Use Eden directives as elements: [:eden/get :title]\n"
                                              "   - Create reusable components with :eden/include\n\n"
                                              "3. **Site Configuration**\n"
                                              "   - Main config in site.edn\n"
                                              "   - Build with: (eden.core/build :site-edn \"site.edn\")\n"
                                              "   - Dev mode: (eden.core/dev :site-edn \"site.edn\")\n\n"
                                              "4. **Development Workflow**\n"
                                              "   - Run from project root (where site.edn is located)\n"
                                              "   - Use `clj -Teden dev` for live reload\n"
                                              "   - Check dist/ for build output\n\n"
                                              "# Best Practices\n"
                                              "- Keep content and presentation separate\n"
                                              "- Use semantic template names\n"
                                              "- Leverage Eden directives instead of inline HTML\n"
                                              "- Test multi-language content if configured\n"
                                              "- Use image processing for optimization\n\n"
                                              "Always check the Eden documentation resources for detailed syntax and examples.")}]}))}

    {:name "eden_create_page"
     :description "Guide for creating a new page in Eden"
     :arguments [{:name "page_type"
                  :description "Type of page to create (blog-post, landing-page, documentation, etc.)"
                  :required? true}
                 {:name "language"
                  :description "Language code (en, es, fr, etc.)"
                  :required? false}]
     :prompt-fn (fn [_ args clj-result-k]
                  (let [page-type (get args "page_type")
                        language (or (get args "language") "en")]
                    (clj-result-k
                     {:description "Create Eden Page"
                      :messages [{:role :user
                                  :content (str "Help me create a new " page-type " page for an Eden site.\n\n"
                                                "Language: " language "\n\n"
                                                "Please:\n"
                                                "1. Create an appropriate markdown file in content/" language "/\n"
                                                "2. Include proper EDN frontmatter with:\n"
                                                "   - :title\n"
                                                "   - :template (choose or create appropriate template)\n"
                                                "   - :slug (if custom URL needed)\n"
                                                "   - Any type-specific fields (date, author, tags, etc.)\n"
                                                "3. Add starter content appropriate for a " page-type "\n"
                                                "4. If needed, create or modify a template in templates/\n"
                                                "5. Explain how to build and preview the page\n\n"
                                                "Follow Eden conventions and best practices.")}]})))}

    {:name "eden_debug_build"
     :description "Help debug Eden build issues"
     :arguments []
     :prompt-fn (fn [_ _ clj-result-k]
                  (clj-result-k
                   {:description "Debug Eden Build"
                    :messages [{:role :user
                                :content (str "Help me debug an Eden build issue.\n\n"
                                              "Please:\n"
                                              "1. Check site.edn for configuration issues\n"
                                              "2. Verify content files have valid EDN frontmatter\n"
                                              "3. Check template syntax for Eden directive errors\n"
                                              "4. Run (eden.core/build :site-edn \"site.edn\" :mode :dev) to see detailed errors\n"
                                              "5. Look for common issues:\n"
                                              "   - Malformed EDN in frontmatter\n"
                                              "   - Missing template references\n"
                                              "   - Invalid Eden directive syntax\n"
                                              "   - File path issues\n"
                                              "   - Image processing errors\n\n"
                                              "Provide specific fixes for any issues found.")}]}))}]))

(defn make-eden-resources
  "Create Eden-specific resources including documentation"
  [nrepl-client-atom working-dir]
  ;; Get default resources from clojure-mcp
  (let [default-resources (mcp-main/make-resources nrepl-client-atom working-dir)
        ;; Filter to only include relevant ones for Eden projects
        filtered-resources (filter #(contains? #{"README.md"
                                                 "CLAUDE.md"
                                                 "Clojure Project Info"}
                                               (:name %))
                                   default-resources)]
    (concat
     filtered-resources
     ;; Add Eden-specific documentation
     [(eden-template-directives-resource)
      (eden-content-structure-resource)
      (eden-site-config-resource)])))

(defn make-minimal-tools
  "Create a minimal set of tools - file operations, eval, and bash"
  [nrepl-client-atom working-dir]
  ;; Filter to just the essential tools from mcp-main
  (let [all-tools (mcp-main/make-tools nrepl-client-atom working-dir)]
    (filter #(contains? #{"unified_read_file"
                          "file_edit"
                          "file_write"
                          "LS"
                          "grep"
                          "glob_files"
                          "clojure_eval"
                          "bash"
                          "think"
                          "scratch_pad"}
                        (:name %))
            all-tools)))

(defn start-embedded-nrepl!
  "Start an embedded nREPL server on a random port"
  []
  (let [server (nrepl-server/start-server :port 0) ; Random available port
        port (.getLocalPort ^java.net.ServerSocket (:server-socket server))]
    (reset! nrepl-server-instance {:server server :port port})
    (println "Started embedded nREPL server on port" port)
    port))

(defn stop-embedded-nrepl!
  "Stop the embedded nREPL server"
  []
  (when-let [{:keys [server]} @nrepl-server-instance]
    (nrepl-server/stop-server server)
    (reset! nrepl-server-instance nil)
    (println "Stopped embedded nREPL server")))

(defn start-stdio-server
  "Start Eden with integrated MCP server.
   This runs both the MCP server and Eden in a single process.
   Must be run from the project root directory containing site.edn."
  [site-edn]
  (let [;; Verify site.edn exists
        site-file (io/file site-edn)
        _ (when-not (.exists site-file)
            (throw (ex-info (str "Site EDN file not found: " site-edn
                                 "\nMust be run from project root directory containing site.edn")
                            {:site-edn site-edn})))

        ;; Start embedded nREPL server on random port
        nrepl-port (start-embedded-nrepl!)

        ;; Project root is current directory (where site.edn is)
        site-root (.getCanonicalPath (io/file "."))

        ;; Configure MCP to connect to our embedded nREPL
        mcp-config {:port nrepl-port
                    :host "localhost"
                    :project-dir site-root}]

    (println "Starting integrated Eden MCP server...")
    (println "Site EDN:" site-edn)
    (println "Project directory:" site-root)

    ;; Start the MCP server with our custom configuration
    (try
      (mcp-core/build-and-start-mcp-server
       mcp-config
       {:make-tools-fn make-minimal-tools
        :make-prompts-fn make-eden-prompts ; Use Eden-specific prompts
        :make-resources-fn make-eden-resources})

      (println "\nEden MCP server ready!")
      (println "The AI assistant can now:")
      (println "  - Read/edit files in the project directory")
      (println "  - Evaluate Clojure code including (eden.core/build ...)")
      (println "  - Run git and build commands")
      (println "  - Access Eden documentation via resources")
      (println "  - Use Eden-specific prompts for common tasks")

      ;; Keep the process running
      (Thread/sleep Long/MAX_VALUE)

      (catch Exception e
        (println "Error starting MCP server:" (.getMessage e))
        (.printStackTrace e)
        (stop-embedded-nrepl!)
        (throw e)))))

(defn -main
  "Main entry point for the integrated server"
  [& args]
  (let [site-edn (or (first args) "site.edn")]
    (start-stdio-server site-edn)))

;; Shutdown hook to clean up
(.addShutdownHook (Runtime/getRuntime)
                  (Thread. #(do
                              (println "\nShutting down...")
                              (stop-embedded-nrepl!)
                              (mcp-core/close-servers nrepl-server-instance))))
