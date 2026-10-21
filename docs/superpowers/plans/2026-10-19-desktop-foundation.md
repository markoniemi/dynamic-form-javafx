# Dynamic Form Desktop Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the JavaFX desktop client's foundation — Maven project, JSON layer, HTTP API client, Testcontainers test stack, PKCE login and the docking shell — so that a headless client proves itself against the real dynamic-form backend in Docker.

**Architecture:** A non-modular (classpath) JavaFX 21 Maven project that defines no DTOs of its own: all models come from the published `dynamic-form-api` jar, and all integration tests run against the real backend and auth server started by the published `dynamic-form-testcontainers` jar. The HTTP layer is `java.net.http.HttpClient` plus a Jackson 2 `ObjectMapper`; the UI layer is isolated behind `ui/dock/` so the docking library can be swapped.

**Tech Stack:** Java 21, JavaFX 21.0.12, Maven, SnapFX 0.8.0 (docking), Jackson 2.22.4, JUnit 5.14.4, Testcontainers 2.0.3 (transitive), `dynamic-form-api` + `dynamic-form-testcontainers` 1.0.0-SNAPSHOT from Repsy.

**Spec:** `docs/DynamicFormDesktopPlan.md` (this plan covers its phases 0-3 only; phases 4-7 — the panes, the 9 field controls, the admin editor, i18n, jpackage and CI — get their own plan once these interfaces exist)

---

## Global Constraints

Every task's requirements implicitly include this section.

**Build**
- Java 21: `maven.compiler.release` = `21`. SnapFX 0.8.0 is compiled to class-file major 65 (Java 21). The local JDK is 26; the release flag keeps output at 21.
- Non-modular: no `module-info.java` anywhere. Everything runs on the classpath.
- Group `com.example`, artifact `dynamic-form-desktop`, version `1.0.0-SNAPSHOT`, root package `com.example.desktop`.
- Unit tests are `*Test` (Surefire), integration tests are `*IT` (Failsafe). Only `*IT` needs Docker.
- Pinned versions: JavaFX `21.0.12`, Jackson `2.22.4`, gson `2.14.0`, JUnit BOM `5.14.4`, surefire/failsafe `3.6.0`, compiler plugin `3.16.0`, javafx-maven-plugin `0.0.8`, install plugin `3.1.4`.

**Dependencies**
- `dynamic-form.version` = `1.0.0-SNAPSHOT` for both `dynamic-form-api` (compile) and `dynamic-form-testcontainers` (test). Verified present on `https://repo.repsy.io/mvn/markoniemi/maven`, published 2026-10-17. Repsy is public read — no credentials.
- The client defines **no DTOs**. Models are `com.example.dynamicform.api.{FormDto, FieldDto, FieldOptionDto, FormListItemDto, FormDataDto, ValidationErrorDto}`.
- **The api jar has no `ApiPaths` and no `Roles` class** — spec section 3.1 is wrong about this. The client defines its own constants (Task 3).
- `jackson-datatype-jsr310` is **mandatory**, not optional: `FormDataDto.submittedAt` is a `java.time.LocalDateTime` and fails to deserialize without `JavaTimeModule`.
- Jackson 2 can construct the `@Value` DTOs because `api/lombok.config` sets `lombok.anyConstructor.addConstructorProperties = true`, so Lombok emits `@java.beans.ConstructorProperties`. No `ParameterNamesModule` and no mixins are needed. If a DTO ever fails to deserialize, fix it in dynamic-form, not here.
- **SnapFX is not on Maven Central** (confirmed: 0 hits, `/org/snapfx/` returns 404). It is installed from its GitHub release by `scripts/install-snapfx.sh` (Task 1). Coordinates after install: `org.snapfx:snapfx-core:0.8.0`.
- The SnapFX jar ships **no POM**, so its dependencies are not transitive. Its `module-info` requires `com.google.gson`, `javafx.controls`, `javafx.graphics`, `javafx.base` and `java.desktop`; the client must declare gson and the JavaFX artifacts itself.

**Backend facts** (verified in source; several correct the spec)
- `GET /api/config/oauth2-issuer-uri` returns a **bare `text/plain` string**, not JSON. Never `readValue` it.
- Success statuses are not uniform: `POST /api/forms` → **201**, `PUT /api/forms/{key}` → 200, `DELETE /api/forms/{key}` → **204**, `POST /api/form-data/{key}` → **200**, `PUT /api/form-data/submission/{id}` → 200, `DELETE /api/form-data/submission/{id}` → **200** (not 204).
- `POST`/`PUT` on form-data take a **bare `Map<String,Object>`**, not a `FormDataDto`.
- Errors are RFC 7807 `application/problem+json`. A 400 validation failure has `detail` = `"Validation failed"` and the field errors under the property **`errors`** (a JSON array of `ValidationErrorDto`). 404 and 403 carry `detail` only, no `errors`.
- **A 401 has an empty body** (produced by the resource-server filter, not the advice) plus a `WWW-Authenticate` header.
- `GET /api/form-data` returns the caller's own submissions for a plain user and all submissions for an admin. No query parameters, no paging. Ordered `submittedAt` descending. `GET /api/forms` has **no defined order**.
- Seeded forms, safe for tests: `contact` ("Contact Us"), `feedback` ("Product Feedback Form"), `survey` ("Customer Satisfaction Survey"). Only `feedback` has both a `date` field (`purchaseDate`) and a `checkbox` field (`features`, 4 options).
- Nothing in the backend coerces submission values: the `data` map is JSON in, JSONB in the column, JSON out. The wire format is whatever the client sends, and the only format the backend has been fed is the React client's: `date` → string `"yyyy-MM-dd"`, `number` → **string** (`"42"`), multi-option `checkbox` → **JSON array of selected option values**. Match that.
- The JWT claim is `roles` with **bare** values (`ADMIN`, `USER`); the `ROLE_` prefix is added server-side. `cognito:groups` is also accepted.

**Auth**
- Auth server is on **fixed host port 9000** with issuer `http://localhost:9000`. Tests cannot run beside a local auth server on 9000.
- The client is `desktop-client`, public (`client-authentication-methods: [none]`), `authorization_code` + PKCE required, redirect URI **fixed** at `http://localhost:8123/callback`, scopes `openid profile email`. This matches the registration dynamic-form already publishes and proves in `DynamicFormStackIT`; the spec's dynamic-port `127.0.0.1` design is **not** what is registered, and its section 4.2 spike is therefore dropped.
- Access tokens live 1h; no refresh token is issued to a public client.

**Code rules**
- UI classes never touch `HttpClient`. Every API call runs off the FX thread and posts results back with `Platform.runLater`.
- All SnapFX types stay inside `com.example.desktop.ui.dock`. No other package imports `org.snapfx.*`.
- **No declarative HTTP client in this plan** (spec section 4.6). The HTTP layer is the hand-written `ApiClient` of Task 4 over `java.net.http.HttpClient`, and `FormApi`/`FormDataApi` stay plain classes wrapping it. Do not add a client library while executing this plan — the response-side work (the `text/plain` issuer endpoint, four different success statuses, the RFC 7807 body, the empty-body 401) is the same either way, so it would buy only the easy half. Retrofit is the agreed candidate for a later switch, once the API is bigger; Feign and anything needing `spring-web` are ruled out, because a JavaFX fat client is not getting a Spring context.

## Review Focus

Input classes the spec implies but never tests. Each has its test added to the owning task.

1. **A 401 with an empty body** — the token expires mid-session and `ApiClient` tries to parse a ProblemDetail out of zero bytes. A reasonable client raises "unauthorized", not a JSON-EOF exception. → Task 4.
2. **Port 8123 already in use** — a second client instance, or a stale process. The user should see which port is taken and why, not a raw `BindException`. → Task 7.
3. **The authorization callback carries `error=access_denied` instead of `code`**, or a `state` that does not match — the user cancelled at the consent screen, or the response was forged. The flow must fail fast with a named reason, never hang until the timeout. → Task 7.
4. **A layout file that is corrupt, hand-edited, or names a node id this build no longer knows** — the app must fall back to the default layout and start, not crash on launch. → Task 8.
5. **Submission values of mixed JSON types** — `number` arrives as a string, a multi-option `checkbox` as an array, a single-option one as `false`, a blank `date` as `""`. The mapper must round-trip all of them through `Map<String,Object>` without coercing any. → Task 2.

---

## File Structure

```
pom.xml                                          Task 1
scripts/install-snapfx.sh                        Task 1
src/main/java/com/example/desktop/
  json/JsonMapperFactory.java                    Task 2  ObjectMapper with JavaTimeModule
  api/ApiPaths.java                              Task 3  endpoint path constants
  api/Roles.java                                 Task 3  role name constants
  api/ProblemDetail.java                         Task 3  RFC 7807 body (api jar has none)
  api/ApiException.java                          Task 3  status + detail + validation errors
  api/ApiClient.java                             Task 4  HttpClient, bearer, status/error mapping
  api/FormApi.java                               Task 6  form CRUD over ApiClient
  api/FormDataApi.java                           Task 6  submission CRUD over ApiClient
  auth/PkceCodes.java                            Task 7  verifier, S256 challenge, state
  auth/LoopbackCallbackServer.java               Task 7  127.0.0.1:8123 one-shot callback
  auth/BrowserLauncher.java                      Task 7  interface; Desktop impl + test impl
  auth/AuthSession.java                          Task 7  token + roles from the JWT payload
  auth/PkceLoginFlow.java                        Task 7  discovery, authorize, code exchange
  ui/dock/LayoutStore.java                       Task 8  layout JSON file read/write, pure IO
  ui/dock/DockLayoutManager.java                 Task 8  SnapFX wiring, the only org.snapfx user
  App.java                                       Task 8  Application entry
src/test/java/com/example/desktop/
  DependenciesTest.java                          Task 1
  json/WireFormatTest.java                       Task 2
  api/ProblemDetailMappingTest.java              Task 3
  api/ApiClientTest.java                         Task 4
  it/StackFixture.java                           Task 5  shared stack, one per test JVM
  it/TokenClient.java                            Task 5  headless password login → token
  it/ConfigIT.java                               Task 5
  it/FormApiIT.java                              Task 6
  it/FormDataApiIT.java                          Task 6
  auth/PkceCodesTest.java                        Task 7
  auth/LoopbackCallbackServerTest.java           Task 7
  it/PkceLoginFlowIT.java                        Task 7
  ui/dock/LayoutStoreTest.java                   Task 8
  Launcher.java                                  Task 8  manual dev entry: stack + app
src/test/resources/oauth2-server.yaml            Task 5  desktop-client registration
```

`DockLayoutManager`, `App` and `Launcher` have no automated test in this plan: they are JavaFX-toolkit wiring, and TestFX arrives with the UI panes in the next plan. Their verification is launching `Launcher` by hand (Task 8). Everything they depend on — `LayoutStore`, `ApiClient`, `PkceLoginFlow` — is tested.

---

### Task 1: Maven skeleton and the SnapFX install script

**Files:**
- Create: `pom.xml`
- Create: `scripts/install-snapfx.sh`
- Create: `.gitignore` (add `target/`; the file already exists with IDE entries)
- Test: `src/test/java/com/example/desktop/DependenciesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a build where `com.example.dynamicform.api.*`, `com.example.dynamicform.testcontainers.*`, `org.snapfx.*`, `javafx.*` and Jackson 2 all resolve. Every later task depends on this.

- [ ] **Step 1: Write `scripts/install-snapfx.sh`**

Exact content — the URL, the artifact name and the coordinates cannot be guessed. Idempotent, so it is safe to rerun after a version bump or a cleaned `~/.m2`. Runs in Git Bash on Windows and natively on a CI runner.

```bash
#!/usr/bin/env bash
# Installs the SnapFX release jar into the local Maven repository.
# SnapFX is not published to Maven Central, so this has to run once per machine
# (and again after a version bump or a cleaned ~/.m2) before the client builds.
set -euo pipefail

VERSION="${1:-0.8.0}"
JAR="snapfx-core-${VERSION}-0.jar"
URL="https://github.com/Beowolve/SnapFX/releases/download/v${VERSION}/${JAR}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading $JAR"
curl -fsSL -o "$WORK/$JAR" "$URL"
curl -fsSL -o "$WORK/$JAR.sha256" "$URL.sha256"
(cd "$WORK" && sha256sum -c "$JAR.sha256")

mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile="$WORK/$JAR" \
  -DgroupId=org.snapfx \
  -DartifactId=snapfx-core \
  -Dversion="$VERSION" \
  -Dpackaging=jar

echo "Installed org.snapfx:snapfx-core:$VERSION"
```

- [ ] **Step 2: Run the script to verify it installs**

Run: `bash scripts/install-snapfx.sh`
Expected: prints `Installed org.snapfx:snapfx-core:0.8.0`, the checksum line reads `snapfx-core-0.8.0-0.jar: OK`, and `~/.m2/repository/org/snapfx/snapfx-core/0.8.0/snapfx-core-0.8.0.jar` exists.

- [ ] **Step 3: Write the failing test**

```java
class DependenciesTest {

  @Test
  void apiJarDtosAreOnTheClasspath() {
    assertDoesNotThrow(() -> Class.forName("com.example.dynamicform.api.FormDto"));
    assertDoesNotThrow(() -> Class.forName("com.example.dynamicform.api.FormDataDto"));
    assertDoesNotThrow(() -> Class.forName("com.example.dynamicform.api.ValidationErrorDto"));
  }

  @Test
  void apiJarHasNoPathOrRoleConstants() {
    // Spec section 3.1 claims these; they do not exist, so the client defines its own (Task 3).
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName("com.example.dynamicform.api.ApiPaths"));
  }

  @Test
  void snapFxAndJavaFxAndTestcontainersAreOnTheClasspath() {
    assertDoesNotThrow(() -> Class.forName("org.snapfx.SnapFX"));
    assertDoesNotThrow(() -> Class.forName("org.snapfx.model.DockNode"));
    assertDoesNotThrow(() -> Class.forName("com.google.gson.Gson"));          // SnapFX needs it
    assertDoesNotThrow(() -> Class.forName("javafx.scene.control.TextField"));
    assertDoesNotThrow(() -> Class.forName("com.example.dynamicform.testcontainers.DynamicFormStack"));
  }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -Dtest=DependenciesTest test`
Expected: FAIL — there is no `pom.xml` yet, so Maven cannot build.

- [ ] **Step 5: Write `pom.xml`**

Single-module POM, no parent. Properties for every pinned version in Global Constraints. Dependencies: `dynamic-form-api` (compile), `jackson-databind` and `jackson-datatype-jsr310` (compile), `javafx-controls` and `javafx-fxml` (compile), `org.snapfx:snapfx-core:0.8.0` (compile), `com.google.code.gson:gson:2.14.0` (compile — SnapFX's jar has no POM so it is not transitive), `dynamic-form-testcontainers` (test), `junit-jupiter` (test, via `junit-bom` in `dependencyManagement`). Add the `repsy` repository from spec section 3.1. Configure surefire (`*Test`) and failsafe (`*IT`, goals `integration-test` and `verify`) and the `javafx-maven-plugin` with `mainClass` `com.example.desktop.App`.

JavaFX artifacts are platform-classified; let the plugin and the `javafx.platform` auto-detection handle it rather than hard-coding `win`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -Dtest=DependenciesTest test`
Expected: PASS, 3 tests. If `snapfx-core` does not resolve, Step 2 was skipped.

- [ ] **Step 7: Commit**

```bash
git add pom.xml scripts/install-snapfx.sh .gitignore src/test/java/com/example/desktop/DependenciesTest.java
git commit -m "build: add Maven skeleton and SnapFX install script"
```

---

### Task 2: JSON mapper and the submission wire format

**Files:**
- Create: `src/main/java/com/example/desktop/json/JsonMapperFactory.java`
- Test: `src/test/java/com/example/desktop/json/WireFormatTest.java`

**Interfaces:**
- Consumes: Task 1's build.
- Produces: `JsonMapperFactory.create()` returning a configured `com.fasterxml.jackson.databind.ObjectMapper`. Tasks 4, 5, 6 and 7 all take this mapper.

- [ ] **Step 1: Write the failing test**

Covers Review Focus item 5. The exact strings are the React client's wire format and must not be "cleaned up".

```java
class WireFormatTest {

  private final ObjectMapper mapper = JsonMapperFactory.create();

  @Test
  void formDtoRoundTripsWithFieldsAndOptions() throws Exception {
    FormDto form = FormDto.builder().id(1L).formKey("feedback").title("Product Feedback Form")
        .fields(List.of(FieldDto.builder().name("features").label("Features").type("checkbox")
            .required(false).options(List.of(new FieldOptionDto("feature1", "Feature 1"))).build()))
        .build();

    assertEquals(form, mapper.readValue(mapper.writeValueAsString(form), FormDto.class));
  }

  @Test
  void formDataDtoDeserializesTheLocalDateTimeTheBackendSends() throws Exception {
    String json = """
        {"id":7,"formKey":"feedback","data":{"productName":"X"},
         "submittedAt":"2026-01-02T03:04:05","submittedBy":"admin"}""";

    FormDataDto dto = mapper.readValue(json, FormDataDto.class);

    assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), dto.getSubmittedAt());
    assertEquals("admin", dto.getSubmittedBy());
  }

  @Test
  void submissionValuesKeepTheirJsonTypesInBothDirections() throws Exception {
    // The backend coerces nothing: these are exactly the shapes the React client sends.
    String json = """
        {"productName":"X","purchaseDate":"2026-01-02","rating":"42",
         "features":["feature1","feature3"],"newsletter":false,"blankDate":""}""";

    Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});

    assertEquals("42", data.get("rating"));                              // number as a string
    assertEquals(List.of("feature1", "feature3"), data.get("features")); // multi-option checkbox
    assertEquals(false, data.get("newsletter"));                         // single-option, unchecked
    assertEquals("", data.get("blankDate"));                             // empty date
    assertEquals(json.replaceAll("\\s", ""), mapper.writeValueAsString(data).replaceAll("\\s", ""));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=WireFormatTest test`
Expected: FAIL — `JsonMapperFactory` does not exist.

- [ ] **Step 3: Implement `JsonMapperFactory.create()` returning `ObjectMapper` in `json/JsonMapperFactory.java`**

Register `JavaTimeModule`. Disable `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` so `submittedAt` serializes as the ISO string the backend sends. Do **not** enable any coercion, and do not set a property naming strategy — the DTO names are already the wire names. Private constructor; the class is a factory only.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=WireFormatTest test`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/json src/test/java/com/example/desktop/json
git commit -m "feat: add Jackson 2 mapper and pin the submission wire format"
```

---

### Task 3: Path and role constants, and the RFC 7807 error body

**Files:**
- Create: `src/main/java/com/example/desktop/api/ApiPaths.java`
- Create: `src/main/java/com/example/desktop/api/Roles.java`
- Create: `src/main/java/com/example/desktop/api/ProblemDetail.java`
- Create: `src/main/java/com/example/desktop/api/ApiException.java`
- Test: `src/test/java/com/example/desktop/api/ProblemDetailMappingTest.java`

**Interfaces:**
- Consumes: `JsonMapperFactory.create()` (Task 2).
- Produces:
  - `ApiPaths` constants: `CONFIG_ISSUER_URI = "/api/config/oauth2-issuer-uri"`, `FORMS = "/api/forms"`, `FORM_DATA = "/api/form-data"`, plus `static String form(String formKey)`, `static String formData(String formKey)`, `static String submission(long id)`.
  - `Roles` constants: `ADMIN = "ADMIN"`, `USER = "USER"` (bare claim values) and `ROLE_ADMIN = "ROLE_ADMIN"` (the server-side authority).
  - `ProblemDetail` with `getStatus()`, `getTitle()`, `getDetail()`, `getErrors()` returning `List<ValidationErrorDto>`.
  - `ApiException extends RuntimeException` with `int getStatus()`, `String getDetail()`, `List<ValidationErrorDto> getErrors()` (never null — empty when the body carried none), and `static ApiException from(int status, String body, ObjectMapper mapper)`.

- [ ] **Step 1: Write the failing test**

The bodies are the exact shapes the backend's `GlobalExceptionHandler` produces.

```java
class ProblemDetailMappingTest {

  private final ObjectMapper mapper = JsonMapperFactory.create();

  @Test
  void validationFailureExposesFieldErrors() {
    String body = """
        {"type":"about:blank","title":"Bad Request","status":400,"detail":"Validation failed",
         "instance":"/api/forms",
         "errors":[{"field":"formKey","message":"Form key is required","code":"NotBlank"}]}""";

    ApiException e = ApiException.from(400, body, mapper);

    assertEquals(400, e.getStatus());
    assertEquals("Validation failed", e.getDetail());
    assertEquals(1, e.getErrors().size());
    assertEquals("formKey", e.getErrors().get(0).getField());
    assertEquals("NotBlank", e.getErrors().get(0).getCode());
  }

  @Test
  void notFoundHasDetailAndNoErrors() {
    ApiException e = ApiException.from(404,
        "{\"status\":404,\"detail\":\"Form not found: missing\"}", mapper);

    assertEquals("Form not found: missing", e.getDetail());
    assertTrue(e.getErrors().isEmpty());
  }

  @Test
  void forbiddenHasDetailAndNoErrors() {
    ApiException e = ApiException.from(403, "{\"status\":403,\"detail\":\"Access Denied\"}", mapper);

    assertEquals(403, e.getStatus());
    assertEquals("Access Denied", e.getDetail());
    assertTrue(e.getErrors().isEmpty());
  }

  @Test
  void pathHelpersMatchTheBackendRoutes() {
    assertEquals("/api/forms/feedback", ApiPaths.form("feedback"));
    assertEquals("/api/form-data/feedback", ApiPaths.formData("feedback"));
    assertEquals("/api/form-data/submission/7", ApiPaths.submission(7L));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ProblemDetailMappingTest test`
Expected: FAIL with "cannot find symbol" for `ApiException` and `ApiPaths`.

- [ ] **Step 3: Implement the four classes**

`ApiPaths` and `Roles` are final classes with a private constructor and `public static final String` fields. `ProblemDetail` is a plain mutable bean so Jackson needs no annotations; it must tolerate unknown properties (`type`, `instance`) — configure that on the class with `@JsonIgnoreProperties(ignoreUnknown = true)` rather than globally, so a typo elsewhere still fails loudly.

`ApiException.from` parses the body into a `ProblemDetail` and falls back to a `detail` of the raw body when the body is not JSON — see Task 4 for the empty-body case, which `from` must also survive.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=ProblemDetailMappingTest test`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/api src/test/java/com/example/desktop/api
git commit -m "feat: add API path and role constants and RFC 7807 error mapping"
```

---

### Task 4: ApiClient

**Files:**
- Create: `src/main/java/com/example/desktop/api/ApiClient.java`
- Test: `src/test/java/com/example/desktop/api/ApiClientTest.java`

**Interfaces:**
- Consumes: `JsonMapperFactory.create()` (Task 2); `ApiException`, `ApiPaths` (Task 3).
- Produces:
  - `ApiClient(String baseUrl, Supplier<String> tokenSupplier, ObjectMapper mapper)` — the token supplier is a supplier, not a token, so Task 7's `AuthSession` can slot in without changing this class. It may return `null` for an unauthenticated call.
  - `String getText(String path)` — for the `text/plain` issuer endpoint.
  - `<T> T get(String path, Class<T> type)` and `<T> T get(String path, TypeReference<T> type)`
  - `<T> T post(String path, Object body, Class<T> type)`
  - `<T> T put(String path, Object body, Class<T> type)`
  - `void delete(String path)`
  - Any non-2xx throws `ApiException`. `delete` accepts both 200 and 204 because the backend is inconsistent between the two delete endpoints.

- [ ] **Step 1: Write the failing test**

Tests run against a `com.sun.net.httpserver.HttpServer` stub on an ephemeral port — no Docker, so these stay unit tests. The last test is Review Focus item 1.

```java
class ApiClientTest {

  private HttpServer server;
  private String baseUrl;
  private final List<String> seenAuthHeaders = new ArrayList<>();

  @BeforeEach
  void startStub() throws IOException { /* bind to port 0, record Authorization, reply per handler */ }

  @AfterEach
  void stopStub() { server.stop(0); }

  @Test
  void getDeserializesAndSendsTheBearerToken() {
    stub("/api/forms", 200, "application/json", "[{\"formKey\":\"contact\",\"title\":\"Contact Us\"}]");
    ApiClient client = new ApiClient(baseUrl, () -> "tok-123", JsonMapperFactory.create());

    List<FormListItemDto> forms =
        client.get(ApiPaths.FORMS, new TypeReference<List<FormListItemDto>>() {});

    assertEquals("contact", forms.get(0).getFormKey());
    assertEquals(List.of("Bearer tok-123"), seenAuthHeaders);
  }

  @Test
  void getTextReturnsTheRawBodyForThePlainTextIssuerEndpoint() {
    stub(ApiPaths.CONFIG_ISSUER_URI, 200, "text/plain", "http://localhost:9000");
    ApiClient client = new ApiClient(baseUrl, () -> null, JsonMapperFactory.create());

    assertEquals("http://localhost:9000", client.getText(ApiPaths.CONFIG_ISSUER_URI));
  }

  @Test
  void postAcceptsTheCreatedStatus() {
    stub(ApiPaths.FORMS, 201, "application/json", "{\"formKey\":\"it-form\",\"title\":\"IT\"}");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    assertEquals("it-form", client.post(ApiPaths.FORMS, Map.of(), FormDto.class).getFormKey());
  }

  @Test
  void deleteAcceptsBoth204AndThe200TheFormDataEndpointReturns() {
    stub("/api/forms/gone", 204, null, "");
    stub("/api/form-data/submission/7", 200, null, "");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    assertDoesNotThrow(() -> client.delete(ApiPaths.form("gone")));
    assertDoesNotThrow(() -> client.delete(ApiPaths.submission(7L)));
  }

  @Test
  void validationFailureBecomesAnApiExceptionWithFieldErrors() {
    stub(ApiPaths.FORMS, 400, "application/problem+json", """
        {"status":400,"detail":"Validation failed",
         "errors":[{"field":"title","message":"Title is required","code":"NotBlank"}]}""");
    ApiClient client = new ApiClient(baseUrl, () -> "t", JsonMapperFactory.create());

    ApiException e = assertThrows(ApiException.class,
        () -> client.post(ApiPaths.FORMS, Map.of(), FormDto.class));

    assertEquals("title", e.getErrors().get(0).getField());
  }

  @Test
  void expiredTokenGivesAnUnauthorizedApiExceptionNotAJsonParseError() {
    // The resource server answers 401 with an EMPTY body and a WWW-Authenticate header.
    stub(ApiPaths.FORMS, 401, null, "");
    ApiClient client = new ApiClient(baseUrl, () -> "expired", JsonMapperFactory.create());

    ApiException e = assertThrows(ApiException.class,
        () -> client.get(ApiPaths.FORMS, new TypeReference<List<FormListItemDto>>() {}));

    assertEquals(401, e.getStatus());
    assertNotNull(e.getDetail());
    assertFalse(e.getDetail().isBlank(), "an empty 401 body must still yield a readable message");
    assertTrue(e.getErrors().isEmpty());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ApiClientTest test`
Expected: FAIL — `ApiClient` does not exist.

- [ ] **Step 3: Implement `ApiClient` in `api/ApiClient.java`**

One `HttpClient` instance per `ApiClient`, built with `HttpClient.newBuilder()`. Every request sets `Accept: application/json` (except `getText`, which asks for `text/plain`), sets `Content-Type: application/json` when there is a body, and adds `Authorization: Bearer <token>` only when the supplier returns a non-null, non-blank token. Treat `2xx` as success and route everything else through `ApiException.from`; for an empty body, supply a status-based message so the 401 case reads as "unauthorized" rather than failing to parse.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=ApiClientTest test`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/api/ApiClient.java src/test/java/com/example/desktop/api/ApiClientTest.java
git commit -m "feat: add ApiClient with bearer auth and error mapping"
```

---

### Task 5: Testcontainers test stack

**Files:**
- Create: `src/test/resources/oauth2-server.yaml`
- Create: `src/test/java/com/example/desktop/it/StackFixture.java`
- Create: `src/test/java/com/example/desktop/it/TokenClient.java`
- Test: `src/test/java/com/example/desktop/it/ConfigIT.java`

**Interfaces:**
- Consumes: `ApiClient` (Task 4), `ApiPaths` (Task 3).
- Produces:
  - `StackFixture.backendUrl()` and `StackFixture.authUrl()` — static accessors over one `DynamicFormStack` started lazily and shared by every `*IT` in the JVM.
  - `TokenClient.accessToken(String username, String password)` — a headless authorization-code + PKCE login that returns an access token. Tasks 6 and 7 both use it.

Requires Docker. First run pulls three images (backend from GHCR, auth server, `postgres:16-alpine`); the backend's health wait allows 2 minutes.

- [ ] **Step 1: Write `src/test/resources/oauth2-server.yaml`**

Only the `desktop-client` registration. `DynamicFormStack.withAuthConfigFile` merges it over the default users, and the oauth2-server rule is that maps merge by key while lists replace — so this file must not contain a `users` list. The redirect URI must be exactly the registered one.

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

- [ ] **Step 2: Write the failing test**

```java
class ConfigIT {

  @Test
  void backendReportsTheAuthServerIssuer() {
    ApiClient client =
        new ApiClient(StackFixture.backendUrl(), () -> null, JsonMapperFactory.create());

    assertEquals("http://localhost:9000", client.getText(ApiPaths.CONFIG_ISSUER_URI));
  }

  @Test
  void adminCanLogInHeadlesslyAndReadForms() {
    String token = TokenClient.accessToken("admin", "admin");

    assertFalse(token.isBlank());
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -Dit.test=ConfigIT verify`
Expected: FAIL — `StackFixture` and `TokenClient` do not exist.

- [ ] **Step 4: Implement `StackFixture`**

A holder class with a `private static DynamicFormStack` created on first access inside a static initializer or a synchronized accessor, built as `new DynamicFormStack().withAuthConfigFile("oauth2-server.yaml").withLogConsumer(frame -> System.out.print(frame.getUtf8String())).start()`, with `Runtime.getRuntime().addShutdownHook` calling `close()`. One stack per test JVM, never per test class — starting it three times would take minutes and fight over host port 9000.

- [ ] **Step 5: Implement `TokenClient.accessToken(String username, String password)` returning `String`**

An authorization-code + PKCE flow driven over `HttpClient` with a `CookieManager` and `Redirect.NEVER`: GET `{authUrl}/oauth2/authorize` with `response_type=code`, `scope=openid`, `client_id=desktop-client`, `redirect_uri=http://localhost:8123/callback`, `code_challenge_method=S256` and an S256 challenge; follow the redirect to the login page; scrape the `_csrf` hidden input; POST the credentials; follow the redirect chain to the callback URL; pull `code` from its query; POST it to `{authUrl}/oauth2/token` with the verifier and read `access_token`.

`dynamic-form`'s `testcontainers/src/test/java/com/example/dynamicform/testcontainers/DynamicFormStackIT.java` is a working implementation of exactly this flow against the same server — read it rather than deriving the redirect sequence from scratch. Note it requests only `scope=openid`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -Dit.test=ConfigIT verify`
Expected: PASS, 2 tests. Allow several minutes on the first run for the image pulls.

- [ ] **Step 7: Commit**

```bash
git add src/test/resources/oauth2-server.yaml src/test/java/com/example/desktop/it
git commit -m "test: add Testcontainers stack fixture and headless token client"
```

---

### Task 6: FormApi and FormDataApi

**Files:**
- Create: `src/main/java/com/example/desktop/api/FormApi.java`
- Create: `src/main/java/com/example/desktop/api/FormDataApi.java`
- Test: `src/test/java/com/example/desktop/it/FormApiIT.java`
- Test: `src/test/java/com/example/desktop/it/FormDataApiIT.java`

**Interfaces:**
- Consumes: `ApiClient` (Task 4), `StackFixture` and `TokenClient` (Task 5).
- Produces:
  - `FormApi(ApiClient client)` with `List<FormListItemDto> list()`, `FormDto get(String formKey)`, `FormDto create(FormDto form)`, `FormDto update(String formKey, FormDto form)`, `void delete(String formKey)`.
  - `FormDataApi(ApiClient client)` with `FormDataDto submit(String formKey, Map<String,Object> data)`, `List<FormDataDto> list()`, `FormDataDto get(long id)`, `FormDataDto update(long id, Map<String,Object> data)`, `void delete(long id)`.
  - Both take and return api-jar DTOs only. `submit` and `update` take a bare map because that is what the endpoints accept.

- [ ] **Step 1: Write the failing tests**

```java
class FormApiIT {

  private static FormApi adminApi;
  private static FormApi userApi;

  @BeforeAll
  static void apis() { /* FormApi over an ApiClient per role, tokens from TokenClient */ }

  @Test
  void listsTheSeededForms() {
    List<String> keys = adminApi.list().stream().map(FormListItemDto::getFormKey).toList();

    assertTrue(keys.containsAll(List.of("contact", "feedback", "survey")), keys.toString());
  }

  @Test
  void readsTheFeedbackFormWithItsDateAndCheckboxFields() {
    FormDto form = userApi.get("feedback");

    assertEquals("Product Feedback Form", form.getTitle());
    FieldDto features = form.getFields().stream()
        .filter(f -> f.getName().equals("features")).findFirst().orElseThrow();
    assertEquals("checkbox", features.getType());
    assertEquals(4, features.getOptions().size());
    assertTrue(form.getFields().stream()
        .anyMatch(f -> f.getName().equals("purchaseDate") && f.getType().equals("date")));
  }

  @Test
  void adminCreatesAndDeletesAFormAndUserCannot() {
    FormDto form = FormDto.builder().formKey("it-form").title("IT")
        .fields(List.of(FieldDto.builder().name("a").label("A").type("text").build())).build();

    assertEquals("it-form", adminApi.create(form).getFormKey());   // 201
    assertThrows(ApiException.class, () -> userApi.create(form));  // 403 for ROLE_USER
    assertDoesNotThrow(() -> adminApi.delete("it-form"));          // 204
  }

  @Test
  void invalidFormGivesFieldLevelValidationErrors() {
    FormDto invalid = FormDto.builder().formKey("Not A Key").title("").fields(List.of()).build();

    ApiException e = assertThrows(ApiException.class, () -> adminApi.create(invalid));

    assertEquals(400, e.getStatus());
    assertEquals("Validation failed", e.getDetail());
    assertTrue(e.getErrors().stream().anyMatch(v -> v.getField().equals("formKey")));
  }

  @Test
  void missingFormGivesNotFound() {
    ApiException e = assertThrows(ApiException.class, () -> adminApi.get("no-such-form"));

    assertEquals(404, e.getStatus());
  }
}
```

```java
class FormDataApiIT {

  private static FormDataApi userDataApi;
  private static FormDataApi adminDataApi;

  @BeforeAll
  static void apis() { /* one FormDataApi per role, tokens from TokenClient, as in FormApiIT */ }

  /** The seeded contact form's fields; any valid submission will do, the backend validates none of it. */
  private static Map<String, Object> contactData() {
    return Map.of("name", "Tester", "email", "t@example.com", "message", "hello");
  }

  @Test
  void submitsTheReactWireFormatAndReadsItBackUnchanged() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("productName", "Widget");
    data.put("purchaseDate", "2026-01-02");   // yyyy-MM-dd string
    data.put("quality", "good");
    data.put("features", List.of("feature1", "feature3"));  // multi-option checkbox: array
    data.put("improvements", "");

    FormDataDto saved = userDataApi.submit("feedback", data);   // 200, not 201

    assertEquals("feedback", saved.getFormKey());
    assertNotNull(saved.getSubmittedAt());
    assertEquals("2026-01-02", saved.getData().get("purchaseDate"));
    assertEquals(List.of("feature1", "feature3"), saved.getData().get("features"));
    assertEquals(saved.getData(), userDataApi.get(saved.getId()).getData());
  }

  @Test
  void submittingToAMissingFormGivesNotFound() {
    ApiException e =
        assertThrows(ApiException.class, () -> userDataApi.submit("no-such-form", Map.of("a", "b")));

    assertEquals(404, e.getStatus());
  }

  @Test
  void userSeesOnlyOwnSubmissionsWhileAdminSeesAll() {
    FormDataDto mine = userDataApi.submit("contact", contactData());

    assertTrue(userDataApi.list().stream().allMatch(d -> d.getSubmittedBy().equals("user")));
    assertTrue(adminDataApi.list().stream().anyMatch(d -> d.getId().equals(mine.getId())));
  }

  @Test
  void onlyAdminDeletesASubmission() {
    FormDataDto mine = userDataApi.submit("contact", contactData());

    assertThrows(ApiException.class, () -> userDataApi.delete(mine.getId()));  // 403
    assertDoesNotThrow(() -> adminDataApi.delete(mine.getId()));               // 200, not 204
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dit.test='FormApiIT,FormDataApiIT' verify`
Expected: FAIL — `FormApi` and `FormDataApi` do not exist.

- [ ] **Step 3: Implement `FormApi` and `FormDataApi`**

Thin delegation to `ApiClient` using `ApiPaths`; no caching, no retry, no business logic. `list()` returns what the server returns in server order — `GET /api/forms` has no defined order, so neither of these classes may promise one.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dit.test='FormApiIT,FormDataApiIT' verify`
Expected: PASS, 9 tests across the two classes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/api src/test/java/com/example/desktop/it
git commit -m "feat: add FormApi and FormDataApi over the published DTOs"
```

---

### Task 7: PKCE login flow

**Files:**
- Create: `src/main/java/com/example/desktop/auth/PkceCodes.java`
- Create: `src/main/java/com/example/desktop/auth/LoopbackCallbackServer.java`
- Create: `src/main/java/com/example/desktop/auth/BrowserLauncher.java`
- Create: `src/main/java/com/example/desktop/auth/AuthSession.java`
- Create: `src/main/java/com/example/desktop/auth/PkceLoginFlow.java`
- Test: `src/test/java/com/example/desktop/auth/PkceCodesTest.java`
- Test: `src/test/java/com/example/desktop/auth/LoopbackCallbackServerTest.java`
- Test: `src/test/java/com/example/desktop/it/PkceLoginFlowIT.java`

**Interfaces:**
- Consumes: `ApiClient.getText` (Task 4), `StackFixture` and `TokenClient` (Task 5), `Roles` (Task 3).
- Produces:
  - `PkceCodes.generate()` → a record with `verifier()`, `challenge()` (S256, base64url, unpadded) and `state()`.
  - `LoopbackCallbackServer(int port)` implementing `AutoCloseable`, with `int port()` (so a test can bind port 0 and still learn the port), `String redirectUri()` returning `http://localhost:<port>/callback` and `String awaitCode(String expectedState, Duration timeout)`.
  - `BrowserLauncher` — a functional interface `void open(URI uri)`; the production implementation uses `java.awt.Desktop`, and tests pass their own.
  - `AuthSession` with `String accessToken()`, `Set<String> roles()` and `boolean isAdmin()`. Roles come from the `roles` claim in the JWT payload, decoded base64url **without signature verification** — the server stays the authority, this only shows and hides UI. Values are bare (`ADMIN`), so `isAdmin()` compares against `Roles.ADMIN`.
  - `PkceLoginFlow(String backendUrl, BrowserLauncher browser, ObjectMapper mapper)` with `AuthSession login()`.
  - `CALLBACK_PORT = 8123` as a constant on `PkceLoginFlow`.

- [ ] **Step 1: Write the failing unit tests**

Steps 1-5 cover Review Focus items 2 and 3.

```java
class PkceCodesTest {

  @Test
  void challengeIsTheUnpaddedBase64UrlSha256OfTheVerifier() throws Exception {
    PkceCodes codes = PkceCodes.generate();

    byte[] expected = MessageDigest.getInstance("SHA-256")
        .digest(codes.verifier().getBytes(StandardCharsets.US_ASCII));
    assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(expected),
        codes.challenge());
    assertFalse(codes.challenge().contains("="));
  }

  @Test
  void verifierAndStateAreFreshEachTimeAndLongEnoughForRfc7636() {
    PkceCodes a = PkceCodes.generate();
    PkceCodes b = PkceCodes.generate();

    assertNotEquals(a.verifier(), b.verifier());
    assertNotEquals(a.state(), b.state());
    assertTrue(a.verifier().length() >= 43 && a.verifier().length() <= 128);
  }
}
```

```java
class LoopbackCallbackServerTest {

  @Test
  void returnsTheCodeFromTheCallbackRequest() throws Exception {
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0)) {
      callAsync(server.redirectUri() + "?code=abc123&state=s1");

      assertEquals("abc123", server.awaitCode("s1", Duration.ofSeconds(5)));
    }
  }

  @Test
  void rejectsAMismatchedState() throws Exception {
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0)) {
      callAsync(server.redirectUri() + "?code=abc123&state=forged");

      Exception e = assertThrows(Exception.class,
          () -> server.awaitCode("s1", Duration.ofSeconds(5)));
      assertTrue(e.getMessage().toLowerCase().contains("state"), e.getMessage());
    }
  }

  @Test
  void reportsTheErrorWhenTheUserCancelsInsteadOfWaitingForACode() throws Exception {
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0)) {
      callAsync(server.redirectUri() + "?error=access_denied&state=s1");

      Exception e = assertThrows(Exception.class,
          () -> server.awaitCode("s1", Duration.ofSeconds(5)));
      assertTrue(e.getMessage().contains("access_denied"), e.getMessage());
    }
  }

  @Test
  void aBusyPortGivesAMessageNamingThePortNotABareBindException() throws Exception {
    try (LoopbackCallbackServer first = new LoopbackCallbackServer(0)) {
      int taken = first.port();

      Exception e = assertThrows(Exception.class, () -> new LoopbackCallbackServer(taken));

      assertTrue(e.getMessage().contains(String.valueOf(taken)), e.getMessage());
    }
  }
}
```

- [ ] **Step 2: Run the unit tests to verify they fail**

Run: `mvn -Dtest='PkceCodesTest,LoopbackCallbackServerTest' test`
Expected: FAIL — `PkceCodes` and `LoopbackCallbackServer` do not exist.

- [ ] **Step 3: Implement `PkceCodes`, `LoopbackCallbackServer` and `BrowserLauncher`**

`PkceCodes.generate()` draws 32 random bytes from `SecureRandom` for the verifier and 16 for the state, both base64url-encoded without padding.

`LoopbackCallbackServer` wraps `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1`, serving one path, `/callback`. Add `int port()` so a test can bind to port 0 and still learn the port. It hands the waiting thread either the code or a failure through a `CompletableFuture`, and always writes a short human-readable HTML body so the browser does not show a blank page. Constructing it on a taken port must throw with the port number in the message. `awaitCode` returns the code only when `state` matches, fails when the query carries `error`, and times out rather than blocking forever.

- [ ] **Step 4: Run the unit tests to verify they pass**

Run: `mvn -Dtest='PkceCodesTest,LoopbackCallbackServerTest' test`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write the failing integration test**

A real end-to-end login against the stack, with a `BrowserLauncher` that drives the login form over `HttpClient` instead of opening a window — the same scraping `TokenClient` already does, but routed through the real production flow so the loopback callback is exercised.

```java
class PkceLoginFlowIT {

  @Test
  void logsInAsAdminThroughTheRealCallbackAndSeesTheAdminRole() {
    PkceLoginFlow flow = new PkceLoginFlow(StackFixture.backendUrl(),
        uri -> submitLoginForm(uri, "admin", "admin"), JsonMapperFactory.create());

    AuthSession session = flow.login();

    assertFalse(session.accessToken().isBlank());
    assertTrue(session.roles().contains(Roles.ADMIN));
    assertTrue(session.isAdmin());
  }

  @Test
  void aPlainUserHasNoAdminRole() {
    PkceLoginFlow flow = new PkceLoginFlow(StackFixture.backendUrl(),
        uri -> submitLoginForm(uri, "user", "user"), JsonMapperFactory.create());

    AuthSession session = flow.login();

    assertTrue(session.roles().contains(Roles.USER));
    assertFalse(session.isAdmin());
  }

  @Test
  void theSessionTokenWorksAgainstTheRealApi() {
    AuthSession session = new PkceLoginFlow(StackFixture.backendUrl(),
        uri -> submitLoginForm(uri, "admin", "admin"), JsonMapperFactory.create()).login();
    FormApi api = new FormApi(new ApiClient(
        StackFixture.backendUrl(), session::accessToken, JsonMapperFactory.create()));

    assertFalse(api.list().isEmpty());
  }
}
```

- [ ] **Step 6: Run the integration test to verify it fails**

Run: `mvn -Dit.test=PkceLoginFlowIT verify`
Expected: FAIL — `PkceLoginFlow` and `AuthSession` do not exist.

- [ ] **Step 7: Implement `AuthSession` and `PkceLoginFlow`**

`login()` does: `ApiClient.getText(ApiPaths.CONFIG_ISSUER_URI)` for the issuer; GET `{issuer}/.well-known/openid-configuration` and read `authorization_endpoint` and `token_endpoint` from it rather than hard-coding the paths; open `LoopbackCallbackServer` on `CALLBACK_PORT`; hand the browser the authorization URL with `client_id=desktop-client`, `response_type=code`, `scope=openid profile email`, the S256 challenge, the state and `redirect_uri`; `awaitCode`; POST the code and verifier to the token endpoint; build the `AuthSession`.

The redirect URI sent in the authorization request and in the token request must be byte-identical to the registered `http://localhost:8123/callback`, or the server rejects the exchange.

- [ ] **Step 8: Run the integration test to verify it passes**

Run: `mvn -Dit.test=PkceLoginFlowIT verify`
Expected: PASS, 3 tests.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/desktop/auth src/test/java/com/example/desktop/auth src/test/java/com/example/desktop/it/PkceLoginFlowIT.java
git commit -m "feat: add OAuth2 authorization code + PKCE login flow"
```

---

### Task 8: Docking shell, App and Launcher

**Files:**
- Create: `src/main/java/com/example/desktop/ui/dock/LayoutStore.java`
- Create: `src/main/java/com/example/desktop/ui/dock/DockLayoutManager.java`
- Create: `src/main/java/com/example/desktop/App.java`
- Create: `src/test/java/com/example/desktop/Launcher.java`
- Test: `src/test/java/com/example/desktop/ui/dock/LayoutStoreTest.java`

**Interfaces:**
- Consumes: `AuthSession` (Task 7) for role gating; `StackFixture` (Task 5) in `Launcher`.
- Produces:
  - `LayoutStore(Path file)` with `void save(String layoutJson)` and `Optional<String> load()`. `load()` returns empty rather than throwing when the file is missing or unreadable.
  - `DockLayoutManager(SnapFX snapFX, LayoutStore store, AuthSession session)` with `Parent build(Stage stage)`, `void saveLayout()` and node ids `form-list`, `form-fill`, `form-editor`, `submissions`.
  - `App extends javafx.application.Application`.

SnapFX 0.8.0's real API, read from the jar — the spec's names are from 0.6.x and some differ:
- `new SnapFX()`, then `initialize(Stage)`, then `buildLayout()` returning `javafx.scene.Parent`.
- `dock(Node content, String title)` → `DockNode`, and `dock(Node, String, DockElement, DockPosition)` for a position. `DockPosition` is `TOP|BOTTOM|LEFT|RIGHT|CENTER`.
- `new DockNode(String layoutId, Node content, String title)` is the constructor that sets a **stable id**; the two-argument one does not.
- **`saveLayout()` returns the layout as a `String`** — it does not write a file. `LayoutStore` owns the file.
- `loadLayout(String)` throws `org.snapfx.persistence.DockLayoutLoadException`.
- `setNodeFactory(DockNodeFactory)`, where `DockNodeFactory.createNode(String id)` returns a `DockNode`.
- `floatNode(DockNode)` for "open in window".

- [ ] **Step 1: Write the failing test**

Pure file IO, so no JavaFX toolkit is needed. Covers Review Focus item 4.

```java
class LayoutStoreTest {

  @TempDir Path dir;

  @Test
  void savesAndLoadsTheLayoutString() {
    LayoutStore store = new LayoutStore(dir.resolve("layout.json"));

    store.save("{\"root\":\"x\"}");

    assertEquals("{\"root\":\"x\"}", store.load().orElseThrow());
  }

  @Test
  void loadReturnsEmptyOnAFirstRunWithNoFile() {
    assertTrue(new LayoutStore(dir.resolve("absent.json")).load().isEmpty());
  }

  @Test
  void saveCreatesMissingParentDirectories() {
    LayoutStore store = new LayoutStore(dir.resolve("nested/deeper/layout.json"));

    assertDoesNotThrow(() -> store.save("{}"));
    assertEquals("{}", store.load().orElseThrow());
  }

  @Test
  void loadReturnsEmptyWhenThePathIsADirectoryInsteadOfAFile() throws Exception {
    Path path = dir.resolve("layout.json");
    Files.createDirectory(path);

    assertTrue(new LayoutStore(path).load().isEmpty(), "an unreadable layout must not crash startup");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=LayoutStoreTest test`
Expected: FAIL — `LayoutStore` does not exist.

- [ ] **Step 3: Implement `LayoutStore`**

`save` creates parent directories then writes UTF-8. `load` returns `Optional.empty()` for any `IOException` — a corrupt or unreadable layout must never stop the app from starting.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=LayoutStoreTest test`
Expected: PASS, 4 tests.

- [ ] **Step 5: Implement `DockLayoutManager`**

`build(Stage)` calls `initialize(stage)`, registers a `DockNodeFactory` that maps the four stable ids (and returns `null` for an id it does not know, which is how a stale layout degrades), then tries `store.load()` and `snapFX.loadLayout(...)`. On `DockLayoutLoadException` — or on an empty store — it docks the default arrangement instead: `form-list` LEFT, `form-fill` CENTER, `submissions` BOTTOM. `form-editor` is docked only when `session.isAdmin()`, and after a successful `loadLayout` a non-admin's `form-editor` node is removed with `snapFX.remove(...)`.

Panes are placeholder `Label`s in this plan; the real panes arrive in the next plan. This class is the only file in the project that may import `org.snapfx.*`.

- [ ] **Step 6: Implement `App` and `Launcher`**

`App.start(Stage)` builds the scene from `DockLayoutManager.build(stage)` with the layout file at `~/.dynamic-form-desktop/layout.json`, and calls `saveLayout()` from `stop()`. For now it logs in through `PkceLoginFlow` against a backend URL taken from the system property `backend.url`, defaulting to `http://localhost:8080`.

`Launcher` (under `src/test`) starts `StackFixture`, sets `backend.url` to its `backendUrl()`, and calls `App.main`, so the app runs against a real backend for manual work — the spec's analogue of dynamic-form's `DevelopmentApplication`.

- [ ] **Step 7: Verify by launching**

Run: `mvn -q test-compile exec:java -Dexec.mainClass=com.example.desktop.Launcher -Dexec.classpathScope=test`
Expected: the stack starts, the system browser opens the auth server's login page, and after logging in as `admin` the window shows four docked placeholder panes. Move a pane, close the window, relaunch: the moved layout comes back. Log in as `user` and the `form-editor` pane is absent.

Record the result here; this is the one step with no automated test behind it.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/desktop/ui src/main/java/com/example/desktop/App.java src/test/java/com/example/desktop/Launcher.java src/test/java/com/example/desktop/ui
git commit -m "feat: add SnapFX docking shell with layout persistence"
```

---

## Exit check for the whole plan

Run: `mvn clean verify`
Expected: all unit tests and all `*IT` pass from a clean checkout, given Docker is running and `scripts/install-snapfx.sh` has been run once. This is spec phase 0-3's combined exit check: both Repsy jars resolve, the GHCR image pulls, the stack boots, and the API ITs pass for both `admin` and `user`.

## Carried-forward notes for the next plan

- Phases 4-7 (the panes, `FieldControlFactory` for all 9 field types, `SubmissionsPane`, `FormEditorPane`, i18n, `jpackage`, CI) are not in this plan.
- Spec section 4.3 also lists `state/AppState` and `i18n/`. Neither appears here: with only placeholder panes there is no cross-pane state to observe and no user-facing copy to translate. Both belong to the first task of the next plan that adds a real pane.
- Spec section 5 item 4 proposed Playwright as the "browser" for the login E2E. Task 7 uses a `BrowserLauncher` that drives the login form over `HttpClient` instead — the same scraping `TokenClient` already does — which keeps Playwright out of the dependency tree. Revisit if the login page ever needs JavaScript.
- CI must run `scripts/install-snapfx.sh` before `mvn verify`, because SnapFX is not on Maven Central. Revisit if it lands there.
- `GET /api/form-data` has no `formKey` filter and no paging, so `SubmissionsPane` must filter client-side — spec section 6 guessed this; it is now confirmed.
- An admin receives **403** when updating another user's submission (`PUT /api/form-data/submission/{id}` has no admin escape), although an admin may read and delete it. The editor UI must not offer an edit it cannot perform.
- The repo is internally inconsistent about `checkbox`: the React form sends an array for a multi-option field but `false`/a value string for a single-option one, while its read-only renderer treats the value as a boolean. `FieldControlFactory` has to pick deliberately; the array is what reaches the wire.
- `-SNAPSHOT` for `dynamic-form.version` means the build is not reproducible. Pin a release before the first installer build (spec open question 4).
- Retrofit is the agreed later experiment for the HTTP layer (spec section 4.6). It is contained to Tasks 4 and 6: `ApiClient` disappears, and `FormApi`/`FormDataApi` become annotated interfaces. Everything else — the mapper, the error types, the stack fixture, the login flow — is unaffected, so the switch does not need a new plan.
- Path and role constants, and annotation-free `FormOperations`/`FormDataOperations` interfaces, in `dynamic-form-api` are **proposed but not decided** (spec section 6). Both are dynamic-form changes, so they are out of scope here. Task 3 therefore defines `ApiPaths` and `Roles` in `com.example.desktop.api`; if the jar later ships them, that task's classes are deleted and the imports change. Note the api jar's versions must not be named `FormService`/`FormDataService` — those names are taken by entity-based Spring services in the backend.

---

## Outcome and follow-up work

All eight tasks were executed (commits `1a7a00c`..`028586a` on `main`). `mvn clean verify` passes from clean with
34 unit tests and 14 integration tests, and every pushed commit has a green GitHub Actions run.

### Fixed after the whole-branch review

- **C1 — a user could brick their own install with four clicks.** SnapFX serializes a graph whose panes have
  all been closed as exactly `{}`, and `loadLayout` accepts that silently instead of throwing. The app then
  treated it as a successful restore, docked nothing, and saved the empty graph on exit — so every later launch
  was blank, with no in-app way back. All four panes are closeable by default, so this needed no file editing.
  `LayoutStore` now refuses to load or persist a layout with no panes, and `DockLayoutManager` falls back to the
  defaults when a loaded graph has no root. Covered by three `LayoutStoreTest` cases.
- **I1 — the whole login ran on the FX thread inside `App.start()`,** so a busy port 8123, a cancelled consent
  screen or an unreachable backend killed the process with only a stderr trace, which a `jpackage` build never
  shows. Every carefully worded error message in the auth layer was unreachable. The stage now appears first,
  login runs in a daemon `Task`, and failures surface in an `Alert`. **No automated test** — JavaFX wiring.
- **I2 — no connect or request timeout on either `HttpClient`,** so a backend that accepts a connection and
  never answers parked the caller indefinitely. Now 10s connect and 30s request, with a package-private
  constructor seam so a test can shorten it. Covered by `ApiClientTest`.
- **I3 — the default layout did not match section 4.4.** Every pane was docked relative to the list, and
  docking twice to the list's RIGHT splits the list's own cell rather than adding a column, so an admin's first
  run showed the editor in a quarter-width strip beside the list. Now fill is RIGHT of list, submissions BOTTOM
  of fill, and the editor is tabbed (CENTER) with fill. **No automated test** — JavaFX wiring.

I1 and I3 have no automated test, so Task 8's manual launch step (step 7) is their only verification.

**Launch verified by hand on 2026-10-21.** `Launcher` brought up all three containers and the desktop UI; the
four panes rendered with their placeholder names; the layout matched section 4.4 (Forms left, Form centre,
Submissions below Form); and the Editor pane appeared for `admin`. That confirms the I3 arrangement fix and the
admin role gating, which were the two shipped-without-a-test changes.

Still unverified by anyone:

- Layout persistence across a restart — move a pane, exit, relaunch, expect it where you left it.
- The Editor pane being **absent** for a non-admin (`user`/`user`). The admin case is confirmed; the negative
  case, including `hideEditorFromNonAdmins` stripping the pane from a layout saved by an admin, is not.
- The C1 recovery path — close all four panes, exit, relaunch, expect the default layout rather than a blank
  window. The unit tests cover `LayoutStore`'s half; the SnapFX half is covered only by the reviewer's probe.
- I1's error paths: a busy port 8123, or a cancelled login, should now reach an `Alert` rather than killing the
  process silently.

### Deferred minor findings

Reviewed, judged minor by effect, and deliberately not fixed. Roughly in the order worth doing.

| # | Where | Issue |
|---|-------|-------|
| m7 | `.github/workflows/maven-build.yaml` | The GHCR login step is redundant now the package is public, and would fail a pull request from a fork, where secrets are empty. Delete it |
| m5 | `AuthSessionTest:44` | `anUnreadableTokenYieldsNoRolesRatherThanFailingTheLogin` calls the constructor, not `fromToken`, so the catch block it is named for never runs — the test would pass if that block rethrew. The real path was checked by hand and is correct. Assert on `fromToken` with a three-part token whose payload is not base64 |
| m2 | `PkceLoginFlow:60-61,122` | `authorization_endpoint`, `token_endpoint` and `access_token` are read with unguarded `get()`, so a malformed metadata or token document gives a bare NPE instead of a named reason. Use `path(...)` + `isMissingNode()` |
| m1 | `LoopbackCallbackServer:103-124` | The future completes before the response is flushed and `close()` uses `stop(0)`, which does not drain in-flight exchanges — so on the error paths the browser usually shows a connection reset rather than the "Sign-in failed" page. Complete the future after flushing, or `stop(1)` |
| m4 | `ApiPaths:21-33` | `form()`/`formData()` do not URL-encode the key. Harmless while keys match `^[a-z0-9-]+$`, but a live bug as soon as `FormEditorPane` lets an admin type one: a space throws `IllegalArgumentException` from `URI.create`, and `#` or `?` silently truncates |
| m9 | `StackFixture:21-32` | A failed start is not cached, so a Docker problem repeats the three-container start (2-minute health wait) for every IT class. Also streams backend logs unconditionally, where the spec suggests logs on failure |
| m3 | `ApiException:40-48` | When a problem document parses but `detail` is blank, the `errors` list is discarded and the raw JSON becomes the message. Unreachable against this backend, which always sets `detail` |
| m6 | `LoopbackCallbackServerTest:52-60` | Both catch branches name the port, so asserting only `contains(port)` cannot distinguish the friendly "already in use" message from the generic fallback. Also assert on the text |
| m8 | `scripts/install-snapfx.sh:16-18` | The `.sha256` comes from the same release as the jar it validates, so it catches transfer corruption but not a replaced asset. Hard-code the digest to make the integrity claim in spec 4.4 true |
| m11 | `DockLayoutManager:108-111` | The javadoc says SnapFX drops a node with an unknown id. It substitutes a placeholder node carrying a message — better behaviour, but not what the comment says |
| m12 | nine files | Lines exceed the 100-column limit the project's review skill mandates. Run google-java-format |
| m13 | `Launcher:14-17` | The documented `exec:java` command relies on `exec-maven-plugin`, which the pom neither declares nor pins, so it resolves whatever is latest |

`m10` (an exception from `saveLayout()` in `stop()` producing a crash trace at exit) was fixed in passing as
part of I1.
