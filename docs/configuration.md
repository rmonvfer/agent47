# Configuration

agent47 reads configuration from two directories:
 - a global directory at `~/.agent47/` 
 - a project directory at `.agent47/` in the current working directory. 

Project-level files override global files, and both override bundled defaults.
The global directory location can be changed by setting the `AGENT47_DIR` environment variable.

```
~/.agent47/
├── auth.json           # Provider credentials
├── settings.json       # Global settings
├── models.yml          # Model and provider configuration
├── sessions/           # Persisted conversation history
├── agents/             # Global agent definitions (.md files)
├── skills/             # Global skills (directories with SKILL.md)
└── commands/           # Global slash commands (.md files)

.agent47/
├── settings.json       # Project-level settings (overrides global)
├── agents/             # Project-level agent definitions
├── skills/             # Project-level skills
└── commands/           # Project-level slash commands
```

## Settings

Settings live in `settings.json` at both the global and project level. When both files exist, they are merged field by
field: a project setting overrides the corresponding global setting when it is explicitly set.

```json
{
  "defaultProvider": "anthropic",
  "defaultModel": "claude-opus-4-6",
  "defaultThinkingLevel": "high",
  "shellPath": "/bin/zsh",
  "shellCommandPrefix": "set -e && ",
  "taskMaxRecursionDepth": 3,
  "theme": "dark",
  "showUsageFooter": true,
  "modelRoles": {
    "smol": "cerebras/zai-glm-4.6",
    "slow": "gpt-5.2-codex"
  },
  "compaction": {
    "enabled": true,
    "reserveTokens": 16384,
    "keepRecentTokens": 20000
  },
  "retry": {
    "enabled": true,
    "maxRetries": 3,
    "baseDelayMs": 1000,
    "maxDelayMs": 30000
  }
}
```

`defaultProvider` and `defaultModel` control which model agent47 uses when no CLI flags are passed.
`defaultThinkingLevel` sets the extended thinking level (`off`, `minimal`, `low`, `medium`, `high`, `xhigh`).

`shellPath` overrides the shell used by the `bash` tool (defaults to the system shell). `shellCommandPrefix` is
prepended to every shell command.

`modelRoles` maps role names to model patterns. The `smol` role selects a fast, cheap model for subagents that need
speed. The `slow` role selects a powerful model for complex reasoning. The `default` role overrides the primary model
selection. Role values are model patterns matched the same way as `defaultModel`. When merging, global and project role
maps are combined (union), so a project can add new roles without removing global ones.

`taskMaxRecursionDepth` limits how deep subagents can spawn other subagents (default: 2).

`compaction` controls automatic context compaction when the conversation approaches the model's context window.
`reserveTokens` is the number of tokens to keep free for the model's response. `keepRecentTokens` is the number of
recent tokens to preserve verbatim when compacting older context.

`retry` controls automatic retry on transient API failures with exponential backoff.

## Models

Model configuration lives in `~/.agent47/models.yml`. agent47 ships with a built-in catalog of 150+ models across 20+ 
providers. The config file lets you add custom providers, add models, and override properties of built-in models.

```yaml
providers:
  # Override built-in provider settings
  anthropic:
    baseUrl: "https://custom-proxy.example.com"
    apiKey: "$CUSTOM_ANTHROPIC_KEY"
    headers:
      X-Custom-Header: "value"
    modelOverrides:
      claude-opus-4-6:
        maxTokens: 16384
        contextWindow: 200000

  # Define a custom provider with its own models
  my-provider:
    baseUrl: "https://api.example.com/v1"
    apiKey: "$MY_PROVIDER_KEY"
    api: "openai-completions"
    authHeader: true
    models:
      - id: "my-model"
        name: "My Model"
        reasoning: true
        input: [ "text", "image" ]
        contextWindow: 128000
        maxTokens: 8192
        cost:
          input: 0.01
          output: 0.02
          cacheRead: 0.002
          cacheWrite: 0.004

  # Auto-discover Ollama models
  ollama:
    baseUrl: "http://localhost:11434"
    api: "openai-completions"
    discovery:
      type: "ollama"
```

A provider entry that defines `models` introduces entirely new models under that provider name. A provider entry with
`modelOverrides` (and no `models`) patches properties of built-in models for that provider. When both a built-in and
custom model share the same `provider + id`, the custom model replaces the built-in.

`api` specifies which LLM protocol to use: `anthropic-messages`, `openai-completions`, `openai-responses`,
`openai-codex-responses`, `google-generative-ai`, and others. Models inherit the provider's `api` unless they specify
their own.

`authHeader: true` causes agent47 to add an `Authorization: Bearer {apiKey}` header automatically. Provider-level
`headers` apply to all models under that provider; model-level `headers` are merged on top.

Setting `discovery.type` to `"ollama"` makes agent47 query the Ollama API at startup and register every locally
available model.

### Config value resolution

String values in models.yml support two dynamic prefixes:

`$VAR_NAME` resolves to the environment variable `VAR_NAME`. For example, `apiKey: "$OPENAI_API_KEY"` reads the key from
the environment at startup.

`!command` executes a shell command and uses its stdout as the value. The result is cached for the lifetime of the
process. The command has a 10-second timeout and returns null on failure. For example,
`apiKey: "!cat ~/.secrets/anthropic"` reads the key from a file via shell.

Values without a prefix are used as literal strings.

### Model resolution

When agent47 starts, it resolves which model to use through a priority chain:

1. Explicit `--provider` and `--model` CLI flags (exact match)
2. `--model` pattern alone (fuzzy matched against all available models)
3. `--provider` alone (use that provider's built-in default model)
4. `settings.defaultProvider` + `settings.defaultModel`
5. `settings.modelRoles["default"]` pattern
6. First available model from the built-in provider priority list
7. First available model

Pattern matching is case-insensitive and supports `provider/modelId` format, exact ID matching, and substring matching
on both ID and display name. When multiple models match a substring, alias models (without date suffixes) are preferred
over dated versions.

Subagents use role-based resolution. The `smol` role tries a priority chain of fast models: `cerebras/zai-glm-4.6`,
`claude-haiku-4-5`, then patterns like `haiku`, `flash`, `mini`. The `slow` role tries `gpt-5.2-codex`, `gpt-5.2`, then
patterns like `codex`, `gpt`, `opus`, `pro`. Both roles can be overridden via `settings.modelRoles`.

## Authentication

Provider credentials are stored in `~/.agent47/auth.json` with restricted file permissions. agent47 checks multiple
sources for credentials in order: runtime overrides, stored credentials in auth.json, environment variables, and
fallback resolvers (used for custom provider API keys from models.yml).

Each provider has a known set of environment variables. For example, Anthropic checks `ANTHROPIC_OAUTH_TOKEN` and
`ANTHROPIC_API_KEY`. OpenAI checks `OPENAI_API_KEY`. Google checks `GEMINI_API_KEY`. For unknown providers, agent47
constructs an env var name from the provider ID: `PROVIDER_NAME_API_KEY` (uppercased, hyphens replaced with
underscores).

auth.json supports two credential types: API keys and OAuth tokens. OAuth tokens include an expiry timestamp and are
automatically refreshed when expired (if a refresh function is registered).

## Discovery hierarchy

Agents, skills, and slash commands are discovered from the filesystem using a consistent priority scheme: project-level
files override user-level files, which override bundled defaults. Deduplication is by name: the first definition found
for a given name wins.

For agents: project `.agent47/agents/*.md`, then global `~/.agent47/agents/*.md`, then bundled classpath resources (
`explore`, `plan`, `task`, `quick_task`).

For skills: project `.agent47/skills/*/SKILL.md`, then global `~/.agent47/skills/*/SKILL.md`. There are no bundled
skills.

For commands: project `.agent47/commands/*.md`, then global `~/.agent47/commands/*.md`, then bundled classpath
resources.

This hierarchy lets you override a bundled agent's behavior for a specific project by placing a file with the same name
in `.agent47/agents/`, without modifying the global configuration or the source code.
