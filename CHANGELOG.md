# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- Release builds use GraalVM Community 25.1.3 across every supported platform.

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

[0.1.3]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.3
[0.1.2]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.2
[0.1.1]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.1
[0.1.0]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.0
