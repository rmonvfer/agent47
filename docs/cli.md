# CLI and TUI

Running `agent47` opens the interactive terminal UI. Prompt arguments are joined into the initial message, so
`agent47 "review this project"` still opens the TUI. Use `-p` or `--print` for an explicit one-shot invocation:

```bash
agent47 -p "summarize the repository"
agent47 -p @prompt.md @diagram.png
```

When no system console is available, agent47 automatically uses print mode. Print mode requires a prompt. An argument
beginning with `@` loads a text file or supported image into the message.

## Options

`--provider` and `--model` select the provider and model. A model may use `provider/id` and a thinking suffix such as
`:high`. `--thinking` sets `off`, `minimal`, `low`, `medium`, `high`, or `xhigh`; `--api-key` stores a credential for
the selected provider. `--system-prompt` replaces the generated persona; the tool list, guidelines, discovered
instructions, and skills section are still appended to it, and `--append-system-prompt` adds text after the skills
section.

`--tools` replaces the primary core tool set with comma-separated names. The default is `read`, `bash`, `edit`, `write`,
`multiedit`, `grep`, `find`, `ls`, `todowrite`, `todoread`, `todocreate`, `todoupdate`, and `batch`; `--no-tools`
disables core and subagent coordination tools. `--models` limits the model cycle in the TUI using comma-separated glob
patterns.

Sessions are persisted unless `--no-session` is set. `-c`/`--continue` loads the latest session for the current project,
`-r`/`--resume ID` resolves a session by ID or unique prefix, `--session PATH` selects an exact file, and
`--session-dir PATH` changes storage.

`--list-models` lists available models, with optional filtering through `--list-models-search`. `--version` and `--help`
provide command information. `agent47 update` and `agent47 update --self` update the executable,
`agent47 update --extensions` updates every unpinned extension repository, and `agent47 update --all` does both.
`agent47 update SOURCE` and `agent47 update --extension SOURCE` update one installed repository.

`-e`/`--extension PATH` loads a Kotlin extension file or directory and may be repeated. `--no-extensions` disables
loose discovery from `.agent47/extensions/` and `~/.agent47/extensions/` while preserving explicit `-e` paths and
installed repositories. `--list-extensions` compiles the selected extensions, prints their canonical paths, and exits.
`--extension-flag name` and `--extension-flag name=value` supply values declared by loaded extensions.

`agent47 install SOURCE`, `remove SOURCE`, `uninstall SOURCE`, and `list` manage extension repositories. Install and
remove use global scope by default; `-l`/`--local` selects the current project. A source may be a local path, a Git URL,
an SSH source, a `file://` Git source, or shorthand such as `git:github.com/owner/repository`. See
[extensions.md](extensions.md) for registry behavior, repository layout, authoring workflow, and the runtime API.

## Interactive UI

The TUI streams assistant output, renders Markdown and diffs, displays tool activity, persists the conversation, and
supports model, provider, theme, session, instruction, and subagent overlays. Enter submits a prompt; Shift+Enter adds a
line. File paths and slash commands have completion in the editor. A session opens even when no credentials are
configured: no model is selected, and the transcript points at `/provider` to connect one and `/model` to choose from
what that provider offers.

A paste longer than ten lines or a thousand characters collapses to a `[paste #N +L lines]` or `[paste #N C chars]`
placeholder so a large block does not bury the editor. The placeholder behaves as one unit: Backspace or Delete beside
it removes the whole marker along with the text it stands for, and what remains expands back to the full content when
the message is submitted.

Built-in slash commands are `/help`, `/commands`, `/new`, `/clear`, `/model`, `/provider`, `/theme`, `/session`,
`/tree`, `/fork`, `/clone`, `/resume`, `/compact`, `/reload`, `/memory`, `/agents`, `/settings`, and `/exit`. `/reload`
recompiles runtime extensions and atomically replaces their hooks, tools, and commands when every script is valid.
`/memory` shows the instruction files loaded for the current session, while `/agents` exposes background-agent status,
steering, types, schedules, and subagent settings. Files in the project or global `commands/` directories add custom
slash commands; see [commands.md](commands.md).

Every session is a tree, not a line: branching from an earlier point leaves the abandoned turns in place rather than
discarding them. `/tree` opens a navigator over that tree — indentation and connectors show its shape, search filters
by typing, arrow keys fold and unfold branches, and selecting an earlier point moves the session's active branch
there, optionally summarizing what gets left behind. `/fork` picks a past user message and copies the branch up to it
into a new session file, preserving the original tree structure rather than replaying messages; the message's text is
left in the editor to revise and resend. `/clone` copies the current branch into a new session file at the point
you're at now. `/resume` lists saved sessions for the project, most recently touched first, and switches to the one
you pick.

While background agents are running, a list of them sits under the editor: a `main` row for the orchestrator followed
by every running or queued agent, each with a state dot, the activity it is on, and the elapsed time and token count
the registry has for it. The list is only there while at least one background agent exists. The Left arrow on an empty
editor moves into it, Up and Down move the highlight, and Enter opens the highlighted agent's transcript in place of
the conversation; the `main` row returns to the conversation. Escape leaves the list, and Escape with a transcript open
returns to the conversation.

A message goes wherever you are looking: with an agent's transcript on screen, plain text steers that agent rather than
the orchestrator. `@name message` steers a named agent from anywhere, and `@main` reaches the orchestrator without
leaving the transcript you are reading. The task bar follows the same rule and shows the todo list of the conversation
on screen, since every agent keeps its own.

The main shortcuts are Escape twice to clear the input, Ctrl+C to interrupt and then exit on repeated presses, Ctrl+L
to clear visible chat, Ctrl+T to toggle thinking, Ctrl+P/Ctrl+N to cycle models, Ctrl+O to expand startup help and
loaded resources, Ctrl+G to toggle the latest thinking block, Ctrl+E to toggle the latest tool details, and
Ctrl+U/Ctrl+D to scroll history. Ctrl+E and Ctrl+U reach the chat only while the editor is empty; with text in it they
are the usual line-editing keys. Run `/help` for the current shortcut list.
