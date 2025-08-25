{:name "eden_debug_build"
 :description "Help debug Eden build issues"
 :arguments []}
---
Help me debug an Eden build issue.

Please:
1. Check site.edn for configuration issues
2. Verify content files have valid EDN frontmatter
3. Check template syntax for Eden directive errors
4. Run (eden.core/build :site-edn "site.edn" :mode :dev) to see detailed errors
5. Look for common issues:
   - Malformed EDN in frontmatter
   - Missing template references
   - Invalid Eden directive syntax
   - File path issues
   - Image processing errors

Provide specific fixes for any issues found.