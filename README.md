# agent47

A lightweight, hackable agentic coding harness built with Kotlin.

agent47 connects to any LLM provider (Anthropic, OpenAI, Google, Ollama, and others) and gives the model a set of coding tools, file reading, editing, writing, shell execution, grep, find, so it can work on your codebase autonomously. It runs as a native binary or on the JVM, with a full interactive terminal UI or a non-interactive print mode for scripting.

## What it can do

agent47 ships with core tools that let the model read files, make precise edits, write files, run shell commands, search with grep, find files by pattern, and list directories. The model picks the right tool for the job and works through multi-step tasks on its own.

Sub-agents let the model delegate work. A top-level agent can spawn specialized sub-agents for exploration, planning, or executing isolated tasks. Each sub-agent gets its own conversation history and tool set but shares auth, models, and settings with the parent. Agent definitions are markdown files with YAML frontmatter, drop one into `.agent47/agents/` to define your own.

Skills are domain-specific knowledge files the model can load on demand. Place a `SKILL.md` in `.agent47/skills/your-skill/` and the model can read it via `skill://your-skill` when it needs that context. Skills appear in the system prompt so the model knows what's available without loading everything upfront.

Slash commands are file-based prompt templates. Place a markdown file in `.agent47/commands/` and invoke it with `/commandname args`. Arguments are substituted into the template using `$1`, `$2`, and `$@` placeholders. Built-in commands handle model switching, thinking level, sessions, and settings.

Model management supports multiple providers simultaneously. Configure custom models, API keys, and provider overrides in `~/.agent47/models.yml`. Ollama models are discovered automatically. Models can be switched on the fly in the interactive UI.

Sessions persist conversation history to disk as JSONL files. You can continue previous sessions, and the model/thinking level state is restored along with the messages.

## Goals

**Hackable.** Everything is file-based and overridable. Agents, skills, and commands are markdown files you can edit, version, and share. Project-level overrides take precedence over user-level ones, which take precedence over bundled defaults.

**Lightweight.** agent47 is a single binary (via GraalVM native image) or a JVM application. No Electron, no browser, no daemon. It starts fast and stays out of your way.

**Multi-provider.** Swap between Anthropic, OpenAI, Google, Ollama, or any OpenAI-compatible API. The model abstraction is uniform, tools, thinking, and streaming work the same across providers.

**Composable.** Sub-agents can spawn sub-agents. Skills compose with tools. Commands compose with the agent. The pieces are simple and they combine.

## Non-goals

**IDE replacement.** agent47 is a terminal tool. It doesn't provide syntax highlighting, LSP integration, or a file tree. Use it alongside your editor.

**Framework or platform.** agent47 is an end-user tool, not a library for building other agents. The internal APIs exist to serve the harness, not as a public SDK.

**Maximal feature surface.** Features that add complexity without clear value don't ship. The codebase should stay small enough that one person can understand all of it.

## Quick start

```
./gradlew :agent47-app:run --args="'what files are in this project?'"
```

For interactive mode, run without arguments:

```
./gradlew :agent47-app:run
```

Build a native binary:

```
./gradlew :agent47-app:nativeCompile
```

Set at least one API key in your environment: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, or `GEMINI_API_KEY`.

## Extending agent47

agent47 is designed to be extended without modifying source code. See the guides in [`docs/`](docs/) for details:

- [Agents](docs/agents.md) - define custom agents with their own system prompts, tools, and spawning policies
- [Skills](docs/skills.md) - create domain-specific knowledge files that agents load on demand
- [Commands](docs/commands.md) - write reusable prompt templates invoked with `/command args`
- [Tools](docs/tools.md) - implement custom tools via the `AgentTool<T>` interface
- [Extensions](docs/extensions.md) - programmatic hooks for intercepting and modifying agent behavior
- [Configuration](docs/configuration.md) - settings, model configuration, authentication, and discovery hierarchy

## Configuration

Global config lives in `~/.agent47/`. Project-level config lives in `.agent47/` at your project root. Project settings override global settings.

| Path | Purpose |
|------|---------|
| `~/.agent47/models.yml` | Custom model definitions and provider config |
| `~/.agent47/settings.json` | Global settings (default model, thinking level, etc.) |
| `~/.agent47/agents/*.md` | User-defined agent types |
| `~/.agent47/skills/*/SKILL.md` | User-defined skills |
| `~/.agent47/commands/*.md` | User-defined slash commands |
| `.agent47/settings.json` | Project-level setting overrides |
| `.agent47/agents/*.md` | Project-level agent overrides |
| `.agent47/skills/*/SKILL.md` | Project-level skills |
| `.agent47/commands/*.md` | Project-level slash commands |

## Module structure

The project is split into Gradle modules organized in layers, where each layer depends only on the ones below it.

**agent47-ai-types** contains all foundational data types: messages, content blocks, models, events, streaming primitives. It has no internal dependencies and no runtime behavior, so modules that only need to describe or display AI data can depend on it without pulling in HTTP clients or provider logic.

**agent47-ai-core** is the AI runtime. It owns the `ApiRegistry` (provider plugin system), `HttpTransport`, tool validation, token overflow handling, and message transforms. Providers register themselves here but are implemented elsewhere.

**agent47-ai-providers** implements all LLM provider integrations: Anthropic, OpenAI (and OpenAI-compatible APIs), and Google. Each provider lives in its own package but shares a single module since they have identical dependencies and are always deployed together.

**agent47-agent-core** is the agentic execution engine. It contains the `Agent` state machine, the multi-turn `AgentLoop` (tool calling, steering, follow-ups), and the `AgentTool<T>` interface. It knows how to orchestrate LLM calls and tool execution but has no opinion about what tools exist.

**agent47-coding-core** is the domain layer for coding. This is where tool implementations live (`BashTool`, `EditTool`, `ReadTool`, `GrepTool`, etc.), along with sub-agent execution, model resolution, auth, slash commands, skills, settings, sessions, and compaction. It is the thickest module and the one most likely to grow.

**agent47-ext-core** provides extension utilities that bridge agent-core and coding-core.

**agent47-tui** is the terminal UI, built with Mosaic (Compose for terminal). It handles chat rendering, the input editor, markdown/diff rendering, and theming.

**agent47-app** is the CLI entry point. It wires providers, models, and settings together and launches either the interactive TUI or non-interactive print mode.

**agent47-model-generator** is a standalone build-time tool that generates Kotlin model definitions from JSON schemas.

**agent47-test-fixtures** provides shared test utilities and mock objects.

## License

MIT - see [LICENSE](LICENSE).
