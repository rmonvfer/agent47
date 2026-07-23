---
name: repository-guide
description: Explain the structure and validation workflow of this extension repository
---

The repository uses `agent47.json` to declare Kotlin extensions, skills, prompt commands, and themes.

Run `agent47 --no-extensions --extension extensions --list-extensions` to compile the Kotlin entrypoints directly.
Run `gradle check` to execute the same validation through the optional authoring build.
