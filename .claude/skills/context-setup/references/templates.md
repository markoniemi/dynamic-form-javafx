# Templates

Fill in only sections that have real content. An empty or generic section costs tokens and teaches nothing.

## Root CLAUDE.md (target ≤ 50 lines)

```markdown
# <Project name>

<One sentence: what this is and the main stack.>

## Modules
- `services/api/` — REST API (Kotlin, Spring). Has its own CLAUDE.md.
- `services/worker/` — background jobs (Kotlin).
- `web/` — frontend (React, TS). Has its own CLAUDE.md.
- `libs/shared/` — code shared by api and worker; changes affect both.

## Commands (run from repo root)
- Build all: `./gradlew build`
- Test one module: `./gradlew :services:api:test`
- Lint: `./gradlew ktlintCheck`

## Repo-wide rules
- <Non-obvious rule> — <short reason>.
- Never edit `generated/`; regenerate with `./gradlew openApiGenerate`.

## Where to find more
- Architecture: docs/architecture.md (read when changing module boundaries)
- Release process: docs/release.md
```

Note the last section uses plain paths, not `@imports`, so those docs load only when needed.

## Module CLAUDE.md (target ≤ 60 lines)

Place it in the module's root directory. Don't repeat anything from the root file.

```markdown
# services/api

## Commands (from this directory)
- Run locally: `../../gradlew bootRun`
- Single test: `../../gradlew test --tests '*UserServiceTest'`

## Layout
- `controller/` → `service/` → `repository/`; controllers never call repositories directly.

## Pitfalls
- Integration tests need Docker running (Testcontainers).
- DB migrations live in `src/main/resources/db/migration`; never edit an applied migration, add a new one.
```

## Path-scoped rule (.claude/rules/<topic>.md)

```markdown
---
paths:
  - "web/**/*.tsx"
  - "web/**/*.ts"
---
# Frontend rules
- Use the components in `web/src/ui/` before adding new ones.
- Data fetching goes through `web/src/api/client.ts`.
```

A rule file without `paths:` loads in every session, so only leave it unscoped if it truly applies everywhere.

## AGENTS.md + CLAUDE.md together

`AGENTS.md` holds the shared content (use the root template). `CLAUDE.md`:

```markdown
@AGENTS.md

## Claude Code only
- <Anything specific to Claude Code, if any.>
```

## Small skill layout

```
.claude/skills/<skill-name>/
├── SKILL.md          # ≤ 300 lines ideal, 500 max: workflow + when to read each reference
├── references/       # detail loaded on demand; tell SKILL.md WHEN to read each file
└── scripts/          # deterministic work; running a script costs no context for its code
```

Write the `description` to say both what the skill does and when to use it — it's the only part always in context and the only thing that decides triggering. Keep it under ~1024 characters.

## Excluding paths

Large generated or vendored directories waste tokens if Claude searches or reads them. Suggest deny rules in `.claude/settings.json` (shared) or `.claude/settings.local.json` (personal):

```json
{
  "permissions": {
    "deny": [
      "Read(./build/**)",
      "Read(./dist/**)",
      "Read(./node_modules/**)",
      "Read(./**/generated/**)"
    ]
  }
}
```

Check that nothing in the deny list is something Claude legitimately needs to read (e.g. generated API clients it must call correctly).
