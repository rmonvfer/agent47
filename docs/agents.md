# Agents

Agents are autonomous actors that have a system prompt, a set of tools, and a model. They run inside the agentic loop,
which streams model responses, executes tool calls, and loops until the model stops or an error occurs.

## Defining an agent

Agent definitions are Markdown files with YAML frontmatter. Place them in `~/.agent47/agents/` for global availability
or `.agent47/agents/` at your project root for project-scoped agents. Project definitions override user definitions,
which override bundled ones.

```markdown
---
name: reviewer
description: Reviews code changes for issues
tools: [read, bash, grep, find]
model: pi/slow
thinking-level: medium
---

You are a code reviewer. When given a diff or file path, analyze the code
for bugs, security issues, and style problems. Be concise and specific.
```

The body of the file becomes the agent's system prompt. The YAML frontmatter configures its capabilities.

## Frontmatter fields

The `name` field identifies the agent. It defaults to the filename without the `.md` extension. The `description` is a
one-line summary shown in agent listings.

The `tools` field is a list of tool names the agent can use. If omitted, the agent gets the default set: `read`, `bash`,
`edit`, `write`, `multiedit`, `todowrite`, and `batch`. Restricting tools is useful for agents that should only read and
analyze, not modify files.

The `model` field accepts a comma-separated list of model patterns. The pattern `pi/smol` resolves to the smallest
available fast model, while `pi/slow` resolves to the most capable model. You can also specify exact model names.

The `thinking-level` field controls extended thinking for reasoning models. Valid values are `off`, `minimal`, `low`,
`medium`, `high`, and `xhigh`.

## Sub-agent spawning

The `spawns` field controls whether an agent can delegate work to other agents via the `task` tool.

```markdown
---
name: architect
spawns: [reviewer, explorer]
---
```

Setting `spawns: none` (the default) prevents the agent from spawning subagents. Setting `spawns: *` or `spawns: all`
allows spawning any agent. A list of names restricts it to those specific agents. Subagents inherit the parent's auth,
settings, and working directory but get their own conversation history (meaning they won't pollute the parent's context
and vice versa).

## Structured output

The `output` field defines a JTD (JSON Type Definition) schema that subagents must conform to when returning results.
This is useful when a parent agent needs structured data from a delegated task (you won't need this most of the time).

```markdown
---
name: analyzer
output:
  properties:
    summary:
      type: string
    issues:
      elements:
        properties:
          file:
            type: string
          line:
            type: uint32
          message:
            type: string
---
```
