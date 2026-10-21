---
name: dynamic-form-desktop-review
description: "Review Java code in the desktop client against this project's standards (Google Java Style, JavaFX threading, the published-DTO rule, SnapFX isolation). Invoke with a file path or paste code to review."
---

# Dynamic Form Desktop Code Review

Review the specified file against this project's standards. If no file specified, ask user which file to review.

## How to review

1. Read target file(s).
2. Check relevant checklist below.
3. Report findings grouped by severity:
   - **Must fix** — security, correctness, or hard project rules
   - **Should fix** — style or maintainability issues
   - **Consider** — optional improvements
4. For each finding: file path + line number, rule violated, one-line fix.
5. Brief summary paragraph.

Do NOT rewrite whole file. Propose targeted edits only.

---

## Project rules (hard)

- [ ] **No DTOs defined here.** Models come from `com.example.dynamicform.api`. A local copy of a backend DTO,
      or a Jackson mixin patching one, is a must-fix — the fix belongs in dynamic-form
- [ ] **No UI class touches `HttpClient`** or an API class directly performing I/O on the FX thread
- [ ] **Every API call runs off the FX thread** and returns to it via `Platform.runLater`
- [ ] **`org.snapfx.*` is imported only inside `ui/dock`**
- [ ] **No Spring, no Feign, no Retrofit, no `spring-web`** — the HTTP layer is `ApiClient` over `java.net.http`
- [ ] Panes communicate through `AppState`, not by holding references to each other
- [ ] No `module-info.java` — the build is deliberately non-modular

## HTTP and the backend contract

- [ ] Status codes are not assumed uniform: 201 for `POST /api/forms`, 204 for `DELETE /api/forms/{key}`,
      200 for both form-data writes **and** `DELETE /api/form-data/submission/{id}`
- [ ] The issuer endpoint is read as **text**, not JSON
- [ ] Error handling tolerates a **non-JSON or empty body** — a 401 carries no body at all
- [ ] Validation errors are read from the `errors` array of the problem document and mapped onto the control
      named by `field`
- [ ] Submission values keep the React wire format: `date` as `"yyyy-MM-dd"`, `number` as a string,
      multi-option `checkbox` as an array. No coercion in either direction
- [ ] No reliance on the order of `GET /api/forms`, and no assumption that `GET /api/form-data` is filtered or
      paged server-side
- [ ] Tokens are never logged; no credentials or tokens in exception messages

## JavaFX

- [ ] Scene-graph mutation happens on the FX thread only
- [ ] Long work runs in a `Task` or a virtual thread, never inline in an event handler
- [ ] Controls are not created in static initializers — `Control.<clinit>` needs a running toolkit, so a class
      loaded before `Platform.startup` fails with "Toolkit not initialized"
- [ ] Bindings and listeners are removed when a pane is undocked, or use weak listeners, so a re-docked pane
      does not leak
- [ ] `DockNode`s are created with the **three-argument** constructor, so they carry a stable layout id
- [ ] A corrupt, stale or unknown layout degrades to the default layout rather than failing startup

## Style & Formatting

- [ ] Two-space indentation; clear names; no commented-out code
- [ ] Javadoc only for public API, genuinely complex logic, or a non-obvious external constraint
- [ ] Comment density matches the surrounding file

## Architecture

- [ ] Constructor injection; no statics holding mutable state
- [ ] `ApiClient` is given a `Supplier<String>` token supplier, not a token string
- [ ] Configuration comes from a system property or a constant, not a hardcoded literal buried in a method
- [ ] Value objects immutable; records for small local carriers

## Exceptions & Error Handling

- [ ] Standard Java exceptions preferred (`IllegalArgumentException`, `IllegalStateException`,
      `NoSuchElementException`); `ApiException` only for HTTP failures
- [ ] No exception swallowed silently. The one sanctioned exception is `LayoutStore.load`, which must return
      empty rather than fail startup — and it says so in a comment
- [ ] A failure the user caused (a busy callback port, a cancelled login) produces a message naming the cause,
      not a raw `BindException` or a timeout

## Testing

- [ ] JUnit 5; unit tests `*Test`, integration tests `*IT`
- [ ] Test names describe the behaviour, in the form `methodOrSubjectWithStateExpectedBehavior`
- [ ] Unit tests need no Docker; anything needing a container is an `*IT`
- [ ] Assertions are on real behaviour, not on a mock's interactions
- [ ] The stack is started once per test JVM, never per test class — it binds fixed host port 9000
- [ ] A test that must resolve a class without running it uses the three-argument `Class.forName`
- [ ] New behaviour arrived with a test that was watched failing first

## Single Responsibility

- [ ] One reason to change per class/method
- [ ] Methods ≤ ~20 lines; no DRY violations

## Dependencies

- [ ] Nothing added that duplicates the JDK (`java.net.http`, `java.util.stream`)
- [ ] SnapFX use stays behind `ui/dock` so the library can be swapped
- [ ] `dynamic-form.version` pins the api jar, the testcontainers jar and the image together — api `X` runs
      against image `X`
- [ ] Check whether dependencies have newer versions or are unmaintained (`mvn dependency:tree`, Maven Central,
      GitHub activity). SnapFX is a preview: re-check its API on every bump
