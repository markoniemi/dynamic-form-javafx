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

| Endpoint | Auth | Success | Purpose |
|----------|------|---------|---------|
| `GET /api/config/oauth2-issuer-uri` | public | 200 | Issuer for discovery, so the client needs only the backend URL. Returns a bare **`text/plain`** string, not JSON |
| `GET /api/forms` | authenticated | 200 | `FormListItemDto[]` (`formKey`, `title`). **No defined order** |
| `GET /api/forms/{key}` | authenticated | 200 | `FormDto` with fields |
| `POST /api/forms` | `ROLE_ADMIN` | **201** | Create form |
| `PUT /api/forms/{key}` | `ROLE_ADMIN` | 200 | Update form |
| `DELETE /api/forms/{key}` | `ROLE_ADMIN` | **204** | Delete form |
| `POST /api/form-data/{key}` | authenticated | **200** | Submit data. Body is a bare `Map<String,Object>`, not a `FormDataDto` |
| `GET /api/form-data` | authenticated | 200 | List submissions: own only for a user, all for an admin. **No query filter, no paging**; ordered `submittedAt` desc |
| `GET /api/form-data/submission/{id}` | owner or admin | 200 | Read submission; 403 for anyone else |
| `PUT /api/form-data/submission/{id}` | **owner only** | 200 | Edit submission. An admin also gets 403 on someone else's |
| `DELETE /api/form-data/submission/{id}` | `ROLE_ADMIN` | **200** | Delete submission (not 204 — the two delete endpoints disagree) |

Success statuses are not uniform, so a client must not assume 200 everywhere or 204 for every delete.

Models come from the `dynamic-form-api` jar (see
[PrepareForJavaFXClient.md](../../dynamic-form/docs/PrepareForJavaFXClient.md)); the client defines no DTOs of
its own. Shapes for reference:
- `FormDto { id, formKey, title, description, fields[] }`
- `FieldDto { name, label, type, required, placeholder, options[] }`, `type` is one of
  `text|email|tel|number|date|textarea|select|radio|checkbox`; `options` is `{ value, label }[]`
- `FormDataDto { id, formKey, data: Map<String,Object>, submittedAt, submittedBy }`
- `ValidationErrorDto { field, message, code }`; map `field` onto the control

Errors are RFC 7807 `application/problem+json`, never a bare array. A 400 has `detail` = `"Validation failed"`
with the `ValidationErrorDto` list under the property **`errors`**; 403, 404 and 409 carry `detail` only. A
**401 has an empty body** (it comes from the resource-server filter, not the exception advice), so parsing a
problem document out of every error response will fail on exactly the case that matters — an expired token.

Nothing in the backend coerces submission values: the `data` map is JSON in, JSONB in the column, JSON out, and
no code looks at `FieldDto.type` when storing. The wire format is therefore whatever the client sends, and the
only format the backend has been fed is the React client's — `date` as a `"yyyy-MM-dd"` string (`""` when
blank), `number` as a **string** (`"42"`), a multi-option `checkbox` as a **JSON array of selected option
values**, a single-option one as the value string or `false`. Match that; see section 4.5.

Seeded forms, safe for tests: `contact` ("Contact Us"), `feedback` ("Product Feedback Form"), `survey`
("Customer Satisfaction Survey"). Only `feedback` has both a `date` field (`purchaseDate`) and a `checkbox`
field (`features`, 4 options).

Roles come from the `roles` claim with **bare** values (`ADMIN`, `USER`); `RoleClaimsConverter` adds the
`ROLE_` prefix server-side, and also accepts `cognito:groups`. The client reads the same claim from the token to
show or hide admin actions; the server stays the authority.

## 3. Decision: copy the backend or use published artifacts?

**Decision: no copy. Depend on three artifacts that dynamic-form publishes.** Why they exist and how
dynamic-form builds them is in
[PrepareForJavaFXClient.md](../../dynamic-form/docs/PrepareForJavaFXClient.md).

| Artifact | Registry | Used for |
|----------|----------|----------|
| `dynamic-form-api` | Repsy Maven (`https://repo.repsy.io/mvn/markoniemi/maven`) | Shared DTOs |
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
  It holds six DTOs and **nothing else** — there is no `ApiPaths` and no `Roles` class, contrary to an earlier
  draft of this section. The client defines its own path and role constants; see section 4.6 for the shared
  interface that could carry them later.
- The client's JSON mapper is Jackson 2. The api jar is plain DTOs (no Jackson types) and dynamic-form guards the
  wire format with a Jackson 2 round-trip test. If a DTO fails to deserialize in the client, fix it in
  dynamic-form (Lombok `@Jacksonized` or records), not with client-side mixins.
- The DTOs are Lombok `@Value` with no no-arg constructor, and Jackson 2 still constructs them because
  `api/lombok.config` sets `lombok.anyConstructor.addConstructorProperties = true`, so Lombok emits
  `@java.beans.ConstructorProperties`. No `ParameterNamesModule` and no mixins are needed. Keep that line in
  mind before "tidying" the api module's Lombok config — removing it breaks every client.
- `jackson-datatype-jsr310` is **mandatory, not optional**: `FormDataDto.submittedAt` is a `LocalDateTime` and
  fails to deserialize without `JavaTimeModule`.
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
- The stack's health wait is `/actuator/health` with a 2 minute timeout; first start also pulls three images
  (backend from GHCR, auth server, `postgres:16-alpine`), so CI should cache or tolerate that.
- **Blocker: `ghcr.io/markoniemi/dynamic-form` is still a private package.** Its anonymous token request returns
  `UNAUTHORIZED`, while `ghcr.io/markoniemi/oauth2-server` issues a token normally. dynamic-form's own CI is
  unaffected (it authenticates to push) and so is local work there (`jib:dockerBuild` writes to the local
  daemon), but this client's tests cannot pull it on any machine or runner that is not logged in to GHCR. Either
  the package gets made public — tracked as action 1 in
  [PrepareForJavaFXClient.md](../../dynamic-form/docs/PrepareForJavaFXClient.md) §6 — or every developer and the
  client's CI needs a `docker login ghcr.io` with a `read:packages` token.
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
| Docking | SnapFX 0.8.0 | See 4.4. Not on Maven Central; installed from its GitHub release by `scripts/install-snapfx.sh` |
| HTTP | `java.net.http.HttpClient` behind a hand-written `ApiClient` | No extra dep. Retrofit is the sanctioned later experiment, Feign is not — see 4.6 |
| Models | `dynamic-form-api` jar | Shared DTOs from dynamic-form, no hand-written copies |
| JSON | Jackson 2 (`jackson-databind`) | Backend is Jackson 3; wire format is plain JSON, guarded by the api jar's round-trip test |
| OAuth2 | Hand-rolled PKCE flow (~150 lines) over `HttpClient` + loopback `HttpServer` | Avoids a heavy lib; alternative: Nimbus `oauth2-oidc-sdk` |
| Token store | In memory only in v1 | Optional later: OS keystore for refresh token |
| i18n | `ResourceBundle` | dynamic-form frontend has i18next, mirror keys if wanted |
| Tests | JUnit 5, TestFX, `dynamic-form-testcontainers` | Brings Testcontainers and `auth-server-testcontainers` transitively; keep Testcontainers at 2.0.3 like dynamic-form |
| Packaging | `javafx-maven-plugin` for dev, `jpackage` for fat installer | Bundles JRE |

### 4.2 Login (OAuth2 authorization code + PKCE, desktop style, RFC 8252)

1. Ask backend `GET /api/config/oauth2-issuer-uri` (bare `text/plain`), then fetch
   `{issuer}/.well-known/openid-configuration` and read `authorization_endpoint` and `token_endpoint` from it
   rather than hard-coding the paths.
2. Start a loopback `com.sun.net.httpserver.HttpServer` on `127.0.0.1:8123`.
3. Open the system browser at the authorization endpoint with `client_id=desktop-client`, S256 PKCE challenge,
   `state`, `redirect_uri=http://localhost:8123/callback`. Do not use an embedded WebView: the browser keeps
   the SSO session and real password managers.
4. Callback delivers `code`; exchange at the token endpoint with the verifier; keep access token in memory.
5. `AuthService` attaches `Authorization: Bearer` to every request and re-runs login on 401 (token TTL is 1h).

The `desktop-client` registration below is **already published** by dynamic-form in
`testcontainers/src/test/resources/desktop-client.yaml` and proven by its `DynamicFormStackIT`. The client's own
test `oauth2-server.yaml` repeats it (and the real deployment config needs it too):

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
              redirect-uris: [http://localhost:8123/callback]
              scopes: [openid, profile, email]
            require-proof-key: true
            token:
              access-token-time-to-live: 1h
```

**Decision: fixed port 8123, and the RFC 8252 dynamic-port spike is dropped.** The registration dynamic-form
published uses a fixed `http://localhost:8123/callback`, and its IT proves that combination works end to end, so
there is nothing left to spike and no reason to diverge. The redirect URI sent in the authorization request and
in the token request must be byte-identical to the registered one, or the exchange is rejected. Because the port
is fixed, a busy 8123 must produce an error naming the port, not a raw `BindException`.

A dynamic loopback port on `127.0.0.1` stays possible later (Spring Authorization Server allows any port for an
IP-literal redirect, though not for `localhost`), but it would mean changing the registration in dynamic-form
too. Note the config-merge rule from the oauth2-server docs: lists replace, so a `redirect-uris` override must
list every URI.

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

Version 0.8.0. The API below was read from the released jar's bytecode, and several names differ from the 0.6.x
docs this plan first referenced:

- `new SnapFX()`, then `initialize(Stage)`, then `buildLayout()` returning `javafx.scene.Parent`.
- `dock(Node content, String title)` → `DockNode`, or `dock(Node, String, DockElement, DockPosition)` for a
  position. `DockPosition` is `TOP|BOTTOM|LEFT|RIGHT|CENTER`.
- `new DockNode(String layoutId, Node content, String title)` is the constructor that sets a **stable id**; the
  two-argument one does not. Stable ids are required for layout persistence: `form-list`, `form-fill`,
  `form-editor`, `submissions`.
- **`saveLayout()` returns the layout as a `String`** — it does not write a file. The client owns the file, at
  `~/.dynamic-form-desktop/layout.json`, per-user and not per-server.
- `loadLayout(String)` throws `org.snapfx.persistence.DockLayoutLoadException`; catch it and fall back to the
  default layout. A layout that names an id this build no longer knows degrades through
  `DockNodeFactory.createNode(String)` returning null.
- `setNodeFactory(DockNodeFactory)`, `floatNode(DockNode)`, `remove(DockNode)`, `hide`, `restore`.
- Default layout: list on the left, fill pane center, submissions below; editor hidden unless ADMIN.
- Multiple fill panes are allowed: id scheme `form-fill:{formKey}`. The node factory parses the prefix on load.
  Double-click in the list opens a form in a new docked tab; "open in window" uses `floatNode`.
- Role gating: do not dock the editor node for non-admins; also strip it from a loaded layout with `remove`.
- The jar ships **no POM**, so its dependencies are not transitive. Its `module-info` requires
  `com.google.gson`, `javafx.controls`, `javafx.graphics`, `javafx.base` and `java.desktop` — the client must
  declare gson and the JavaFX artifacts itself. It is compiled to class-file major 65 (Java 21).
- **Packaging decision:** SnapFX is still not on Maven Central (checked: zero search hits, `/org/snapfx/`
  returns 404). Rather than cloning and building it, `scripts/install-snapfx.sh` downloads the release jar
  `snapfx-core-0.8.0-0.jar` from GitHub, verifies its published sha256 and installs it as
  `org.snapfx:snapfx-core:0.8.0` with `maven-install-plugin`. It is idempotent, so it is re-runnable after a
  version bump or a cleaned `~/.m2`, and CI runs it before `mvn verify`.
  Pin an exact version and re-check the API on each bump (still a preview, may break). Isolate all SnapFX use in
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
the control named `field` (read from the `errors` property of the problem document). The server is the source of
truth. Submitted `data` is `Map<fieldName, value>`, with the value types from section 2 — notably `number` as a
string and a multi-option `checkbox` as an array, matching the React client.

Note the repo is internally inconsistent about `checkbox`: the React form sends an array for a multi-option
field but `false` or a value string for a single-option one, while its read-only renderer treats the value as a
boolean. `FieldControlFactory` has to pick deliberately; the array is what reaches the wire.

### 4.6 Decision: which HTTP client?

**Decision: a hand-written `ApiClient` over `java.net.http.HttpClient` for v1. Retrofit is the sanctioned
later experiment; Feign is not.**

The conventional options for a Java REST client split on whether the client runs inside Spring:

| Approach | Style | Where it is conventional |
|----------|-------|--------------------------|
| `java.net.http.HttpClient` + Jackson | imperative, zero deps | JDK 11+, any plain-Java client. The default when you want no dependency |
| **Retrofit** + OkHttp + `converter-jackson` | declarative interfaces | The standard non-Spring declarative choice |
| Spring `RestClient` | fluent imperative | Spring 6.1+; the modern replacement for `RestTemplate` |
| Spring `@HttpExchange` + `HttpServiceProxyFactory` | declarative interfaces | Spring 6+, pushed in Boot 4; Spring's own answer to Feign |
| JAX-RS Client / MicroProfile Rest Client | imperative / declarative | Jakarta EE, Quarkus |
| OpenAPI Generator | generated | When the server publishes an OpenAPI document |
| Spring Cloud OpenFeign | declarative | Was conventional in Spring Cloud; now feature-complete, superseded by `@HttpExchange` |

This client has no Spring and should not grow one, which removes the whole Spring column. That leaves
`HttpClient` or Retrofit.

**Why `HttpClient` wins for v1:** request building is not where this API's difficulty lives. The `text/plain`
issuer endpoint, four different success statuses, the RFC 7807 body with errors under `errors`, the empty-body
401 and re-login on 401 are all response-side. Retrofit hands back `Response<T>` and `errorBody()` and that
parsing gets written either way, so it would save the easy half and cost three jars plus roughly 1 MB in the
`jpackage` image. Eight endpoints is below the size where a declarative client pays for itself.

**When to revisit:** if the endpoint count grows well past eight, or the declarative style is wanted for
consistency with the other repos here. Switch to **Retrofit**, not Feign — Retrofit is the conventional
non-Spring declarative client, whereas the Feign used elsewhere in these repos is **Spring Cloud** OpenFeign
(`@FeignClient`, `@GetMapping`, `@EnableFeignClients`, a Spring context). Without Spring that would mean plain
OpenFeign (`io.github.openfeign:feign-core`) with `@RequestLine` and a manual `Feign.builder()` — different
annotations and different wiring, so the familiarity would largely not transfer, and Spring Cloud OpenFeign is
feature-complete besides. The switch is contained: `ApiClient` disappears and `FormApi`/`FormDataApi` become
annotated interfaces.

**The OpenAPI route is currently closed.** dynamic-form has no springdoc, no `openapi` and no swagger
configuration anywhere, so there is no document to generate from. If springdoc were added to the backend,
generating a typed client would arguably become the conventional answer and would subsume both this decision
and much of the api-jar question below.

**No HTTP-annotated interface in `dynamic-form-api`,** whichever client is used. A shared client interface only
prevents drift if both sides use it, and the server cannot implement one annotated for a specific client
library. It would also add that library as a compile dependency for every consumer, including the backend, and
break the api jar's stated rule (`PrepareForJavaFXClient.md` §2: validation-api and provided Lombok, nothing
else).

**The annotation-free variant is still open** — the pattern from `microservice-example`, which involves no
client library at all: an annotation-free interface in the shared module (`UserService`) with the client
interface extending it (`UserClient extends UserService`) and adding only the HTTP mapping. Two things checked
in source before transplanting it:

- **In that precedent the server does not implement the shared interface either** — `UserClient` is its only
  implementor. So the pattern buys a written-down contract and a pre-declared client interface, not
  compile-checked server agreement. An earlier draft of this section claimed it protected both directions; that
  was wrong.
- Because nothing implements it server-side, the `Jwt`/`Authentication` parameters on `FormDataController` are
  **not** a blocker: the client has no such parameters, so a shared interface can cover the full form-data
  surface. (They would block a server-side `implements`, which is not what this is.)
- Retrofit permits a service interface to extend another one — its `validateServiceInterface` walks the
  hierarchy and rejects only type parameters, not inheritance — so each method just has to be re-declared with
  its HTTP annotation, exactly the `@Override @GetMapping` shape of `UserClient`. Verified against Retrofit
  trunk (3.0.0); re-confirm on whichever version gets pinned.

See section 6 for how this would land, including the naming collision to avoid.

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
4. **Login E2E (one test)**: real system-browser step is hard to automate. Pass a custom `BrowserLauncher` that
   drives the login form over `HttpClient` (cookie manager, scrape the `_csrf` field, follow the redirect chain)
   instead of opening a window. Keeps the production flow — including the real loopback callback — under test
   without a human and without pulling Playwright into the dependency tree. dynamic-form's
   `DynamicFormStackIT` is a working implementation of the same scraping flow against the same server; read it
   rather than deriving the redirect sequence again. Playwright (as dynamic-form's `AdminRoleIT` uses it) stays
   the fallback if the login page ever needs JavaScript.
5. Headless CI for TestFX via Monocle (`-Dtestfx.headless=true -Dglass.platform=Monocle`), or `xvfb` on Linux.

## 6. Optional backend changes (not needed for v1)

- None required. Publishing the api jar, testcontainers jar and GHCR image is dynamic-form's work, tracked in
  its `docs/PrepareForJavaFXClient.md`; this project only consumes them.
- CORS is irrelevant for a desktop client; no change.
- A `GET /api/form-data?formKey=` filter and paging would help `SubmissionsPane` on large data. Confirmed: there
  is no filter and no paging today, and the endpoint returns own submissions for a user and all for an admin, so
  until then `SubmissionsPane` filters client-side.
- `PUT /api/form-data/submission/{id}` is owner-only with **no admin escape**, so an admin can read and delete
  another user's submission but not edit it. Either the editor UI must not offer that edit, or the backend grows
  the admin case.
- **Path and role constants in the api jar** (proposed, not yet decided). `ApiPaths.FORMS = "/api/forms"` and
  `Roles.ADMIN` used by both the backend's annotations and the client, so paths and role names cannot drift on a
  literal. Zero dependency cost, and it works for `FormDataController` too since paths do not touch its security
  parameters. `PrepareForJavaFXClient.md` §2 already promises these, so it closes a gap rather than opening one.
  Shape note: Spring splits class-level and method-level paths and annotations need compile-time constants, so
  the jar wants both the raw templates (`FORMS = "/api/forms"`, `BY_KEY = "/{key}"`) and composing helpers
  (`ApiPaths.form(formKey)`) for the client.
- **Annotation-free operation interfaces in the api jar** (proposed, not yet decided; section 4.6). Value is a
  written-down contract plus a pre-declared client interface — not compile-checked server agreement, since
  nothing server-side would implement them.
  **Do not call them `FormService` and `FormDataService`:** both names already exist in
  `com.example.backend.service` as Spring `@Service` classes, and those are **entity**-based
  (`FormService.get(String) → Form`, `FormDataService.getAll() → List<FormData>`), so they could not implement a
  DTO-based interface of the same name anyway. Use `FormOperations` / `FormDataOperations`, or
  `FormApi` / `FormDataApi`.

## 7. Delivery phases

Phases 0-3 are specified task-by-task in
[docs/superpowers/plans/2026-10-19-desktop-foundation.md](superpowers/plans/2026-10-19-desktop-foundation.md).
Phases 4-7 get their own plan once those interfaces exist.

| # | Phase | Output | Exit check |
|---|-------|--------|------------|
| 0 | Prereqs | All three artifacts are on Repsy at `1.0.0-SNAPSHOT` (verified 2026-10-17). Remaining: run `scripts/install-snapfx.sh` once, and get the GHCR package made public — **it is still private** (see 3.2). The loopback spike is dropped (4.2) | Both jars resolve from Repsy, the image pulls without a login, `org.snapfx:snapfx-core:0.8.0` is in `~/.m2` |
| 1 | Skeleton | Maven project, `App`, SnapFX with placeholder panes, layout save/load | App starts, layout restores |
| 2 | Test stack | Test `oauth2-server.yaml` with `desktop-client`, JUnit helper around `DynamicFormStack`, `Launcher` | `*IT` fetches `/api/config/oauth2-issuer-uri` |
| 3 | Auth + API | PKCE flow, `ApiClient` on api-jar DTOs, API ITs | API IT passes for admin and user |
| 4 | Read path | `FormListPane`, `FormFillPane` (all 9 field types), submit | TestFX: submit creates submission |
| 5 | Submissions | `SubmissionsPane` view/edit/delete | TestFX + role checks |
| 6 | Admin | `FormEditorPane`, create/update/delete forms | Admin IT passes |
| 7 | Polish and package | i18n, error/busy UX, `jpackage` installer, CI workflow (build and ITs; triggers: push, PR, and `repository_dispatch` type `dynamic-form-built`, which dynamic-form sends after publishing on a push to `main`) | Installer runs on clean Windows VM |

## 8. Open questions

Still open:

1. Does the production deployment (AWS Cognito, per `docs/AwsImprovements.md`) need to be supported by the
   desktop client? Cognito needs its own public client, and role mapping already reads `cognito:groups`.
2. Target OS for the installer: Windows only, or also macOS and Linux?
3. Should the client remember login across restarts (refresh token in OS keystore)? The oauth2-server never
   issues refresh tokens to public clients, so this needs either a confidential client or re-login each start.
4. Snapshot or release versions for `dynamic-form.version` in CI? Repsy serves snapshots (confirmed), so
   `-SNAPSHOT` works. Releases are reproducible; snapshots are not. Proposed: `-SNAPSHOT` for local dev and the
   dispatch-triggered CI run, pinned release for installer builds. Note a dispatch-triggered CI build only picks
   up a new backend version if `dynamic-form.version` floats; with a pinned release the dispatch just re-runs the
   same build.

Resolved:

5. ~~Work against local snapshots until dynamic-form publishes?~~ dynamic-form is published; depend on Repsy and
   GHCR directly.
6. ~~Which HTTP client, and should `dynamic-form-api` carry an interface for it?~~ `java.net.http.HttpClient`
   behind a hand-written `ApiClient` for v1, with Retrofit — not Feign — as the later experiment if the API
   grows. No HTTP-annotated interface in the api jar. See section 4.6. Path/role constants and annotation-free
   operation interfaces remain proposed in section 6.
7. ~~Loopback redirect: dynamic port or fixed?~~ Fixed `http://localhost:8123/callback`, matching the
   registration dynamic-form already publishes, see section 4.2.
8. ~~SnapFX while it is off Maven Central: clone-and-build, vendored jar, or wait?~~ Install the GitHub release
   jar, checked against its published sha256, via `scripts/install-snapfx.sh`; see section 4.4.
