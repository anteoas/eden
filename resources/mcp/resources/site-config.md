{:name "Eden Site Configuration"
 :description "Complete reference for site.edn configuration"
 :url "eden://site-config"
 :mime-type "text/markdown"}
---
# Eden Site Configuration

The `site.edn` file is Eden's central configuration file that controls how your site is built. It must be located in your project root directory.

## Required Keys

### `:wrapper`
The template that wraps all pages with HTML structure (doctype, head, body tags).
- Example: `:wrapper :base` uses `templates/base.edn`
- This template receives the page content in its `:body` context
- Use `[:eden/body]` in the wrapper to insert page content

### `:index`
Template used for the homepage (`/`).
- Example: `:index :home` uses `templates/home.edn` for the root URL
- This is typically your landing page or main entry point

### `:render-roots`
Set of content keys to start rendering from.
- Eden automatically discovers and renders ALL pages reachable through `:eden/link` directives from these roots
- **Usually contains only one entry** (typically `:home`)
- Multiple entries are only needed for **disconnected pages** that aren't linked from your main site navigation
- Common use cases for multiple roots:
  - Landing pages for email campaigns
  - Standalone forms for social media ads
  - Promo pages with no site navigation
  - Special event pages kept separate from main site
- Example: `:render-roots #{:home}` (typical - renders entire linked site)
- Example: `:render-roots #{:home :black-friday-promo :newsletter-signup}` (home plus isolated campaign pages)

**Important:** If you find yourself adding many entries to `:render-roots`, it likely means your pages aren't properly linked together. Check that your navigation templates use `:eden/link` to connect all pages you want to render. Eden's link-following ensures that one root (usually `:home`) is sufficient for most sites.

### `:lang`
Language configuration map defining available languages.
- At least one language required with `:default true`
- The default language has no URL prefix
- Non-default languages get URL prefixes (e.g., `/de/about`, `/fr/about`)
- Example: `:lang {:en {:name "English" :default true}}`
- Multi-language example:
  ```clojure
  :lang {:en {:name "English" :default true}
         :no {:name "Norsk"}
         :de {:name "Deutsch"}}
  ```

## Optional Keys

### `:url-strategy`
Controls how HTML files are written to disk.
- `:flat` (default) - Creates `/about.html`, `/contact.html`
- `:nested` - Creates `/about/index.html`, `/contact/index.html` (better for many web servers)
- Custom function - Provide your own URL generation logic

### `:page-url-strategy`
Controls how URLs appear in generated links.
- `:default` - Clean URLs like `/about`, `/contact`
- `:with-extension` - Traditional URLs like `/about.html`, `/contact.html`
- Custom function - Provide your own link formatting logic

### `:content`
Path to content directory relative to site.edn.
- Default: `"content"`
- Example: `:content "site-content"` looks for content in `site-content/` directory

### `:templates`
Path to templates directory relative to site.edn.
- Default: `"templates"`
- Example: `:templates "layouts"` looks for templates in `layouts/` directory

### `:assets`
Path to assets directory relative to site.edn.
- Default: `"assets"`
- Example: `:assets "static"` looks for CSS/JS/images in `static/` directory

### `:output`
Path to build output directory relative to site.edn.
- Default: `"dist"`
- Example: `:output "public"` builds site to `public/` directory

### `:image-processor`
Boolean flag to enable automatic image resizing.
- Default: `false`
- When `true`, processes images with `?size=WxH` parameters in URLs
- Example: `<img src="/images/hero.jpg?size=800x600">` creates a resized version
- Works in both HTML and CSS files

### `:build-constants`
Map of data available in all templates globally.
- Useful for site-wide configuration values
- Available via `:eden/get` in any template
- Example:
  ```clojure
  :build-constants {:site/name "My Company"
                    :copyright/year 2024
                    :contact/email "info@example.com"
                    :social/twitter "@mycompany"}
  ```

## Language Configuration Details

Each language in the `:lang` map can have these fields:

### `:name`
Display name shown in language switchers.
- Example: `{:name "English"}`

### `:default`
Boolean indicating the default language.
- Only one language can be default
- Default language has no URL prefix
- Required for one language
- Example: `{:default true}`



## Custom URL Strategies

### External Function
Reference a function from your codebase:
```clojure
{:url-strategy my.namespace/custom-url-strategy}
```

The function receives a map with `:path` and returns the output filename:
```clojure
(defn custom-url-strategy
  [{:keys [path]}]
  (if (= path "/")
    "index.html"
    (str (subs path 1) ".html")))
```

### Inline Function
Define directly in site.edn using SCI (Small Clojure Interpreter):
```clojure
{:url-strategy (fn [{:keys [path]}]
                 (if (= path "/")
                   "index.html"
                   (str (subs path 1) ".html")))}
```

### Page URL Strategy Function
Similar options available for `:page-url-strategy`:
```clojure
{:page-url-strategy (fn [{:keys [slug lang site-config]}]
                      (let [lang-prefix (when-not (= lang (:default-lang site-config))
                                          (str "/" (name lang)))]
                        (str lang-prefix "/" slug)))}
```

## Complete Example

### Simple Blog
```clojure
{:wrapper :base
 :index :home
 :render-roots #{:home}
 :lang {:en {:name "English" :default true}}
 :url-strategy :nested
 :build-constants {:site/name "My Blog"
                   :author "Jane Doe"
                   :copyright/year 2024}}
```

### Multi-language Corporate Site
```clojure
{:wrapper :base
 :index :home
 :render-roots #{:home}
 :lang {:en {:name "English" :default true}
        :de {:name "Deutsch"}
        :fr {:name "Français"}
        :jp {:name "日本語"}}
 :url-strategy :nested
 :page-url-strategy :default
 :image-processor true
 :build-constants {:company/name "ACME Corp"
                   :contact/email "info@acme.com"
                   :contact/phone "+1-555-0100"}}
```

### Marketing Site with Campaign Pages
```clojure
{:wrapper :base
 :index :home
 :render-roots #{:home           ; Main site
                 :summer-sale    ; Unlinked promo page for email campaign
                 :webinar-signup} ; Standalone form for social media ads
 :lang {:en {:name "English" :default true}}
 :url-strategy :nested
 :image-processor true
 :build-constants {:site/name "Product Launch"
                   :event/date "2024-06-15"
                   :promo/code "SUMMER2024"}}
```

## Configuration Validation

Eden validates your configuration at build time:
- Missing required keys cause build errors
- Invalid template references show warnings
- Unreachable content (not in render-roots or linked) produces warnings
- Multiple default languages cause errors

## Best Practices

1. **Keep render-roots minimal** - Usually just `#{:home}` is enough
2. **Use build-constants for global data** - Avoid hardcoding values in templates
3. **Choose url-strategy based on your server** - `:nested` works better with most modern hosts
4. **Set image-processor only when needed** - Adds processing time to builds
5. **Use meaningful language names** - Display names appear in language switchers
6. **Organize related config together** - Group all language settings, all paths, etc.