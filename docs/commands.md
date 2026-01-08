# Slash Commands

Slash commands are prompt templates that expand into full messages when invoked. They let you define reusable prompts
with argument substitution.

## Creating a command

Commands are Markdown files with YAML frontmatter. Place them in `~/.agent47/commands/` for global availability or
`.agent47/commands/` at your project root. Project commands override user commands, which override bundled ones.

```markdown
---
name: review
description: Review a file for issues
---

Review the file at $1 for bugs, security issues, and code quality problems.
Focus on logic errors and edge cases. Be specific about line numbers.
```

Invoke it with `/review src/main/kotlin/App.kt`. The `$1` placeholder is replaced with the first argument.

## Argument substitution

Commands support positional arguments and a catch-all. Arguments are split on whitespace, with support for quoted
strings.

`$1`, `$2`, `$3` etc. are positional arguments. `$@` and `$ARGUMENTS` expand to all arguments joined with spaces.
Quoting works like shell quoting: `"hello world"` is a single argument.

```markdown
---
name: explain
description: Explain code in a file between two lines
---

Read $1 from line $2 to line $3 and explain what the code does.
Assume I'm familiar with the language but not this codebase.
```

Usage: `/explain src/server.kt 45 80`

## Commands without arguments

Commands don't need arguments. A command can be a fixed prompt that you invoke frequently.

```markdown
---
name: status
description: Summarize current work status
---

Look at the recent git history, any modified files, and the current branch.
Give me a brief summary of what's in progress and what's ready to commit.
```

Usage: `/status`
