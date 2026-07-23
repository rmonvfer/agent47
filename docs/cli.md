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
the selected provider. `--system-prompt` replaces the generated prompt and `--append-system-prompt` appends to it.

`--tools` replaces the primary core tool set with comma-separated names. The default is `read`, `bash`, `edit`, `write`,
`grep`, `find`, `ls`, `multiedit`, `todowrite`, `todoread`, `todocreate`, `todoupdate`, and `batch`; `--no-tools`
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
line. File paths and slash commands have completion in the editor.

Built-in slash commands are `/help`, `/commands`, `/new`, `/clear`, `/model`, `/provider`, `/theme`, `/session`,
`/compact`, `/reload`, `/memory`, `/agents`, `/settings`, and `/exit`. `/reload` recompiles runtime extensions and
atomically replaces their hooks, tools, and commands when every script is valid. `/memory` shows the instruction files loaded for the current
session, while `/agents` exposes background-agent status, steering, types, schedules, and subagent settings. Files in
the project or global `commands/` directories add custom slash commands; see [commands.md](commands.md).

The main shortcuts are Ctrl+C to interrupt and then exit on repeated presses, Ctrl+L to clear visible chat, Ctrl+T to
toggle thinking, Ctrl+P/Ctrl+N to cycle models, Ctrl+O to open settings, Ctrl+G to toggle the latest thinking block,
Ctrl+E to toggle the latest tool details, Ctrl+R to view subagent results, and Ctrl+U/Ctrl+D to scroll history. Run
`/help` for the current shortcut list.
