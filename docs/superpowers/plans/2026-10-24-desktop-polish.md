# Dynamic Form Desktop Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close spec phase 7 for a demo project — every user-visible string in the bundle, the seven live findings from the foundation review fixed, formatting enforced by the build rather than by review, and the lost panes-review findings rebuilt — so the codebase is complete and clean.

**Architecture:** No new components and no restructuring. Each task is a contained fix to an existing class with the test that proves it, plus one build-level addition (`spotless`) that makes the style rule mechanical. The panes, the API layer and the docking shell keep their current interfaces.

**Tech Stack:** Java 21, JavaFX 21.0.12, JUnit 5.14.4, TestFX 4.0.18, `spotless-maven-plugin` with `google-java-format`, `exec-maven-plugin`. No new production dependency.

**Spec:** `docs/plans/DynamicFormDesktopPlan.md` phase 7, with two parts deliberately dropped — see *Scope* below. The deferred findings this plan closes are listed in `docs/superpowers/plans/2026-10-19-desktop-foundation.md` under *Deferred minor findings*.

## Scope

Phase 7 in the spec reads "i18n, error/busy UX, `jpackage` installer, CI workflow". What this plan does and does not cover, decided with the project owner on 2026-10-24:

- **No `jpackage` installer.** This is a demo project; nobody installs it on a machine the owner does not control. The spec's phase 7 exit check ("installer runs on clean Windows VM") is therefore void and is replaced by the exit check at the end of this plan.
- **No CI additions.** The remaining phase 7 CI work was the installer's. The workflow already has its `push`, `pull_request` and `repository_dispatch` triggers, the SnapFX step and `mvn -B clean verify`, and its GHCR login was removed on 2026-10-24.
- **No renames or restructuring.** `FormApi` → `FormService`, moving `ApiPaths` into the services, the config file and renaming `App` are the spec's *Further development* section and stay deferred. Note the spec argues both ways on the service names: section 6 says not to use them, reasoning about the shared api jar, not about the client's own package. Settle that there, not here.
- **Error and busy UX is already in place** and needs no task: all four panes drive `BusyOverlay` and surface failures through `ErrorBanner`, `showMessage` or `errorText`. What is left of that phase 7 item is the i18n gap in Task 1.

## Global Constraints

Everything in the foundation and panes plans' Global Constraints still holds. In addition:

- **No new production dependency.** `spotless-maven-plugin` and `exec-maven-plugin` are build plugins, not dependencies.
- **No behaviour change where a finding is about wording or formatting.** A reformat that alters a string a test asserts on is a defect, not a reformat.
- **User-visible strings come from the `Messages` bundle, never inline literals** — the rule the panes plan set, now applied to the four places that still break it.
- `Messages.get` returns the key itself for a missing key, so a typo is visible and never fatal. Keep that; Task 1 adds the test that makes a missing key visible in CI rather than on screen.
- The wire format is fixed and must not be "improved" (spec section 2).
- `dynamic-form.version` stays one property for api jar, testcontainers jar and image.

## Review Focus

Input classes these fixes imply that nothing currently exercises. Each has its test added to the owning task.

1. **A key the code asks for that the bundle does not have** — `Messages.get` returns the key, so the user reads `form.submit` on a button instead of "Submit". Nothing fails the build today. → Task 1.
2. **OIDC metadata without `authorization_endpoint`, or a token response without `access_token`** — a wrong issuer URL, or an auth server answering an error document with 200. Today a bare `NullPointerException` instead of a named reason. → Task 2.
3. **The browser closing, or the user cancelling, before the callback response is flushed** — the future completes first and `stop(0)` does not drain, so the browser shows a connection reset where a "Sign-in failed" page was written. → Task 3.
4. **A 400 problem document whose `detail` is blank but whose `errors` array is populated** — the field errors are discarded and the raw JSON becomes the message, so a rejected form shows the user a JSON blob and marks no control. → Task 4.
5. **Docker down, or host port 9000 already bound** — the failed stack start is not cached, so every `*IT` class repeats the three-container start and its 2-minute health wait before failing the same way. → Task 6.

---

## File Structure

```
pom.xml                                            Tasks 7, 8  exec and spotless plugins
src/main/resources/i18n/messages.properties        Task 1  four keys the code still inlines
src/main/java/com/example/desktop/
  App.java                                         Task 1  window title, sign-in alert
  ui/field/FieldControlFactory.java                Task 1  unknown-type prompt
  auth/PkceLoginFlow.java                          Task 2  guarded metadata and token reads
  auth/LoopbackCallbackServer.java                 Task 3  flush before completing; drain on close
  api/ApiException.java                            Task 4  keep errors when detail is blank
src/test/java/com/example/desktop/
  i18n/MessagesTest.java                           Task 1  every key the code asks for exists
  auth/PkceLoginFlowTest.java                      Task 2  created: malformed metadata and token
  auth/LoopbackCallbackServerTest.java             Tasks 3, 5  flush order; the m6 assertion
  api/ProblemDetailMappingTest.java                Task 4
  it/StackFixture.java                             Task 6  cache a failed start; logs on failure
```

---

### Task 1: Every user-visible string in the bundle

**Files:**
- Modify: `src/main/java/com/example/desktop/App.java:58,128,202`
- Modify: `src/main/java/com/example/desktop/ui/field/FieldControlFactory.java:113`
- Modify: `src/main/resources/i18n/messages.properties`
- Test: `src/test/java/com/example/desktop/i18n/MessagesTest.java`

**Interfaces:**
- Consumes: `Messages.get(String)`, `Messages.format(String, Object...)`.
- Produces: four bundle keys — `app.title`, `app.titleAdmin`, `login.failed`, `field.unsupportedType`. `app.titleAdmin` and `field.unsupportedType` take one `MessageFormat` argument (`{0}`).

The four literals are the only ones left: `"Dynamic Form Desktop"` (twice, once with `" (admin)"` appended), `"Cannot sign in"`, and `"Unsupported field type: " + field.getType()`. Build the admin title with `Messages.format("app.titleAdmin", Messages.get("app.title"))` rather than concatenating, so a translation can put the marker first.

- [ ] **Step 1: Write the failing tests**

```java
  @Test
  void theKeysTheApplicationAsksForAllExist() {
    // Messages.get returns the key when it is missing, by design — a typo must never be fatal.
    // The cost is that a missing key reaches the user as "form.submit" on a button, and nothing
    // notices. This is where it gets noticed.
    for (String key :
        List.of(
            "app.title",
            "app.titleAdmin",
            "login.failed",
            "field.unsupportedType",
            "form.submit",
            "common.error",
            "navigation.forms",
            "navigation.submissions",
            "navigation.createForm")) {
      assertNotEquals(key, Messages.get(key), key + " is missing from the bundle");
    }
  }

  @Test
  void theAdminTitleIsBuiltByTheBundleRatherThanByConcatenation() {
    assertTrue(Messages.format("app.titleAdmin", "Dynamic Form Desktop").contains("Dynamic Form Desktop"));
    assertNotEquals(
        "app.titleAdmin", Messages.format("app.titleAdmin", "x"), "the key must resolve");
  }

  @Test
  void theUnsupportedTypeMessageNamesTheType() {
    assertTrue(Messages.format("field.unsupportedType", "signature").contains("signature"));
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dtest=MessagesTest test`
Expected: FAIL — `app.title`, `app.titleAdmin`, `login.failed` and `field.unsupportedType` resolve to themselves.

- [ ] **Step 3: Add the four keys and replace the literals**

Add the keys to `messages.properties` beside the existing `navigation`/`form` groups. Then replace each literal with the bundle call. `FieldControlFactory` uses `Messages.format("field.unsupportedType", field.getType())`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dtest=MessagesTest,FieldControlFactoryTest test`
Expected: PASS. `FieldControlFactoryTest` is included because its unknown-type case asserts on the placeholder control, which now gets its prompt from the bundle.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop src/main/resources/i18n \
  src/test/java/com/example/desktop/i18n
git commit -m "Move the last four user-visible literals into the bundle; test that every key resolves"
```

---

### Task 2: PkceLoginFlow reads metadata and tokens defensively (m2)

**Files:**
- Modify: `src/main/java/com/example/desktop/auth/PkceLoginFlow.java:60,61,122`
- Test: `src/test/java/com/example/desktop/auth/PkceLoginFlowTest.java` (create)

**Interfaces:**
- Consumes: nothing new.
- Produces: no API change. `login()` throws `IllegalStateException` naming the missing field instead of `NullPointerException`.

`authorization_endpoint`, `token_endpoint` and `access_token` are read with unguarded `get(...).asText()`. A wrong issuer, a server answering an error document with 200, or a trimmed discovery document gives a bare NPE with nothing to act on. Use `path(...)`, check `isMissingNode()`, and throw naming both the field and the document it was missing from. Step 1 covers Review Focus item 2.

There is no unit test for this class today (`PkceLoginFlowIT` covers the happy path against the real server). The new test stubs a local `HttpServer`, as `ApiClientTest` does, so a malformed document needs no container.

- [ ] **Step 1: Write the failing tests**

```java
class PkceLoginFlowTest {

  @Test
  void metadataWithoutAnAuthorizationEndpointSaysSoRatherThanThrowingNpe() {
    // A wrong issuer URL, or an auth server that answers an error document with status 200.
    stubIssuer("{\"token_endpoint\":\"http://localhost:9000/oauth2/token\"}");

    Exception e = assertThrows(Exception.class, () -> flow().login());

    assertFalse(e instanceof NullPointerException, "a bare NPE names nothing the user can fix");
    assertTrue(e.getMessage().contains("authorization_endpoint"), e.getMessage());
  }

  @Test
  void metadataWithoutATokenEndpointSaysSo() {
    stubIssuer("{\"authorization_endpoint\":\"http://localhost:9000/oauth2/authorize\"}");

    Exception e = assertThrows(Exception.class, () -> flow().login());

    assertTrue(e.getMessage().contains("token_endpoint"), e.getMessage());
  }

  @Test
  void aTokenResponseWithoutAnAccessTokenSaysSo() {
    // The server answered 200 with something that is not a token response.
    stubIssuer(bothEndpoints());
    stubToken("{\"error\":\"invalid_grant\"}");

    Exception e = assertThrows(Exception.class, () -> flow().login());

    assertTrue(e.getMessage().contains("access_token"), e.getMessage());
  }
}
```

The test needs a `BrowserLauncher` that drives the stubbed authorize endpoint instead of opening a window; `PkceLoginFlowIT` already has that shape — read it rather than inventing one.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dtest=PkceLoginFlowTest test`
Expected: FAIL with `NullPointerException` on the first two, and on the third.

- [ ] **Step 3: Guard the three reads**

Replace each `get(name).asText()` with a `path(name)` read that throws `IllegalStateException` naming the field when `isMissingNode()` or the text is blank.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dtest=PkceLoginFlowTest test`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the login IT to prove the happy path still works**

Run: `mvn -Dit.test=PkceLoginFlowIT verify`
Expected: PASS — the guards must not change the working flow.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/desktop/auth/PkceLoginFlow.java \
  src/test/java/com/example/desktop/auth/PkceLoginFlowTest.java
git commit -m "Name the missing field when OIDC metadata or a token response is malformed"
```

---

### Task 3: The callback page reaches the browser (m1)

**Files:**
- Modify: `src/main/java/com/example/desktop/auth/LoopbackCallbackServer.java:105,144`
- Test: `src/test/java/com/example/desktop/auth/LoopbackCallbackServerTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: no API change.

`callback.complete(params)` runs at line 105, before the response is written at line 119, and `close()` uses `stop(0)`, which does not drain in-flight exchanges. So on the error paths the caller tears the server down while the response is still unsent and the browser shows a connection reset instead of the page that was written for it. Complete the future after the response is flushed, and use `stop(1)`. Step 1 covers Review Focus item 3.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void theBrowserGetsTheFailurePageAndNotAConnectionReset() {
    // The future completing first lets the caller close the server mid-response.
    try (LoopbackCallbackServer server = new LoopbackCallbackServer(0)) {
      HttpResponse<String> browserResponse = get(server.port(), "/callback?error=access_denied&state=s1");

      assertEquals(400, browserResponse.statusCode());
      assertFalse(browserResponse.body().isBlank(), "the browser must receive the page, not a reset");
    }
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=LoopbackCallbackServerTest test`
Expected: FAIL — an `IOException` from the request, or an empty body.

- [ ] **Step 3: Flush before completing, and drain on close**

Move the `callback.complete(...)` call after the response body is written and the exchange closed, on both the success and the error path. Change `stop(0)` to `stop(1)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dtest=LoopbackCallbackServerTest test`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/auth/LoopbackCallbackServer.java \
  src/test/java/com/example/desktop/auth/LoopbackCallbackServerTest.java
git commit -m "Send the callback page before completing the future; drain in-flight exchanges on close"
```

---

### Task 4: A blank problem detail keeps its field errors (m3)

**Files:**
- Modify: `src/main/java/com/example/desktop/api/ApiException.java:36-50`
- Test: `src/test/java/com/example/desktop/api/ProblemDetailMappingTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: no API change. `ApiException.from` keeps `getErrors()` populated when `detail` is blank but `errors` is not.

`from` only trusts a parsed problem document when `detail` is non-blank; otherwise it falls through and makes the raw JSON the message, discarding the `errors` list. Unreachable against today's backend, which always sets `detail` — but it is the branch that decides whether a rejected form marks its controls or shows the user a JSON blob, and `FormFillPane` routes on `getErrors()`. Step 1 covers Review Focus item 4.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void errorsSurviveAProblemDocumentWithNoDetail() {
    String body =
        """
        {"status":400,"errors":[{"field":"title","message":"Title is required","code":"NotBlank"}]}""";

    ApiException e = ApiException.from(400, body, JsonMapperFactory.create());

    assertEquals("title", e.getErrors().get(0).getField());
    assertFalse(e.getDetail().isBlank(), "a blank detail still needs a readable message");
    assertFalse(e.getDetail().contains("{"), "the raw JSON is not a message for a user");
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ProblemDetailMappingTest test`
Expected: FAIL — `getErrors()` is empty and the detail is the raw JSON.

- [ ] **Step 3: Keep the errors and fall back to the status reason**

Treat a parsed document as usable when it has either a `detail` or a non-empty `errors` list, using `reasonFor(status)` as the message when `detail` is blank.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dtest=ProblemDetailMappingTest,ApiClientTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/api/ApiException.java \
  src/test/java/com/example/desktop/api/ProblemDetailMappingTest.java
git commit -m "Keep field errors when a problem document has no detail"
```

---

### Task 5: The busy-port test can fail (m6)

**Files:**
- Test: `src/test/java/com/example/desktop/auth/LoopbackCallbackServerTest.java:52-60`

**Interfaces:** none. Test-only.

`aBusyPortGivesAMessageNamingThePortNotABareBindException` asserts only that the message contains the port number. Both the friendly "already in use" message and the generic fallback name the port, so the test passes either way and cannot fail for the reason it is named for. Assert on the wording too, and on the message not being a bare `BindException`.

- [ ] **Step 1: Strengthen the assertion and watch it hold**

Add assertions that the message names the port **and** says the port is in use, in whatever words the production message uses — read `LoopbackCallbackServer`'s constructor for the exact text rather than guessing.

- [ ] **Step 2: Prove the test can fail**

Temporarily replace the friendly message in `LoopbackCallbackServer` with the raw exception's message, run the test, and confirm it now fails. Restore the message.

Run: `mvn -Dtest=LoopbackCallbackServerTest test`
Expected: FAIL while the message is raw, PASS once restored. A test never seen failing proves nothing — this task exists because of exactly that.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/example/desktop/auth/LoopbackCallbackServerTest.java
git commit -m "Assert the busy-port message says the port is in use, not merely that it names it"
```

---

### Task 6: A failed stack start fails fast (m9)

**Files:**
- Modify: `src/test/java/com/example/desktop/it/StackFixture.java:21-32`

**Interfaces:**
- Consumes: `DynamicFormStack` from `dynamic-form-testcontainers`.
- Produces: `backendUrl()` and `authUrl()` unchanged. A start that failed once throws the same cause for every later caller without retrying.

`stack()` assigns the field only after `start()` returns, so a Docker problem or a busy port 9000 leaves it null and the next `*IT` class repeats the whole three-container start and its 2-minute health wait. With seven IT classes that is a quarter of an hour to be told the same thing seven times. Cache the failure and rethrow it. Also attach the log consumer only when a start fails, which is what the spec suggests — today every run prints the backend's log. Step 1 covers Review Focus item 5.

- [ ] **Step 1: Write the failing test**

```java
class StackFixtureTest {

  @Test
  void aStartThatFailedOnceIsNotRetried() {
    // No Docker needed: the fixture is asked to start against a deliberately impossible
    // configuration, and what is under test is that the second call does not try again.
    AtomicInteger starts = new AtomicInteger();

    Exception first = assertThrows(Exception.class, () -> StackFixture.startCounting(starts::incrementAndGet));
    Exception second = assertThrows(Exception.class, () -> StackFixture.startCounting(starts::incrementAndGet));

    assertEquals(1, starts.get(), "the second caller must get the cached failure");
    assertSame(first.getCause(), second.getCause());
  }
}
```

`startCounting` is a package-private seam taking the start action, so the test needs no Docker. If introducing that seam looks worse than the finding, cache the failure and verify by inspection instead — say so in the commit message rather than leaving a test that cannot fail.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=StackFixtureTest test`
Expected: FAIL — `startCounting` does not exist, and the current code starts twice.

- [ ] **Step 3: Cache the failure and move the logs to the failure path**

Hold the thrown cause in a field and rethrow it on every later call. Attach `withLogConsumer` only when the start throws, so a green run is quiet.

- [ ] **Step 4: Run the test, then the full IT suite**

Run: `mvn -Dtest=StackFixtureTest test` then `mvn clean verify`
Expected: PASS, and every `*IT` still green — the fixture is what they all depend on.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/example/desktop/it/StackFixture.java \
  src/test/java/com/example/desktop/it/StackFixtureTest.java
git commit -m "Cache a failed stack start instead of repeating it per IT class; print backend logs only on failure"
```

---

### Task 7: The documented launch command is pinned (m13)

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Produces: `exec-maven-plugin` declared and pinned, with `mainClass` and `classpathScope` configured, so the documented command is `mvn test-compile exec:java`.

`Launcher`'s javadoc, the panes plan's exit check and the project skill all document an `exec:java` command, but the pom neither declares nor pins `exec-maven-plugin`, so Maven resolves whatever is newest the first time anyone runs it. Declare it with the `mainClass` and `classpathScope` already configured, so the documented command shortens to `mvn test-compile exec:java`.

- [ ] **Step 1: Declare and configure the plugin**

Add `org.codehaus.mojo:exec-maven-plugin` with a pinned `${exec-maven-plugin.version}` property, configured with `com.example.desktop.Launcher` as `mainClass` and `test` as `classpathScope`.

- [ ] **Step 2: Verify the command runs**

Run: `mvn -q test-compile exec:java`
Expected: the stack starts and the window appears. Docker must be running. Close the window to end it.

This is the only step in the plan that needs a human at the screen. If Docker is unavailable, record that it was not verified rather than claiming it.

- [ ] **Step 3: Update the three places that document the long command**

`src/test/java/com/example/desktop/Launcher.java`, `.claude/skills/dynamic-form-desktop/SKILL.md` and the panes plan's exit check.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/test/java/com/example/desktop/Launcher.java .claude/skills docs
git commit -m "Declare and pin exec-maven-plugin; shorten the documented launch command"
```

---

### Task 8: Formatting enforced by the build (m12)

**Files:**
- Modify: `pom.xml`
- Modify: every file `spotless` reformats (about 40 long lines across 23 files)

**Interfaces:**
- Produces: `mvn spotless:check` bound to the build, failing on a file that is not google-java-format clean; `mvn spotless:apply` fixes.

The project's review skill mandates Google Java Style with a 100-column limit, and 23 files break it — including files this plan touches. Reviewing style by hand is what let that happen, so make it mechanical.

**Decision:** bind `spotless:check` to the `verify` phase, not `validate`. A formatting failure must not stop a developer from running tests; CI runs `verify` and will catch it.

- [ ] **Step 1: Add the plugin and watch the check fail**

Add `com.diffplug.spotless:spotless-maven-plugin` with a pinned version, `<googleJavaFormat/>` for `java`, bound to `verify`.

Run: `mvn spotless:check`
Expected: FAIL, listing the files that violate the format. Record the count — it is the before number.

- [ ] **Step 2: Reformat**

Run: `mvn spotless:apply`

- [ ] **Step 3: Prove the reformat changed no behaviour**

Run: `mvn clean verify`
Expected: PASS with the same test counts as before the reformat. google-java-format rewraps string concatenations, so a changed assertion message is possible — if a test fails, the reformat broke it and the fix belongs in the test, not in a `spotless` exclusion.

- [ ] **Step 4: Commit the plugin and the reformat separately**

Two commits, because a reformat touching 23 files makes a review of anything else impossible.

```bash
git add pom.xml
git commit -m "Enforce google-java-format with spotless at verify"
git add src
git commit -m "Reformat with google-java-format; no behaviour change"
```

---

### Task 9: Rebuild the lost panes-review findings

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-desktop-panes.md` (its *Deferred minor findings* section)

**Interfaces:** none. This task produces a written list, not code.

Commit `0892f7c` states "Eleven minors remain deferred, listed in the ledger". No such ledger is in the repository — the list existed only in the session that produced it. The panes plan's outcome section records that the count is the only surviving record and that a fresh review is the way back.

- [ ] **Step 1: Review the panes for the findings that were lost**

Review `src/main/java/com/example/desktop/ui/` — the four panes, `ui/field`, `ui/common`, `ui/dock` — against the project's review skill. Use `dynamic-form-desktop-review`, or `/code-review`.

- [ ] **Step 2: Write the findings into the panes plan**

Replace the "that ledger was never written" paragraph with a table in the same shape as the foundation plan's: number, where, issue, roughly in the order worth doing. Note that the count need not come to eleven — this is a fresh review, not a recovery, and the four minors `0892f7c` fixed are already gone.

- [ ] **Step 3: Fix anything the review rates critical or important**

A finding at that level is not deferred material: fix it with its test, as its own commit, following this plan's task shape. A minor goes in the table.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-10-21-desktop-panes.md
git commit -m "Rebuild the panes review's deferred findings as a written ledger"
```

---

## Exit check for the whole plan

Run: `mvn clean verify`
Expected: every unit test and every `*IT` green from a clean checkout, given Docker is running and `mvn initialize` has been run once, with `spotless:check` passing as part of it.

This replaces phase 7's "installer runs on clean Windows VM", which the dropped installer makes void. Phase 7 is complete when:

- no user-visible string is an inline literal, and a missing bundle key fails the build rather than reaching the screen;
- the seven live foundation findings are closed (`m1`, `m2`, `m3`, `m6`, `m9`, `m12`, `m13`, with `m5` already fixed and `m4`, `m7`, `m8`, `m10`, `m11` closed earlier);
- the panes review's findings exist in writing again;
- formatting is enforced mechanically.

Then launch by hand once, with a saved layout already in place — the case that broke before:

```
mvn -q test-compile exec:java
```

## Carried-forward notes for the next plan

- **The `jpackage` installer**, if this ever stops being a demo. It needs a target-OS decision (spec open question 2), and one runner per platform.
- **The *Further development* section**: the `FormApi` → `FormService` renames, moving `ApiPaths` into the services, the config file, renaming `App`, and a launcher that keeps the stack up in the background. Resolve the spec's own contradiction about the service names first — section 6 forbids them, reasoning about the api jar rather than the client.
- **Spec open questions 1, 3 and 4** are untouched: Cognito support, remembering a login across restarts, and whether CI pins a release or floats `-SNAPSHOT`.
- **Multiple fill panes** (`form-fill:{formKey}`) and `floatNode` for "open in window" — spec section 4.4. The node factory still needs the prefix parse when that lands, and `attachPaneContent` in `DockLayoutManager` will need to cover the dynamic ids rather than the four fixed ones.
- **Re-login as a different user does not re-dock the layout.** Whether the editor pane exists is settled when the window is built, so signing back in as someone with other roles leaves the panes as they were.
- The spec has **two `## 8` headings** — *Open questions* and *Further development*. Renumbered: *Further development* is now section 9, and the spec's own section 9 records what became of the list.
