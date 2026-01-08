# Extensions

Extensions are programmatic hooks that plug into the agent execution pipeline. Unlike agents, skills, and commands (
which are file-based), extensions are Kotlin code that intercepts and modifies agent behavior at runtime.

## Defining an extension

An extension is an `ExtensionDefinition` with an ID and one or more hooks. All hooks are optional.

```kotlin
val myExtension = ExtensionDefinition(
    id = "my-extension",
    beforeAgent = BeforeAgentHook { context ->
        // Modify or inspect messages before the agent processes them
        context.messages
    },
    afterAgent = AfterAgentHook { context ->
        // Run after the agent completes (logging, cleanup, etc.)
    },
    transformContext = ContextTransformHook { context ->
        // Transform the AI context before it goes to the LLM
        context
    },
)
```

## Loading extensions

Extensions are loaded into an `ExtensionRunner`, which sequences them and manages their lifecycle.

```kotlin
val runner = ExtensionRunner()
runner.load(myExtension)
runner.load(anotherExtension)
```

Multiple extensions compose in load order. Before-agent hooks chain: the first extension's output becomes the second's
input. Tool wrappers nest: the last-loaded extension's wrapper is the outermost layer.

## Hook types

The `beforeAgent` hook runs before the agent processes user input. It receives the message list and returns a (
potentially modified) message list. Use this for input filtering, message injection, or preprocessing.

The `afterAgent` hook runs after the agent completes. It receives the final message list. Use this for logging,
analytics, or cleanup.

The `transformContext` hook transforms the `Context` object (system prompt, messages, tools) just before it is sent to
the LLM. Use this for dynamic prompt injection, tool filtering, or context window management.

## Wrapping tools

The `toolWrapper` hook intercepts tool execution. It receives each tool and returns a wrapped version.

```kotlin
val loggingExtension = ExtensionDefinition(
    id = "logging",
    toolWrapper = object : ToolWrapper {
        override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> {
            return object : AgentTool<T> by tool {
                override suspend fun execute(
                    toolCallId: String,
                    parameters: JsonObject,
                    onUpdate: AgentToolUpdateCallback<T>?,
                ): AgentToolResult<T> {
                    println("Tool ${tool.definition.name} called with $parameters")
                    val result = tool.execute(toolCallId, parameters, onUpdate)
                    println("Tool ${tool.definition.name} returned")
                    return result
                }
            }
        }
    },
)
```

## Registering commands

Extensions can register custom slash commands programmatically.

```kotlin
val commandExtension = ExtensionDefinition(
    id = "custom-commands",
    registerCommands = { registry ->
        registry.registerCommand("/deploy", "Deploy to staging")
        registry.registerCommand("/rollback", "Rollback last deployment")
    },
)
```

## Events

The `ExtensionRunner` emits events via a `SharedFlow` when extensions are loaded or tools are wrapped. Subscribe to
`runner.events` to observe the extension lifecycle.
