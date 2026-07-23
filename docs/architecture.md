# Architecture

agent47 is a Gradle multi-module Kotlin application. It uses Kotlin 2.2.20, a JDK 25 toolchain, JVM 21 bytecode, and
GraalVM Native Image for the distributable binary. The modules separate transport-neutral AI types, provider
protocols, the agent loop, coding behavior, UI state, and application assembly.

## Modules

`agent47-ai-types` defines messages, content blocks, events, model metadata, usage, thinking blocks, and tool
definitions. `agent47-ai-core` adds the provider registry, runtime dispatch, HTTP transport, request transforms, tool
validation, and overflow/usage utilities. `agent47-ai-providers` implements Anthropic, Google, OpenAI-compatible,
Responses, and related provider protocols.

`agent47-agent-core` owns the stateful `Agent`, streaming loop, events, tool execution, retries, and public
`AgentTool<T>` contract. It depends on the AI runtime but has no coding-product or terminal concerns.

`agent47-coding-core` builds the product layer: file and shell tools, tool schemas, model and authentication registries,
settings, instruction/skill/command discovery, sessions, compaction, agent definitions, background execution,
scheduling, worktree isolation, transcripts, and memory. `agent47-ext-core` defines extension lifecycle and wrapping
hooks around agent behavior.

`agent47-ui-core` contains reusable Compose state for chat history, the editor, overlays, status and task bars, fuzzy
matching, and tool presentation. `agent47-tui` renders that state with Mosaic and handles terminal input, Markdown,
diffs, themes, slash commands, and overlays. `agent47-app` is the composition root: it parses Clikt options, loads
configuration, resolves credentials and models, builds tools and prompts, restores sessions, and selects print mode or
the TUI.

`agent47-test-fixtures` supplies shared test helpers. `agent47-model-generator` is a separate application that generates
the bundled model catalog and provider prompt data. `build-logic` contains the shared Kotlin library and application
conventions.

## Runtime flow

At startup, `Agent47Command` builds paths from the working directory and `AGENT47_DIR`, loads global and project
settings, loads subagent settings, initializes authentication and the model registry, and registers provider protocol
implementations in an application-owned `ApiRegistry`. Model configuration and bounded Ollama discovery are resolved
before the initial model is selected. The corresponding `AiRuntime` is injected into root, nested, and scheduled agents.

The application then discovers agents, skills, slash commands, and instruction files. It instantiates the selected core
tools, adds background-agent coordination tools, constructs the system prompt, and creates an `AgentClient`. Print mode
sends one message and streams the final run to standard output. Interactive mode hands the same client and registries to
the TUI, which can mutate session-level model, thinking, settings, and background-agent state.

For each turn, the agent loop sends the current messages, model settings, and tool definitions through `AiRuntime` to
the provider selected by the model. Provider implementations translate the neutral context to the remote protocol and
stream events back. The loop accumulates assistant content, executes requested tools, appends tool-result messages, and
continues until the model stops, the run is aborted, or an error ends it. Session managers persist completed messages;
context compaction summarizes older history when configured thresholds are reached.

Subagent tasks resolve an agent definition and model, build an independent prompt and tool set, and launch through the
background registry. They report progress asynchronously and finish through `submit_result`, with optional JTD
validation. The orchestrator can collect results with `check_inbox`, exchange messages with `send_message`, or inspect
the same state from the TUI.

## Building and API documentation

Use `./gradlew build` for the complete build, `./gradlew test` for tests, and a module-qualified task for focused work.
`./gradlew :agent47-app:nativeCompile` produces the native executable. Dokka 2 API documentation is generated with
`./gradlew dokkaGeneratePublicationHtml`; the legacy `dokkaHtml` task is disabled.
