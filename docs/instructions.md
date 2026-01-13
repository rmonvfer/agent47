# Instructions

Instructions are markdown files that inject project-level or user-level context into the system prompt. They let you define coding conventions, project rules, or any guidance that should apply to every conversation.

## File names

agent47 looks for these file names in priority order: `AGENTS.md`, `AGENT47.md`, `CLAUDE.md`. When multiple files exist in the same directory, all of them are loaded.

## Discovery tiers

Discovery runs through four tiers. Files found earlier take priority when deduplicating by absolute path.

**Project-level** instructions are found by walking upward from the current working directory to the git worktree root (or filesystem root). The walk stops at the first directory that contains any matching file name. All matches from that directory are collected.

**Global** instructions come from `~/.agent47/AGENTS.md`. This is the canonical location for instructions that should apply across all projects.

**Claude Code compatibility** checks `~/.claude/CLAUDE.md` for users migrating from Claude Code. This file is loaded only when it exists and hasn't already been discovered via an earlier tier.

**Settings-declared** instructions are explicit file paths configured in `settings.json`. Each entry can be an absolute path, a path relative to the working directory, a `~/`-prefixed path, or a glob pattern.

## Settings configuration

Add an `instructions` list to your global or project `settings.json`:

```json
{
  "instructions": [
    "docs/coding-rules.md",
    "~/shared-instructions.md",
    "docs/*.md"
  ]
}
```

Global and project instruction lists are concatenated, with global entries loaded first.

## Injection format

Each discovered file is wrapped with a header identifying its origin:

```
Instructions from: /absolute/path/to/file.md
<file content>
```

Multiple files are separated by blank lines. The combined string is injected into the system prompt after the guidelines section and before the skills section.
