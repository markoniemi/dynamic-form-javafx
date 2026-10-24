---
name: dynamic-form-desktop
description: Expert knowledge for the Dynamic Form desktop client (JavaFX, SnapFX, OAuth2 PKCE, Testcontainers). Use this skill when working on client code, fixing bugs, or adding features to the desktop UI or its API layer.
---

# Dynamic Form Desktop

## Overview

A JavaFX fat client for the dynamic-form application. It talks to the dynamic-form backend over REST and **copies
no code from it**: DTOs and the integration-test stack come from published artifacts.

## Technology Stack

- **Language:** Java 21 (`maven.compiler.release` 21). Non-modular — no `module-info.java`, everything on the classpath.
- **UI:** JavaFX 21.0.12, SnapFX 0.8.0 for dockable/floatable panes
- **HTTP:** `java.net.http.HttpClient` behind a hand-written `ApiClient`. No Spring, no Feign, no Retrofit.
- **JSON:** Jackson 2 with `JavaTimeModule`
- **Auth:** hand-rolled OAuth2 authorization code + PKCE (RFC 8252) over a loopback `com.sun.net.httpserver.HttpServer`
- **Models:** `com.example:dynamic-form-api` — the only source of DTOs
- **Testing:** JUnit 5; `com.example:dynamic-form-testcontainers` for integration tests
- **Packaging:** `javafx-maven-plugin` for dev, `jpackage` for the installer

## Project Structure

```
pom.xml                                The install-snapfx profile fetches SnapFX; it is not on Maven Central
src/main/java/com/example/desktop/
  DesktopApp.java                      Application entry: wires services, builds the layout
  auth/                                PkceCodes, LoopbackCallbackServer, BrowserLauncher, AuthSession, PkceLoginFlow
  api/                                 Roles, ProblemDetail, ApiException, ApiClient, FormService, FormDataService
  json/JsonMapperFactory.java          the configured ObjectMapper
  ui/dock/                             LayoutStore, DockLayoutManager — the only package importing org.snapfx
  ui/forms/, ui/data/, ui/common/      panes
  state/                               AppState: observable properties shared between panes
  i18n/                                ResourceBundle keys
src/test/java/com/example/desktop/
  it/                                  StackFixture, TokenClient, and the *IT tests
  Launcher.java                        manual dev entry: starts the stack, then the app
  StackLauncher.java                   holds the stack, publishes target/dev-stack.properties
```

## Development Workflow

### Prerequisite, once per machine

```bash
mvn initialize
```

The `install-snapfx` profile downloads the release jar, checks it against the sha256 pinned in `pom.xml` and
installs it. The profile activates only when the jar is missing, so this is a no-op afterwards. It needs its
own invocation rather than being part of `mvn verify`, because Maven resolves dependencies before it runs any
phase.

Rerun after a SnapFX version bump or a cleaned `~/.m2`. Without it the build cannot resolve
`org.snapfx:snapfx-core`.

### Running

```bash
mvn javafx:run                 # against a backend on http://localhost:8080
mvn test-compile exec:java     # starts the real stack in Docker first (Launcher)

# stack stays up across app restarts (two terminals); Ctrl+C in the first stops it
mvn -q test-compile exec:java@stack
mvn -q test-compile exec:java@app -Dconfig.file=target/dev-stack.properties
```

### Testing

```bash
mvn test      # unit tests only (surefire, *Test). No Docker.
mvn verify    # adds integration tests (failsafe, *IT). Docker required.
```

See the `test-all` skill for the integration-test prerequisites — Docker, free host port 9000, and a pullable
backend image.

## The backend contract

Read `docs/DynamicFormDesktopPlan.md` section 2 for the full table. The parts that trip people up:

- `GET /api/config/oauth2-issuer-uri` returns a bare **`text/plain`** string. Never `readValue` it.
- Success statuses are **not uniform**: `POST /api/forms` → 201, `PUT /api/forms/{key}` → 200,
  `DELETE /api/forms/{key}` → 204, `POST /api/form-data/{key}` → 200,
  `DELETE /api/form-data/submission/{id}` → 200.
- `POST`/`PUT` on form-data take a bare `Map<String,Object>`, not a `FormDataDto`.
- Errors are RFC 7807. A 400 has `detail` = `"Validation failed"` and the field errors under an **`errors`**
  array of `{ field, message, code }`; map `field` onto the control. A **401 has an empty body**.
- `GET /api/form-data` returns own submissions for a user and all for an admin. No filter, no paging, so filter
  client-side. `GET /api/forms` has **no defined order**.
- An admin gets **403** updating another user's submission, though it may read and delete it.
- Seeded forms: `contact`, `feedback`, `survey`. Only `feedback` has a `date` and a `checkbox` field.

### Submission wire format

Nothing in the backend coerces values — the `data` map is JSON in, JSONB in the column, JSON out. Match the
React client exactly: `date` → `"yyyy-MM-dd"` string (`""` when blank), `number` → **string** (`"42"`),
multi-option `checkbox` → JSON array of selected option values, single-option `checkbox` → the value string or
`false`.

## Coding Conventions

- **No DTOs of our own.** Everything comes from `com.example.dynamicform.api`. A DTO that is wrong or will not
  deserialize is fixed in dynamic-form, never with a client-side mixin or a local copy.
- **Jackson needs `JavaTimeModule`.** `FormDataDto.submittedAt` is a `LocalDateTime`. The `@Value` DTOs
  deserialize only because `api/lombok.config` emits `@ConstructorProperties`; that is the api jar's contract,
  not something to work around here.
- **The UI never touches HTTP.** No UI class sees `HttpClient`. API calls run off the FX thread and post results
  back with `Platform.runLater`.
- **Panes talk through `AppState`,** never by referencing each other, so the docking framework can create,
  destroy and re-dock them freely.
- **SnapFX stays in `ui/dock`.** No other package imports `org.snapfx.*`.
- **Validation:** client-side `required` and basic format checks are a convenience; the server is the authority.
  Always surface its `ValidationErrorDto`s against the control named by `field`.
- **Immutability:** prefer immutable value objects; records for small local carriers.
- **Dependency injection by constructor.** No service locators, no statics holding mutable state. `ApiClient`
  takes a `Supplier<String>` token supplier rather than a token, so the session can change underneath it.

## SnapFX notes

The API was read from the released jar; the 0.6.x documentation differs in places.

- `new SnapFX()` → `initialize(Stage)` → `buildLayout()` returning `javafx.scene.Parent`
- `dock(Node, String)` → `DockNode`, or `dock(Node, String, DockElement, DockPosition)`; `DockPosition` is
  `TOP|BOTTOM|LEFT|RIGHT|CENTER`
- `new DockNode(String layoutId, Node content, String title)` is the constructor that sets a **stable id** —
  the two-argument one does not, and stable ids are what layout persistence needs
- **`saveLayout()` returns a `String`**; it does not write a file. `LayoutStore` owns the file at
  `~/.dynamic-form-desktop/layout.json`.
- `loadLayout(String)` throws `DockLayoutLoadException` — catch it and fall back to the default layout
- `setNodeFactory(DockNodeFactory)`; `createNode(String)` returning null is how an unknown id degrades
- The jar ships **no POM**, so its `com.google.gson` requirement is declared directly in our `pom.xml`

## Clean Code Principles (Uncle Bob)

- **Single Responsibility Principle:** Each class and method has one, and only one, reason to change. If a class or method is doing too many things, split it.
- **Open/Closed Principle:** Classes should be open for extension but closed for modification. Prefer adding new behaviour via new classes rather than modifying existing ones.
- **Keep Methods Small:** Methods should do one thing. If a method exceeds ~20 lines, consider breaking it into smaller, focused methods.
- **Meaningful Names:** Use clear, intention-revealing names for classes, methods, and variables. The name should explain the *why*, not just the *what*.
- **Don't Repeat Yourself (DRY):** Avoid duplicating logic. Extract shared behaviour into a utility method or service.
- **Comments Are a Last Resort:** Code should be self-documenting. If a comment is needed, consider refactoring the code to make the intent clearer instead. Javadoc is acceptable only for public API surface or genuinely complex logic — or to record a non-obvious external constraint, such as why a class is loaded without initialising it.
- **Favor Polymorphism Over Conditionals:** When branching on type or state, prefer polymorphism (strategy, visitor, etc.) over long if/else or switch/case chains.
- **Immutability:** Prefer immutable objects, especially for value objects.

## Effective Java (Joshua Bloch)

### Object Creation
- **Prefer static factory methods over constructors** (Item 1): They have names, can return cached instances, and can return subtypes. Use when the constructor intent isn't obvious.
- **Use builders for classes with many parameters** (Item 2): When a constructor or factory has more than 3-4 parameters, use the Builder pattern.
- **Enforce the singleton property with a private constructor or enum** (Item 3): Constants and factory classes get a private constructor; avoid manual singleton patterns.
- **Prefer dependency injection over hardwiring resources** (Item 5): Never create a collaborator inside a class with `new`; inject it through the constructor.

### Methods and APIs
- **Check parameters for validity** (Item 49): Validate method parameters at the start. Throw `IllegalArgumentException` for bad values, `NullPointerException` (or use `Objects.requireNonNull`) for nulls.
- **Return empty collections or optionals, not null** (Item 54/55): Never return `null` for a collection — return `Collections.emptyList()`. For optional single values, return `Optional<T>`.
- **Use `Optional` for optional return values** (Item 55): Return `Optional<T>` from methods that may produce no result. Never return `Optional` from a method that returns a collection.
- **Keep APIs minimal** (Item 57 spirit): Don't expose more than necessary. Prefer package-private or private over public.

### Classes and Interfaces
- **Favor composition over inheritance** (Item 18): Extend only where an is-a relationship is genuine.
- **Design and document for inheritance or prohibit it** (Item 19): If a class is not designed for extension, mark it `final`.
- **Prefer interfaces over abstract classes** (Item 20): Interfaces allow multiple implementations and retrofitting. Use abstract classes only when sharing implementation state.
- **Use interfaces only to define types** (Item 22): Don't use interfaces as constant holders (constant interface anti-pattern) — a `final` class with a private constructor holds constants.
- **Prefer enums over int constants** (Item 34): Use `enum` for any fixed set of named values; add methods to enums rather than switch statements on them elsewhere.

### Generics and Collections
- **Don't use raw types** (Item 26): Always parameterize generic types (`List<String>`, not `List`).
- **Prefer generic methods** (Item 30): Type parameters on methods make APIs safer and cleaner than casting.
- **Use bounded wildcards to increase API flexibility** (Item 31): Producer `? extends T`, consumer `? super T` (PECS).

### Exceptions
- **Use exceptions only for exceptional conditions** (Item 69): Never use exceptions for normal control flow.
- **Use checked exceptions for recoverable conditions, unchecked for programming errors** (Item 70): `RuntimeException` subclasses for bugs; checked exceptions only when the caller can realistically recover.
- **Avoid unnecessary checked exceptions** (Item 71): Prefer unchecked exceptions in most cases.
- **Favor standard exceptions** (Item 72): Use `IllegalArgumentException`, `IllegalStateException`, `NullPointerException`, `UnsupportedOperationException`, `NoSuchElementException` before creating custom ones. `ApiException` exists because an HTTP status plus a problem document is genuinely new information.
- **Document all exceptions thrown by each method** (Item 74): Use `@throws` in Javadoc for checked exceptions; document unchecked ones where useful.
- **Never swallow exceptions silently** (Item 77): At minimum, log the exception. An empty catch block is almost always wrong — the one deliberate exception is `LayoutStore.load`, where an unreadable layout must not stop the app from starting.

### General Programming
- **Minimize the scope of local variables** (Item 57): Declare variables where first used; prefer `final` where possible.
- **Prefer for-each loops over traditional for loops** (Item 58): Use enhanced for loops unless you need the index or need to remove elements.
- **Know and use the standard library** (Item 59): Prefer `java.util`, `java.util.concurrent`, `java.util.stream` and `java.net.http` over hand-rolled equivalents.
- **Prefer primitive types to boxed primitives** (Item 61): Be aware of unboxing NPE risk — `FormDataDto.getId()` returns a boxed `Long`.
- **Avoid strings where other types are more appropriate** (Item 62): Don't use `String` for structured data. The one place strings are unavoidable is the submission `data` map, whose shape the backend does not constrain.
- **Beware the performance of string concatenation** (Item 63): Use `StringBuilder` for building strings in loops.
- **Refer to objects by their interfaces** (Item 64): Declare variables and parameters as the interface type (`List<String> list = new ArrayList<>()`, not `ArrayList<String>`).
