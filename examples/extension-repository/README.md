# agent47 extension repository

This directory is a complete extension repository. It contains independent Kotlin entrypoints, a skill, a prompt
command, and a theme. `agent47.json` declares the resource directories.

Install it from a local checkout while developing:

```bash
agent47 install ./examples/extension-repository --local
agent47 list
agent47 --list-extensions
```

Publish the directory as a Git repository to make the same source installable by URL:

```bash
agent47 install git:github.com/owner/repository
agent47 update git:github.com/owner/repository
```

The Gradle files are optional author tooling. They invoke an installed agent47 executable to compile every extension
entrypoint using the same embedded compiler users run:

```bash
gradle check
gradle check -Pagent47Executable=/absolute/path/to/agent47
```

Neither installation nor runtime invokes Gradle. End users receive the source repository and need only the standalone
agent47 executable; remote Git sources additionally require `git`.
