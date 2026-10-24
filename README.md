# dynamic-form-desktop

A JavaFX 21 desktop client for the [dynamic-form](../dynamic-form) application. It reuses the
dynamic-form backend unchanged and copies no code from it: the backend's DTOs and its test stack
arrive as published artifacts, and the backend itself runs as a container image.

| Artifact | Registry | Used for |
|----------|----------|----------|
| `com.example:dynamic-form-api` | Repsy Maven | Shared DTOs. The client defines none of its own |
| `com.example:dynamic-form-testcontainers` | Repsy Maven | `DynamicFormStack`: backend + auth server |
| `ghcr.io/markoniemi/dynamic-form` | GHCR | Backend image, started by the testcontainers jar |

One property in `pom.xml`, `dynamic-form.version`, pins all three: api `X` runs against image `X`.
Both registries are public for reading, so no credentials and no `docker login` are needed.

The design, the backend contract and the recorded decisions live in
[docs/plans/DynamicFormDesktopPlan.md](docs/plans/DynamicFormDesktopPlan.md).

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker, for the integration tests and for both launchers. Host port 9000 must be free: the auth
  server runs on a fixed port because the `iss` claim has to match what the backend expects.

## Build

SnapFX is not on Maven Central, so install it into the local repository once before the first
build:

```bash
mvn initialize
```

The `install-snapfx` profile downloads the release jar, checks it against a pinned sha256 and
installs it. It activates only while the jar is missing, so later builds never touch the network.
Run it again after a SnapFX version bump or a cleaned `~/.m2`.

It needs its own invocation — `mvn verify` alone cannot do it. Maven resolves a project's
dependencies before it runs any phase, so a profile bound to `initialize` cannot repair a
dependency that is already missing when resolution happens.

Then:

```bash
mvn verify          # unit tests, integration tests against the real backend, format check
mvn test            # unit tests only (*Test), no Docker needed
mvn spotless:apply  # fix formatting; spotless:check runs as part of verify
```

Tests split by name: `*Test` runs under surefire, `*IT` under failsafe against containers started
by `DynamicFormStack`. UI tests run headless under Monocle; the system properties that select it
are set by the build, not expected on the command line.

To select several integration tests, use the comma form — `-Dit.test='ShellIT,StartupIT'`. The
`A+B` form matches nothing.

## Running the desktop app

There are two Docker-backed ways to run it, both from `src/test` because the Testcontainers
dependency is test-scoped.

### Launcher — one command, containers die with the app

Starts the stack in Docker, points the app at it, and hands over. Best for a quick look.

```bash
mvn -q test-compile exec:java
```

The containers stop when the app exits, so every restart pays the container startup again.

### StackLauncher — containers stay up across app restarts

Two terminals. The first holds the stack and nothing else:

```bash
mvn -q test-compile exec:java@stack
```

The backend's host port is random and `DynamicFormStack` has no reuse, so a second process cannot
guess the URL. `StackLauncher` writes it to `target/dev-stack.properties`, in the shape
`DesktopConfig` already reads, and prints the exact command to run. In the second terminal:

```bash
mvn -q test-compile exec:java@app -Dconfig.file=target/dev-stack.properties
```

Quitting the app leaves the containers up, so the next start is fast. `Ctrl+C` in the first
terminal stops the stack and deletes `target/dev-stack.properties`, so a dead URL cannot outlive
the containers it pointed at.

### Against a backend you are already running

`exec:java@app` starts the app alone and no containers, so it works against any reachable backend:

```bash
mvn -q test-compile exec:java@app -Dbackend.url=http://localhost:8080
```

That is the same execution the StackLauncher flow uses; only the source of `backend.url` differs.
Without the flag it falls back to the config file and then to `http://localhost:8080`.

### From an IDE

Point the run configuration at **`com.example.desktop.DesktopLauncher`**, not `DesktopApp`.

The `java` launcher refuses a main class that extends `Application` unless the JavaFX modules are
on the module path, and this project keeps JavaFX as ordinary classpath jars:

```
Error: JavaFX runtime components are missing, and are required to run this application
```

`DesktopLauncher` does not extend `Application`, which is all that check looks at, so no module
path and no `--add-modules` are needed. The test-scoped `Launcher` and `StackLauncher` work in an
IDE for the same reason.

Settings go in **VM options**, before the main class: `-Dconfig.file=target/dev-stack.properties`
or `-Dbackend.url=...`. Anything after the main class is a program argument and is ignored.

`javafx-maven-plugin` is configured with `DesktopApp` as its `mainClass`, but `mvn javafx:run` has
not been used here and the plugin's own dependencies are not in the local repository, so the first
attempt needs network access.

### Signing in

The app opens on a sign-in screen. Clicking **Sign in** opens the system browser for an OAuth2
authorization code flow with PKCE — there is no username/password dialog, because OAuth 2.1
removed the password grant and the authorization server does not implement it.

With either launcher, `admin`/`admin` shows the form editor pane; `user`/`user` does not.

## Configuration

An optional properties file, read at startup. The app looks at `-Dconfig.file=<path>` if set,
otherwise `~/.dynamic-form-desktop/config.properties`, otherwise the defaults. A system property of
the same name wins over the file, which is what makes `-Dbackend.url=...` and both launchers work.

| Key | Default | Meaning |
|-----|---------|---------|
| `backend.url` | `http://localhost:8080` | Backend base URL; must be an absolute http/https URI |
| `locale` | the JVM default | Language tag, e.g. `en`. Only the base bundle ships today, so another tag falls back to it |
| `layout.file` | `~/.dynamic-form-desktop/layout.json` | Where the dock layout is saved |

Three keys and no more. The OAuth2 callback port is deliberately not one of them: the client
registration the backend publishes is a fixed `http://localhost:8123/callback`, and the redirect
URI must match byte for byte in both the authorization and the token request.

Nothing in config loading throws — a config problem must never be the reason the window does not
appear. Unknown or invalid entries are ignored and named in the status bar instead.

## Project layout

A single-module, non-modular Maven project (classpath, no `module-info.java`).

| Package | Holds |
|---------|-------|
| `com.example.desktop` | `DesktopApp` (the `Application`), `SessionPanes` |
| `com.example.desktop.api` | `ApiClient`, `FormService`, `FormDataService` |
| `com.example.desktop.auth` | `PkceLoginFlow`, `AuthSession` |
| `com.example.desktop.config` | `DesktopConfig` |
| `com.example.desktop.state` | `AppState`, the only channel between panes |
| `com.example.desktop.i18n` | `Messages`, over `src/main/resources/i18n/messages.properties` |
| `com.example.desktop.ui.common` | The shell — `MainMenuBar`, `StatusBar`, `LoginPane` — and shared widgets |
| `com.example.desktop.ui.forms`, `…ui.data` | The four panes: form list, form fill, form editor, submissions |
| `com.example.desktop.ui.field` | `FieldControlFactory`: one control per field type |
| `com.example.desktop.ui.dock` | `DockLayoutManager` and everything SnapFX |

Three rules worth knowing before changing anything:

- **No DTOs of our own.** Every model comes from `com.example.dynamicform.api`. A DTO that is wrong
  gets fixed in dynamic-form, not with a client-side mixin or a local copy.
- **The UI never touches HTTP.** UI classes never see `HttpClient`. Every API call runs off the FX
  thread and posts its result back with `Platform.runLater`. Panes communicate through `AppState`,
  never by referencing each other, so the docking framework can create, destroy and re-dock them
  freely.
- **SnapFX stays in one package.** All `org.snapfx.*` imports live in `com.example.desktop.ui.dock`.
  SnapFX is a preview library and is not on Maven Central; isolating it keeps a swap cheap.
