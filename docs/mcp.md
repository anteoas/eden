# Eden MCP Server

## Overview

Eden's MCP (Model Context Protocol) server enables AI assistants to interact with Eden projects through a structured interface. The server uses [clojure-mcp](https://github.com/bhauman/clojure-mcp) to provide full access to Eden's build system and Clojure REPL.

## Architecture

The MCP server runs as a single process with an embedded nREPL server:

```
Claude Desktop → stdio → MCP Server → embedded nREPL → Eden
```

- **Single Process**: Both MCP server and Eden run in the same JVM
- **Embedded nREPL**: Starts on a random port for internal communication
- **Eden-Aware Tools**: File operations, Clojure evaluation, and bash commands
- **Eden Resources**: Built-in documentation for Eden's template system

## Usage

### For Claude Desktop

Add to your Claude Desktop MCP configuration:

```json
{
  "mcpServers": {
    "eden": {
      "command": "clj",
      "args": ["-M:mcp"]
    }
  }
}
```

Or with a custom site.edn location:

```json
{
  "mcpServers": {
    "eden": {
      "command": "clj",
      "args": ["-Teden", "mcp-stdio", ":site-edn", "\"site/site.edn\""]
    }
  }
}
```

### Command Line

```bash
# Start MCP server (uses site.edn in current directory)
clj -M:mcp

# Or with custom site.edn location
clj -Teden mcp-stdio :site-edn '"site/site.edn"'
```

## Available Tools

The MCP server provides essential tools for working with Eden projects:

- `unified_read_file` - Smart file reading with Clojure awareness
- `file_edit` - Edit files with precise text replacement
- `file_write` - Write complete files
- `LS` - List directory contents
- `grep` - Search file contents
- `glob_files` - Find files by pattern
- `clojure_eval` - Evaluate Clojure code (can call Eden functions)
- `bash` - Execute shell commands
- `think` - Log thought process
- `scratch_pad` - Persistent storage between tool calls

## Eden-Specific Features

### Resources

The server provides Eden documentation as MCP resources:

- **Eden Template Directives** - Complete reference for Eden's template system
- **Eden Content Structure** - How to structure content files and frontmatter
- **Eden Site Configuration** - Guide to configuring site.edn

### Prompts

Eden-specific prompts help with common tasks:

- `eden_project_context` - Understand the Eden project structure
- `eden_create_page` - Guide for creating new pages
- `eden_debug_build` - Help debug build issues

### Direct Eden Access

Through the `clojure_eval` tool, you can directly call Eden functions:

```clojure
;; Build the site
(eden.core/build :site-edn "site.edn" :mode :prod)

;; Start dev server
(eden.core/dev :site-edn "site.edn")

;; Clean build artifacts
(eden.core/clean)
```

## Configuration

The MCP server uses the site.edn location to determine:
- Project root directory (parent of site.edn)
- Working directory for all file operations
- Context for build commands

## How It Works

1. The server verifies site.edn exists in the project root
2. Starts an embedded nREPL server on a random port
3. Configures clojure-mcp to connect to this nREPL
4. Registers Eden-specific tools, prompts, and resources
5. Runs everything in a single process

## Benefits

- **Single Process**: Simpler architecture, no separate servers needed
- **Full REPL Access**: Evaluate any Clojure code including Eden functions
- **Project Context**: Automatically sets working directory based on site.edn
- **Eden Integration**: Access to Eden-specific documentation and helpers