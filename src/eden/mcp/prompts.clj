(ns eden.mcp.prompts
  "System prompt generation for Eden MCP"
  (:require [clojure.string :as str]))

(defn generate-system-prompt
  "Generate comprehensive system prompt for AI assistants"
  [site-config]
  (str "# Eden Static Site Generator\n\n"

       "## Core Principles\n"
       "1. Never break the build - handle errors gracefully\n"
       "2. Clear, actionable warnings with fix instructions\n"
       "3. Defensive programming - assume data might be missing\n"
       "4. Automatic rebuilding - the MCP server rebuilds after every change\n\n"

       "## Eden Directives\n\n"

       "### :eden/get\n"
       "Retrieves values from context data.\n\n"
       "Examples:\n"
       "- `[:eden/get :title]` - Get title field\n"
       "- `[:eden/get :user :name]` - Get nested value\n"
       "- `[:eden/get :missing \"default\"]` - With default value\n\n"

       "### :eden/each\n"
       "Iterates over collections.\n\n"
       "Examples:\n"
       "- `[:eden/each :products [:div [:eden/get :name]]]`\n"
       "- `[:eden/each :news :limit 3 :order-by [:date :desc] ...]`\n"
       "- `[:eden/each :eden/all :where {:type \"news\"} ...]`\n\n"

       "### :eden/if\n"
       "Conditional rendering.\n\n"
       "Examples:\n"
       "- `[:eden/if :premium [:div \"Premium content\"]]`\n"
       "- `[:eden/if :user [:div \"Welcome\"] [:div \"Please login\"]]`\n\n"

       "### :eden/t\n"
       "Translation with interpolation.\n\n"
       "Examples:\n"
       "- `[:eden/t :nav/home]`\n"
       "- `[:eden/t :footer/copyright {:year 2024}]`\n\n"

       "### :eden/link\n"
       "Smart linking to pages.\n\n"
       "Examples:\n"
       "- `[:eden/link :about [:a {:href [:eden/get :link/href]} \"About\"]]`\n"
       "- `[:eden/link {:page-id :privacy :lang :en} ...]`\n\n"

       "### :eden/render\n"
       "Render content with template.\n\n"
       "Examples:\n"
       "- `[:eden/render :footer]`\n"
       "- `[:eden/render {:template :card :data :product}]`\n\n"

       "## Current Site Configuration\n\n"
       (when-let [langs (get-in site-config [:lang])]
         (str "Languages: "
              (str/join ", " (map (fn [[code cfg]]
                                    (str (name code) " (" (:name cfg)
                                         (when (:default cfg) " - default") ")"))
                                  langs))
              "\n"))
       (when-let [url-strategy (get-in site-config [:url-strategy])]
         (str "URL Strategy: " (name url-strategy) "\n"))
       "Templates: landing, page, products, news, team-member\n"
       "Content structure:\n"
       "- content/{lang}/{page}.md - Main pages\n"
       "- content/{lang}/products/*.md - Product pages\n"
       "- content/{lang}/news/*.md - News articles\n\n"

       "## Content Format\n\n"
       "EDN-style frontmatter:\n"
       "```clojure\n"
       "---eden\n"
       "{:template :page\n"
       " :title \"About Us\"\n"
       " :slug \"about\"\n"
       " :order 1}\n"
       "---\n\n"
       "# Markdown content here\n"
       "```\n\n"

       "## MCP Server Behavior\n\n"
       "1. **Auto-rebuild**: Writing content, uploading assets, or changing config "
       "automatically triggers a rebuild. You don't need to manually rebuild.\n\n"
       "2. **Build feedback**: Every write operation returns build results including "
       "warnings. Check these to ensure changes worked correctly.\n\n"
       "3. **Git workflow**: Use git-commit regularly to save work. "
       "Use create-pr to publish to production.\n\n"
       "4. **Error handling**: Operations that fail return clear error messages. "
       "The build will continue even with warnings.\n"))