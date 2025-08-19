# Eden MCP Server

Eden implements a Model Context Protocol (MCP) server that enables AI assistants to interact with the static site generator through a standardized protocol.

## Architecture

The MCP server is structured as follows:

- **`api.clj`** - Shared high-level operations used by all handlers
- **`simulator.clj`** - Validates content changes before committing them to disk
- **`server.clj`** - HTTP/WebSocket server implementation
- **`stdio.clj`** - STDIO transport for direct CLI integration
- **`protocol.clj`** - MCP protocol implementation
- **`tools.clj`** - Tool definitions and routing
- **`handlers/`** - Individual tool implementations

## Available Tools

The Eden MCP server provides the following tools for site management:

### Content Management

#### `list-content`
Lists all content files with their metadata.

**Parameters:**
- `language` (optional) - Filter by language code (e.g., "no", "en")
- `type` (optional) - Filter by content type (e.g., "news", "product")
- `template` (optional) - Filter by template name

**Returns:** Array of content items with path, title, template, and metadata

#### `read-content`
Reads the raw content of a specific file.

**Parameters:**
- `path` (required) - Path relative to content directory (e.g., "no/products/logifish.md")

**Returns:** Raw file content as text

#### `write-content`
Creates or updates a content file. **Important:** This tool first simulates the change to validate it will build correctly before writing to disk.

**Parameters:**
- `path` (required) - Path relative to content directory
- `frontmatter` (optional) - EDN frontmatter as an object
- `content` (required) - Markdown content as string

**Returns:** Success message with build status and any warnings, or validation error if simulation fails

#### `delete-content`
Deletes a content file and triggers a rebuild.

**Parameters:**
- `path` (required) - Path relative to content directory
- `confirm` (required) - Boolean confirmation to prevent accidental deletion

**Returns:** Success message with rebuild status

### Template Operations

#### `list-templates`
Lists all available templates in the site.

**Parameters:** None

**Returns:** Array of templates with their names and file paths

#### `preview-template`
Renders a template with sample data for preview purposes.

**Parameters:**
- `template` (required) - Template name
- `data` (optional) - Sample data object for rendering

**Returns:** Rendered HTML as text

### Build Operations

#### `build-site`
Triggers a full site build.

**Parameters:**
- `clean` (optional) - Boolean to clean output directory before building

**Returns:** Build status with number of pages built and any warnings

#### `get-build-status`
Gets the current build status without triggering a new build.

**Parameters:** None

**Returns:** Current build status

## Content Validation with Simulator

A key feature of the Eden MCP server is the **content simulator**. Before any write operation:

1. The simulator creates a temporary in-memory copy of the site
2. Applies the proposed change
3. Runs a full build to validate the change
4. Returns any errors or warnings
5. Only if validation passes does the actual file get written

This ensures that content changes won't break the site build and provides immediate feedback about issues like:
- Missing required frontmatter fields
- Invalid template references
- Broken internal links
- Malformed Hiccup structures

## Usage Examples

### With Claude Desktop

Add to your Claude Desktop configuration (`~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "eden": {
      "command": "clojure",
      "args": ["-M:mcp-stdio"],
      "cwd": "/path/to/your/eden/site"
    }
  }
}
```

### With HTTP Server

Start the MCP server:
```bash
clojure -M:mcp --port 3456 --secret your-secret-key
```

The server will create a `.mcp-port` file with connection details.

### With STDIO (Direct Integration)

```bash
clojure -M:mcp-stdio
```

This mode is used for direct integration with tools like Claude Desktop.

## Error Handling

All tools return consistent error responses:

- **Success**: Returns `{:content [{:type "text" :text "..."}]}` for MCP compatibility
- **Error**: Returns `{:error "error message"}`
- **Validation Failure**: Detailed error with the specific issue and the file is NOT written

## Development

To add a new tool:

1. Create the handler in `handlers/your-handler.clj`
2. Add high-level operations to `mcp/api.clj` if needed
3. Register the tool in `tools.clj` with its schema
4. Add the tool routing in the `call-tool` function

All handlers should:
- Validate inputs
- Use the MCP API for operations
- Return MCP-compliant responses
- Handle errors gracefully