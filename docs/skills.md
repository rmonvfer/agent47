# Skills

Skills are domain-specific knowledge files that agents can load on demand. They appear in the system prompt, so the 
model knows what knowledge is available without loading everything upfront.

## Creating a skill

A skill is a directory containing a `SKILL.md` file. Place it in `~/.agent47/skills/my-skill/` for global availability
or `.agent47/skills/my-skill/` at your project root. Project skills override user skills with the same name.

```markdown
---
name: postgres-patterns
description: PostgreSQL query patterns and migration conventions for this project
---

This project uses PostgreSQL 16 with the following conventions:

Alembic generates all migrations. Tables use snake_case naming.
Indexes follow the pattern `ix_{table}_{column}`. Foreign keys follow
`fk_{table}_{referenced_table}`.

Common query patterns:

\```sql
-- Paginated listing with cursor-based pagination
SELECT * FROM items
WHERE created_at < :cursor
ORDER BY created_at DESC
LIMIT :page_size;
\```
```

The `name` defaults to the directory name. The `description` is what appears in the system prompt to help the model
decide when to load the skill.

## Multi-file skills

A skill directory can contain additional files beyond `SKILL.md`. The model accesses them through the `read` tool using
the `skill://` protocol.

```
.agent47/skills/api-docs/
  SKILL.md
  endpoints.md
  error-codes.md
  auth-flow.md
```

The model reads additional files with `skill://api-docs/endpoints.md`. The main `SKILL.md` is accessed with
`skill://api-docs`.

## Auto-applied skills

Setting `alwaysApply: true` in the frontmatter makes the skill content automatically available in context without the
model needing to explicitly load it.

```markdown
---
name: project-conventions
description: Project coding conventions
alwaysApply: true
---

Use 4-space indentation. All public functions require KDoc.
```

Use this sparingly because every auto-applied skill consumes context window tokens at every turn.

## File-scoped skills

The `globs` field associates a skill with specific file patterns. This hints to the model that the skill is relevant
when working with matching files.

```markdown
---
name: react-patterns
description: React component patterns for this project
globs: [src/components/**/*.tsx, src/hooks/**/*.ts]
---
```
