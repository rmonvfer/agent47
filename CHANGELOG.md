# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-02-18

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
- Homebrew tap for macOS and Linux.

[0.1.0]: https://github.com/rmonvfer/agent47/releases/tag/v0.1.0
