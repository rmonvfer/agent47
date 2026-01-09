---
name: init
description: Create or update AGENTS.md with project conventions
---

Analyze this codebase and create an AGENTS.md file in the project root containing:

1. Build, lint, and test commands — especially how to run a single test
2. Code style guidelines including imports, formatting, types, naming conventions, and error handling

The file will be given to agentic coding tools that operate in this repository. Keep it around 150 lines.

If there are existing convention files such as Cursor rules (in .cursor/rules/ or .cursorrules), Copilot instructions (in .github/copilot-instructions.md), or Claude instructions (in .claude/ or CLAUDE.md), incorporate their content.

If an AGENTS.md already exists in the project root, read it and improve it rather than starting from scratch.

$ARGUMENTS
