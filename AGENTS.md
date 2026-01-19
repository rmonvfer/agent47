# AGENTS.md

This file provides context for AI coding agents operating in the agent47 repository. agent47 is a multi-module Kotlin project that implements an agentic coding assistant with AI provider integrations, a TUI, and an extensible tool/skill system.

## Project Structure

The project uses Gradle with convention plugins defined in `build-logic/`. Modules:

- `agent47-ai-types` — shared AI data models (messages, content blocks, tool definitions)
- `agent47-ai-core` — AI runtime, provider registry, HTTP transport
- `agent47-ai-providers` — concrete provider implementations (Anthropic, OpenAI, Google)
- `agent47-agent-core` — agentic loop, tool execution, state management
- `agent47-coding-core` — coding tools (read, edit, bash, write), agents, skills, config
- `agent47-ext-core` — extension handling
- `agent47-tui` — terminal user interface (Mordant-based)
- `agent47-app` — application entry point, CLI argument parsing (Clikt)
- `agent47-test-fixtures` — shared test utilities
- `agent47-model-generator` — model/prompt generation

## Build Commands

```bash
./gradlew build                              # build everything
./gradlew :agent47-agent-core:build          # build a single module
./gradlew test                               # run all tests
./gradlew :agent47-ai-core:test              # run tests in one module
./gradlew :agent47-ai-core:test --tests "co.agentmode.agent47.ai.core.RuntimeTest"           # single test class
./gradlew :agent47-ai-core:test --tests "co.agentmode.agent47.ai.core.RuntimeTest.testName"  # single test method
./gradlew check                              # run tests + static analysis
./gradlew koverHtmlReport                    # code coverage report
```

There is no Makefile or justfile. All build tasks go through `./gradlew`.

## Testing

Tests use JUnit 5 with `kotlin.test` assertions and `kotlinx.coroutines.test.runTest` for async tests. Test files live under `src/test/kotlin/` mirroring the main source package structure, named with a `Test` suffix (e.g., `RuntimeTest.kt`).

Test names use backtick-quoted descriptive sentences:

```kotlin
@Test
fun `runtime resolves registered provider and returns completion`() = runTest {
    // ...
}
```

Integration tests spin up embedded servers (e.g., `com.sun.net.httpserver.HttpServer`) rather than mocking HTTP clients.

## Kotlin & JVM Configuration

- **Kotlin**: 2.3.0
- **JVM target**: 24
- **Serialization**: kotlinx-serialization 1.9.0
- **Coroutines**: kotlinx-coroutines 1.10.2
- **CLI**: Clikt 5.0.3, Mordant 3.0.2
- **Native**: GraalVM native-image via `org.graalvm.buildtools.native`
- **Compiler flags**: `-Xjsr305=strict`; all warnings are errors in CI

Library modules use `explicitApiWarning()`, meaning public API members should have explicit visibility modifiers.

## Code Style

### Formatting

The project follows `kotlin.code.style=official` (set in `gradle.properties`). There is no `.editorconfig`. Convention plugins in `build-logic/` enforce compilation settings uniformly across modules.

### Naming

- Classes, interfaces, enums: `PascalCase` (`AgentConfig`, `ModelRegistry`)
- Functions, variables, properties: `camelCase` (`resolveModel`, `systemPrompt`)
- Constants and enum values: `UPPER_SNAKE_CASE` (`VALID_ENV_HINT`, `AVAILABLE_THEMES`)
- Test methods: backtick-quoted sentences describing behavior

### Imports

Keep imports at the top of the file, never inside functions or methods. Organize them by group: Kotlin stdlib, project-internal packages, then third-party dependencies. Do not use wildcard imports unless the file already does.

### Nullability

Use Kotlin's type system for null safety. Prefer safe calls (`?.`), Elvis (`?:`), and `let`/`also` blocks over explicit null checks. Avoid `!!` except where nullability is provably impossible.

### Error Handling

- CLI errors: throw `Abort` (from Clikt) for fatal user-facing errors
- Fallible operations: use `runCatching { }.getOrNull()` / `.getOrDefault()` for operations that can fail gracefully (I/O, network)
- Do not use checked exceptions; prefer Kotlin Result patterns

### Coroutines

Mark functions `suspend` when they perform async work. Use `runTest` in tests. Avoid `runBlocking` in production code (the one exception is isolated CLI smoke-test helpers).

### Serialization

Use `kotlinx.serialization` annotations (`@Serializable`, `@SerialName`) for data classes that cross serialization boundaries. Build JSON objects with `buildJsonObject { put(...) }` when constructing ad-hoc payloads.

### Visibility

Library modules (`agent47.kotlin-library-conventions`) enable `explicitApiWarning()`. Declare `public` explicitly on API-facing classes, functions, and properties in library modules. Application modules do not require explicit visibility.

## Architecture Notes

The agentic loop lives in `agent47-agent-core`. The main entry point (`Agent47Command` in `agent47-app`) wires together config, model resolution, tool registration, instruction loading, and the TUI. Tools are registered via `createCoreTools()` and can be filtered by CLI flags.

Instruction files (`AGENTS.md`, `AGENT47.md`, `CLAUDE.md`) are auto-discovered by walking up from the working directory to the git root. Agent definitions (markdown with YAML frontmatter) go in `.agent47/agents/` at project or user level.

Provider implementations register themselves via `registerAnthropicProviders()`, `registerOpenAiProviders()`, etc., which populate a global `ApiRegistry`.

## Important Conventions

- Do not add imports inside functions or methods; all imports go at the top of the file.
- Match the style of surrounding code when editing a file; consistency within a file takes priority over external standards.
- Preserve existing code comments unless they are provably false.
- Do not create Alembic migrations by hand (if applicable); all migrations are autogenerated.
- Use `bun` instead of `npm` for any JavaScript/TypeScript tooling in the project.
- Never run or restart development servers automatically.
