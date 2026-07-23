# Kotlin extensions

agent47 extensions are Kotlin `.kts` source files. The standalone executable contains the Kotlin compiler and compiles
extensions in process, so users do not install Java, Kotlin, JARs, or a companion runtime.

Extensions are trusted application code. They run with the same filesystem, process, and network access as agent47.
Read an extension before installing it.

## Loading extensions

Global extensions live in `~/.agent47/extensions/` and project extensions in `.agent47/extensions/`. A directory may
contain individual `.kts` files or use `index.kts` as its entry point. Explicit paths load first, followed by project,
package, and global extensions.

```bash
agent47 -e ./my-extension.kts
agent47 --no-extensions -e ./my-extension.kts --list-extensions
```

`-e`/`--extension` may be repeated. `--no-extensions` disables automatic project, package, and global discovery but
keeps explicit paths. `/reload` recompiles the selected files and replaces the active extension set only when every
script compiles successfully.

## Script API

Top-level calls register behavior for the current file. The canonical file path is the extension identity.

```kotlin
beforeAgent { messages -> messages }

transformContext { context ->
    context.copy(systemPrompt = context.systemPrompt + "\nKeep answers concise.")
}

registerCommand("hello", "Display a greeting") { args, context ->
    context.notify("Hello ${args.ifBlank { "world" }}")
}
```

`beforeAgent` may replace the message list before a run. `afterAgent` observes the final list. `transformContext` may
replace the model request context. Handlers run in extension load order; one failing lifecycle handler is reported
without preventing later extensions from running.

`on(eventType)` observes agent events such as `agent_start`, `agent_end`, `turn_start`, `turn_end`, `message_start`,
`message_update`, `message_end`, `tool_execution_start`, `tool_execution_update`, and `tool_execution_end`. Use `"*"`
to observe every agent event.

```kotlin
on("tool_execution_end") { event, context ->
    context.ui.setStatus("last-tool", event.type)
}
```

`onInput` receives interactive and extension-injected prompts. It may continue, transform the text, or handle it
without sending it to the model.

```kotlin
import co.agentmode.agent47.ext.core.InputHookResult

onInput { event, _ ->
    if (event.text == "ping") InputHookResult.Transform("Reply with pong.")
    else InputHookResult.Continue
}
```

Built-in and extension slash commands are control-plane actions and do not pass through `onInput`.

## Tools

`registerTool` accepts an `AgentTool<T>`. `wrapTools` wraps the complete root-session tool set. `onToolCall` can replace
tool input or block execution, and `onToolResult` can replace returned content or details.

```kotlin
import co.agentmode.agent47.ext.core.ToolCallHookResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

onToolCall { event, _ ->
    if (event.toolName == "dangerous") {
        ToolCallHookResult(block = true, reason = "Blocked by project policy")
    } else {
        ToolCallHookResult(input = buildJsonObject {
            event.input.forEach { (key, value) -> put(key, value) }
            put("extensionObserved", true)
        })
    }
}
```

The live context exposes `availableTools`, `activeToolNames`, `registerTool`, `unregisterTool`, and `setActiveTools`.
Changing the active set also rebuilds the model-visible tool section of the system prompt.

## Context and sessions

Every context-aware hook receives `ExtensionContext`. It exposes the working directory, runtime mode, current and
available models, thinking level, messages, system prompt, tools, extension flags, session metadata, and a TUI bridge.

The context can set the model or thinking level, send a steering or follow-up message, wait for idle, abort the current
run, execute a child process, append custom session data, label entries, and change the session name. Session controls
create, resume, or fork sessions.

```kotlin
import co.agentmode.agent47.ext.core.ExtensionMessageDelivery

registerCommand("review", "Ask for a focused review") { _, context ->
    context.sendUserMessage(
        "Review the current changes for correctness.",
        ExtensionMessageDelivery.FOLLOW_UP,
    )
}
```

`onSessionStart` and `onSessionShutdown` observe startup, reload, new, resume, fork, and quit transitions. Shutdown runs
against the old session context; start runs against the selected session context.

`beforeCompaction` may cancel compaction or supply a `CompactionResult`. `afterCompaction` receives the result, reason,
and whether an extension supplied it.

## UI, shortcuts, and rendering

`context.ui` provides notifications, selection and confirmation dialogs, single-line input, a multiline editor, status
items, widgets, terminal title, and editor text access. Dialog methods return `null` or `false` in print mode.

`registerShortcut` adds a TUI key binding such as `"ctrl+g"`. Built-in shortcuts keep precedence.
`registerToolRenderer` and `registerMessageRenderer` return terminal lines for custom rendering. Returning `null` or
throwing falls back to agent47's standard renderer.

```kotlin
import co.agentmode.agent47.ext.core.ToolRenderer

registerShortcut("alt+r", "Run review") { context ->
    context.ui.setEditorText("Review the current changes")
}

registerToolRenderer("echo", ToolRenderer { data, _ ->
    listOf("echo: ${data.output}")
})
```

## Flags and providers

Flags are declared before they are read. Values are supplied with `--extension-flag name` for a Boolean or
`--extension-flag name=value` for a string. Unknown, duplicate, malformed, and invalid Boolean values fail startup.

```kotlin
registerFlag("verbose", "Show extension diagnostics")

if (getFlag("verbose") == "true") {
    onSessionStart { _, context -> context.notify("Extension diagnostics enabled") }
}
```

`registerProvider` installs an `ApiProvider` and its model definitions for the lifetime of the loaded extension set.
Providers may be keyless for local services or declare an API key. Reload unregisters providers and models belonging
to files that are no longer active.

## Packages

An extension package is a local path or Git repository containing extensions, skills, prompt commands, themes, or any
combination of those resources.

```bash
agent47 extension install ./my-package
agent47 extension install owner/repository
agent47 extension install https://github.com/owner/repository.git
agent47 extension install owner/repository@v1.0.0
agent47 extension list
agent47 extension update
agent47 extension remove owner/repository
```

Commands use global scope by default. Add `-l` or `--local` to install, update, remove, or list the project-scoped
registry under `.agent47/`. Local filesystem packages remain in place when removed. Git packages are cloned into the
agent47 package directory; unpinned packages update with a fast-forward pull, while tagged or branch-ref packages stay
pinned.

Packages may declare resources in `agent47.json`:

```json
{
  "extensions": ["extensions"],
  "skills": ["skills"],
  "prompts": ["prompts"],
  "themes": ["themes"]
}
```

Entries are files or directories relative to the package root and cannot escape it. Directory discovery is recursive
for `.kts` extensions and non-recursive for JSON themes. Without a manifest, the four conventional directories above
are used. Package themes use the OpenCode JSON theme shape and appear in the TUI theme picker.

See [`examples/extensions`](../examples/extensions) for complete command and tool examples.
