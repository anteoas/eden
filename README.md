# Eden

Static sites from EDN and Markdown.

Eden is a Clojure static site generator designed for content-driven websites.

## Features

- **Content-driven**: Write content in EDN or Markdown with EDN frontmatter
- **Multi-language support**: Built-in internationalization with translations
- **Flexible templates**: Hiccup-style templates with powerful directives
- **Smart dependency tracking**: Only builds pages that are linked
- **Modern asset pipeline**: CSS/JS bundling with esbuild (when available)
- **Image processing**: Resize images with query parameters
- **Developer friendly**: File watching, browser-sync, and build reports

## Quick Start

```bash
# Install Eden as a tool
clj -Ttools install io.github.anteoas/eden \
  '{:git/url "https://github.com/anteoas/eden.git" :git/tag "v2025.08.19"}' \
  :as eden

# Create and initialize a new site
mkdir my-site
cd my-site
clj -Teden init

# Start development server
npm run dev

# Build for production
npm run build
```

## Project Structure

```
my-site/
├── site.edn          # Site configuration
├── templates/        # Hiccup templates
│   ├── base.edn     # HTML wrapper
│   └── home.edn     # Page templates
├── content/          # Content files
│   └── en/          # English content
│       ├── home.edn # Homepage content
│       └── strings.edn # Translations
├── assets/          # Static assets
│   ├── css/        # Stylesheets
│   ├── js/         # JavaScript
│   └── images/     # Images
└── dist/           # Build output (generated)
```

## Template Directives

Eden templates use special directives for dynamic content:

- `:eden/body` - Insert page content
- `:eden/get` - Retrieve values from context
- `:eden/get-in` - Access nested data
- `:eden/each` - Iterate over collections
- `:eden/if` - Conditional rendering
- `:eden/link` - Smart page linking
- `:eden/render` - Render components
- `:eden/t` - Translations with interpolation
- `:eden/with` - Merge data into context
- `:eden/include` - Include other templates

## Configuration

Configure your site in `site.edn`:

```clojure
{:wrapper :base        ; HTML wrapper template
 :index :home          ; Homepage template
 :render-roots #{:home} ; Starting pages (dependencies auto-discovered)
 :url-strategy :nested ; :flat for page.html, :nested for page/index.html
 :lang {:en {:name "English" 
             :default true}}}
```

## MCP Integration (Coming Soon)

Eden will include Model Context Protocol (MCP) support, allowing AI assistants like Claude to help manage site content. This feature is currently under development.

## Documentation

- [Getting Started Guide](docs/getting-started.md) - Quick start and basic usage
- [Template Reference](docs/reference.md) - Complete directive and configuration reference
- [Internals Guide](docs/internals.md) - Architecture and implementation details

## License

Copyright © 2025

Distributed under the Eclipse Public License version 2.0.