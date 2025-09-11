# Eden - Getting Started Guide

Eden is a powerful static site generator built with Clojure that makes it easy to create multilingual websites with dynamic content. This guide will help you get started quickly.

## Installation

Eden requires Clojure to be installed on your system. Once you have Clojure, install Eden:

```bash
clj -Ttools install io.github.anteoas/eden \
  '{:git/url "https://github.com/anteoas/eden.git" :git/tag "v1.0.0"}' \
  :as eden
```

## Creating Your First Site

### 1. Initialize a New Site

```bash
# Create a new site directory
mkdir my-site
cd my-site

# Initialize Eden in this directory
clj -Teden init
```

This creates the following structure:

```
my-site/
├── site.edn          # Site configuration
├── templates/        # Page templates
│   ├── base.edn     # HTML wrapper
│   └── home.edn     # Homepage template
├── content/          # Your content
│   └── en/          # English content
│       └── home.edn # Homepage content
└── assets/          # CSS, JS, images
    ├── css/
    └── js/
```

### 2. Understanding site.edn

The `site.edn` file is your site's configuration:

```clojure
{:wrapper :base        ; HTML wrapper template
 :index :home          ; Homepage template
 :render-roots #{:home} ; Pages to render
 :lang {:en {:name "English" :default true}}
 :url-strategy :nested} ; Clean URLs
```

Key settings:
- **`:wrapper`** - The template that wraps all pages (provides `<html>`, `<head>`, etc.)
- **`:index`** - The template used for your homepage
- **`:render-roots`** - Starting points for rendering (Eden follows links to find all pages). Usually just `#{:home}` - multiple roots only needed for disconnected pages (email campaigns, landing pages)
- **`:lang`** - Language configuration (add more for multilingual sites)
- **`:url-strategy`** - How URLs are generated (`:flat` for `/page.html` or `:nested` for `/page/index.html`)

### 3. Creating Content

Content files can be either EDN or Markdown:

#### EDN Content (content/en/about.edn):
```clojure
{:title "About Us"
 :slug "about"
 :template :page
 :content/html "<p>Welcome to our company...</p>"}
```

Note: Markdown content is automatically converted to HTML and stored under `:html/content`. You can add this manually in EDN files but markdown is preferred for prose content.

#### Markdown Content (content/en/blog-post.md):
```markdown
{:title "My First Blog Post"
 :slug "blog/first-post"
 :template :blog
 :date "2024-01-15"}
---

# Welcome to My Blog

This is my first blog post using Eden!
```

### 4. Building Templates

Templates use Hiccup syntax with special Eden directives:

#### Basic Page Template (templates/page.edn):
```clojure
[:article
 [:h1 [:eden/get :title]]
 [:eden/body]]
```

#### Using Template Directives:
```clojure
[:div
 ;; Get data from content
 [:h1 [:eden/get :title]]
 
 ;; Conditional rendering
 [:eden/if :show-date
  [:time [:eden/get :date]]]
 
 ;; Iterate over collections
 [:ul
  [:eden/each :items
   [:li [:eden/get :name]]]]
 
 ;; Smart linking
 [:eden/link :about
  [:a {:href [:eden/get :link/href]} 
   [:eden/get :link/title]]]
 
 ;; Translations
 [:p [:eden/t :welcome-message 
      {:name [:eden/get :user-name]}]]]
```

## Building Your Site

### Development Mode

After initializing your site with `clj -Teden init`, you'll have npm scripts available:

```bash
# Start dev server with file watching and hot reload
npm run watch
```

This will:
- Watch for file changes
- Automatically rebuild affected pages
- Serve your site
- Show build reports with warnings

### Production Build

For production deployment:

```bash
# Build static site to dist/
npm run build

# Clean build artifacts
npm run clean
```

### Using Eden Tool Directly

You can also use the Eden tool directly without npm:

```bash
# Show available commands
clj -Teden help

# Development
clj -Teden watch

# Production build
clj -Teden build

# Clean artifacts
clj -Teden clean
```

## Translations and Internationalization

Eden makes it easy to build multilingual sites with the `:eden/t` directive:

### Basic Translation
```clojure
;; In template
[:h1 [:eden/t :welcome/title]]
[:p [:eden/t :welcome/message]]

;; In content/en/strings.edn
{:welcome/title "Welcome"
 :welcome/message "Welcome to our amazing site!"}

;; In content/no/strings.edn  
{:welcome/title "Velkommen"
 :welcome/message "Velkommen til vår fantastiske side!"}
```

### Translation with Interpolation
```clojure
;; strings.edn - use {{variable}} syntax
{:greeting "Hello, {{name}}!"}

;; Template with variable substitution
[:p [:eden/t :greeting {:name [:eden/get :user-name]}]]
```

### Nested Translation Keys
```clojure
;; In strings.edn
{:errors {:not-found "Page not found"
          :forbidden "Access denied"}}

;; Use vectors for nested keys
[:span [:eden/t [:errors :not-found]]]
```

## Working with Multiple Languages

Eden has excellent multilingual support:

### 1. Configure Languages (site.edn):
```clojure
{:lang {:en {:name "English" :default true}
        :no {:name "Norsk"}
        :de {:name "Deutsch"}}}
```

### 2. Organize Content by Language:
```
content/
├── en/
│   ├── home.edn
│   ├── about.edn
│   └── strings.edn  ; English translations
├── no/
│   ├── home.edn
│   ├── about.edn
│   └── strings.edn  ; Norwegian translations
└── de/
    ├── home.edn
    ├── about.edn
    └── strings.edn  ; German translations
```

### 3. Language Switching:
```clojure
[:nav.language-switcher
 [:eden/link {:lang :no}
  [:a {:href [:eden/get :link/href]} [:eden/t :lang/norwegian]]]
 [:eden/link {:lang :en}
  [:a {:href [:eden/get :link/href]} [:eden/t :lang/english]]]]
```

## Advanced Features

### Image Processing

Enable automatic image processing (site.edn):

```clojure
{:image-processor true}
```

When enabled, Eden processes images referenced in your HTML and CSS with the `size` parameter:

```html
<!-- HTML: Original image (no processing) -->
<img src="/assets/images/hero.jpg">

<!-- HTML: Resize to width and height -->
<img src="/assets/images/hero.jpg?size=800x600">

<!-- HTML: Width only (maintains aspect ratio) -->
<img src="/assets/images/hero.jpg?size=1200x">
```

```css
/* CSS: Background images with sizing */
.hero {
  background-image: url('/assets/images/bg.jpg?size=1920x');
}

.thumbnail {
  background-image: url('/assets/images/thumb.jpg?size=200x200');
}
```

Features:
- High-quality image scaling
- Maintains aspect ratio by default
- Generates placeholders for missing images
- Preserves original image format

### Dynamic Content with :eden/render

Render reusable components:

```clojure
;; In template
[:eden/render {:data :products.featured
               :template :product-card}]

;; Or render all matching content
[:eden/each :eden/all :where {:type "product"}
 [:eden/render {:data [:eden/get :content-key]
                :template :product-card}]]
```

### Pages vs Sections

Eden distinguishes between standalone pages and sections within pages:

- **Pages**: Separate HTML files with their own URL (e.g., `/about`, `/services`)
- **Sections**: Parts of a page with anchor links (e.g., `/about#team`, `/services#pricing`)

When you use `:eden/render` with a `:section-id`, it creates a section that can be linked to:

```clojure
;; Creates a section on the about page
[:eden/render {:data :about.team
               :template :team-section
               :section-id true}]

;; Link to it from anywhere
[:eden/link :about.team
 [:a {:href [:eden/get :link/href]} "Meet Our Team"]]
;; Results in: /about#team (or just #team if on same page)
```

### Navigation Helpers

Eden provides smart navigation:

```clojure
;; Link to parent page
[:eden/link {:nav :parent}
 [:a {:href [:eden/get :link/href]} 
  [:eden/t :nav/back-to] " " [:eden/get :link/title]]]

;; Link to root/home
[:eden/link {:nav :root}
 [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
```

## MCP Integration (Coming Soon)

Eden will include Model Context Protocol (MCP) support, allowing AI assistants like Claude to help manage your site content. This feature is currently under development.

When ready, MCP will enable:
- AI-assisted content creation and editing
- Template management
- Automated site builds
- Multi-language content management

Stay tuned for updates!

## Common Patterns

### Blog Listing Page

```clojure
[:div.blog-posts
 [:eden/each :eden/all 
  :where {:type "blog"}
  :order-by [:date :desc]
  :limit 10
  [:article
   [:eden/link [:eden/get :content-key]
    [:h2 [:eden/get :title]]
    [:time [:eden/get :date]]
    [:p [:eden/get :excerpt]]
    [:a {:href [:eden/get :link/href]} 
     [:eden/t :blog/read-more]]]]]]
```

### FAQ Page with Sections

```clojure
;; First, render FAQ items as sections
[:div.faq-list
 [:eden/render {:data :faq.pricing
                :template :faq-item
                :section-id "pricing"}]
 [:eden/render {:data :faq.shipping
                :template :faq-item
                :section-id "shipping"}]
 [:eden/render {:data :faq.returns
                :template :faq-item
                :section-id "returns"}]]

;; Then anywhere on the page (or other pages), link to them
[:nav.quick-links
 [:h3 "Common Questions"]
 [:ul
  [:li [:eden/link :faq.pricing
        [:a {:href [:eden/get :link/href]} "Pricing FAQ"]]]
  [:li [:eden/link :faq.shipping
        [:a {:href [:eden/get :link/href]} "Shipping Info"]]]
  [:li [:eden/link :faq.returns
        [:a {:href [:eden/get :link/href]} "Return Policy"]]]]]
```

## Troubleshooting

### Build Warnings

Eden provides clear warnings to help you fix issues:

- **Missing template**: Check that the template exists in `templates/`
- **Missing content**: Verify the content file exists for the specified language
- **Missing translation**: Add the translation key to your strings file
- **Ambiguous link**: A content ID exists as both a page and section

### Development Tips

1. **Use the REPL**: Connect to a Clojure REPL to test templates interactively
2. **Check build reports**: Eden generates detailed reports of what was built
3. **Validate links**: Eden warns about broken internal links during build

## Next Steps

- Explore the [template directives](internals.md#template-directives) in detail
- Learn about [custom URL strategies](internals.md#url-strategies)
- Set up [CI/CD deployment](internals.md#deployment)

For more technical details about Eden's architecture and extending its functionality, see the [Internals Guide](internals.md).
