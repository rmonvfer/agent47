# Agents

Agent definitions configure specialized subagents with a system prompt, model policy, tool set, context behavior, and
execution limits. Put Markdown definitions in `~/.agent47/agents/` for global use or `.agent47/agents/` for a project.
Project definitions override global definitions with the same name, and global definitions override bundled agents.

## Definition format

The Markdown body is the agent's system prompt. YAML frontmatter configures execution:

```markdown
---
name: reviewer
display_name: Code Reviewer
description: Reviews code changes for correctness and security
tools: [read, grep, find, ls]
disallowed_tools: [write, edit]
spawns: none
model: pi/slow, claude-opus
thinking: high
prompt_mode: append
inherit_context: true
skills: [security-review]
memory: project
max_turns: 12
isolation: worktree
output_transcript: true
enabled: true
output:
  properties:
    summary:
      type: string
    issues:
      elements:
        properties:
          file:
            type: string
          message:
            type: string
---

Review the requested change and return only evidence-backed findings.
```

`name` defaults to the filename and `description` defaults to the name. `display_name` changes the UI label without
changing the lookup name. `enabled: false` keeps a definition visible in listings but prevents it from being selected.

`tools` accepts a YAML list or comma-separated names. If omitted, a subagent receives the core defaults: `read`, `bash`,
`edit`, `write`, `grep`, `find`, `ls`, `multiedit`, `todowrite`, `todoread`, `todocreate`, `todoupdate`, and `batch`.
`disallowed_tools` is accepted as metadata and currently affects whether persistent memory is writable; it does not
remove tools from the execution set, so use `tools` to enforce tool availability.

`model` accepts comma-separated model patterns. `pi/smol` and `pi/slow` use the corresponding model role; other values
use normal model matching, with the parent model as fallback. `thinking` or `thinking-level` accepts `off`, `minimal`,
`low`, `medium`, `high`, or `xhigh`.

`prompt_mode` is `replace` by default. `append` preserves the parent system prompt and appends the agent instructions.
`inherit_context` prepends a text rendering of the parent conversation to the task. `skills` preloads the named skills.
`memory` selects persistent memory under `user`, `project`, or `local` scope. An agent without write/edit access receives
memory as read-only context.

`max_turns` sets a soft turn limit. At the limit the agent is asked to wrap up; `graceTurns` from `subagents.json`
controls how many additional turns are allowed before aborting. `isolation: worktree` runs the task in a temporary Git
worktree and commits changes to a branch before cleanup. `output_transcript` overrides the global transcript setting.

`isolated`, `persist_session`, and `session_dir` are accepted compatibility fields but do not currently change runtime
behavior. Task-level `isolation: worktree` and `resume` are operational through the `task` tool.

## Delegation and output

`spawns` controls whether this agent receives a nested `task` tool. Omit it or use `none` to prevent delegation, use
`all` or `*` to allow every agent, or provide comma-separated names. The global `taskMaxRecursionDepth` still limits
nested delegation. The current runtime treats a named policy as permission to delegate but does not filter the nested
registry to only those names.

`output` is a JTD schema for `submit_result`. Both nested YAML, as shown above, and inline JSON objects are supported.
The runtime validates structured results against the schema. A `schema` supplied to the parent `task` call can provide
the schema when the definition does not have one.

Subagents run in the background. The orchestrator receives task IDs immediately, can continue working, and later uses
`check_inbox` to collect results or `send_message` to steer a running agent. Scheduled tasks can use six-field cron,
intervals such as `5m`, or one-shot values such as `+10m`; schedules run only while the current session is active.

## Subagent settings

Operational settings live in `~/.agent47/subagents.json` and `.agent47/subagents.json`. Files are merged field by field,
with project values overriding global values. A complete file is:

```json
{
  "maxConcurrent": 4,
  "defaultMaxTurns": 0,
  "graceTurns": 5,
  "defaultJoinMode": "smart",
  "schedulingEnabled": true,
  "disableDefaultAgents": false,
  "outputTranscript": true,
  "fleetView": true,
  "widgetMode": "background",
  "pushNotifications": false,
  "toolDescriptionMode": "full"
}
```

`maxConcurrent` is clamped to 1–1024. `defaultMaxTurns` is 0 for unlimited or up to 10,000; `graceTurns` is 1–1,000.
`defaultJoinMode` accepts `smart`, `async`, or `group`. `widgetMode` accepts `background`, `all`, or `off`, and
`toolDescriptionMode` accepts `full`, `compact`, or `custom`. Scheduling, bundled-agent registration, transcript files,
the background widget, and opt-in completion pushes use their corresponding settings. Pulling results with
`check_inbox` remains available when push notifications are disabled. `fleetView` and `toolDescriptionMode` are
persisted and exposed in settings but do not currently change execution behavior.
