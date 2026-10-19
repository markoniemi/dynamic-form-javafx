# Dynamic Form Desktop – Plan

JavaFX fat client for the existing [dynamic-form](../../dynamic-form) application. It reuses the dynamic-form
backend unchanged, uses [SnapFX](https://github.com/Beowolve/SnapFX) for dockable panes, and tests against
[oauth2-server](../../oauth2-server) the same way dynamic-form does.

## 1. Goals and non-goals

**Goals**
- Desktop client with feature parity to the React frontend: list forms, fill and submit a form, browse
  submissions, and (ADMIN) create, edit and delete forms and submissions.
- Dockable, floatable, persistable panes via SnapFX (list, form, submissions, editor side by side).
- Login through OAuth2 authorization code + PKCE against oauth2-server.
- End-to-end tests with real backend and real auth server in Docker (Testcontainers).

**Non-goals**
- No backend changes in v1 (see section 6 for the small ones that may be wanted later).
- No offline mode, no local DB. "Fat" = rich client with real UI logic and state, plus a bundled runtime.

## 2. What the backend gives us

Source: `dynamic-form/backend` (Spring Boot 4.0.3, Java 21, JWT resource server).

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `GET /api/config/oauth2-issuer-uri` | public | Issuer for discovery, so the client needs only the backend URL |
| `GET /api/forms` | authenticated | `FormListItemDto[]` (`formKey`, `title`) |
| `GET /api/forms/{key}` | authenticated | `FormDto` with fields |
| `POST /api/forms` | `ROLE_ADMIN` | Create form |
| `PUT /api/forms/{key}` | `ROLE_ADMIN` | Update form |
| `DELETE /api/forms/{key}` | `ROLE_ADMIN` | Delete form |
| `POST /api/form-data/{key}` | authenticated | Submit data |
| `GET /api/form-data` | authenticated | List submissions |
| `GET/PUT /api/form-data/submission/{id}` | authenticated | Read / edit submission |
| `DELETE /api/form-data/submission/{id}` | `ROLE_ADMIN` | Delete submission |

Models come from the `dynamic-form-api` jar (see
[PrepareForJavaFXClient.md](../../dynamic-form/docs/PrepareForJavaFXClient.md)); the client defines no DTOs of
its own. Shapes for reference:
- `FormDto { id, formKey, title, description, fields[] }`
- `FieldDto { name, label, type, required, placeholder, options[] }`, `type` is one of
  `text|email|tel|number|date|textarea|select|radio|checkbox`; `options` is `{ value, label }[]`
- `FormDataDto { id, formKey, data: Map<String,Object>, submittedAt, submittedBy }`
- `ValidationErrorDto { field, message, code }` returned on 400; map `field` onto the control

Roles come from the `roles` claim (`RoleClaimsConverter` adds `ROLE_` prefix). The client reads the same
claim from the ID/access token to show or hide admin actions; the server stays the authority.

## 3. Decision: copy the backend or use published artifacts?

**Decision: no copy. Depend on three artifacts that dynamic-form publishes.** Why they exist and how
dynamic-form builds them is in
[PrepareForJavaFXClient.md](../../dynamic-form/docs/PrepareForJavaFXClient.md).

| Artifact | Registry | Used for |
|----------|----------|----------|
| `dynamic-form-api` | Repsy Maven (`https://repo.repsy.io/mvn/markoniemi/maven`) | DTOs, path and role constants |
| `dynamic-form-testcontainers` | Repsy Maven | `DynamicFormStack`: backend + auth server for tests |
| `ghcr.io/markoniemi/dynamic-form` | GHCR | Backend image, started by the testcontainers jar |

Why not copy the backend: a copy drifts, pulls Spring Boot 4, JPA, MapStruct and Lombok into a plain JavaFX build,
and tests something other than the shipped artifact. The cost is that tests need Docker, which dynamic-form CI
already assumes.

`oauth2-server` is still consumed through `auth-server-testcontainers`, but indirectly: it is a dependency of
`dynamic-form-testcontainers`, so the client does not declare it.

### 3.1 Dependencies in the client `pom.xml`

```xml
<repositories>
  <repository>
    <id>repsy</id>
    <url>https://repo.repsy.io/mvn/markoniemi/maven</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.example</groupId>
  <artifactId>dynamic-form-api</artifactId>
  <version>${dynamic-form.version}</version>
</dependency>
<dependency>
  <groupId>com.example</groupId>
  <artifactId>dynamic-form-testcontainers</artifactId>
  <version>${dynamic-form.version}</version>
  <scope>test</scope>
</dependency>
```

- One property `dynamic-form.version` for both; api `X` runs against image `X`.
- Api package is `com.example.dynamicform.api` (decided and published; do not use `com.example.backend.dto`).
  It also holds constants `ApiPaths` (e.g. `ApiPaths.FORMS = "/api/forms"`) and `Roles` (e.g. `Roles.ADMIN`).
  Use them in `FormApi`/`FormDataApi` and role checks instead of string literals.
- The client's JSON mapper is Jackson 2. The api jar is plain DTOs (no Jackson types) and dynamic-form guards the
  wire format with a Jackson 2 round-trip test. If a DTO fails to deserialize in the client, fix it in
  dynamic-form (Lombok `@Jacksonized` or records), not with client-side mixins.
- Repsy is public read, so the client needs no credentials to resolve the jars. Only dynamic-form needs
  `REPSY_USER`/`REPSY_TOKEN` to publish.
- The api jar and image come from the same build and the same version, so a client built against api `X` must run
  against image `X`. The testcontainers jar defaults to `ghcr.io/markoniemi/dynamic-form:<its own jar version>`,
  which keeps the pair aligned as long as the client uses one `dynamic-form.version`.
- The api jar brings `jakarta.validation-api`; the client may use the annotations' constraints for client-side
  checks but needs no Hibernate Validator at runtime unless it wants to run validation itself.

### 3.2 Test topology (provided by the testcontainers jar)

```
 JavaFX test JVM  ──HTTP──▶ backend container :8080  (ghcr.io/markoniemi/dynamic-form)
        │                          │  JWK fetch over Docker network
        └──browser/PKCE──▶ auth container :9000 (oauth2-server, alias "auth")
```

The client writes none of this wiring. `DynamicFormStack` already does what `docker-compose.yaml` does: shared
network, auth server on fixed host port 9000 with issuer `http://localhost:9000` (so the `iss` claim matches),
backend using `JWK_SET_URI=http://auth:9000/oauth2/jwks`, health wait, default users `admin/admin [USER,ADMIN]`
and `user/user [USER]`.

Client usage:

```java
DynamicFormStack stack = new DynamicFormStack()
    .withAuthConfigFile("oauth2-server.yaml")   // classpath resource, adds desktop-client (section 4.2)
    .start();
String backend = stack.backendUrl();
String auth    = stack.authUrl();               // http://localhost:9000
// AutoCloseable: stop it in @AfterAll / a shutdown hook
```

`DynamicFormContainer` (backend only, caller supplies `withIssuerUri`/`withJwkSetUri`) also exists, but the client
should use the stack. Use `withLogConsumer(...)` on the container to print backend logs when an IT fails.

Consequences to keep in mind:
- Fixed port 9000: tests cannot run alongside a local auth server on 9000.
- The client's `src/test/resources/oauth2-server.yaml` only needs the `desktop-client` registration; the stack merges
  it over the default users. The oauth2-server rule still applies: lists replace, so a list override (e.g.
  `redirect-uris`) must contain every entry.
- Database: Postgres (decided). The stack runs the image's default `prod` profile (Postgres + Flyway), so the
  `forms/*.json` samples the ITs in section 5 assume are seeded. Startup is slower than H2 and needs a third
  image (postgres). If seeding is missing, raise it in dynamic-form rather than seeding from the client.
- The stack's health wait is `/actuator/health` with a 2 minute timeout; first start also pulls two images
  (backend from GHCR, auth server), so CI should cache or tolerate that.
- A `Launcher` main under `src/test` starts the stack and the app for manual dev, like dynamic-form's
  `DevelopmentApplication`.
- Everything is pinned by `dynamic-form.version`. Use a release (or a dated snapshot) in CI so the client build is
  reproducible; `-SNAPSHOT` plus `latest` is for local work only.

## 4. Client architecture

### 4.1 Stack

| Concern | Choice | Note |
|---------|--------|------|
| Java / JavaFX | 21 / 21+ | SnapFX needs 21+, matches backend |
| Build | Maven | Same toolchain as dynamic-form and oauth2-server |
| Docking | SnapFX 0.6.x | See 4.4, not on Maven Central |
| HTTP | `java.net.http.HttpClient` | No extra dep |
| Models | `dynamic-form-api` jar | Shared DTOs from dynamic-form, no hand-written copies |
| JSON | Jackson 2 (`jackson-databind`) | Backend is Jackson 3; wire format is plain JSON, guarded by the api jar's round-trip test |
| OAuth2 | Hand-rolled PKCE flow (~150 lines) over `HttpClient` + loopback `HttpServer` | Avoids a heavy lib; alternative: Nimbus `oauth2-oidc-sdk` |
| Token store | In memory only in v1 | Optional later: OS keystore for refresh token |
| i18n | `ResourceBundle` | dynamic-form frontend has i18next, mirror keys if wanted |
| Tests | JUnit 5, TestFX, `dynamic-form-testcontainers` | Brings Testcontainers and `auth-server-testcontainers` transitively; keep Testcontainers at 2.0.3 like dynamic-form |
| Packaging | `javafx-maven-plugin` for dev, `jpackage` for fat installer | Bundles JRE |

### 4.2 Login (OAuth2 authorization code + PKCE, desktop style, RFC 8252)

1. Ask backend `GET /api/config/oauth2-issuer-uri`, then fetch `{issuer}/.well-known/openid-configuration`.
2. Start a loopback `com.sun.net.httpserver.HttpServer` on `127.0.0.1:<free port>`.
3. Open the system browser at the authorization endpoint with `client_id=desktop-client`, S256 PKCE challenge,
   `state`, `redirect_uri=http://127.0.0.1:<port>/callback`. Do not use an embedded WebView: the browser keeps
   the SSO session and real password managers.
4. Callback delivers `code`; exchange at the token endpoint with the verifier; keep access token in memory.
5. `AuthService` attaches `Authorization: Bearer` to every request and re-runs login on 401 (token TTL is 1h).

New client in the test `oauth2-server.yaml` (and in the real deployment config):

```yaml
spring:
  security:
    oauth2:
      authorizationserver:
        client:
          desktop-client:
            registration:
              client-id: desktop-client
              client-authentication-methods: [none]
              authorization-grant-types: [authorization_code]
              redirect-uris: [http://127.0.0.1/callback]
              scopes: [openid, profile, email]
            require-proof-key: true
            token:
              access-token-time-to-live: 1h
```

**Risk to verify first (spike):** Spring Authorization Server should accept any port for a loopback IP-literal
redirect URI (RFC 8252 §7.3), but it does not do so for `localhost`. Confirm with a one-hour spike against the
oauth2-server image. If it fails, fall back to a fixed port (e.g. `http://127.0.0.1:53682/callback`) registered
in the client, with a clear error when the port is busy. Note the config-merge rule from oauth2-server docs:
lists replace, so `redirect-uris` must list every URI.

### 4.3 Layers

```
com.example.desktop
├── App.java                    Application entry, wires services, builds SnapFX layout
├── auth/                       PkceLoginFlow, LoopbackCallbackServer, AuthSession (token, roles)
├── api/                        ApiClient (HttpClient + bearer), FormApi, FormDataApi (take and return `dynamic-form-api` DTOs), ApiException(+`ValidationErrorDto` list)
├── ui/
│   ├── dock/                   DockLayoutManager (SnapFX setup, save/load layout, node factory)
│   ├── forms/FormListPane      list + refresh, selection event
│   ├── forms/FormFillPane      renders a Form dynamically (4.5), submit
│   ├── forms/FormEditorPane    ADMIN: edit fields/options, create/update/delete
│   ├── data/SubmissionsPane    TableView of FormData, open/edit/delete
│   └── common/                 ErrorBanner, BusyOverlay, LoginPane
├── state/                      AppState (selected form, session) – observable properties
└── i18n/
```

Rules: UI classes never touch `HttpClient`; every API call runs off the FX thread (`Task` / virtual thread) and
posts the result back with `Platform.runLater`. Panes communicate through `AppState`, not by referencing each
other, so SnapFX can create, destroy and re-dock them freely.

### 4.4 SnapFX integration

- `DockLayoutManager` creates one `SnapFX`, registers `DockNode`s with **stable ids**: `form-list`,
  `form-fill`, `form-editor`, `submissions`. Stable ids are required for layout persistence.
- Default layout: list on the left, fill pane center, submissions below; editor hidden unless ADMIN.
- Multiple fill panes are allowed: id scheme `form-fill:{formKey}`. The node factory parses the prefix on load.
  Double-click in the list opens a form in a new docked tab; "open in window" uses `floatNode`.
- Persistence: `snapFX.saveLayout()` to `~/.dynamic-form-desktop/layout.json` on exit; `loadLayout` on start with
  `DockLayoutLoadException` caught and fallback to the default layout. Per-user, not per-server.
- Role gating: do not dock the editor node for non-admins; also strip it from a loaded layout.
- **Packaging risk:** SnapFX 0.6.x is a public preview and "Maven Central publishing is currently in
  progress". Until it lands:
  1. Clone SnapFX, `./gradlew publishToMavenLocal`, depend on `org.snapfx:snapfx-core:<version>` from
     `~/.m2`. Fine for dev.
  2. For CI, either wait for Central, vendor the built jar into a repo-local Maven repo (`libs/` with
     `<repository>file://…`), or add a CI step that builds SnapFX first.
  Pin an exact version and re-check the API on each bump (preview, may break). Isolate all SnapFX use in
  `ui/dock/` so a swap (e.g. BentoFX, which is on Central) stays cheap.
- JPMS: SnapFX advertises JPMS support. Start non-modular (classpath) for fewer surprises with Jackson and
  Testcontainers; revisit when building the jpackage runtime image.

### 4.5 Dynamic form rendering

`FieldControlFactory` maps `FieldDto.type` to a control plus a value accessor:

| type | Control | Value |
|------|---------|-------|
| text, email, tel | `TextField` | `String` |
| number | `TextField` + numeric `TextFormatter` | `Number` |
| date | `DatePicker` | ISO string (check how the React frontend serializes dates and match it) |
| textarea | `TextArea` | `String` |
| select | `ComboBox` over `options` | option `value` |
| radio | `ToggleGroup` of `RadioButton` | option `value` |
| checkbox | `CheckBox` (single) or group when `options` exist | `Boolean` / `List<String>` (match frontend; verify in `FormDataService` and `survey.json`) |

Client-side validation: `required` plus basic email format, and always show server `ValidationErrorDto` against
the control named `field`. The server is the source of truth. Submitted `data` is `Map<fieldName, value>`.

## 5. Testing strategy

1. **Unit**: `PkceLoginFlow` (verifier/challenge, state check), `FieldControlFactory`, `ApiClient` error mapping,
   layout manager fallback. No Docker.
2. **API integration (`*IT`, Failsafe)**: `DynamicFormStack` (from `dynamic-form-testcontainers`, started once per test JVM) + real `ApiClient` with a token from a headless
   PKCE flow. Cover: list seeded forms, user cannot `POST /api/forms` (403), admin can, submit with validation
   error returns `ValidationErrorDto`. Getting a token headlessly: drive the oauth2-server login form with
   `HttpClient` (cookie manager, follow redirects) instead of a browser.
3. **UI (TestFX, `*IT`)**: launch the app against the stack with an injected `AuthSession` (skip the browser
   step), assert: forms appear in the list, filling and submitting creates a submission, admin sees the editor
   pane, user does not, layout survives save and load.
4. **Login E2E (one test)**: real system-browser step is hard to automate. Use Playwright (as dynamic-form's
   `AdminRoleIT` does) as the "browser" by passing a custom `BrowserLauncher` that opens the URL in Playwright
   and fills the login form. Keeps the production flow under test without a human.
5. Headless CI for TestFX via Monocle (`-Dtestfx.headless=true -Dglass.platform=Monocle`), or `xvfb` on Linux.

## 6. Optional backend changes (not needed for v1)

- None required. Publishing the api jar, testcontainers jar and GHCR image is dynamic-form's work, tracked in
  its `docs/PrepareForJavaFXClient.md`; this project only consumes them.
- CORS is irrelevant for a desktop client; no change.
- A `GET /api/form-data?formKey=` filter and paging would help `SubmissionsPane` on large data. Check what
  `GET /api/form-data` returns first (own submissions only vs all).

## 7. Delivery phases

| # | Phase | Output | Exit check |
|---|-------|--------|------------|
| 0 | Prereqs | dynamic-form side is done (api jar, testcontainers jar, GHCR image published). Remaining: pick `dynamic-form.version` and confirm it resolves; SnapFX in `~/.m2`; loopback redirect spike (4.2) | Both jars resolve from Repsy, image pulls (public GHCR package), spike result recorded here |
| 1 | Skeleton | Maven project, `App`, SnapFX with two empty panes, layout save/load | App starts, layout restores |
| 2 | Test stack | Test `oauth2-server.yaml` with `desktop-client`, JUnit helper around `DynamicFormStack`, `Launcher` | `*IT` fetches `/api/config/oauth2-issuer-uri` |
| 3 | Auth + API | PKCE flow, `ApiClient` on api-jar DTOs, API ITs | API IT passes for admin and user |
| 4 | Read path | `FormListPane`, `FormFillPane` (all 9 field types), submit | TestFX: submit creates submission |
| 5 | Submissions | `SubmissionsPane` view/edit/delete | TestFX + role checks |
| 6 | Admin | `FormEditorPane`, create/update/delete forms | Admin IT passes |
| 7 | Polish and package | i18n, error/busy UX, `jpackage` installer, CI workflow (build and ITs; triggers: push, PR, and `repository_dispatch` type `dynamic-form-built`, which dynamic-form sends after publishing on a push to `main`) | Installer runs on clean Windows VM |

## 8. Open questions

1. ~~Work against local snapshots until dynamic-form publishes?~~ Resolved: dynamic-form is published, depend on
   Repsy and GHCR directly. Dispatch-triggered CI builds pick up the new version only if `dynamic-form.version`
   floats (`-SNAPSHOT`/`latest`); with a pinned release the dispatch just re-runs the same build, so decide in
   question 5.
2. Does the production deployment (AWS Cognito, per `docs/AwsImprovements.md`) need to be supported by the
   desktop client? Cognito needs its own public client, and role mapping already reads `cognito:groups`.
3. Target OS for the installer: Windows only, or also macOS and Linux?
4. Should the client remember login across restarts (refresh token in OS keystore)? The oauth2-server never
   issues refresh tokens to public clients, so this needs either a confidential client or re-login each start.
5. Snapshot or release versions for `dynamic-form.version` in CI? Repsy serves snapshots (confirmed), so
   `-SNAPSHOT` works. Releases are reproducible; snapshots are not. Proposed: `-SNAPSHOT` for local dev and the
   dispatch-triggered CI run, pinned release for installer builds.
