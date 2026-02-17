# Security Policy

## Threat Model

agent47 is an agentic coding assistant that executes shell commands, reads files, and edits code on behalf of the user. It runs with the same permissions as the user's shell session. There is no sandbox, no privilege separation, and no capability restriction beyond what the user configures.

By design, agent47 trusts the LLM provider to return well-formed tool calls, trusts instruction files (`AGENTS.md`, `AGENT47.md`, skill files) to contain safe directives, and trusts the user's shell environment. It does not attempt to validate the semantic safety of commands before execution.

agent47 does **not** protect against prompt injection, malicious instruction files placed in the working directory, or adversarial content in files the model reads. If an attacker can modify files in your project or influence the model's context, they can cause agent47 to execute arbitrary commands. This is inherent to the architecture and is the same trust model as running a shell script you downloaded from the internet.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |

## Reporting a Vulnerability

If you discover a security vulnerability in agent47, please report it responsibly.

**Email:** security@agent47.co

We aim to acknowledge reports within 48 hours and provide an initial assessment within 7 days. Critical vulnerabilities that affect the integrity of the tool execution pipeline will be prioritized.

Please include:

- A description of the vulnerability and its impact
- Steps to reproduce
- Affected version(s)
- Any suggested mitigation or fix

## Scope

This policy covers the agent47 source code and its direct behavior. The following are **out of scope**:

- Vulnerabilities in upstream dependencies (report these to the respective projects)
- Prompt injection attacks (this is an unsolved problem in the LLM ecosystem, not a bug in agent47)
- Misconfiguration of API keys or environment variables
- Behavior of LLM providers themselves
