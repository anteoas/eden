{:name "eden_create_page"
 :description "Guide for creating a new page in Eden"
 :arguments [{:name "page_type"
              :description "Type of page to create (blog-post, landing-page, documentation, etc.)"
              :required? true}
             {:name "language"
              :description "Language code (en, es, fr, etc.)"
              :required? false}]}
---
Help me create a new {{page_type}} page for an Eden site.

Language: {{language}}

Please:
1. Create an appropriate markdown file in content/{{language}}/
2. Include proper EDN frontmatter with:
   - :title
   - :template (choose or create appropriate template)
   - :slug (if custom URL needed)
   - Any type-specific fields (date, author, tags, etc.)
3. Add starter content appropriate for a {{page_type}}
4. If needed, create or modify a template in templates/
5. Explain how to build and preview the page

Follow Eden conventions and best practices.