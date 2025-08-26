{:name "Eden Template Directives"
 :description "Complete reference for Eden's template system directives"
 :url "eden://template-directives"
 :mime-type "text/markdown"}
---
# Eden Template Directives

Eden templates use EDN-based directives as elements for dynamic content generation.

## Data Access

### `:eden/get`
Get a value from the context data.

```clojure
[:eden/get :title]                ; Get title from data
[:eden/get :missing "default"]    ; With default value
```

**Special Behavior:**
- Keys in `:content/*` namespace return `RawString` for raw HTML rendering
- Content from markdown files is automatically converted to HTML and stored under `:content/html`
- While you can manually add `:content/html` in EDN files with raw HTML, this is discouraged
- Missing keys without defaults show visible error indicator in development mode

### `:eden/get-in`
Get nested values using a path vector.

```clojure
[:eden/get-in [:user :profile :name]]     ; Nested access
[:eden/get-in [:items 0 :title] "N/A"]    ; With default
```

### `:eden/with`
Merge map data into context.

```clojure
[:eden/with :product-details
  [:div
    [:h2 [:eden/get :name]]
    [:p [:eden/get :description]]]]
```

## Control Flow

### `:eden/if`
Conditional rendering based on truthiness.

```clojure
[:eden/if :show-content
  [:div "Shows if truthy"]]

[:eden/if :premium
  [:span "Premium"]
  [:span "Free"]]                 ; With else branch
```

### `:eden/each`
Iterate over collections with powerful filtering, sorting, grouping, and limiting options.

**Collection sources:** The collection key (e.g., `:posts`, `:items`) refers to a field in the current page's data context. This data comes from your content files (markdown frontmatter or EDN). 

Example content file (`products.md`):
```markdown
{:title "Our Products"
 :items [{:name "Widget" :price 99 :category :tools}
         {:name "Gadget" :price 199 :category :electronics}]}
---
# Product Catalog
Browse our selection...
```

Then in your template you can iterate with `[:eden/each :items ...]`.

```clojure
;; Basic iteration over a field from page data
[:ul
 [:eden/each :items        ; :items from markdown frontmatter or EDN content
  [:li [:eden/get :name]]]]

;; With options (order matters for some combinations)
[:eden/each :features      ; :features array from landing.edn (complex page)
 :where {:published true :type :blog}  ; Filter (works with regular collections now!)
 :order-by [:date :desc]               ; Sort by field
 :limit 10                              ; Limit results  
 [:article [:eden/get :title]]]

;; Note: :group-by changes the iteration structure - see below
```

#### Special Collection: `:eden/all`
Iterate over ALL content in the current language:
```clojure
;; Get all blog posts across the site
[:eden/each :eden/all :where {:type :blog}
 [:article
  [:h2 [:eden/get :title]]
  [:p [:eden/get :excerpt]]]]

;; All products grouped by category
[:eden/each :eden/all 
 :where {:type :product}
 :group-by :category
 :order-by [:eden.each/group-key]
 [:section
  [:h3 [:eden/get :eden.each/group-key]]
  [:eden/each :eden.each/group-items
   [:div [:eden/get :name]]]]]
```

#### Special Variables
Available within the iteration body:

**For all iterations:**
- `:eden.each/index` - Current index (0-based)

**For map iterations:**
- `:eden.each/key` - Current map key
- `:eden.each/value` - Current map value

**For grouped iterations:**
- `:eden.each/group-key` - Name of current group
- `:eden.each/group-items` - Collection of items in current group

```clojure
;; Using index for alternating styles
[:eden/each :items
 [:div {:class [:eden/if (even? [:eden/get :eden.each/index])
                 "even-row"
                 "odd-row"]}
  [:eden/get :content]]]

;; Iterating over a map
[:dl
 [:eden/each :metadata
  [:dt [:eden/get :eden.each/key]]
  [:dd [:eden/get :eden.each/value]]]]
```

#### Grouped Iteration Pattern
When using `:group-by`, the body receives each group:
```clojure
[:eden/each :products :group-by :category
 [:div.category-section
  ;; Group name
  [:h2 [:eden/get :eden.each/group-key]]
  ;; Iterate items within this group
  [:div.product-grid
   [:eden/each :eden.each/group-items
    [:div.product-card
     [:h3 [:eden/get :name]]
     [:p [:eden/get :price]]]]]]]
```

#### Context Switching
Inside `:eden/each`, the `:data` context becomes the current item:
```clojure
;; Outside :eden/each
[:h1 [:eden/get :page-title]]     ; Gets from page data

[:eden/each :products
 ;; Inside :eden/each
 [:div
  [:eden/get :name]                ; Gets from current product
  [:eden/get :price]]]              ; Gets from current product
```

#### Common Integration Patterns

**With `:eden/link` for dynamic navigation:**
```clojure
[:nav
 [:eden/each :navigation-items
  [:eden/link [:eden/get :content-key]
   [:a {:href [:eden/get :link/href]} 
    [:eden/get :menu-label]]]]]
```

**With `:eden/render` for dynamic components:**
```clojure
[:eden/each :featured-sections
 [:eden/render {:data [:eden/get :content-key]
                :template [:eden/get :section-type]
                :section-id [:eden/get :anchor-id]}]]
```

**Nested iterations for hierarchical data:**
```clojure
[:eden/each :categories
 [:div.category
  [:h2 [:eden/get :name]]
  [:eden/each :subcategories
   [:div.subcategory
    [:h3 [:eden/get :name]]
    [:eden/each :products
     [:div [:eden/get :title]]]]]]]
```

#### Filtering with `:where`
The `:where` clause supports simple equality matching on all collection types:
```clojure
;; Filter regular collections (now works!)
[:eden/each :posts :where {:published true} ...]

;; Multiple conditions (AND logic)
[:eden/each :products :where {:category :electronics 
                               :featured true} ...]

;; Filter :eden/all content
[:eden/each :eden/all :where {:type :testimonial
                               :homepage true} ...]

;; Works with all options
[:eden/each :items
 :where {:active true :category :tools}
 :order-by [:priority :desc]
 :limit 5
 ...]
```

#### Sorting with `:order-by`
```clojure
;; Ascending order (default)
[:eden/each :items :order-by [:name] ...]
[:eden/each :items :order-by [:name :asc] ...]

;; Descending order
[:eden/each :posts :order-by [:date :desc] ...]

;; Sort groups by group key
[:eden/each :items :group-by :category 
 :order-by [:eden.each/group-key :asc] ...]
```

#### Best Practices
1. **Use keywords for enum-like values in `:where`**: `:type :blog` not `:type "blog"`
2. **Filter early**: Use `:where` to reduce the dataset before sorting/grouping
3. **Access parent context carefully**: Data context switches to each item
4. **Combine with `:eden/link` for dynamic links**: Use `[:eden/get :content-key]` pattern  
5. **Limit results for performance**: Use `:limit` for large collections
6. **Group and sort together**: Often used for organized navigation menus
7. **Remember `:group-by` changes structure**: Must iterate `:eden.each/group-items` within groups

## Content Inclusion

### `:eden/body`
Insert content passed to a wrapper template.
```clojure
[:html
  [:body
    [:main [:eden/body]]]]         ; Page content goes here
```

### `:eden/include`
Include another template directly.
```clojure
[:eden/include :header]
[:eden/include :nav {:active :home}]  ; With additional context
```

### `:eden/render`
Renders a component with specific data and template, creating reusable page sections with isolated data contexts.

#### Syntax Forms
```clojure
[:eden/render content-key]         ; Simple form
[:eden/render {:options}]          ; Map form with options
```

#### Parameters
- `content-key` - Simple form, uses content as data source
- Options map:
  - `:data` - Content key to render (becomes the new `:data` context)
  - `:template` - Template to use for rendering
  - `:section-id` - Create linkable section (registers for fragment URLs)

#### Data Context Behavior
`:eden/render` replaces the current `:data` context with the specified content. The rendered template only has access to this new data (plus global context like `:lang`, `:strings`, `:build-constants`).

#### Template Resolution
When no explicit `:template` is provided:
1. Checks for `:template` field in the content's frontmatter/data
2. Falls back to using the content key as template name
3. Shows error if neither exists

#### Examples

**Default Template Behavior**
```clojure
;; If content/en/team-intro.md has frontmatter {:template :hero ...}
[:eden/render :team-intro]         ; Automatically uses :hero template
```

**Rendering Markdown Content as Sections**
```clojure
;; content/en/features/security.md:
;; ---
;; {:title "Security Features"
;;  :icon "shield"
;;  :priority 1}
;; ---
;; # Enterprise-grade Security
;; We protect your data with industry-leading encryption...

;; In template:
[:eden/render {:data :features.security
               :template :feature-card
               :section-id "security"}]    ; Creates #security anchor on current page
```

**Building Pages from Multiple Content Files**
```clojure
;; Assembling a homepage from various markdown files
[:div.homepage
 [:eden/render {:data :home.hero
                :template :hero-banner}]
 
 [:eden/render {:data :home.features
                :template :feature-grid
                :section-id "features"}]
 
 [:eden/render {:data :testimonials.featured
                :template :testimonial-carousel
                :section-id "testimonials"}]]
```

**Structured Data (EDN Use Case)**
```clojure
;; content/en/sidebar.edn - structured data without prose
{:links [{:title "Documentation" :url "/docs"}
         {:title "API Reference" :url "/api"}
         {:title "GitHub" :url "https://github.com/..."}]
 :recent-posts [:blog.post-1 :blog.post-2 :blog.post-3]
 :template :sidebar-widget}

;; In template:
[:aside [:eden/render :sidebar]]   ; Uses :sidebar-widget template
```

**Dynamic Component Rendering**
```clojure
;; Render components based on data
[:eden/each :page-sections
 [:eden/render {:data [:eden/get :content-key]
                :template [:eden/get :component-type]
                :section-id [:eden/get :anchor]}]]
```

**Nested Component System**
```clojure
;; Dashboard composed of multiple widgets
[:div.dashboard
 [:div.row
  [:div.col [:eden/render :metrics.revenue]]
  [:div.col [:eden/render :metrics.users]]]
 [:div.row
  [:eden/render :alerts.critical]
  [:eden/render :activity-feed]]]
```

#### Section vs Page Distinction

- **Pages**: Standalone HTML files with their own URL (e.g., `/about`, `/services`)
- **Sections**: Parts of the current page with anchor links (e.g., `#team`, `#pricing`)

When you use `:section-id`:
- The content becomes part of the current page (not a separate page)
- Registers in the sections registry for `:eden/link` to reference
- Generates fragment URLs (`#section-id`) for same-page links
- Generates full URLs with fragments (`/page#section-id`) for cross-page links

```clojure
;; This creates a section, not a page:
[:eden/render {:data :about.team
               :template :team-grid
               :section-id "team"}]

;; Link to it from same page:
[:eden/link :about.team ...]       ; Generates: #team

;; Link to it from another page:
[:eden/link :about.team ...]       ; Generates: /about#team
```

#### Context Inheritance
The rendered component receives:
- **Replaced**: `:data` (completely replaced with new content)
- **Inherited**: `:lang`, `:strings`, `:build-constants`, `:site-config`, `:page->url`, `:warn!`
- **Not inherited**: Parent's `:body`, `:path`

#### Error Handling
- **Missing content**: Shows error message in rendered output
- **Missing template**: Falls back to displaying content key name with error
- **Circular references**: Eden tracks render depth and warns about potential cycles

#### Performance Notes
- Content is loaded once and cached during build
- Dependencies are tracked for incremental rebuilds
- Sections are registered globally for link validation

#### Common Patterns

**FAQ Page with Linkable Sections**
```clojure
;; Each FAQ item from markdown becomes a linkable section
[:div.faq
 [:eden/each :faqs
  [:eden/render {:data [:eden/get :content-key]
                 :template :faq-item
                 :section-id [:eden/get :slug]}]]]
```

**Product Features from Markdown**
```clojure
;; content/en/features/ contains multiple .md files
[:section.features
 [:h2 "Features"]
 [:div.grid
  [:eden/each :eden/all
   :where {:category "feature"}
   :order-by [:priority :asc]
   [:eden/render {:data [:eden/get :content-key]
                  :template :feature-card}]]]]
```

## Navigation

### `:eden/link`
Create smart internal links. The body is a template that receives `:link/href` and `:link/title` in its context.

#### Syntax Forms

```clojure
;; Shorthand keyword form
[:eden/link :about ...]
;; Equivalent to:
[:eden/link {:content-key :about} ...]

;; Map form with options
[:eden/link {:content-key :contact  ; Target page (optional)
             :lang :de}              ; Target language (optional)
 ...]

;; Dynamic form (evaluated in context)
[:eden/link [:eden/get :target-page] ...]
```

#### Link Resolution Logic

- **`:content-key` only** → Different page, current language
- **`:lang` only** → Current page, different language (language switcher)
- **Both `:content-key` and `:lang`** → Specific page in specific language
- **`:nav :parent` or `:nav :root`** → Navigation helpers

#### Common Patterns

```clojure
;; Basic page link - uses target page's title
[:eden/link :about
  [:a {:href [:eden/get :link/href]} 
   [:eden/get :link/title]]]

;; With translated text
[:eden/link :contact
  [:a {:href [:eden/get :link/href]} 
   [:eden/t :nav/contact]]]

;; Language switcher - stays on CURRENT page
[:eden/link {:lang :no}
  [:a {:href [:eden/get :link/href]} "Norsk"]]
[:eden/link {:lang :en}
  [:a {:href [:eden/get :link/href]} "English"]]

;; Link to specific page in specific language
[:eden/link {:content-key :products :lang :de}
  [:a {:href [:eden/get :link/href]} 
   [:eden/t :nav/products-in-german]]]

;; Dynamic link in iteration
[:eden/each :products
  [:eden/link [:eden/get :content-key]
    [:a {:href [:eden/get :link/href]}
     [:eden/get :title]]]]         ; Product's title from iteration

;; Navigation helpers
[:eden/link {:nav :parent}
  [:a {:href [:eden/get :link/href]}
   [:eden/t :nav/back-to {:page [:eden/get :link/title]}]]]

[:eden/link {:nav :root}
  [:a.home-link {:href [:eden/get :link/href]} 
   [:eden/t :nav/home]]]
```

#### Section Linking
When linking to content that was rendered with a `:section-id`:
- Same page: generates `#section-id`
- Different page: generates `/page#section-id`
- Pages have priority over sections if both exist

## Internationalization

### `:eden/t`
Translate strings using locale files.

```clojure
[:eden/t :welcome]                 ; Simple translation
[:eden/t :missing "Default"]       ; With fallback
[:eden/t :greeting {:name [:eden/get :username]}]  ; Interpolation
[:eden/t [:errors :not-found]]     ; Nested keys
```

**Best Practice:** Always use translations or content data instead of hardcoded strings:
```clojure
;; GOOD - text from translations
[:button [:eden/t :buttons/submit]]

;; BAD - hardcoded string
[:button "Submit"]
```

Translation files are stored in `content/<lang>/strings.edn`:
```clojure
{:buttons/submit "Submit"
 :greeting "Hello, {{name}}!"
 :errors {:not-found "Page not found"}}
```

## Content Structure

Eden internally stores all content in EDN format. When processing markdown files:
1. Markdown content is parsed and converted to HTML
2. The HTML is stored under the `:content/html` key
3. This HTML is returned as a `RawString` when accessed via `[:eden/get :content/html]`

While you can manually add `:content/html` keys in EDN files with raw HTML strings, this is discouraged in favor of using markdown files for content with prose.