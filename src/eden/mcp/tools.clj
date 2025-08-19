(ns eden.mcp.tools
  "MCP tool definitions for Eden"
  (:require [eden.mcp.handlers.content :as content]
            [eden.mcp.handlers.templates :as templates]
            [eden.mcp.handlers.build :as build]
            [eden.mcp.handlers.docs :as docs]))

(defn list-tools
  "List available MCP tools"
  [_config]
  {:tools
   [{:name "list-content"
     :description "List content files with metadata"
     :inputSchema
     {:type "object"
      :properties {:language {:type "string"}
                   :type {:type "string"
                          :description "Filter by content type"}
                   :template {:type "string"
                              :description "Filter by template"}}
      :required []}}

    {:name "read-content"
     :description "Read a content file with parsed frontmatter"
     :inputSchema
     {:type "object"
      :properties {:path {:type "string"
                          :description "Content file path relative to content/"}}
      :required ["path"]}}

    {:name "write-content"
     :description "Create or update a content file"
     :inputSchema
     {:type "object"
      :properties {:path {:type "string"
                          :description "Content file path relative to content/"}
                   :frontmatter {:type "string"
                                 :description "EDN frontmatter as string, e.g. '{:title \"My Post\" :template :blog}'"}
                   :content {:type "string"
                             :description "Markdown content"}}
      :required ["path" "content"]}}

    {:name "delete-content"
     :description "Delete a content file"
     :inputSchema
     {:type "object"
      :properties {:path {:type "string"}
                   :confirm {:type "boolean"
                             :description "Confirm deletion"}}
      :required ["path" "confirm"]}}

    {:name "list-templates"
     :description "List available templates with their requirements"
     :inputSchema
     {:type "object"
      :properties {}
      :required []}}

    {:name "preview-template"
     :description "Preview template with sample data"
     :inputSchema
     {:type "object"
      :properties {:template {:type "string"
                              :description "Template name (without .edn extension)"}
                   :data {:type "string"
                          :description "EDN data as string, e.g. '{:title \"Test\" :content/html \"<p>Hello</p>\"}'"}}
      :required ["template"]}}

    {:name "build-site"
     :description "Trigger a site build"
     :inputSchema
     {:type "object"
      :properties {:clean {:type "boolean"
                           :description "Clean before building"}}
      :required []}}

    {:name "get-build-status"
     :description "Get current build status and warnings"
     :inputSchema
     {:type "object"
      :properties {}
      :required []}}

    {:name "get-documentation"
     :description "Get documentation for Eden template system and content structure"
     :inputSchema
     {:type "object"
      :properties {:topic {:type "string"
                           :description "Topic: template-directives, content-schema, quickstart, site-config"}}
      :required []}}

    {:name "get-site-config"
     :description "Get current site configuration from site.edn"
     :inputSchema
     {:type "object"
      :properties {}
      :required []}}

    {:name "analyze-template"
     :description "Analyze a template to show used fields and directives"
     :inputSchema
     {:type "object"
      :properties {:template {:type "string"
                              :description "Template name (without .edn extension)"}}
      :required ["template"]}}

    {:name "list-directives"
     :description "List all available Eden template directives with brief descriptions"
     :inputSchema
     {:type "object"
      :properties {}
      :required []}}]})

(defn call-tool
  "Call a specific tool with arguments"
  [config tool-name arguments]
  (case tool-name
    "list-content" (content/list-content config arguments)
    "read-content" (content/read-content config arguments)
    "write-content" (content/write-content config arguments)
    "delete-content" (content/delete-content config arguments)
    "list-templates" (templates/list-templates config arguments)
    "preview-template" (templates/preview-template config arguments)
    "build-site" (build/build-site config arguments)
    "get-build-status" (build/get-build-status config arguments)
    "get-documentation" (docs/get-documentation config arguments)
    "get-site-config" (docs/get-site-config config arguments)
    "analyze-template" (docs/analyze-template config arguments)
    "list-directives" (docs/list-directives config arguments)
    {:error (str "Unknown tool: " tool-name)}))
