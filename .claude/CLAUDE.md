# Claude Development Guidelines

Detailed conventions live in the project skills under `.claude/skills/`: `dynamic-form-desktop` (architecture,
backend and auth contract, SnapFX notes, workflow), `dynamic-form-desktop-review`, `test-all`. Load
`dynamic-form-desktop` before touching client code, the API layer or anything that talks to the backend.

## What this project is

A JavaFX fat client for the [dynamic-form](../../dynamic-form) application. It reuses the dynamic-form backend
unchanged and **copies no code from it** — the backend's DTOs and its test stack arrive as published artifacts.

| Artifact | Registry | Used for |
|----------|----------|----------|
| `com.example:dynamic-form-api` | Repsy Maven | Shared DTOs. The client defines none of its own |
| `com.example:dynamic-form-testcontainers` | Repsy Maven | `DynamicFormStack`: backend + auth server for tests |
| `ghcr.io/markoniemi/dynamic-form` | GHCR | Backend image, started by the testcontainers jar |

One property, `dynamic-form.version`, pins all three: api `X` runs against image `X`.

## Key Resources

- **docs/DynamicFormDesktopPlan.md** — the spec: goals, backend contract, architecture, delivery phases, and the
  recorded decisions with their reasoning. Read this before designing anything.
- **docs/superpowers/plans/** — task-by-task implementation plans derived from the spec.
- **../dynamic-form/docs/PrepareForJavaFXClient.md** — what the published artifacts guarantee, from the
  producing side. The authority on the api and testcontainers jars.

## Rules that must not be missed

- **No DTOs of our own** — fix a wrong DTO in dynamic-form, not with a mixin or local copy.
- **The UI never touches HTTP**; panes talk through `AppState`; `org.snapfx.*` imports stay in `ui/dock`.
- When changing anything that touches the backend or auth server, verify against the real containers.
- **Build prerequisite:** run `mvn initialize` once (and after a SnapFX bump or cleaned `~/.m2`) to install SnapFX.
  It needs its own invocation; `mvn verify` alone cannot do it.

## Git Commit Messages

One line, semicolons between concerns, imperative mood, capitalized, no period:
`Add ApiClient with bearer auth; map RFC 7807 problem bodies to ApiException; handle the empty-body 401`

Focus on what changed and why. A longer body is fine when the *why* doesn't fit — a corrected assumption, a
non-obvious constraint, or a decision a reader would otherwise question.

## Implementation Tasks

- **Do not commit** unless explicitly asked.
- Plan all changes upfront; validate completeness before committing.
- Write the test first, watch it fail, then implement. A test never seen failing proves nothing.

## Tooling

Prefer IntelliJ MCP tools (`mcp__idea__*`) over generic file/shell tools; they know the project structure.
