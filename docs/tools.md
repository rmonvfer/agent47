# Tools

Tools are capabilities that agents can invoke during conversation. The model sees tool definitions (name, description,
parameter schema) in the LLM request and decides when to call them. The agent loop executes the tool and feeds the
result back to the model.

## Built-in tools

agent47 ships with a set of core tools for coding tasks.

`read` reads file contents, with optional line offset and limit. It also supports the `skill://` protocol for loading
skill files.

`bash` executes shell commands and returns stdout/stderr. Commands run in the agent's working directory.

`edit` makes precise string replacements in files. The model specifies the exact text to find and its replacement.

`write` creates or overwrites files with new content.

`multiedit` applies multiple edits across files in a single tool call.

`grep` searches file contents using ripgrep patterns, with support for glob filtering and context lines.

`find` finds files by glob pattern.

`ls` lists directory contents.

`batch` runs multiple tool calls in parallel within a single turn.

`todowrite` manages an in-memory task list that persists across turns.

`task` spawns subagents to handle delegated work (see [agents.md](agents.md) for spawning configuration).

## Implementing a custom tool

A custom tool implements the `AgentTool<T>` interface from `agent47-agent-core`.

```kotlin
class TimeTool : AgentTool<JsonObject> {
    override val definition = ToolDefinition(
        name = "current_time",
        description = "Returns the current date and time.",
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
        },
    )

    override val label = "Getting current time"

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<JsonObject>?,
    ): AgentToolResult<JsonObject> {
        val now = java.time.Instant.now().toString()
        return AgentToolResult(
            content = listOf(TextContent(text = now)),
            details = buildJsonObject { },
        )
    }
}
```

The `definition` is sent to the LLM as part of the tool list. The `parameters` field is a JSON Schema object describing
the tool's expected input. The `label` is a human-readable string shown in the UI while the tool is executing.

The `execute` method receives the parsed `parameters` from the model's tool call and returns content blocks (typically
`TextContent`) plus optional typed details. The `onUpdate` callback can be used to stream partial results to the UI
during long-running operations.

## Tool schema DSL

The `coding-core` module provides a DSL for building tool definitions without writing raw JSON Schema.

```kotlin
val definition = toolDefinition("search_docs", "Search project documentation.") {
    string("query") { required = true }
    int("limit")
    boolean("include_archived")
    enum("format", listOf("markdown", "plain"))
}
```

## Controlling tool availability

Agent definitions specify which tools are available via the `tools` frontmatter field. When creating tools
programmatically, `createCoreTools()` accepts an `enabled` list to filter which tools are instantiated.

```kotlin
val tools = createCoreTools(
    cwd = projectPath,
    enabled = listOf("read", "bash", "grep", "find"),
    skillReader = skillRegistry,
)
```
