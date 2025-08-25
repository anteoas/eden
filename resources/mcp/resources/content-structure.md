{:name "Eden Content Structure"
 :description "How to structure content files and frontmatter in Eden"
 :url "eden://content-structure"
 :mime-type "text/markdown"}
---
# Eden Content Structure

## File Organization
```
content/
├── en/           # English content
│   ├── index.md
│   ├── about.md
│   └── blog/
│       ├── post-1.md
│       └── post-2.md
└── es/           # Spanish content
    └── index.md
```

## Frontmatter Format
Content files use EDN frontmatter separated by `---`:
```clojure
{:title "Page Title"
 :template :blog-post  ; Template to use
 :slug "custom-url"    ; Optional custom URL
 :date "2024-01-15"
 :author "Jane Doe"
 :tags ["clojure" "web"]
 :draft false}
---
# Markdown Content Here
Regular markdown content...
```

## Common Frontmatter Fields
- `:title` - Page title
- `:template` - Template name (keyword)
- `:slug` - Custom URL slug
- `:date` - Publication date
- `:draft` - Hide from production
- `:tags` - List of tags
- `:description` - SEO description
- `:image` - Featured image path

## Content Keys
Content is accessed by key path:
- `index.md` → `:index`
- `about.md` → `:about`
- `blog/post-1.md` → `:blog.post-1`

## Language Support
Multi-language sites use language codes:
- `content/en/` - English
- `content/es/` - Spanish
- `content/fr/` - French
Access: `:en.index`, `:es.index`, etc.