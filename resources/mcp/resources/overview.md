{:name "Eden Overview"
 :description "Core concepts and best practices for Eden static site generator"
 :url "eden://overview"
 :mime-type "text/markdown"}
---
# Eden Overview

Eden is a static site generator with a clear separation of concerns: content belongs in content files, layout belongs in templates, and strings belong in translation files.

## Core Philosophy

### Separation of Concerns
- **Content** (`content/`) - Your actual content: markdown for prose, EDN for structured data
- **Templates** (`templates/`) - Site layout and structure using Hiccup and Eden directives
- **Assets** (`assets/`) - CSS, JavaScript, images
- **Configuration** (`site.edn`) - Site-wide settings

### No Hardcoded Strings in Templates
Templates should never contain hardcoded text. Use:
- `[:eden/get :field]` for content data
- `[:eden/t :key]` for UI strings and translations
- `[:eden/link]` for dynamic page titles

## Content vs Strings

**Content** is the substance of your site - blog posts, product descriptions, about pages. Each piece of content typically becomes a page or section. Content lives in markdown or EDN files named after what they represent (`about.md`, `products.edn`).

**Strings** are the UI text - button labels, navigation text, error messages, form placeholders. These are shared across pages and need translation. Strings live in `strings.edn` files, one per language.

## Content Files

### When to Use Markdown (.md)
For prose content with metadata:
```markdown
{:title "Blog Post"
 :date "2024-01-15"
 :author "Jane Doe"}
---
# My Blog Post
Write your article content here...
```

### When to Use EDN (.edn)
For structured data without prose:
```clojure
{:links [{:title "Home" :url "/"}
         {:title "About" :url "/about"}]
 :social {:twitter "@company"
          :github "company/repo"}}
```

## File Organization

### Content Structure
```
content/
├── en/
│   ├── home.md          ; → :home (page content)
│   ├── about.md         ; → :about (page content)
│   ├── blog/
│   │   └── post-1.md    ; → :blog.post-1 (page content)
│   ├── navigation.edn   ; → :navigation (structured data)
│   └── strings.edn      ; UI translations
└── no/
    └── (same structure for Norwegian)
```

### Content Keys
Files map to keywords with dots for nesting:
- `content/en/home.md` → `:home`
- `content/en/blog/post-1.md` → `:blog.post-1`

## Templates

Templates use Eden directives to pull content:
```clojure
[:article
 [:h1 [:eden/get :title]]      ; From content file
 [:eden/body]                   ; Markdown HTML
 [:footer [:eden/t :copyright]]] ; From strings.edn
```

## Best Practices

1. **Use markdown for articles** - Any content with prose
2. **Use EDN for data** - Navigation, configuration, metadata
3. **Translate everything** - UI text goes in `strings.edn`
4. **Let Eden handle HTML** - Markdown becomes `:content/html` automatically
5. **Link everything** - Use `:eden/link` for internal navigation
6. **One render-root usually suffices** - Eden discovers linked pages from `:home`

## Working with an Existing Site

The project should already have basic configuration if initialized with `clj -Teden init`.

**For the user to see changes:**
1. Start a server: `(eden.core/serve)` - tell the user to open http://localhost:3000
2. After each change: `(eden.core/build)` - updates what they see
3. Check build output for warnings - Eden guides you if something's missing