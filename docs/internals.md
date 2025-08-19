# Eden Internals - Developer Guide

This guide provides a deep dive into Eden's architecture, implementation details, and extension points for developers who want to understand or extend the system.

## System Architecture

Eden follows a pipeline architecture where data flows through distinct phases:

```
Configuration → Loading → Processing → Rendering → Output
     ↓           ↓           ↓           ↓          ↓
  site.edn   Content    Templates    HTML      dist/
            + Assets    + Directives  Generation  files
```

### Core Namespaces

- **`eden.core`** - Entry points and orchestration
- **`eden.config`** - Configuration parsing and URL strategies
- **`eden.loader`** - Content and template loading
- **`eden.site-generator`** - Template directive processing
- **`eden.renderer`** - HTML generation and page assembly
- **`eden.builder`** - File output and asset copying
- **`eden.pipeline`** - Build orchestration
- **`eden.mcp.*`** - MCP server and handlers

## Build Pipeline

### 1. Configuration Phase (`eden.config`)

The build starts by loading `site.edn` and parsing configuration:

```clojure
{:site-config config
 :site-dir (parent-of-site-edn)
 :output-dir "dist"
 :mode :dev/:prod
 :verbose? true/false}
```

URL strategies are compiled into functions:
- `:flat` → `page.html`
- `:nested` → `page/index.html`
- Custom functions for advanced routing

### 2. Loading Phase (`eden.loader`)

#### Content Loading
- Scans `content/<lang>/` directories
- Supports `.edn` and `.md` (with frontmatter)
- Markdown is converted to HTML immediately
- Content is indexed by key derived from file path:
  - `content/en/home.edn` → `:home`
  - `content/en/blog/post-1.md` → `:blog.post-1`
  - Directories become namespace separators with dots

```clojure
{:en {:home {:title "Welcome"
             :content/html "<p>...</p>"
             :template :home}
      :about {:title "About"
              :slug "about"
              :template :page}}
 :no {:home {:title "Velkommen"
             :content/html "<p>...</p>"}}}
```

#### Template Loading
- Loads `.edn` files from `templates/`
- Templates are Hiccup data structures
- Supports SCI evaluation for dynamic templates

### 3. Processing Phase (`eden.site-generator`)

#### Template Expansion
Templates are first expanded to identify dependencies:

```clojure
;; Before expansion
[:eden/link :about [:a {:href [:eden/get :link/href]} "About"]]

;; After expansion
{:eden/expanded :link
 :expanded-arg {:content-key :about}
 :body [[:a {:href [:eden/get :link/href]} "About"]]
 :eden/references #{:about}}
```

#### Directive Processing
Directives are processed via multimethods based on element type. The dispatch determines whether an element is:
- An Eden directive (`:eden/get`, `:eden/link`, etc.)
- A Hiccup element (regular HTML tags)
- A scalar value (strings, numbers, etc.)
- Other special cases

All directives:
- **`:eden/get`** - Retrieve values from context data
- **`:eden/get-in`** - Nested data access with paths
- **`:eden/each`** - Iterate over collections
- **`:eden/if`** - Conditional rendering
- **`:eden/link`** - Smart page linking with dependency tracking
- **`:eden/render`** - Render nested components
- **`:eden/t`** - Translation with interpolation
- **`:eden/with`** - Merge data into context
- **`:eden/body`** - Insert page body content
- **`:eden/include`** - Include other templates

### 4. Rendering Phase (`eden.renderer`)

#### Page Discovery and Processing

The rendering phase follows this flow:

1. **Start with render-roots**: Begin with pages specified in `:render-roots`
2. **Expand templates**: Process each template to find `:eden/link` directives
3. **Collect dependencies**: Build a graph of which pages link to which
4. **Compute transitive closure**: Find all pages reachable from roots
5. **Render each page**: Process discovered pages through their templates

#### How Pages are Processed

For each page to be rendered:

1. **Load page content**: Get the content file (e.g., `home.edn`)
2. **Identify template**: Use `:template` field from content, or default to content key
3. **Build page context**: Merge page content into `:data` field
4. **Process template**: Expand all directives with page data
5. **Wrap in layout**: Insert result into wrapper template's `:eden/body`
6. **Generate HTML**: Convert Hiccup to HTML string

```clojure
;; Simplified page rendering flow
(defn render-page [context page]
  (let [;; Page content becomes :data
        page-content (:content page)
        template-key (or (:template page-content) 
                        (:content-key page))
        template (get templates template-key)
        
        ;; Process template with page data
        page-context (assoc context :data page-content)
        page-html (process template page-context)
        
        ;; Wrap in site layout
        wrapper (:wrapper config)
        wrapper-context (assoc context :body page-html)
        final-html (process wrapper wrapper-context)]
    
    (str "<!DOCTYPE html>" 
         (replicant/render final-html))))
```

#### Example Flow

Given this setup:
```clojure
;; site.edn
{:render-roots #{:home}
 :wrapper :base}

;; content/en/home.edn
{:title "Welcome"
 :template :home-page
 :featured-product :products.widget}

;; templates/home-page.edn
[:div
 [:h1 [:eden/get :title]]
 [:eden/link :about
  [:a {:href [:eden/get :link/href]} "About Us"]]
 [:eden/render :featured-product]]
```

Processing steps:
1. Start with `:home` (from render-roots)
2. Expand template, find link to `:about`
3. Add `:about` to pages to render
4. Render `:home` with its data (`:title` = "Welcome")
5. Render `:about` with its data
6. Both wrapped in `:base` template

#### Context Structure
During rendering, each directive has access to:

```clojure
{:data {...}           ; Current page/component data
 :body [...]           ; Body content for wrapper
 :lang :en             ; Current language
 :path [:products :x]  ; Page hierarchy path
 :pages {...}          ; All pages registry
 :sections {...}       ; Section registry
 :strings {...}        ; Translations
 :content-data {...}   ; All content for language
 :templates {...}      ; All templates
 :page->url fn         ; URL generation function
 :site-config {...}    ; Site configuration
 :build-constants {...}; Build-time data
 :warn! fn}            ; Warning collector
```

### 5. Output Phase (`eden.builder`)

Files are written to disk with the configured URL strategy:
- Pages are written as HTML files
- Assets are processed and copied
- Build reports are generated

#### Asset Processing

Eden uses esbuild for CSS and JavaScript processing when available:

**CSS Processing:**
- Bundles CSS files with dependencies
- Minifies in production mode
- Falls back to simple copy if esbuild not installed

**JavaScript Processing:**
- Bundles JS modules
- Creates IIFE format for browser compatibility
- Generates sourcemaps in development mode
- Minifies in production mode
- Falls back to simple copy if esbuild not installed

Assets are processed from `assets/css/` and `assets/js/` to the output directory.

## Template Directives

### :eden/get - Data Access

Simple key access from `:data`:
```clojure
[:eden/get :title]           ; Get :title from data
[:eden/get :missing "N/A"]   ; With default value
```

Content namespace special handling:
```clojure
[:eden/get :content/html]    ; Returns RawString for HTML
```

### :eden/get-in - Nested Access

Path-based access:
```clojure
[:eden/get-in [:user :name]]          ; Nested access
[:eden/get-in [:items 0 :title]]      ; Array access
[:eden/get-in [:missing :path] "N/A"] ; With default
```

### :eden/each - Iteration

Multiple iteration modes:

```clojure
;; Simple collection
[:eden/each :items
 [:li [:eden/get :name]]]

;; With options
[:eden/each :items
 :order-by [:date :desc]
 :limit 5
 :where {:published true}
 [:article ...]]

;; Group by field
[:eden/each :posts
 :group-by :category
 [:section
  [:h2 [:eden/get :eden.each/group-key]]
  [:eden/each :eden.each/group-items
   [:article ...]]]]

;; All content
[:eden/each :eden/all :where {:type "blog"}
 [:article ...]]
```

Special variables:
- `:eden.each/index` - Current index
- `:eden.each/key` - Map key (for maps)
- `:eden.each/value` - Map value (for maps)
- `:eden.each/group-key` - Group name
- `:eden.each/group-items` - Items in group

### :eden/link - Smart Linking

Handles multiple link types:

```clojure
;; Direct page link
[:eden/link :about ...]

;; With options
[:eden/link {:content-key :about
             :lang :en} ...]

;; Navigation helpers
[:eden/link {:nav :parent} ...]  ; Parent page
[:eden/link {:nav :root} ...]    ; Home page

;; Dynamic (from :eden/each)
[:eden/link [:eden/get :content-key] ...]
```

Link resolution order:
1. Check for standalone page
2. Check for section on another page
3. Generate warning if not found

### :eden/render - Component Rendering

Render nested components:

```clojure
;; Simple
[:eden/render :sidebar]

;; With explicit template
[:eden/render {:data :product.featured
               :template :product-card}]

;; With section ID (for anchors)
[:eden/render {:data :products.logifish
               :template :product-detail
               :section-id "logifish"}]

;; Dynamic (in :eden/each)
[:eden/render {:data [:eden/get :content-key]
               :template [:eden/get :template-id]}]
```

### :eden/t - Translations

Translation with interpolation:

```clojure
;; Simple
[:eden/t :nav/home]

;; With default
[:eden/t :missing/key "Default text"]

;; With interpolation
[:eden/t :welcome {:name [:eden/get :user-name]}]
; Replaces {{name}} in translation

;; Nested keys
[:eden/t [:errors :not-found]]
```

### :eden/if - Conditionals

```clojure
;; Simple boolean
[:eden/if :is-featured
 [:span.badge "Featured"]]

;; With else branch
[:eden/if :has-image
 [:img {:src [:eden/get :image]}]
 [:div.placeholder]]

;; Nested paths
[:eden/if [:user :is-admin]
 [:a {:href "/admin"} "Admin"]]
```

### :eden/with - Context Merging

Merge map data into context:

```clojure
[:eden/with :product-details
 [:div
  [:h3 [:eden/get :name]]
  [:p [:eden/get :description]]]]
```

## MCP Protocol

Eden implements the Model Context Protocol for AI integration.

### Server Modes

1. **HTTP Mode** (`eden.mcp.server`)
   - Runs on configurable port
   - Optional authentication
   - CORS support

2. **STDIO Mode** (`eden.mcp.stdio`)
   - Direct process communication
   - For Claude Desktop integration

### Available Tools

Tools are defined in `eden.mcp.tools`:

- **Content Management**
  - `list_content` - List all content files
  - `read_content` - Read specific content
  - `write_content` - Create/update content
  - `delete_content` - Remove content

- **Template Management**
  - `list_templates` - List all templates
  - `read_template` - Read template source
  - `preview_template` - Render template preview

- **Build Operations**
  - `build_site` - Trigger full build
  - `get_build_status` - Check build progress
  - `get_build_report` - Get last build report

- **Configuration**
  - `get_config` - Read site configuration
  - `update_config` - Modify settings

### MCP Handler Architecture

Each handler namespace implements specific functionality:

```clojure
(ns eden.mcp.handlers.content)

(defn list-content [config arguments]
  {:content (list-all-content config)})

(defn read-content [config {:keys [lang path]}]
  {:content (load-content-file config lang path)})
```

### Resources and Prompts

MCP also exposes:
- **Resources** - Direct file access
- **Prompts** - Guided workflows for common tasks

## URL Strategies

### Built-in Strategies

Eden provides two URL strategies out of the box:

```clojure
;; :flat strategy
/about → about.html
/blog/post → blog/post.html

;; :nested strategy  
/about → about/index.html
/blog/post → blog/post/index.html
```

### Custom URL Strategies

Implement custom strategies as functions:

```clojure
;; In site.edn
{:url-strategy my.namespace/custom-strategy}

;; In code
(defn custom-strategy [{:keys [path]}]
  (str (str/join "/" path) ".html"))
```

### Page URL Strategies

Control how links are generated:

```clojure
;; :default - Clean URLs
/en/about → /en/about

;; :with-extension - Traditional
/en/about → /en/about.html

;; Custom function
(defn custom-page-url [{:keys [slug lang site-config]}]
  ...)
```

## Build Constants

Inject build-time data into all pages:

```clojure
{:build-constants {:build/time (java.util.Date.)
                   :build/version "1.2.3"
                   :build/env "production"}}
```

Access in templates:
```clojure
[:footer "Built at " [:eden/get :build/time]]
```

## Warning System

Eden collects warnings during build without failing:

```clojure
{:type :missing-template
 :template-name :blog
 :content-key :blog.post-1
 :location [:template :home]
 :message "Template 'blog' not found"}
```

Warning types:
- `:missing-template` - Template not found
- `:missing-content` - Content not found
- `:missing-page` - Link target not found
- `:ambiguous-link` - Multiple link targets
- `:missing-key` - Data key not found
- `:unconfigured-language` - Language not in config

## Development Mode

### File Watching

Uses Hawkeye for file watching:
- Detects changes in content, templates, assets
- Triggers full rebuild on any change
- Build report available at `_report.html` in output directory

### Dev Server

Currently uses browser-sync (npm package) for development server:
- Serves static files from output directory
- Live reload on file changes
- Port 3000 by default

Note: Future versions may switch to embedded Jetty server for better integration.

## Extension Points

### Custom Directives

Add new template directives:

```clojure
(defmethod sg/process-element :my/directive
  [elem context]
  ...)
```

### Asset Processors

Integrate asset pipelines:

```clojure
(defn process-assets [config assets]
  ;; CSS/JS bundling
  ;; Image optimization
  ...)
```

### Content Loaders

Support new content formats:

```clojure
(defmethod load-content-file ".yaml"
  [file]
  (yaml/parse (slurp file)))
```

## Testing

### Unit Testing

Test individual components:

```clojure
(deftest test-eden-link
  (testing "basic link generation"
    (let [template [:eden/link :about ...]
          context {:pages {:about {:slug "about"}}}]
      (is (= expected (sg/process template context))))))
```

### Integration Testing

Test full build pipeline:

```clojure
(deftest test-full-build
  (let [config (load-config "test-site.edn")
        result (pipeline/run-build config)]
    (is (= 0 (:error-count result)))
    (is (fs/exists? "dist/index.html"))))
```

### MCP Simulator

The MCP simulator validates content changes before writing to disk:

```clojure
(require '[eden.mcp.simulator :as sim])

;; Simulate a content change to validate it
(sim/simulate-content-change
  {:site-edn "site.edn"
   :path "en/home.edn"
   :content "{:title \"New Title\"}"})
;; Returns: {:success? true, :html "...", :warnings [...]}
```

Used by MCP handlers to:
- Validate content syntax before writing
- Preview rendered HTML
- Catch template errors early
- Prevent broken content from being saved

## Deployment

### Static Hosting

Eden generates standard static HTML files that can be deployed to any static hosting service. The configurable URL strategies (`:flat` vs `:nested`) and page URL strategies (`:default` vs `:with-extension`) allow you to match the requirements of different hosting platforms.

Common deployment targets include:
- Static site hosts (GitHub Pages, Netlify, Vercel, etc.)
- CDNs with origin storage (CloudFront + S3, etc.)
- Traditional web servers (nginx, Apache, etc.)

Choose the appropriate URL strategy in `site.edn` based on your hosting platform's requirements.

### CI/CD Integration

Eden can be integrated into CI/CD pipelines:

```yaml
# Example GitHub Actions workflow
- name: Install Eden
  run: clj -Ttools install io.github.anteoas/eden '{:git/url "..." :git/tag "v1.0.0"}' :as eden
  
- name: Build site
  run: clj -Teden build :mode '"prod"'
```

### Docker Deployment

```dockerfile
FROM clojure:openjdk-17
COPY . /site
WORKDIR /site
RUN clj -Ttools install io.github.anteoas/eden '{:git/url "..." :git/tag "v1.0.0"}' :as eden
RUN clj -Teden build :mode '"prod"'
FROM nginx:alpine
COPY --from=0 /site/dist /usr/share/nginx/html
```

## REPL Development

Connect to a REPL for interactive development:

```clojure
(require '[eden.core :as eden])
(eden/build :site-edn "site.edn" :mode :dev)
```
