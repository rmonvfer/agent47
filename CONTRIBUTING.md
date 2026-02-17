# Contributing to agent47

Contributions are welcome. This document covers the basics of getting set up, making changes, and submitting them.

## Prerequisites

- **JDK 21** — [JetBrains Runtime](https://github.com/JetBrains/JetBrains-Runtime) is recommended, but any JDK 21 distribution works.
- **Git** — for cloning and version control.
- The Gradle wrapper (`./gradlew`) handles everything else. You do not need to install Gradle separately.

## Getting Started

Clone the repository and verify the build:

```bash
git clone https://github.com/rmonvfer/agent47.git
cd agent47
./gradlew build
```

Run all tests:

```bash
./gradlew test
```

Run tests for a single module:

```bash
./gradlew :agent47-ai-core:test
```

Build a native binary (requires GraalVM):

```bash
./gradlew :agent47-app:nativeCompile
```

## Project Structure

agent47 is a multi-module Gradle project. The modules form a layered architecture where each layer depends only on the ones below it:

- **agent47-ai-types** — data models (messages, content blocks, tool definitions)
- **agent47-ai-core** — AI runtime, provider registry, HTTP transport
- **agent47-ai-providers** — LLM provider implementations (Anthropic, OpenAI, Google)
- **agent47-agent-core** — agentic loop, tool execution, state management
- **agent47-coding-core** — coding tools, sub-agents, skills, configuration
- **agent47-ext-core** — extension handling
- **agent47-ui-core** — shared UI state
- **agent47-tui** — terminal user interface
- **agent47-app** — CLI entry point

For detailed architecture information, see the [README](README.md).

## Making Changes

### Issue First

Open an issue before starting work on non-trivial changes. This avoids duplicate effort and ensures the change aligns with the project's direction. Bug fixes and small improvements can go straight to a PR.

### Branch and PR Workflow

1. Fork the repository and create a feature branch from `main`.
2. Make your changes in small, focused commits.
3. Use [conventional commits](https://www.conventionalcommits.org/) for commit messages (e.g., `feat:`, `fix:`, `refactor:`, `test:`, `docs:`).
4. Open a pull request against `main` with a clear description of what changed and why.
5. Ensure CI passes before requesting review.

### Code Style

The project follows Kotlin official code style (`kotlin.code.style=official`). Refer to [AGENTS.md](AGENTS.md) for detailed coding conventions including naming, imports, nullability, error handling, and testing patterns.

Key points:

- Explicit visibility modifiers on public API in library modules.
- Imports at the top of the file, never inside functions.
- Test names use backtick-quoted descriptive sentences.
- Prefer `runCatching` / `Result` over exceptions for fallible operations.

### Testing

All changes should include tests where applicable. Tests use JUnit 5 with `kotlin.test` assertions. Async tests use `kotlinx.coroutines.test.runTest`. Integration tests use embedded servers rather than mocking HTTP clients.

## License

By contributing to agent47, you agree that your contributions will be licensed under the [MIT License](LICENSE).
