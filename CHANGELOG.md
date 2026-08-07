# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.1] - 2026-08-07

### Added

- Kotlin extension scripts register session-tree navigation hooks directly with `beforeTree` and
  `afterTree`.

### Changed

- The agents widget sits flush with the transcript, drops agents as they finish (and disappears
  entirely when only the main agent remains), and marks activity with filled dots instead of
  spinners; the focused agent view carries a per-agent colored identity header. Agent selection
  starts with the Left arrow, leaving mouse-wheel scrolling free.

### Fixed

- Pasting into the editor is lossless: fast pastes no longer drop characters, and line breaks inside
  a paste no longer submit the input mid-paste. A paste beyond 10 lines or 1000 characters collapses
  to a `[paste #N ...]` placeholder that deletes as one unit and expands back to the full text on
  submit. Ships a patched Mosaic dependency vendored under `third_party/mosaic/`.
- Edit tool diffs anchor their line numbers to the file's actual line positions.

## [0.3.0] - 2026-08-05

### Added

- The session is navigable as a tree: `/tree` moves the conversation to any earlier point (with filtering, search,
  and folding), optionally recording an LLM-generated summary of the branch being left; `/fork` starts a new session
  from an earlier user message, `/clone` duplicates the active branch, and `/resume` picks a session interactively.
  Extensions can observe and steer navigation through `session_before_tree` and `session_tree` events, and branch
  summarization is configurable under `branchSummary` in settings.
- Background agents have a runtime surface under the editor: an agent list with live activity, arrow-key selection
  into a focused per-agent transcript, `@agentname` messages that steer a specific agent, and direct steering of the
  focused agent by typing plainly while viewing it.
- The terminal window title follows the application, session name, and working directory.

### Changed

- Tool calls render with uniform spacing and per-tool collapsed previews; edit tools always show their diff, the
  bash tool renders as a tinted card, and Ctrl+E toggles all tool output.
- Model, thinking-level, and provider status changes render as spaced status lines, and status/error lines are
  transcript-only instead of assistant messages.
- The editor input starts flush at the left edge, and the fork dialog fits its list and shows message ages.

### Fixed

- Task cards for background agent launches show the launch summary instead of a fabricated completion count.
- Focused agent transcripts stay pinned to the bottom and surface the agent's in-flight tool activity.

## [0.2.2] - 2026-08-04

### Fixed

- Interactive sessions start without provider credentials. The session begins with no model selected and the
  terminal UI guides provider connection through `/provider` and model selection through `/model`; only print mode
  requires an API key up front.

## [0.2.1] - 2026-08-04

### Added

- Compiled extension bytecode is cached under `~/.agent47/cache/kotlin-extensions/`, so unchanged scripts skip the
  Kotlin compiler on later launches while still being evaluated with current flag values on every load.

## [0.2.0] - 2026-08-04

### Changed

- agent47 ships as a self-contained archive bundling a Java runtime. The installer unpacks releases under
  `~/.agent47/dist/<version>` and links a launcher into `~/.local/bin`, and self-update installs new versions alongside
  the current one with an atomic launcher switch.
- Kotlin extensions compile with the full JIT compiler, reducing cold extension loading from tens of seconds to a few
  seconds.

### Removed

- The GraalVM native image build and its runtime class loading machinery.

## [0.1.6] - 2026-07-23

### Added

- Git and local extension repositories with global or project registries, managed checkouts, manifest-based resource discovery, pinned revisions, and atomic updates.
- Interactive startup help showing the version, shortcuts, context files, skills, and extensions, with Ctrl+O expansion and double-Escape input clearing.
- A complete extension repository example containing Kotlin extensions, a skill, a prompt command, a theme, and an authoring build.

### Changed

- Application bootstrap and terminal UI responsibilities are organized into focused runtime, controller, input, overlay, layout, and rendering components with expanded test coverage.
- The terminal layout uses consistent horizontal padding, prioritizes active work in the compact task bar, and renders code blocks without literal fence lines.
- Extension updates support executable-only, repository-only, combined, and single-repository flows.

### Fixed

- Dialog scrims dim plain chat, user-message, editor, focus-banner, and extension text consistently.
- Startup details remain visible when toggled after a conversation, and clearing the conversation resets stale transcript scrolling.

## [0.1.5] - 2026-07-23

### Fixed

- Native builds preserve Mosaic's Java 22 FFM implementation and register its complete foreign-call surface so the interactive terminal UI starts on GraalVM 25.1.3.

## [0.1.4] - 2026-07-23

### Changed

- Release smoke tests launch every native artifact in a real pseudo-terminal and reject startup exceptions.

## [0.1.3] - 2026-07-23

### Added

- Kotlin `.kts` extensions compiled in process by the standalone native executable.
- Extension hooks for agent, tool, input, session, compaction, rendering, UI, flags, shortcuts, and providers.
- Git and local extension package installation, updates, removal, discovery, skills, prompts, and themes.
- Expanded sub-agent configuration, scheduling, structured results, transcript inspection, steering, and worktree isolation.
- Native release smoke tests that compile and load the complete extension API surface.

### Changed

- Application and sub-agent runtimes now share the same injected provider registry and model resolution.
- Instruction, skill, agent, and prompt discovery consistently applies project and user precedence.
- Release builds use GraalVM Community 25.1.3 on macOS Apple Silicon and Linux ARM64/x86-64.

### Fixed

- Prompt file arguments beginning with `@` are escaped before CLI parsing.
- Native image prompts gracefully fall back when AWT image resizing is unavailable.
- Event-stream cancellation, background-agent completion, provider resolution, and model matching edge cases.

## [0.1.2] - 2026-07-22

### Added

- Built-in `agent47 update` command with verified, atomic binary replacement.
- Automatic daily update checks on interactive startup with restart into newly installed releases.
- Runtime `--version` reporting and configurable update settings.

## [0.1.1] - 2026-07-22

### Fixed

- Native release builds now use the GraalVM installation supplied by the build environment.

## [0.1.0] - 2026-07-22

### Added

- Interactive terminal UI with markdown rendering, diff display, and theming.
- Multi-provider support: Anthropic, OpenAI, Google, and any OpenAI-compatible API.
- Core coding tools: file read, edit, write, bash execution, grep, glob, and directory listing.
- Sub-agent system for delegating work to specialized agents with isolated conversation histories.
- Skills system for loading domain-specific knowledge on demand from markdown files.
- Slash commands for reusable prompt templates invoked with `/command args`.
- Session persistence with JSONL-based conversation history and state restoration.
- Model management with runtime switching, custom model definitions, and automatic Ollama discovery.
- File-based configuration hierarchy: project-level overrides user-level overrides bundled defaults.
- Context compaction for managing long conversations within token limits.
- GraalVM native image support for single-binary distribution.
- Install script for curl-based installation.

[0.3.1]: https://github.com/rmonvfer/agent47/releases/tag/v0.3.1
[0.3.0]: https://github.com/rmonvfer/agent47/releases/tag/v0.3.0
[0.2.2]: https://github.com/rmonvfer/agent47/releases/tag/v0.2.2
[0.2.1]: https://github.com/rmonvfer/agent47/releases/tag/v0.2.1
[0.2.0]: https://github.com/rmonvfer/agent47/releases/tag/v0.2.0
[0.1.6]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.6
[0.1.5]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.5
[0.1.4]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.4
[0.1.3]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.3
[0.1.2]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.2
[0.1.1]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.1
[0.1.0]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.0
