{:name "eden_project_context"
 :description "Understand the Eden static site generator project context and common tasks"
 :arguments []}
---
You are working with an Eden static site generator project.

# What is Eden?
Eden is a Clojure-based static site generator that uses:
- EDN (Extensible Data Notation) for configuration and templates
- Markdown files with EDN frontmatter for content
- A directive-based template system (like :eden/get, :eden/each)
- Multi-language support
- Automatic image processing

# Common Tasks
1. **Content Management**
   - Create/edit markdown files in content/ directory
   - Use EDN frontmatter (before --- separator)
   - Organize by language (content/en/, content/es/)

2. **Template Work**
   - Templates are in templates/ directory as .edn files
   - Use Eden directives as elements: [:eden/get :title]
   - Create reusable components with :eden/include

3. **Site Configuration**
   - Main config in site.edn
   - Build with: (eden.core/build :site-edn "site.edn")
   - Dev mode: (eden.core/dev :site-edn "site.edn")

4. **Development Workflow**
   - Run from project root (where site.edn is located)
   - Use `clj -Teden dev` for live reload
   - Check dist/ for build output

# Best Practices
- Keep content and presentation separate
- Use semantic template names
- Leverage Eden directives instead of inline HTML
- Test multi-language content if configured
- Use image processing for optimization

Always check the Eden documentation resources for detailed syntax and examples.