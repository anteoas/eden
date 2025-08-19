# Eden Template Directive Reference

This document provides complete reference documentation for all Eden template directives.

## :eden/get

Retrieves a value from the current context data, following Clojure's `get` semantics.

### Syntax
```clojure
[:eden/get key]
[:eden/get key default-value]
```

### Parameters
- `key` - Key to look up in `:data` (works with maps and vectors)
- `default-value` (optional) - Value to return if key is not found or value is nil

### Semantics (same as Clojure)
- Works on maps (keyword/string keys) and vectors (numeric indices)
- Returns nil if key not found and no default provided
- Returns default value if key not found or value is nil
- For vectors, key must be a valid index

### Examples
```clojure
;; Basic map access
[:h1 [:eden/get :title]]

;; With default value
[:span [:eden/get :subtitle "Untitled"]]

;; Vector access by index
[:eden/get 0]  ; First item if :data is a vector

;; In attributes
[:img {:src [:eden/get :image-url]
       :alt [:eden/get :image-alt]}]
```

### Special Behavior
- Keys in `:content/*` namespace return `RawString` for HTML content
- Missing keys without defaults show visible error indicator in development mode

## :eden/get-in

Retrieves nested values using a path vector, following Clojure's `get-in` semantics.

### Syntax
```clojure
[:eden/get-in path]
[:eden/get-in path default-value]
```

### Parameters
- `path` - Vector of keys to traverse through nested structures
- `default-value` (optional) - Value to return if path not found

### Semantics (same as Clojure)
- Traverses nested maps and vectors
- Returns nil if any key in path not found and no default provided
- Returns default value if path cannot be fully traversed
- Empty path returns the data itself

### Examples
```clojure
;; Nested map access
[:eden/get-in [:user :profile :name]]

;; Mixed map and vector access
[:eden/get-in [:items 0 :title]]  ; First item's title

;; With default
[:eden/get-in [:config :theme :color] "#000000"]

;; Empty path returns full data
[:eden/get-in []]

;; Single element path (equivalent to :eden/get)
[:eden/get-in [:title]]
```

## :eden/each

Iterates over collections with powerful filtering and transformation options.

### Syntax
```clojure
[:eden/each collection-key template]
[:eden/each collection-key options... template]
```

### Parameters
- `collection-key` - Key in data or `:eden/all` for all content
- `options` (optional):
  - `:where {field value}` - Filter items
  - `:order-by [field direction]` - Sort items
  - `:limit n` - Limit number of items
  - `:group-by field` - Group items by field
- `template` - Template to render for each item

### Special Variables
Available in template during iteration:
- `:eden.each/index` - Current index (0-based)
- `:eden.each/key` - Map key (when iterating maps)
- `:eden.each/value` - Map value (when iterating maps)
- `:eden.each/group-key` - Group name (with `:group-by`)
- `:eden.each/group-items` - Items in group (with `:group-by`)

### Examples
```clojure
;; Simple list
[:ul
 [:eden/each :items
  [:li [:eden/get :name]]]]

;; With filtering and sorting
[:eden/each :posts
 :where {:published true}
 :order-by [:date :desc]
 :limit 5
 [:article
  [:h2 [:eden/get :title]]
  [:p "Posted on " [:eden/get :date]]]]

;; Iterate all content
[:eden/each :eden/all
 :where {:type "product"}
 [:div [:eden/get :title]]]

;; Map iteration
[:dl
 [:eden/each :metadata
  [:dt [:eden/get :eden.each/key]]
  [:dd [:eden/get :eden.each/value]]]]

;; Grouping
[:eden/each :articles
 :group-by :category
 [:section
  [:h2 [:eden/get :eden.each/group-key]]
  [:eden/each :eden.each/group-items
   [:article [:eden/get :title]]]]]

;; Using index
[:eden/each :items
 [:div {:class (if (even? [:eden/get :eden.each/index])
                 "even-row"
                 "odd-row")}
  [:eden/get :content]]]
```

## :eden/if

Conditional rendering based on truthiness.

### Syntax
```clojure
[:eden/if condition then-branch]
[:eden/if condition then-branch else-branch]
```

### Parameters
- `condition` - Value or path to check
- `then-branch` - Rendered if condition is truthy
- `else-branch` (optional) - Rendered if condition is falsy

### Truthiness
Following Clojure semantics:
- Falsy: `nil`, `false`
- Truthy: Everything else (including empty collections and empty strings)

### Examples
```clojure
;; Simple conditional
[:eden/if :premium
 [:span.badge "Premium"]]

;; With else branch
[:eden/if :logged-in
 [:a {:href "/profile"} "Profile"]
 [:a {:href "/login"} "Login"]]

;; Nested path
[:eden/if [:user :is-admin]
 [:a {:href "/admin"} "Admin Panel"]]

;; Check for data presence
[:eden/if :optional-field
 [:div [:eden/get :optional-field]]]
```

## :eden/link

Creates smart links to pages and sections with automatic URL generation.

### Syntax
```clojure
[:eden/link target body]
[:eden/link {:options} body]
```

### Parameters
- `target` - Can be:
  - Keyword - Link to page/section by content key
  - Map with options:
    - `:content-key` - Target page/section
    - `:lang` - Target language
    - `:nav` - Navigation helper (`:parent` or `:root`)
  - Dynamic expression - `[:eden/get :field]`
- `body` - Template to render with link context

### Link Context
The body receives additional data:
- `:link/href` - Generated URL
- `:link/title` - Page title

### Examples
```clojure
;; Simple page link
[:eden/link :about
 [:a {:href [:eden/get :link/href]}
  [:eden/get :link/title]]]

;; Language switching
[:eden/link {:lang :no}
 [:a {:href [:eden/get :link/href]} "Norsk"]]

;; Link to specific page in specific language
[:eden/link {:content-key :contact :lang :en}
 [:a {:href [:eden/get :link/href]} "Contact Us"]]

;; Navigation helpers
[:eden/link {:nav :parent}
 [:a {:href [:eden/get :link/href]}
  "Back to " [:eden/get :link/title]]]

[:eden/link {:nav :root}
 [:a.home {:href [:eden/get :link/href]} "Home"]]

;; Dynamic linking in :eden/each
[:eden/each :related-pages
 [:eden/link [:eden/get :content-key]
  [:a {:href [:eden/get :link/href]}
   [:eden/get :link/title]]]]
```

### Section Links
When linking to sections (created with `:eden/render` + `:section-id`):
- Same page: `#section-id`
- Different page: `/page#section-id`

## :eden/render

Renders a component with specific data and template.

### Syntax
```clojure
[:eden/render content-key]
[:eden/render {:options}]
```

### Parameters
- `content-key` - Simple form using content as template name
- Options map:
  - `:data` - Content key or data to render
  - `:template` - Template to use
  - `:section-id` - Create linkable section

### Examples
```clojure
;; Simple component
[:eden/render :sidebar]

;; With explicit template
[:eden/render {:data :latest-news
               :template :news-widget}]

;; Create section
[:eden/render {:data :team.management
               :template :team-section
               :section-id "management"}]

;; Dynamic rendering
[:eden/each :components
 [:eden/render {:data [:eden/get :content-key]
                :template [:eden/get :widget-type]}]]
```

### Section Registration
Using `:section-id` registers the section for `:eden/link` to reference.

## :eden/t

Translates text using locale-specific strings.

### Syntax
```clojure
[:eden/t key]
[:eden/t key default]
[:eden/t key interpolations]
```

### Parameters
- `key` - Translation key (keyword or vector for nested)
- `default` (optional) - Fallback text
- `interpolations` - Map of values to substitute

### String Files
Translations stored in `content/<lang>/strings.edn`:
```clojure
{:nav/home "Home"
 :greeting "Hello, {{name}}!"
 :errors {:not-found "Page not found"}}
```

### Examples
```clojure
;; Simple translation
[:eden/t :nav/home]

;; With default
[:eden/t :new-feature "Coming Soon"]

;; With interpolation
[:eden/t :greeting {:name [:eden/get :username]}]
;; If strings.edn has: {:greeting "Hello, {{name}}!"}
;; And :username is "Alice"
;; Results in: "Hello, Alice!"

;; Nested keys
[:eden/t [:errors :not-found]]

;; Multiple interpolations
[:eden/t :order-status 
 {:status [:eden/get :status]
  :date [:eden/get :ship-date]}]
```

## :eden/with

Merges map data into the current context.

### Syntax
```clojure
[:eden/with data-key body]
```

### Parameters
- `data-key` - Key containing map to merge
- `body` - Template with access to merged data

### Examples
```clojure
;; Merge product details
[:eden/with :product
 [:div.product
  [:h2 [:eden/get :name]]
  [:p.price [:eden/get :price]]
  [:p [:eden/get :description]]]]

;; Nested with
[:eden/with :user
 [:div.profile
  [:h1 [:eden/get :name]]
  [:eden/with :preferences
   [:div.settings
    [:p "Theme: " [:eden/get :theme]]
    [:p "Language: " [:eden/get :language]]]]]]
```

## :eden/body

Inserts the body content, typically used in wrapper templates.

### Syntax
```clojure
[:eden/body]
```

### Usage
Primarily in wrapper templates (e.g., `base.edn`):
```clojure
[:html
 [:head
  [:title [:eden/get :title]]]
 [:body
  [:header "..."]
  [:main [:eden/body]]
  [:footer "..."]]]
```

## :eden/include

Includes another template in place.

### Syntax
```clojure
[:eden/include template-name]
[:eden/include template-name override-context]
```

### Parameters
- `template-name` - Keyword name of template
- `override-context` (optional) - Additional context to merge

### Examples
```clojure
;; Simple include
[:eden/include :header]

;; With context override
[:eden/include :nav {:active-page :home}]

;; In page template
[:div.page
 [:eden/include :header]
 [:main [:eden/body]]
 [:eden/include :footer {:year 2024}]]
```

## Context Structure

All directives operate within a context containing:

```clojure
{:data {...}           ; Current page/component data
 :body [...]           ; Body content (for wrappers)
 :lang :en             ; Current language code
 :path [:section :id]  ; Page hierarchy path
 :pages {...}          ; All pages registry
 :sections {...}       ; Sections registry
 :strings {...}        ; Translation strings
 :content-data {...}   ; All content for current language
 :templates {...}      ; All loaded templates
 :page->url fn         ; URL generation function
 :site-config {...}    ; Site configuration
 :build-constants {...}; Build-time constants
 :current-page-id :x   ; Current page identifier
 :warn! fn}            ; Warning collector
```

## Directive Composition

Directives can be nested and composed:

```clojure
;; Conditional iteration
[:eden/if :show-posts
 [:eden/each :posts
  :limit 3
  [:article [:eden/get :title]]]]

;; Dynamic links in loops
[:eden/each :menu-items
 [:eden/link [:eden/get :page-id]
  [:a {:href [:eden/get :link/href]
       :class [:eden/if :active "active" ""]}
   [:eden/t [:eden/get :label-key]]]]]

;; Nested data access
[:eden/with :config
 [:eden/if [:settings :enable-comments]
  [:eden/include :comment-form
   {:user [:eden/get-in [:current-user :name]]}]]]
```

## Error Handling

In development mode, Eden provides helpful error messages:

- **Missing data**: Shows `[:eden/get :missing-key]` indicator
- **Missing template**: Error message in rendered output
- **Missing translation**: Shows `### key ###` placeholder
- **Invalid directive**: Preserves directive in output for debugging

Production builds collect warnings without failing, allowing partial success.

## Best Practices

1. **Use semantic keys**: `:nav/home` instead of `:home-nav-text`
2. **Avoid defaults in templates**: Define all translations in strings files
3. **Prefer `:eden/link`**: Use for all internal links to get validation
4. **Group related data**: Use `:eden/with` to avoid repetitive paths
5. **Create reusable components**: Use `:eden/render` for repeated patterns
6. **Test with missing data**: Ensure templates handle optional fields gracefully

## Site Configuration (site.edn)

Complete reference for all site.edn configuration options:

### Required Fields

```clojure
{:wrapper :base        ; Template that wraps all pages
 :index :home          ; Template for homepage (/)
 :render-roots #{:home} ; Set of root pages to start rendering from
 :lang {:en {:name "English" ; At least one language required
             :default true}}} ; One must be default
```

### Optional Fields

```clojure
{;; URL Generation
 :url-strategy :nested  ; How files are output
                        ; :flat (default) - /page.html
                        ; :nested - /page/index.html
                        ; Or custom function
 
 :page-url-strategy :default ; How links are formatted
                             ; :default - /page
                             ; :with-extension - /page.html
                             ; Or custom function

 ;; Directory Paths (relative to site.edn)
 :templates "templates"  ; Template directory (default: "templates")
 :content "content"      ; Content directory (default: "content")
 :assets "assets"        ; Assets directory (default: "assets")
 :output "dist"          ; Output directory (default: "dist")

 ;; Features
 :image-processor false  ; Enable image processing (default: false)
 
 ;; Build Constants - available in all templates
 :build-constants {:site/name "My Site"
                   :site/author "John Doe"
                   :copyright/year 2024}
 
 ;; Multiple Languages
 :lang {:en {:name "English"
             :default true}   ; Default has no URL prefix
        :no {:name "Norsk"}   ; Creates /no/ URLs
        :de {:name "Deutsch"  ; Creates /de/ URLs
             :hidden true}}}  ; Hidden from language switchers
```

### Advanced Configuration

#### Custom URL Strategy Function

You can define custom URL strategies in three ways:

**1. Reference an external function:**
```clojure
;; In site.edn
{:url-strategy my.namespace/custom-url-strategy}

;; In my/namespace.clj
(defn custom-url-strategy
  "Receives {:path '/'} and returns output filename"
  [{:keys [path]}]
  (if (= path "/")
    "index.html"
    (str (subs path 1) ".html")))
```

**2. Inline function using SCI (evaluated at build time):**
```clojure
;; In site.edn
{:url-strategy (fn [{:keys [path]}]
                 (if (= path "/")
                   "index.html"
                   (str (subs path 1) ".html")))}
```

#### Custom Page URL Strategy Function

Similarly for page URL strategies:

**1. Reference an external function:**
```clojure
;; In site.edn
{:page-url-strategy my.namespace/custom-page-url}

;; In my/namespace.clj
(defn custom-page-url
  "Generates URLs for :eden/link directives"
  [{:keys [slug lang site-config]}]
  (let [lang-prefix (when-not (= lang :en) 
                      (str "/" (name lang)))]
    (str lang-prefix "/" slug ".html")))
```

**2. Inline function using SCI:**
```clojure
;; In site.edn
{:page-url-strategy (fn [{:keys [slug lang site-config]}]
                      (let [default-lang :en
                            lang-prefix (when-not (= lang default-lang)
                                          (str "/" (name lang)))]
                        (str lang-prefix "/" slug ".html")))}
```

## Image Processing Configuration

When `:image-processor true` is set in site.edn, Eden processes images with query parameters.

### URL Query Parameters

Use the `size` parameter in image URLs:

```html
<!-- Width and height -->
<img src="/assets/images/hero.jpg?size=800x600">

<!-- Width only (maintains aspect ratio) -->
<img src="/assets/images/hero.jpg?size=800x">

<!-- Note: Height-only is not supported via URL parameters -->
```

### CSS Background Images

```css
.hero {
  /* Same format as HTML */
  background-image: url('/assets/images/bg.jpg?size=1920x');
}

.thumbnail {
  /* Creates 200x200 thumbnail */
  background-image: url('/assets/images/thumb.jpg?size=200x200');
}
```

### Processing Behavior

- **Quality**: Uses high-quality scaling algorithm
- **Aspect Ratio**: Always maintained (no stretching option via URLs)
- **Missing Images**: Generates placeholder with filename and dimensions
- **Caching**: Processed images are cached, only regenerated if source changes
- **Format Preservation**: Output format matches input (JPG stays JPG, PNG stays PNG)

### Output Location

Processed images are placed in `.temp/images/` with descriptive names:
- `hero.jpg?size=800x` → `hero-800x.jpg`
- `hero.jpg?size=800x600` → `hero-800x600.jpg`

### Placeholder Images

When source images are missing, Eden generates placeholders showing:
- The missing filename
- Requested dimensions
- Gray background with border

This helps identify missing assets during development without breaking the build.