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

---

agent47 connects to any LLM provider and gives it coding tools - file reading, editing, shell execution, grep, glob - so
it can work on your codebase autonomously. It ships as a single native binary with an interactive terminal UI.

## Install

**Install script** (macOS and Linux):

```bash
curl -fsSL https://raw.githubusercontent.com/rmonvfer/agent47/main/scripts/install.sh | bash
```

The installer supports macOS on Apple Silicon and Linux on ARM64 and x86-64. It verifies the release binary against
the published SHA-256 checksums before replacing an existing installation. To install a specific release or update to
the latest one:

```bash
curl -fsSL https://raw.githubusercontent.com/rmonvfer/agent47/main/scripts/install.sh | AGENT47_VERSION=v0.1.4 bash
curl -fsSL https://raw.githubusercontent.com/rmonvfer/agent47/main/scripts/install.sh | bash
```

agent47 checks for updates at most once every 24 hours when starting an interactive session. A verified update replaces
the installed binary atomically and restarts the command. To force a check immediately:

```bash
agent47 update
```

Automatic checks can be configured under `updates` in `~/.agent47/settings.json` or disabled for a single launch with
`AGENT47_NO_AUTO_UPDATE=1`.

**Build from source** (requires GraalVM Community 25.1.3):

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
agent47 -p "what files are in this project?"
```

Without `-p`/`--print`, a prompt argument becomes the first message in the interactive TUI. When no system console is
available, agent47 automatically uses print mode.

## Features

**Multi-provider.** agent47 works with Anthropic, OpenAI, Google, Ollama, and any OpenAI-compatible API. Switch between
models at runtime. Configure custom model definitions, API keys, and provider overrides in `~/.agent47/models.yml`.
Ollama models are discovered automatically.

**Coding tools.** The primary agent starts with the full core set: `read`, `bash`, `edit`, `write`, `grep`, `find`, `ls`,
`multiedit`, todo tools, and `batch`. Use `--tools` to replace that set. Subagent coordination adds `task`, `check_inbox`,
and `send_message` unless tools are disabled.

**Sub-agents and skills.** A top-level agent can spawn specialized sub-agents for exploration, planning, or isolated
tasks. Each sub-agent gets its own conversation and tool set. Skills are domain-specific knowledge files the model loads
on demand - drop a markdown file into `.agent47/skills/` and the model can pull it in when it needs that context.

**Interactive TUI.** The terminal UI renders Markdown, displays diffs, and supports theming. Sessions persist to disk as
JSONL so you can pick up where you left off. Slash commands provide quick access to model switching, session management,
and custom prompt templates.

**Hackable and extensible.** Everything is file-based and overridable. Agents, skills, and commands are markdown files
you can edit, version, and share. Runtime extensions are ordinary Kotlin `.kts` source files loaded directly by the
self-contained native executable; users do not install Java or distribute extension JARs. See
[docs/extensions.md](docs/extensions.md).

## Configuration

Global configuration lives in `~/.agent47/`. Project-level configuration lives in `.agent47/` at your project root.
Project settings override global settings.

| Path                           | Purpose                                               |
|--------------------------------|-------------------------------------------------------|
| `~/.agent47/models.yml`        | Custom model definitions and provider config          |
| `~/.agent47/settings.json`     | Global settings (default model, thinking level, etc.) |
| `~/.agent47/subagents.json`    | Global subagent runtime settings                      |
| `~/.agent47/agents/*.md`       | User-defined agent types                              |
| `~/.agent47/skills/*/SKILL.md` | User-defined skills                                   |
| `~/.agent47/commands/*.md`     | User-defined slash commands                           |
| `~/.agent47/extensions/*.kts`  | User-defined Kotlin runtime extensions                |
| `~/.agent47/packages.json`     | Installed global extension package registry           |
| `.agent47/settings.json`       | Project-level setting overrides                       |
| `.agent47/subagents.json`      | Project-level subagent runtime overrides              |
| `.agent47/agents/*.md`         | Project-level agent overrides                         |
| `.agent47/skills/*/SKILL.md`   | Project-level skills                                  |
| `.agent47/commands/*.md`       | Project-level slash commands                          |
| `.agent47/extensions/*.kts`    | Project-level Kotlin runtime extensions               |
| `.agent47/packages.json`       | Installed project extension package registry          |

See [docs/configuration.md](docs/configuration.md) for full details.

## Documentation

- [Agents](docs/agents.md) - define custom agents with their own system prompts, tools, and spawning policies
- [Skills](docs/skills.md) - create domain-specific knowledge files that agents load on demand
- [Commands](docs/commands.md) - write reusable prompt templates invoked with `/command args`
- [Tools](docs/tools.md) - implement custom tools via the `AgentTool<T>` interface
- [Extensions](docs/extensions.md) - programmatic hooks for intercepting and modifying agent behavior
- [Configuration](docs/configuration.md) - settings, model configuration, authentication, and discovery hierarchy
- [CLI and TUI](docs/cli.md) - command-line modes, options, slash commands, and shortcuts
- [Architecture](docs/architecture.md) - module boundaries and the request-to-tool runtime flow
- [Instructions](docs/instructions.md) - project, global, compatibility, and configured instruction discovery

## Why Kotlin?

agent47 uses Kotlin across its AI types, provider integrations, agent loop, coding tools, and terminal UI.

Kotlin on the JVM gives you real concurrency via coroutines backed by a thread pool, not a single-threaded event loop.
Coroutines let independent subagents run concurrently while provider responses and tool events stream through the same
typed event model.

The type system catches entire categories of bugs at compile time through sealed hierarchies, non-nullable types by
default, and exhaustive `when` expressions. GraalVM native image compiles the application to a single binary with fast
startup, combining Kotlin's static types with straightforward deployment.

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
