# Kotlin extensions

agent47 extensions are Kotlin `.kts` source files. The standalone executable contains the Kotlin compiler and compiles
extensions in process, so users do not install Java, Kotlin, JARs, or a companion runtime.

Extensions are trusted application code. They run with the same filesystem, process, and network access as agent47.
Read an extension before installing it.

## Loading extensions

Global extensions live in `~/.agent47/extensions/` and project extensions in `.agent47/extensions/`. A directory may
contain individual `.kts` files or use `index.kts` as its entry point. Explicit paths load first, followed by project,
project-installed repositories, global-installed repositories, project extensions, and global extensions.

```bash
agent47 -e ./my-extension.kts
agent47 --no-extensions -e ./my-extension.kts --list-extensions
```

`-e`/`--extension` may be repeated. `--no-extensions` disables loose extension discovery from
`.agent47/extensions/` and `~/.agent47/extensions/`; explicitly selected files and installed repositories remain
enabled. `/reload` recompiles the selected files and replaces the active extension set only when every script compiles
successfully.

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

## Extension repositories

An extension repository is an ordinary source repository containing Kotlin extensions, skills, prompt commands,
themes, or any combination of those resources. Installation consumes the repository directly: authors do not publish
a package or JAR, and users do not need Java or Gradle.

```bash
agent47 install git:github.com/owner/repository
agent47 install https://github.com/owner/repository.git
agent47 install git@github.com:owner/repository.git
agent47 install git:github.com/owner/repository@v1.0.0
agent47 install ./local-repository
agent47 install file:///absolute/path/to/repository.git

agent47 install git:github.com/owner/repository --local
agent47 list

agent47 update
agent47 update --extensions
agent47 update --all
agent47 update git:github.com/owner/repository
agent47 update --extension git:github.com/owner/repository

agent47 remove git:github.com/owner/repository
agent47 remove git:github.com/owner/repository --local
agent47 uninstall git:github.com/owner/repository
```

Install and remove use global scope by default. Add `-l` or `--local` to declare the source in
`.agent47/extensions.json`; that file can be committed, and agent47 clones a missing checkout when the project starts.
The project declaration wins when the same repository is installed globally. Local sources remain in place when
removed, while removing a managed Git source deletes its checkout. `remove` and `uninstall` are equivalent.

Plain `agent47 update` updates the agent47 executable. `--extensions` updates every unpinned repository, `--all`
updates the executable and repositories, and either a positional source or `--extension SOURCE` updates one installed
repository. A source with an `@ref` remains pinned. An unpinned checkout with local changes is not replaced. Updates
validate a fresh checkout before replacing the installed revision, and a failed clone or invalid manifest leaves the
working revision intact. Installing or updating a remote source requires `git` on `PATH`.

Global declarations are stored in `~/.agent47/extensions.json`. Project declarations are stored in
`.agent47/extensions.json`, with project-local filesystem sources made relative to that file:

```json
{
  "repositories": [
    "git:github.com/owner/repository@v1.0.0",
    "../local-repository"
  ]
}
```

Managed checkouts live under the corresponding `git/` directory beside the registry. The registry contains source
declarations only; it does not contain copied scripts, dependency metadata, or executable code.

Without a manifest, agent47 discovers the conventional repository layout:

```text
extensions/   Kotlin .kts entrypoints
skills/       skill directories containing SKILL.md
prompts/      slash-command prompt files
themes/       JSON themes
```

A repository may instead declare resource paths in `agent47.json`:

```json
{
  "extensions": ["extensions"],
  "skills": ["skills"],
  "prompts": ["prompts"],
  "themes": ["themes"]
}
```

Entries are files or directories relative to the repository root and cannot escape it. Directory discovery is recursive
for `.kts` extensions and non-recursive for JSON themes. An `index.kts` file is the entrypoint for its directory, so
sibling `.kts` files are not independently loaded. Without a manifest, the four conventional directories above are
used. A repository must resolve at least one supported resource. Repository themes use the OpenCode JSON theme shape
and appear in the TUI theme picker.

See [`examples/extension-repository`](../examples/extension-repository) for a complete repository containing two
extensions, a skill, a prompt command, a theme, a manifest, and an optional Gradle authoring check. Gradle is an
author-side convenience only; agent47 never invokes it when installing or loading a repository. The standalone
[`examples/extensions`](../examples/extensions) scripts provide smaller API examples.

## Script API reference

Each `.kts` entrypoint is evaluated once when it is loaded. Registration calls apply only to that entrypoint. The
complete top-level registration surface is:

```kotlin
beforeAgent { messages -> messages }
afterAgent { messages -> }
transformContext { context -> context }
wrapTools(wrapper)
registerTool(tool)
on("event_type") { event, context -> }
beforeCompaction { event, context -> CompactionHookResult() }
afterCompaction { event, context -> }
registerProvider(provider, models, apiKey = null, requiresAuth = true)
onToolCall { event, context -> ToolCallHookResult() }
onToolResult { event, context -> ToolResultHookResult() }
onInput { event, context -> InputHookResult.Continue }
onSessionStart { event, context -> }
onSessionShutdown { event, context -> }
registerShortcut("ctrl+k", "Description") { context -> }
registerToolRenderer("tool-name", renderer)
registerMessageRenderer("custom-type", renderer)
registerFlag("flag-name", "Description")
getFlag("flag-name")
registerCommand("command-name", "Description") { arguments, context -> }
```

`beforeAgent` and `transformContext` replace their input values. `afterAgent` observes the completed message list.
Registration order is execution order for lifecycle, event, input, and tool hooks. A failing lifecycle handler is
reported and later handlers still run. Tool-call hooks may replace input or block execution; tool-result hooks may
replace content or details. Input hooks may continue, transform, or consume input.

`ExtensionContext` exposes `cwd`, `mode`, `model`, `availableModels`, `thinkingLevel`, `messages`, `isIdle`,
`systemPrompt`, `availableTools`, `activeToolNames`, `sessionId`, `sessionEntries`, `sessionName`, `flags`, `ui`, and
`session`. It can notify the user, deliver a steering or follow-up message, register or unregister tools, choose active
tools, select a model or thinking level, append or send custom messages, name the session, label entries, wait for idle,
execute a child process, and abort the current run.

`ExtensionUi` provides notification, selection, confirmation, input, editor, status, widget, title, and editor-text
operations. Dialog methods return `null` or `false` when no interactive UI is available. `ExtensionSessionControl`
creates, switches, and forks sessions. Command handlers receive the smaller `ExtensionCommandContext`, which exposes
the working directory, UI availability, notifications, user-message delivery, and reload.

Types used by hooks and renderers live in `co.agentmode.agent47.ext.core`. Tool implementations use
`co.agentmode.agent47.agent.core.AgentTool` and the shared types from `co.agentmode.agent47.ai.types`. The compiling
[`api-surface.kts`](../examples/extensions/api-surface.kts) example is the compatibility reference for every
top-level registration call.
