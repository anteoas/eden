{:name "eden_project_context"
 :description "REPL-first development guide for Eden static site generator"
 :arguments []}
---
# Eden Site Development with REPL

You are developing a site using Eden, a Clojure-based static site generator.

## Quick Start

First, load the Eden namespaces:
```clojure
(require '[eden.core :as eden])
(require '[eden.loader :as loader])
(require '[babashka.fs :as fs])
(require '[clojure.edn :as edn])
```

Then you can use these commands:
```clojure
(eden/build)    ; Build site (dev mode by default)
(eden/serve)    ; Serve dist/ with browser auto-reload
(eden/clean)    ; Clean build artifacts
```

As an AI agent, use `(eden/build)` to build the site, then `(eden/serve)` to preview it.

## Core Principles

1. **REPL-First Development**
   - Use `clojure_eval` for all Eden operations (build, dev server, etc.)
   - Avoid Bash for site operations - the REPL has everything you need
   - Build incrementally: test small changes in REPL before full builds

2. **Eden Workflow**
   ```clojure
   ;; For AI agents (you):
   (eden/build)              ; Build site (dev mode by default)
   (eden/serve)              ; Serve and preview

   ;; For humans developing:
   (eden/watch)              ; Watch + rebuild + serve

   ;; Production build:
   (eden/build :mode :prod)  ; Optimized build

   ;; Clean build artifacts:
   (eden/clean)
   ```

3. **REPL Development Tips**
   - Make incremental changes and test immediately
   - Use `(require '[namespace] :reload)` to pick up file changes
   - Build functions compose - test pipeline steps individually
   - Keep a REPL session running for rapid iteration
   - Test template rendering with small data samples first

## Project Structure
- `site.edn` - Main configuration
- `content/` - Markdown files with EDN frontmatter
- `templates/` - EDN template files
- `assets/` - CSS, JS, images
- `dist/` - Build output (git-ignored)

## Common Patterns

### Testing Template Changes
```clojure
;; Load and test a template with sample data
(def site-data (loader/load-site-data "site.edn" "dist"))
;; Inspect and modify site-data as needed
```

### Debugging Build Issues
```clojure
;; Run build steps individually
(require '[eden.pipeline :as pipeline])
(-> {:site-edn "site.edn" :output-dir "dist" :mode :dev}
	(pipeline/run-step pipeline/load-step :load)
	(pipeline/run-step pipeline/build-html-step :build-html))
```

### Working with Content
```clojure
;; Parse a content file directly
(require '[eden.content :as content])
(content/parse-file "content/en/home.md")

;; List all content files
(fs/glob "content" "**/*.{md,edn}")
```

## Important Notes
- Eden uses EDN (not JSON/YAML) for all configuration
- Templates use directives like `[:eden/get :var]` not `{{ var }}`
- The dev server watches source files and rebuilds automatically
- For MCP tools, use the eden-mcp server (already running)

## Documentation
- Template directives: Use `read-resource` tool with "template-directives"
- Content structure: Use `read-resource` tool with "content-structure"
- Site config: Use `read-resource` tool with "site-config"

## What is Eden?
Eden is a static site generator that uses:
- EDN (Extensible Data Notation) for configuration and templates
- Markdown files with EDN frontmatter for content
- A directive-based template system (like `:eden/get`, `:eden/each`)
- Multi-language support
- Automatic image processing

Remember: The REPL is your primary interface. Build iteratively, test frequently.
