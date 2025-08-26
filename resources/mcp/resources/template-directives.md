{:name "Eden Template Directives"
 :description "Complete reference for Eden's template system directives"
 :url "eden://template-directives"
 :mime-type "text/markdown"}
---
# Eden Template Directives

Eden templates use EDN-based directives as elements for dynamic content generation.

## Core Directives

### `:eden/get`
Get a value from the context data.
```clojure
[:eden/get :title]                ; Get title from data
[:eden/get :missing "default"]    ; With default value
```

### `:eden/get-in`
Get nested values using a path vector.
```clojure
[:eden/get-in [:user :profile :name]]     ; Nested access
[:eden/get-in [:items 0 :title] "N/A"]    ; With default
```

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

```clojure
;; Basic iteration
[:ul
 [:eden/each :items
  [:li [:eden/get :name]]]]

;; With all options
[:eden/each :posts
 :where {:published true :type :blog}  ; Filter items
 :order-by [:date :desc]               ; Sort by field
 :limit 10                              ; Limit results
 :group-by :category                   ; Group by field
 [:article [:eden/get :title]]]
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
The `:where` clause supports simple equality matching:
```clojure
;; Single condition
[:eden/each :posts :where {:published true} ...]

;; Multiple conditions (AND logic)
[:eden/each :products :where {:category :electronics 
                               :featured true} ...]

;; With :eden/all
[:eden/each :eden/all :where {:type :testimonial
                               :homepage true} ...]
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
2. **Access parent context carefully**: Data context switches to each item
3. **Combine with `:eden/link` for dynamic links**: Use `[:eden/get :content-key]` pattern
4. **Limit results for performance**: Use `:limit` for large collections
5. **Group and sort together**: Often used for organized navigation menus

### `:eden/with`
Merge map data into context.
```clojure
[:eden/with :product-details
  [:div
    [:h2 [:eden/get :name]]
    [:p [:eden/get :description]]]]
```

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
Render content with a specific template. Can create linkable sections.
```clojure
[:eden/render :sidebar]            ; Simple component

[:eden/render {:data :team.leadership
               :template :team-grid
               :section-id "leadership"}]  ; Creates #leadership anchor

;; Dynamic rendering
[:eden/each :widgets
  [:eden/render {:data [:eden/get :content-key]
                 :template [:eden/get :widget-type]}]]
```

### `:eden/t`
Translate strings using locale files.
```clojure
[:eden/t :welcome]                 ; Simple translation
[:eden/t :missing "Default"]       ; With fallback
[:eden/t :greeting {:name [:eden/get :username]}]  ; Interpolation
[:eden/t [:errors :not-found]]     ; Nested keys
```

## Important Patterns

### Link Context
`:eden/link` provides a template context, not just a link:
```clojure
;; CORRECT - eden/link wraps a template
[:eden/link :about
  [:div.link-wrapper
    [:a {:href [:eden/get :link/href]}
     [:span.icon "→"]
     [:eden/get :link/title]]]]

;; The body has access to:
;; - :link/href - the generated URL
;; - :link/title - the target page's title
;; - All current context data
```

### No Hardcoded Strings
Always use translations or content data:
```clojure
;; GOOD - text from translations
[:eden/link :about
  [:a {:href [:eden/get :link/href]} 
   [:eden/t :nav/about]]]

;; BAD - hardcoded string
[:eden/link :about
  [:a {:href [:eden/get :link/href]} 
   "About Us"]]
```

### Dynamic vs Static Links
```clojure
;; Static - known at template time
[:eden/link :products ...]

;; Dynamic - from data/iteration
[:eden/link [:eden/get :target-page] ...]
[:eden/link [:eden/get-in [:nav :home-link]] ...]
```