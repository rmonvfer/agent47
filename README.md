<p align="center">
  <!-- <img src="docs/assets/logo.png" width="120" alt="agent47 logo"> -->
  <h1 align="center">agent47</h1>
  <p align="center">An open-source, hackable agentic coding assistant.</p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License"></a>
  <a href="https://github.com/rmonvfer/agent47/releases/latest"><img src="https://img.shields.io/github/v/release/rmonvfer/agent47" alt="Latest Release"></a>
  <a href="https://github.com/rmonvfer/agent47/actions/workflows/ci.yml"><img src="https://github.com/rmonvfer/agent47/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
</p>

<p align="center">
  <!-- Replace with your terminal recording: drop a GIF at docs/assets/demo.gif -->
  <img src="docs/assets/demo.gif" width="720" alt="agent47 terminal demo">
</p>

---

agent47 connects to any LLM provider and gives it coding tools - file reading, editing, shell execution, grep, glob - so
it can work on your codebase autonomously. It ships as a single native binary with an interactive terminal UI.

## Install

**Install script** (macOS and Linux):

```bash
curl -fsSL https://agent47.co/install.sh | bash
```

**Homebrew**:

```bash
brew install rmonvfer/tap/agent47
```

**Build from source** (requires JDK 21):

```bash
git clone https://github.com/rmonvfer/agent47.git
cd agent47
./gradlew :agent47-app:nativeCompile
# Binary at agent47-app/build/native/nativeCompile/agent47
```

## Quick Start

Set at least one API key:

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
# or OPENAI_API_KEY, GEMINI_API_KEY, etc.
```

Run interactively:

```bash
agent47
```

Run a one-shot command:

```bash
agent47 "what files are in this project?"
```

## Features

**Multi-provider.** agent47 works with Anthropic, OpenAI, Google, Ollama, and any OpenAI-compatible API. Switch between
models at runtime. Configure custom model definitions, API keys, and provider overrides in `~/.agent47/models.yml`.
Ollama models are discovered automatically.

**Coding tools.** The model gets a set of tools for working with code: read files, make precise edits, write files, run
shell commands, search with grep, find files by glob pattern, and list directories. It picks the right tool for each
step and works through multi-step tasks on its own.

**Sub-agents and skills.** A top-level agent can spawn specialized sub-agents for exploration, planning, or isolated
tasks. Each sub-agent gets its own conversation and tool set. Skills are domain-specific knowledge files the model loads
on demand - drop a markdown file into `.agent47/skills/` and the model can pull it in when it needs that context.

**Interactive TUI.** The terminal UI renders Markdown, displays diffs, and supports theming. Sessions persist to disk as
JSONL so you can pick up where you left off. Slash commands provide quick access to model switching, session management,
and custom prompt templates.

**Hackable and extensible.** Everything is file-based and overridable. Agents, skills, and commands are markdown files
you can edit, version, and share. Project-level configuration takes precedence over user-level, which takes precedence
over bundled defaults. The codebase is small enough that one person can understand all of it.

## Configuration

Global configuration lives in `~/.agent47/`. Project-level configuration lives in `.agent47/` at your project root.
Project settings override global settings.

| Path                           | Purpose                                               |
|--------------------------------|-------------------------------------------------------|
| `~/.agent47/models.yml`        | Custom model definitions and provider config          |
| `~/.agent47/settings.json`     | Global settings (default model, thinking level, etc.) |
| `~/.agent47/agents/*.md`       | User-defined agent types                              |
| `~/.agent47/skills/*/SKILL.md` | User-defined skills                                   |
| `~/.agent47/commands/*.md`     | User-defined slash commands                           |
| `.agent47/settings.json`       | Project-level setting overrides                       |
| `.agent47/agents/*.md`         | Project-level agent overrides                         |
| `.agent47/skills/*/SKILL.md`   | Project-level skills                                  |
| `.agent47/commands/*.md`       | Project-level slash commands                          |

See [docs/configuration.md](docs/configuration.md) for full details.

## Documentation

- [Agents](docs/agents.md) - define custom agents with their own system prompts, tools, and spawning policies
- [Skills](docs/skills.md) - create domain-specific knowledge files that agents load on demand
- [Commands](docs/commands.md) - write reusable prompt templates invoked with `/command args`
- [Tools](docs/tools.md) - implement custom tools via the `AgentTool<T>` interface
- [Extensions](docs/extensions.md) - programmatic hooks for intercepting and modifying agent behavior
- [Configuration](docs/configuration.md) - settings, model configuration, authentication, and discovery hierarchy

## Why Kotlin?

Every agentic coding tool in this space is written in TypeScript or Go. agent47 is the first built end-to-end in Kotlin,
and that's a deliberate choice. 

Kotlin on the JVM gives you real concurrency via coroutines backed by a thread pool, not a single-threaded event loop.
When an agent spawns three subagents that each run shell commands and stream LLM responses, those actually run in
parallel. TypeScript's `async/await` looks similar but fundamentally serializes CPU-bound work on one thread. For an
agentic harness that orchestrates tools, subagents, and streaming simultaneously, this matters.

The type system catches entire categories of bugs at compile time that TypeScript's structural typing lets slip through:
sealed hierarchies for message types, non-nullable types by default, and exhaustive `when` expressions over tool
results. The codebase has zero `Any` casts and zero runtime type checks. GraalVM native image compiles it all to a
single binary with fast startup and no JVM overhead, so you get the development-time safety of a statically typed
language with the deployment simplicity of a Go binary.

## How Is This Different?

**vs Claude Code.** Claude Code is a proprietary tool locked to Anthropic's models. agent47 is open-source, works with
any provider, and is designed to be extended through file-based configuration. You can define custom agents, skills, and
commands without modifying source code.

**vs Pi.** agent47 is heavily inspired by Pi and in fact, most of our agentic harness is based on its design (although
we've made some changes where it makes sense to support the features we think are important)

**vs OpenCode.** Similar space, different trade-offs. agent47 is built in Kotlin with a native binary option, has a
richer subagent and skills system, and uses a layered module architecture.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding conventions, and the PR process.

## Security

See [SECURITY.md](SECURITY.md) for the threat model and vulnerability reporting instructions.

## License

MIT - see [LICENSE](LICENSE).
