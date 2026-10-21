# Claude Development Guidelines

This document provides development guidelines for this project. Detailed conventions live in the project skills
under `.claude/skills/` (`dynamic-form-desktop`, `dynamic-form-desktop-review`, `test-all`).

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

## Integration with the dynamic-form and oauth2-server projects

Authentication comes from [oauth2-server](../../oauth2-server), but indirectly: it is a dependency of
`dynamic-form-testcontainers`, so this project does not declare it. When changing anything that touches the
backend or the auth server, verify against the real containers, especially:

- JWT claims, scopes and roles. The claim is `roles` with **bare** values (`ADMIN`, `USER`); the `ROLE_` prefix
  is added server-side.
- OIDC discovery and JWKS endpoints. The auth server runs on **fixed host port 9000** with issuer
  `http://localhost:9000`, because the `iss` claim must match what the backend expects.
- The `desktop-client` registration: public client, PKCE required, redirect URI **`http://localhost:8123/callback`**
  — a fixed port, matching what dynamic-form publishes.

Backend behaviour worth remembering, because it is not uniform:

- `GET /api/config/oauth2-issuer-uri` returns a bare **`text/plain`** string, not JSON.
- Success statuses differ per endpoint: `POST /api/forms` → 201, `DELETE /api/forms/{key}` → 204,
  `POST /api/form-data/{key}` → 200, `DELETE /api/form-data/submission/{id}` → 200.
- Errors are RFC 7807 `application/problem+json`; validation errors sit under an `errors` array of
  `{ field, message, code }`. A **401 has an empty body**, so never assume an error response is parseable JSON.
- Submission values are not coerced anywhere. Match the React client's wire format: `date` as `"yyyy-MM-dd"`,
  `number` as a **string**, a multi-option `checkbox` as a JSON array of selected option values.

## IntelliJ MCP Tool Preferences

When working with this project, prefer IntelliJ IDE MCP tools (`mcp__idea__*`) over generic alternatives:

- **File operations**: Use `mcp__idea__read_file`, `mcp__idea__create_new_file` instead of Bash/generic Read/Write
- **Project navigation**: Use `mcp__idea__search_symbol`, `mcp__idea__search_text`, `mcp__idea__list_directory_tree`
- **Building/testing**: Use `mcp__idea__build_project`, `mcp__idea__execute_run_configuration`
- **Refactoring**: Use `mcp__idea__rename_refactoring` for safe renames across the project
- **Git operations**: Use `mcp__idea__git_status` to check repo state
- **Linting/diagnostics**: Use `mcp__idea__get_file_problems`, `mcp__idea__lint_files`
- **Debugging**: Use `mcp__idea__xdebug_*` tools for stepping through code when needed

IntelliJ MCP tools are aware of the project structure, dependencies, and IDE state, making them more reliable
than shell-based alternatives.

## Architecture Overview

A single-module Maven project, non-modular (classpath, no `module-info.java`):

- **UI**: JavaFX 21 with SnapFX for dockable panes
- **HTTP**: `java.net.http.HttpClient` behind a hand-written `ApiClient` — no Spring, no declarative client
- **JSON**: Jackson 2, with `JavaTimeModule` (mandatory: `FormDataDto.submittedAt` is a `LocalDateTime`)
- **Auth**: hand-rolled OAuth2 authorization code + PKCE over a loopback `HttpServer`
- **Tests**: JUnit 5 unit tests (`*Test`), and integration tests (`*IT`) against the real backend in Docker

### Key Architectural Patterns

**No DTOs of our own** — every model comes from `com.example.dynamicform.api`. If a DTO is wrong or fails to
deserialize, fix it in dynamic-form, not with a client-side mixin or a local copy.

**The UI never touches HTTP** — UI classes never see `HttpClient`. Every API call runs off the FX thread and
posts its result back with `Platform.runLater`. Panes communicate through `AppState`, never by referencing each
other, so the docking framework can create, destroy and re-dock them freely.

**SnapFX stays in one package** — all `org.snapfx.*` imports live in `com.example.desktop.ui.dock`. SnapFX is a
preview library and is not on Maven Central; isolating it keeps a swap cheap.

**Build prerequisite** — SnapFX must be installed into the local repository before the first build:
`bash scripts/install-snapfx.sh`. It is idempotent; rerun it after a version bump or a cleaned `~/.m2`.

## Git Commit Messages

Commit messages use a **one-line format with semicolons** to separate concerns:

```
Brief action; additional change; optional note
```

**Examples:**
- `Add ApiClient with bearer auth; map RFC 7807 problem bodies to ApiException; handle the empty-body 401`
- `Install SnapFX from its GitHub release; enable snapshots on the Repsy repository; declare gson directly`

**Guidelines:**
- One line only — concise and scannable in git log
- Use semicolons to separate multiple logical changes
- Use imperative mood: "add", "fix", "refactor" (not "added", "fixed")
- Focus on **what changed and why**, not implementation details
- Capitalize first word
- No period at end

A longer body is acceptable when the *why* does not fit on the subject line — a corrected assumption, a
non-obvious constraint, or a decision a reader would otherwise question.

## Implementation Tasks

During implementation tasks (planning, coding, testing):
- **Do not commit** unless explicitly asked
- Work iteratively and validate completeness before committing
- Plan all changes upfront before execution
- Write the test first, watch it fail, then implement. A test never seen failing proves nothing.
