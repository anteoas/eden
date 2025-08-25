{:name "Eden Template Directives"
 :description "Complete reference for Eden's template system directives"
 :url "eden://template-directives"
 :mime-type "text/markdown"}
---
# Eden Template Directives

Eden templates use EDN-based directives as elements for dynamic content generation.

## Core Directives

### `:eden/get`
Get a value from the context.
```clojure
[:eden/get :title]              ; Get title from data
[:eden/get :site.title]         ; Nested access
[:eden/get [:data :description]] ; Vector path
```

### `:eden/if`
Conditional rendering based on truthiness.
```clojure
[:eden/if :show-content
  [:div "This shows if show-content is truthy"]]
```

### `:eden/each`
Iterate over collections.
```clojure
[:ul
  [:eden/each :posts
    [:li [:eden/get :title]]]]
```

### `:eden/with`
Create local bindings.
```clojure
[:eden/with {:user :current-user}
  [:p "Welcome " [:eden/get :user.name]]]
```

### `:eden/link`
Create internal links with proper URL handling.
```clojure
[:eden/link :about "About Us"]        ; Link to about page
[:eden/link [:blog :post-1] "Post"]   ; Link to nested page
```

### `:eden/body`
Insert content passed to a wrapper template.
```clojure
[:main
  [:eden/body]]  ; Content from wrapped template goes here
```

### `:eden/include`
Include another template.
```clojure
[:eden/include :partials.header]
[:eden/include :components.nav]
```

### `:eden/render`
Render a page reference inline.
```clojure
[:eden/render :featured-post]  ; Render another page's content
```

### `:eden/t`
Translate strings for i18n.
```clojure
[:eden/t :welcome-message]      ; Get translation
[:eden/t :greeting {:name "John"}] ; With interpolation
```

## Directive Combinations
Directives can be nested and combined:
```clojure
[:eden/if :posts
  [:ul
    [:eden/each :posts
      [:eden/if :published
        [:li
          [:eden/link [:blog [:eden/get :slug]]
            [:eden/get :title]]]]]]]
```