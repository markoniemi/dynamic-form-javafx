# Desktop Shell and Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the spec's *Further development* list: the client reads its backend address from a config file, opens on a login screen rather than an unasked-for browser window, arranges its panes the way the owner wants with a three-dots pane menu and a menubar that can bring a closed pane back, shows a status line, and carries the names the owner chose.

**Architecture:** No new layer. One new `config` package holding a single loader, three new classes in `ui/common` (login screen, menubar, status bar), new methods on `DockLayoutManager` for the arrangement and for reopening a pane, and one new class in `ui/dock` that decorates a SnapFX pane header. The window is assembled in the application class as `BorderPane(menubar, dock root, status bar)`. Everything SnapFX stays inside `com.example.desktop.ui.dock`.

**Tech Stack:** Java 21, JavaFX 21.0.12, SnapFX 0.8.0, Jackson 2.22.4, JUnit 5.14.4, TestFX 4.0.18 under Monocle, `dynamic-form-testcontainers` 1.0.0-SNAPSHOT. No new dependency, production or test.

**Spec:** `docs/plans/DynamicFormDesktopPlan.md` — section 9 *Further development*, with the four decisions the owner made on 2026-10-24 recorded in Task 9. Sections 4.2, 4.3 and 4.4 constrain how each item may be built.

## Global Constraints

Everything in the foundation, panes and polish plans' Global Constraints still holds. Repeated here because every task in this plan touches at least one:

- **No DTOs of our own.** Every model comes from `com.example.dynamicform.api`. A client-side mixin or local copy is a defect; fix the DTO in dynamic-form.
- **The UI never touches HTTP.** UI classes never see `HttpClient`. Every API call runs off the FX thread and posts its result back with `Platform.runLater`.
- **Panes communicate through `AppState`, never by referencing each other** — the docking framework creates, destroys and re-docks them freely.
- **All `org.snapfx.*` imports live in `com.example.desktop.ui.dock`.** SnapFX is a preview library and not on Maven Central; isolating it keeps a swap cheap. (Test code in `com.example.desktop.it` already constructs `new org.snapfx.SnapFX()`; do not widen that.)
- **User-visible strings come from the `Messages` bundle, never inline literals.** New keys go below the generated block in `src/main/resources/i18n/messages.properties`, in the "Desktop-only" section, with a comment saying why the React bundle has no key path to mirror.
- **Google Java Style, 100 columns, enforced by `spotless:check` at `verify`.** Run `mvn spotless:apply` before committing.
- **The wire format is fixed** (spec section 2) and must not be "improved": `date` as `"yyyy-MM-dd"`, `number` as a string, a multi-option `checkbox` as a JSON array.
- **The loopback redirect URI stays `http://localhost:8123/callback`** and is *not* configurable. It must be byte-identical to the registration dynamic-form publishes, so `PkceLoginFlow.CALLBACK_PORT` stays a constant (spec 4.2).
- `dynamic-form.version` stays one property for the api jar, the testcontainers jar and the image.
- Write the test first, watch it fail, then implement. Commit per task.

## Review Focus

Input classes these changes imply that nothing currently exercises, most likely to bite first:

1. **A config file that is malformed, unreadable, or full of unknown keys** — a hand-edited `config.properties`. The app must start on the defaults and say which entries it ignored, not die before the window appears. → Task 2.
2. **A blank or unparseable `backend.url`** — a trailing comment, a missing scheme, `localhost:8080` without `http://`. Today that reaches `URI.create` inside `ApiClient` and surfaces as an `IllegalArgumentException` with no mention of the config file. → Task 2.
3. **A layout saved before this change** — every existing user has one. The new default ratios and the new pane order must not be forced onto it; a restored layout keeps the sizes the user dragged. → Task 3.
4. **A relayout that drops SnapFX's cached `DockNodeView`** — any drag, dock, float or reopen. The three-dots menu must come back and must not be added twice, or the header grows a menu button per relayout. → Task 4.
5. **A pane closed, then the app restarted** — reopen must work whether the node is still in the graph as hidden or has gone from the saved layout entirely. → Task 6.

A sixth, covered in Task 7 without its own line here: a background request completing after a sign-out must not write into the next session's panes.

---

## File Structure

**Created**

| File | Responsibility |
|------|----------------|
| `src/main/java/com/example/desktop/config/DesktopConfig.java` | Reads the config file, applies system-property overrides, exposes typed values and a list of ignored entries |
| `src/main/java/com/example/desktop/ui/common/LoginPane.java` | The pre-login screen: a Sign in button, a message line, a busy state |
| `src/main/java/com/example/desktop/SessionPanes.java` | One signed-in session's four panes and its `PaneFactory`, plus the guard that stops a retired session's background task writing to the screen |
| `src/main/java/com/example/desktop/ui/common/StatusBar.java` | One-line status strip bound to `AppState.status()`, plus the signed-in user |
| `src/main/java/com/example/desktop/ui/common/MainMenuBar.java` | File / View / Help menus over callbacks the application supplies |
| `src/main/java/com/example/desktop/ui/dock/PaneHeaderMenu.java` | Hides SnapFX's float button and puts a three-dots `MenuButton` in the pane header; idempotent |
| `src/test/java/com/example/desktop/StackLauncher.java` | Dev launcher that starts the stack, writes its URLs to a properties file and blocks |
| `src/test/java/com/example/desktop/config/DesktopConfigTest.java` | Config loading, overrides, bad input |
| `src/test/java/com/example/desktop/ui/common/LoginPaneTest.java` | Login screen states |
| `src/test/java/com/example/desktop/ui/common/StatusBarTest.java` | Status binding |
| `src/test/java/com/example/desktop/ui/common/MainMenuBarTest.java` | Menu structure and callbacks |
| `src/test/java/com/example/desktop/it/PaneHeaderIT.java` | Header decoration against real SnapFX views |
| `src/test/java/com/example/desktop/SignOutIT.java` | Sign-out and sign-in-again rebuild. In `com.example.desktop`, not `…it`, because it drives `DesktopApp`'s package-visible window states; failsafe matches on the `*IT` name, not the package |

**Renamed**

| From | To |
|------|-----|
| `api/FormApi.java` | `api/FormService.java` |
| `api/FormDataApi.java` | `api/FormDataService.java` |
| `App.java` | `DesktopApp.java` |
| `api/ApiPaths.java` | deleted; constants move into the two services, `ApiClient` and `PkceLoginFlow` |
| `test/.../api/ApiPathsTest.java` | `test/.../api/PathBuildingTest.java` |

**Modified**

- `api/ApiClient.java` — gains `pathSegment(String)`; constructors unchanged.
- `auth/PkceLoginFlow.java` — gains `ISSUER_URI_PATH`; drops the `ApiPaths` import.
- `ui/dock/DockLayoutManager.java` — new default arrangement with split ratios, `reopen`, `resetToDefaults`, hide-on-close, header decoration hook, and three test-facing structure accessors.
- `state/AppState.java` — new `status()` property.
- `ui/forms/FormListPane.java`, `ui/forms/FormFillPane.java`, `ui/data/SubmissionsPane.java` — publish a status message where they already show a banner.
- `src/main/resources/i18n/messages.properties` — new desktop-only keys.
- `pom.xml` — `mainClass` follows the rename; two named `exec-maven-plugin` executions (`app`, `stack`).
- `src/test/java/com/example/desktop/Launcher.java` — follows the rename, reuses the properties writer.
- `docs/plans/DynamicFormDesktopPlan.md` — decisions recorded, section 6 amended, duplicate heading number fixed.
- `.claude/skills/dynamic-form-desktop/SKILL.md`, `README.md` (if it documents the launch command) — the renamed main class and the new commands.

---

### Task 1: The names the owner chose

Mechanical rename with no behaviour change, done first so every later task is written against the final names. Spec section 6 warns against `FormService`/`FormDataService`, but it argues about the shared api jar; these are `com.example.desktop.api` classes and the jar holds only DTOs, so there is no collision. Task 9 records that.

**Files:**
- Rename: `src/main/java/com/example/desktop/api/FormApi.java` → `FormService.java`
- Rename: `src/main/java/com/example/desktop/api/FormDataApi.java` → `FormDataService.java`
- Rename: `src/main/java/com/example/desktop/App.java` → `DesktopApp.java`
- Delete: `src/main/java/com/example/desktop/api/ApiPaths.java`
- Modify: `src/main/java/com/example/desktop/api/ApiClient.java`, `src/main/java/com/example/desktop/auth/PkceLoginFlow.java`
- Modify: `src/main/java/com/example/desktop/ui/forms/FormListPane.java`, `ui/forms/FormFillPane.java`, `ui/forms/FormEditorPane.java`, `ui/data/SubmissionsPane.java`
- Modify: `pom.xml:184` (`javafx-maven-plugin` `mainClass`), `src/test/java/com/example/desktop/Launcher.java:31`
- Rename test: `src/test/java/com/example/desktop/api/ApiPathsTest.java` → `PathBuildingTest.java`
- Modify tests: `it/ConfigIT.java`, `it/FormApiIT.java`, `it/FormDataApiIT.java`, `it/AdminEditorIT.java`, `it/ReadPathIT.java`, `it/SubmissionsIT.java`, `it/PkceLoginFlowIT.java`, `api/ApiClientTest.java`, `api/ProblemDetailMappingTest.java`, `auth/PkceLoginFlowTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `FormService` and `FormDataService` with the method sets `FormApi`/`FormDataApi` have today, unchanged, plus their own path constants and builders:
  - `FormService.FORMS = "/api/forms"`, `public static String path(String formKey)`
  - `FormDataService.FORM_DATA = "/api/form-data"`, `public static String path(String formKey)`, `public static String submissionPath(long id)`
  - `ApiClient.pathSegment(String value)` — `public static String`, the percent-encoder `ApiPaths.segment` is today, javadoc carried over
  - `PkceLoginFlow.ISSUER_URI_PATH = "/api/config/oauth2-issuer-uri"`
  - `DesktopApp` with `public static void main(String[])` and the behaviour `App` has today

- [ ] **Step 1: Move the path tests onto the new owners**

Rename `ApiPathsTest` to `PathBuildingTest` and retarget every assertion. Same cases, same values:

```java
class PathBuildingTest {
  @Test
  void anOrdinaryKeyIsLeftAlone() {
    assertEquals("/api/forms/feedback", FormService.path("feedback"));
    assertEquals("/api/form-data/feedback", FormDataService.path("feedback"));
    assertEquals("/api/form-data/submission/7", FormDataService.submissionPath(7L));
  }
  // aSpaceIsPercentEncodedRatherThanLeftToBreakUriCreate,
  // charactersThatWouldSilentlyTruncateThePathAreEncoded,
  // aSlashCannotEscapeIntoAnotherPathSegment,
  // aPlusStaysAPlusInsteadOfBecomingASpace — unchanged assertions, FormService.path in place of
  // ApiPaths.form and FormDataService.path in place of ApiPaths.formData.
  @Test
  void theEncoderIsReachableForAnyPathSegment() {
    assertEquals("a%20b", ApiClient.pathSegment("a b"));
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -q test -Dtest=PathBuildingTest`
Expected: compilation failure — `FormService`, `FormDataService` and `ApiClient.pathSegment` do not exist.

- [ ] **Step 3: Do the rename**

Rename the two api classes and `App`, move `ApiPaths`' constants and builders onto them, move `segment` to `ApiClient.pathSegment` and `CONFIG_ISSUER_URI` to `PkceLoginFlow.ISSUER_URI_PATH` (its only consumer), delete `ApiPaths`, and update every reference listed under **Files**. `DependenciesTest:26` asserts `com.example.dynamicform.api.ApiPaths` is *absent from the jar* — that assertion stays exactly as it is; it is about the api jar, not about this class.

Prefer `mcp__idea__rename_refactoring` for the three class renames so references and the javafx plugin's `mainClass` string are not missed by hand.

- [ ] **Step 4: Run the whole unit suite**

Run: `mvn -q test`
Expected: PASS, same test count as before plus the one new encoder case.

- [ ] **Step 5: Run the integration suite**

Run: `mvn -q verify`
Expected: PASS. Docker must be running.

- [ ] **Step 6: Follow the rename through the docs**

Update the launch commands and class names in `.claude/skills/dynamic-form-desktop/SKILL.md`, `README.md` and `Launcher`'s javadoc. Leave the three historical plans in `docs/superpowers/plans/` alone — they record what was built at the time.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Rename FormApi and FormDataApi to services; rename App to DesktopApp; move the paths onto their services and delete ApiPaths"
```

---

### Task 2: Config file

**Files:**
- Create: `src/main/java/com/example/desktop/config/DesktopConfig.java`
- Create: `src/test/java/com/example/desktop/config/DesktopConfigTest.java`
- Modify: `src/main/java/com/example/desktop/DesktopApp.java`, `src/main/java/com/example/desktop/i18n/Messages.java`
- Modify: `src/main/resources/i18n/messages.properties`

**Interfaces:**
- Consumes: `DesktopApp` from Task 1.
- Produces:
  - `DesktopConfig.load()` → `DesktopConfig`, reading `-Dconfig.file` if set, else `~/.dynamic-form-desktop/config.properties`, else defaults
  - `DesktopConfig.load(Path file, Properties overrides)` → `DesktopConfig`, public — Task 8's test lives in another package and needs it
  - `String backendUrl()`, `Locale locale()`, `Path layoutFile()`, `List<String> ignored()`
  - `Messages.setLocale(Locale)`

Three keys and no more. `backend.url` (default `http://localhost:8080`), `locale` (default `Locale.getDefault()`), `layout.file` (default `~/.dynamic-form-desktop/layout.json`). The callback port is deliberately not a key — the registration dynamic-form publishes is a fixed `http://localhost:8123/callback` and the URI must match byte for byte (spec 4.2). A system property of the same name wins over the file, so `-Dbackend.url=...` keeps working exactly as it does today and the Testcontainers launchers need no change.

- [ ] **Step 1: Write the failing test**

```java
class DesktopConfigTest {
  @TempDir Path dir;

  @Test
  void aMissingFileGivesTheDefaults() {
    DesktopConfig config = DesktopConfig.load(dir.resolve("absent.properties"), new Properties());
    assertEquals("http://localhost:8080", config.backendUrl());
    assertTrue(config.ignored().isEmpty());
  }

  @Test
  void theFileSuppliesTheBackendUrlAndTheLayoutPath() throws IOException {
    Path file = write("backend.url=http://example.test:9999\nlayout.file=/tmp/l.json\n");
    DesktopConfig config = DesktopConfig.load(file, new Properties());
    assertEquals("http://example.test:9999", config.backendUrl());
    assertEquals(Path.of("/tmp/l.json"), config.layoutFile());
  }

  @Test
  void aSystemPropertyWinsOverTheFile() throws IOException {
    Path file = write("backend.url=http://from-file:1\n");
    Properties overrides = new Properties();
    overrides.setProperty("backend.url", "http://from-property:2");
    assertEquals("http://from-property:2", DesktopConfig.load(file, overrides).backendUrl());
  }

  @Test
  void anUnknownKeyIsIgnoredAndReported() throws IOException {
    Path file = write("backend.url=http://example.test:1\nnonsense=1\n");
    DesktopConfig config = DesktopConfig.load(file, new Properties());
    assertEquals("http://example.test:1", config.backendUrl());
    assertEquals(List.of("nonsense"), config.ignored());
  }

  @Test
  void aBackendUrlWithoutASchemeFallsBackToTheDefaultAndIsReported() throws IOException {
    // Reaches URI.create inside ApiClient otherwise, as an IllegalArgumentException that never
    // mentions the config file.
    DesktopConfig config = DesktopConfig.load(write("backend.url=localhost:8080\n"), new Properties());
    assertEquals("http://localhost:8080", config.backendUrl());
    assertEquals(List.of("backend.url"), config.ignored());
  }

  @Test
  void aBlankBackendUrlFallsBackToTheDefault() throws IOException {
    assertEquals(
        "http://localhost:8080",
        DesktopConfig.load(write("backend.url=   \n"), new Properties()).backendUrl());
  }

  @Test
  void anUnreadableFileDoesNotStopStartup() throws IOException {
    // A directory where a file is expected: Files.newInputStream throws IOException, and a config
    // problem must never be the reason the window does not appear.
    Path asDirectory = Files.createDirectory(dir.resolve("config.properties"));
    DesktopConfig config = DesktopConfig.load(asDirectory, new Properties());
    assertEquals("http://localhost:8080", config.backendUrl());
    assertEquals(List.of("config.file"), config.ignored());
  }

  @Test
  void anUnknownLocaleTagFallsBackToTheSystemLocale() throws IOException {
    DesktopConfig config = DesktopConfig.load(write("locale=not a tag\n"), new Properties());
    assertEquals(Locale.getDefault(), config.locale());
    assertEquals(List.of("locale"), config.ignored());
  }
}
```

`write(String)` is a private helper writing the text to `dir.resolve("config.properties")` and returning the path.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -q test -Dtest=DesktopConfigTest`
Expected: FAIL — `com.example.desktop.config.DesktopConfig` does not exist.

- [ ] **Step 3: Implement `DesktopConfig`**

A final class, private constructor, static factories as in **Interfaces**. Invalid or unknown entries are collected into `ignored()` as key names and never throw; an unparseable `backend.url` means one that is not an absolute `http`/`https` URI. `Locale.forLanguageTag` returns an undefined locale rather than throwing, so treat an empty language as invalid.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=DesktopConfigTest`
Expected: PASS

- [ ] **Step 5: Wire it into `DesktopApp` and `Messages`**

`DesktopApp.start` loads the config once, keeps it in a field, and uses `backendUrl()` where it reads the `backend.url` system property today (both call sites), `layoutFile()` in `layoutStore()`, and `locale()` to call `Messages.setLocale(...)` before the first string is read. `Messages.setLocale(Locale)` re-resolves the bundle; `get` and `format` keep their current signatures and their missing-key behaviour of returning the key.

Add the bundle keys, below the generated block:

```properties
config.ignored=Ignored these config entries: {0}
```

`DesktopApp` passes `String.join(", ", config.ignored())` into that and publishes it through the status bar once Task 5 exists; until then it goes to `System.err`.

- [ ] **Step 6: Run the whole suite**

Run: `mvn -q verify`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Read the backend URL, locale and layout path from a config file; keep system properties winning; report ignored entries instead of failing startup"
```

---

### Task 3: The arrangement the owner asked for

Forms top-left, submissions under them, the form view on the right at roughly two thirds of the width. The editor stays tabbed with the form view. Ratios apply to the default arrangement only — a restored layout keeps whatever the user dragged.

**Files:**
- Modify: `src/main/java/com/example/desktop/ui/dock/DockLayoutManager.java`
- Modify: `src/test/java/com/example/desktop/it/ShellIT.java`

**Interfaces:**
- Consumes: `DockLayoutManager.build(Stage)`, `nodeById(String)`, `currentLayout()` as they are.
- Produces, all SnapFX-free so the isolation rule holds:
  - `public List<String> layoutIdsInOrder()` — the graph's layout ids, depth first, left-to-right then top-to-bottom
  - `public double rootSplitFraction()` — the root split's first divider position, or `-1` when the root is not a split
  - `public Orientation rootOrientation()` — `javafx.geometry.Orientation`, or null when the root is not a split

- [ ] **Step 1: Write the failing tests in `ShellIT`**

```java
@Test
void theDefaultArrangementPutsTheListAndSubmissionsLeftOfTheFormView() {
  DockLayoutManager manager =
      onFx(() -> manager(new LayoutStore(layoutDir.resolve("default.json")), adminState()));
  onFx(() -> manager.build(freshStage()));

  assertEquals(
      List.of(
          DockLayoutManager.FORM_LIST,
          DockLayoutManager.SUBMISSIONS,
          DockLayoutManager.FORM_FILL,
          DockLayoutManager.FORM_EDITOR),
      fxValue(manager::layoutIdsInOrder));
  assertEquals(Orientation.HORIZONTAL, fxValue(manager::rootOrientation));
  assertEquals(0.33, fxValue(manager::rootSplitFraction), 0.02,
      "the form view gets about two thirds of the width");
}

@Test
void aSavedLayoutKeepsItsOwnProportions() {
  // Review Focus 3: every existing user has a layout saved before this change, and the new
  // defaults must not be pushed onto it.
  LayoutStore store = new LayoutStore(layoutDir.resolve("dragged.json"));
  String dragged =
      onFx(
          () -> {
            DockLayoutManager first = manager(store, adminState());
            first.build(freshStage());
            first.setRootSplitFractionForTest(0.80);
            return first.currentLayout();
          });
  store.save(dragged);

  DockLayoutManager second = onFx(() -> manager(store, adminState()));
  onFx(() -> second.build(freshStage()));

  assertEquals(0.80, fxValue(second::rootSplitFraction), 0.02);
}
```

`setRootSplitFractionForTest(double)` is a package-visible method on `DockLayoutManager` standing in for the user dragging a divider; if `DockSplitPane.setDividerPosition` turns out not to survive serialization, assert on `structureOf(...)` equality instead and note it in the task's commit message.

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -q verify -Dit.test=ShellIT -DskipTests`
Expected: FAIL — the new accessors do not exist; once they do, the order assertion fails because `dockDefaults` docks submissions below the *fill* pane today, and the fraction assertion fails because nothing sets a ratio.

- [ ] **Step 3: Rearrange `dockDefaults` and set the ratio**

Dock the list, then the fill pane to its `RIGHT`, then submissions to the `BOTTOM` **of the list** (not of the fill pane, which is what today's code does and what puts submissions under the form), then the editor `CENTER` on the fill pane so the two stay tabbed. Then set the root split's first divider to `0.33`.

`SnapFX.setRootSplitRatios(double...)` returns `false` when it cannot apply the ratios — treat that as a failure worth a `System.err` line, not an exception, and call it after `buildLayout()` if it returns false before. Only `dockDefaults()` may set ratios; `restoreSaved()` must not.

Implement the three accessors over `snapFX.getDockGraph().getRoot()`, walking `DockContainer.getChildren()` and reading `DockSplitPane.getDividerPositions()`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q verify -Dit.test=ShellIT -DskipTests`
Expected: PASS, with the five existing `ShellIT` tests still green — `aLayoutSurvivesSaveAndReload` in particular, since it compares structures.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Arrange the default layout as forms over submissions on the left and the form view at two thirds on the right; leave a restored layout's proportions alone"
```

---

### Task 4: Three-dots pane menu

SnapFX's own float button goes; a `MenuButton` with *Float window* and *Close panel* takes its place. Duplicate panel is deliberately out of scope — it needs the `form-fill:{formKey}` multi-instance ids from spec 4.4 and gets its own plan.

**Files:**
- Create: `src/main/java/com/example/desktop/ui/dock/PaneHeaderMenu.java`
- Modify: `src/main/java/com/example/desktop/ui/dock/DockLayoutManager.java`
- Create: `src/test/java/com/example/desktop/it/PaneHeaderIT.java`
- Modify: `src/main/resources/i18n/messages.properties`

**Interfaces:**
- Consumes: `DockLayoutManager` from Task 3.
- Produces:
  - `PaneHeaderMenu.decorate(SnapFX snapFX, DockNode node)` — package-visible static, idempotent
  - `public void decorateHeaders()` on `DockLayoutManager` — applies it to every docked node, safe to call repeatedly
  - `public static final String MENU_STYLE_CLASS = "pane-menu"` on `PaneHeaderMenu`, the marker that makes the decoration idempotent and findable from a test

The actions call `SnapFX.floatNode(node)` and `SnapFX.close(node)` directly rather than `DockNodeView.setOnFloatRequest`, which SnapFX wires itself through its layout engine.

- [ ] **Step 1: Write the failing test**

```java
class PaneHeaderIT extends UiTest {
  @TempDir Path layoutDir;

  @Test
  void everyPaneHeaderCarriesTheMenuAndNoFloatButton() {
    DockLayoutManager manager = built();

    assertEquals(1, fxValue(() -> manager.headerMenus(DockLayoutManager.FORM_LIST)).size());
    assertFalse(fxValue(() -> manager.floatButtonVisible(DockLayoutManager.FORM_LIST)));
  }

  @Test
  void theMenuOffersFloatAndClose() {
    DockLayoutManager manager = built();

    MenuButton menu = fxValue(() -> manager.headerMenus(DockLayoutManager.FORM_FILL)).getFirst();
    assertEquals(
        List.of(Messages.get("pane.float"), Messages.get("pane.close")),
        fxValue(() -> menu.getItems().stream().map(MenuItem::getText).toList()));
  }

  @Test
  void closingFromTheMenuRemovesThePaneFromTheGraph() {
    DockLayoutManager manager = built();

    onFx(() -> manager.headerMenus(DockLayoutManager.SUBMISSIONS).getFirst()
        .getItems().get(1).fire());

    assertFalse(fxValue(() -> manager.layoutIdsInOrder().contains(DockLayoutManager.SUBMISSIONS)));
  }

  @Test
  void decoratingTwiceDoesNotAddASecondMenu() {
    // Review Focus 4: SnapFX rebuilds and re-caches its node views on any relayout, and there is
    // no view-created hook, so decorateHeaders is called again after every structural change.
    DockLayoutManager manager = built();
    onFx(manager::decorateHeaders);

    assertEquals(1, fxValue(() -> manager.headerMenus(DockLayoutManager.FORM_LIST)).size());
  }
}
```

`built()` is this class's own helper: it constructs a `DockLayoutManager` the way `ShellIT.manager` does — stand-in `Label` panes, the editor granted because the state is an admin's — over a `LayoutStore` in `layoutDir`, calls `build(freshStage())` on the FX thread and returns it. Copy `ShellIT`'s `manager`, `freshStage` and `adminState` helpers rather than making them public; the duplication is two short methods and keeps `ShellIT` a closed test.

`headerMenus(String layoutId)` returns the header's children with style class `PaneHeaderMenu.MENU_STYLE_CLASS` as `List<MenuButton>`, and `floatButtonVisible(String layoutId)` reads `DockNodeView.isFloatButtonVisible()`. Both are public on `DockLayoutManager` so the test needs no `org.snapfx` import; both return an empty list / false for an id that is not docked.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -q verify -Dit.test=PaneHeaderIT -DskipTests`
Expected: FAIL — `PaneHeaderMenu` and the three new `DockLayoutManager` methods do not exist.

- [ ] **Step 3: Implement the decoration**

`PaneHeaderMenu.decorate` looks the view up with `snapFX.getLayoutEngine().getDockNodeView(node)`, returns quietly when that is null (the view is built lazily, so a node not yet laid out is normal), calls `setFloatButtonVisible(false)`, and adds a `MenuButton` carrying `MENU_STYLE_CLASS` to `getHeader()` — but only when the header has no child with that class already, which is what makes it idempotent. Menu text comes from the bundle:

```properties
pane.float=Float window
pane.close=Close panel
pane.menu=Panel menu
```

`pane.menu` is the accessible text / tooltip; the button's own label is `⋯`, a glyph that needs no translation, matching how `FormListPane` labels its refresh button.

`DockLayoutManager.decorateHeaders()` iterates the four ids and calls `decorate` for each node it finds. `build(Stage)` calls it last, after `buildLayout()`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q verify -Dit.test=PaneHeaderIT -DskipTests`
Expected: PASS

- [ ] **Step 5: Run the whole suite**

Run: `mvn -q verify`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Replace the pane float button with a three-dots menu offering float and close; re-apply it idempotently after every relayout"
```

---

### Task 5: Status bar

**Files:**
- Create: `src/main/java/com/example/desktop/ui/common/StatusBar.java`
- Create: `src/test/java/com/example/desktop/ui/common/StatusBarTest.java`
- Modify: `src/main/java/com/example/desktop/state/AppState.java`, `src/test/java/com/example/desktop/state/AppStateTest.java`
- Modify: `src/main/java/com/example/desktop/ui/forms/FormListPane.java`, `ui/forms/FormFillPane.java`, `ui/data/SubmissionsPane.java`
- Modify: `src/main/java/com/example/desktop/DesktopApp.java`, `src/main/resources/i18n/messages.properties`

**Interfaces:**
- Consumes: `AppState`, `DesktopConfig.ignored()` from Task 2.
- Produces:
  - `AppState.status()` → `StringProperty`, empty by default, written on the FX thread only
  - `new StatusBar(AppState state)` extends `HBox`; `public String text()` and `public String userText()` for tests
  - The panes keep every `ErrorBanner` call they have; the status property is additional, not a replacement

- [ ] **Step 1: Write the failing tests**

```java
// AppStateTest
@Test
void statusStartsEmptyAndCarriesWhatIsPutInIt() {
  AppState state = new AppState();
  assertEquals("", state.status().get());
  state.status().set("Loaded 3 forms");
  assertEquals("Loaded 3 forms", state.status().get());
}

// StatusBarTest extends UiTest
@Test
void theBarShowsWhateverTheStateSays() {
  AppState state = new AppState();
  StatusBar bar = onFx(() -> new StatusBar(state));
  onFx(() -> state.status().set("Submitted"));
  assertEquals("Submitted", fxValue(bar::text));
}

@Test
void theBarNamesTheSignedInUser() {
  AppState state = /* adminState() equivalent: a session for "admin" */;
  StatusBar bar = onFx(() -> new StatusBar(state));
  assertTrue(fxValue(bar::userText).contains("admin"));
}
```

Build the session the way `ShellIT.stateWithRoles` does — an unsigned JWT with a `roles` claim through `AuthSession.fromToken`. If the claim set needs a `sub`/`preferred_username` for `username()` to return `admin`, add it there.

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -q test -Dtest=AppStateTest+StatusBarTest`
Expected: FAIL — `AppState.status()` and `StatusBar` do not exist.

- [ ] **Step 3: Implement `AppState.status()` and `StatusBar`**

`StatusBar` is an `HBox` with a message `Label` bound to `state.status()`, a spacer, and a user `Label` bound to the session's `username()` plus `(admin)` when `state.admin()` holds — reuse the `app.titleAdmin` pattern rather than concatenating. Style class `status-bar`; add the rule to `src/main/resources/css/application.css`.

- [ ] **Step 4: Publish from the three panes**

Where each pane already shows a banner on a result, also set the status, on the FX thread, from the bundle:

```properties
status.formsLoaded={0} forms
status.submissionsLoaded={0} submissions
status.submitted=Submission saved
```

- `FormListPane.refresh` success → `status.formsLoaded` with the row count.
- `SubmissionsPane.refresh` success → `status.submissionsLoaded` with the row count.
- `FormFillPane.submit` success → `status.submitted`.

Failures keep going to the banner; the status line is not an error channel.

- [ ] **Step 5: Put the bar in the window**

`DesktopApp` builds `BorderPane` with the dock root in the centre and the `StatusBar` at the bottom, and replaces the `System.err` line from Task 2 with `state.status().set(Messages.format("config.ignored", ...))` when `config.ignored()` is non-empty.

- [ ] **Step 6: Run the whole suite**

Run: `mvn -q verify`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add a status bar fed by an AppState status property; publish load counts and a submit confirmation; report ignored config entries there"
```

---

### Task 6: Menubar, and a closed pane that can come back

The one real gap the owner's question exposed: a closed pane is gone for good today, and the only way back is deleting `layout.json`.

**Files:**
- Create: `src/main/java/com/example/desktop/ui/common/MainMenuBar.java`
- Create: `src/test/java/com/example/desktop/ui/common/MainMenuBarTest.java`
- Modify: `src/main/java/com/example/desktop/ui/dock/DockLayoutManager.java`
- Modify: `src/test/java/com/example/desktop/it/ShellIT.java`
- Modify: `src/main/java/com/example/desktop/DesktopApp.java`, `src/main/resources/i18n/messages.properties`

**Interfaces:**
- Consumes: `DockLayoutManager` from Tasks 3 and 4.
- Produces:
  - `DockLayoutManager.reopen(String layoutId)` → `boolean`: restores a hidden node, or docks a fresh one at its default position when the id is absent; false when the factory declines the id
  - `DockLayoutManager.resetToDefaults()` → `Parent`: discards the graph, docks the defaults with their ratios, returns the new root for the caller to set on the scene
  - `DockLayoutManager.reopenableIds()` → `List<String>`: the ids the factory will build that are not currently docked
  - `new MainMenuBar(MainMenuBar.Actions actions)` extends `MenuBar`, where `Actions` is an interface with `void reopen(String layoutId)`, `void resetLayout()`, `void signOut()`, `void exit()`, and `List<String> reopenable()`

Close becomes `DockCloseBehavior.HIDE` so a closed pane stays in the graph and `SnapFX.restore` can bring it back. `closeAllPanes()` keeps working; the blank-layout fallback in `restoreSaved()` still covers a graph whose root ends up null.

- [ ] **Step 1: Write the failing tests**

```java
// ShellIT
@Test
void aClosedPaneCanBeReopened() {
  DockLayoutManager manager =
      onFx(() -> manager(new LayoutStore(layoutDir.resolve("reopen.json")), adminState()));
  onFx(() -> manager.build(freshStage()));

  onFx(() -> manager.close(manager.nodeById(DockLayoutManager.SUBMISSIONS)));
  assertFalse(fxValue(() -> manager.layoutIdsInOrder().contains(DockLayoutManager.SUBMISSIONS)));

  assertTrue(fxValue(() -> manager.reopen(DockLayoutManager.SUBMISSIONS)));
  assertNotNull(fxValue(() -> manager.nodeById(DockLayoutManager.SUBMISSIONS)));
  assertNotNull(fxValue(() -> manager.nodeById(DockLayoutManager.SUBMISSIONS).getContent()),
      "a reopened pane with no content renders as an empty panel");
}

@Test
void aPaneClosedBeforeARestartCanStillBeReopened() {
  // Review Focus 5: whether a hidden node survives serialization is SnapFX's business, so reopen
  // has to work from an absent id too.
  LayoutStore store = new LayoutStore(layoutDir.resolve("closed-then-restarted.json"));
  onFx(() -> {
    DockLayoutManager first = manager(store, adminState());
    first.build(freshStage());
    first.close(first.nodeById(DockLayoutManager.SUBMISSIONS));
    first.saveLayout();
  });

  DockLayoutManager second = onFx(() -> manager(store, adminState()));
  onFx(() -> second.build(freshStage()));
  assertTrue(fxValue(() -> second.reopen(DockLayoutManager.SUBMISSIONS)));
  assertNotNull(fxValue(() -> second.nodeById(DockLayoutManager.SUBMISSIONS)));
}

@Test
void reopenDeclinesAnIdTheFactoryWillNotBuild() {
  DockLayoutManager asUser =
      onFx(() -> manager(new LayoutStore(layoutDir.resolve("user-reopen.json")), userState()));
  onFx(() -> asUser.build(freshStage()));
  assertFalse(fxValue(() -> asUser.reopen(DockLayoutManager.FORM_EDITOR)));
}

@Test
void resetGoesBackToTheDefaultArrangement() {
  LayoutStore store = new LayoutStore(layoutDir.resolve("reset.json"));
  DockLayoutManager manager = onFx(() -> manager(store, adminState()));
  onFx(() -> manager.build(freshStage()));
  onFx(() -> manager.close(manager.nodeById(DockLayoutManager.FORM_LIST)));

  assertNotNull(onFx(manager::resetToDefaults));
  assertEquals(
      List.of(DockLayoutManager.FORM_LIST, DockLayoutManager.SUBMISSIONS,
          DockLayoutManager.FORM_FILL, DockLayoutManager.FORM_EDITOR),
      fxValue(manager::layoutIdsInOrder));
  assertEquals(0.33, fxValue(manager::rootSplitFraction), 0.02);
}

// MainMenuBarTest extends UiTest
@Test
void theViewMenuListsOnlyWhatIsReopenable() {
  RecordingActions actions = new RecordingActions(List.of(DockLayoutManager.SUBMISSIONS));
  MenuBar bar = onFx(() -> new MainMenuBar(actions));
  Menu view = fxValue(() -> bar.getMenus().get(1));
  onFx(() -> view.show());   // the list is built on show, so a pane closed meanwhile appears
  assertEquals(
      List.of(Messages.get("navigation.submissions"), Messages.get("menu.resetLayout")),
      fxValue(() -> view.getItems().stream().map(MenuItem::getText).toList()));
}

@Test
void signOutAndExitReachTheActions() {
  RecordingActions actions = new RecordingActions(List.of());
  MenuBar bar = onFx(() -> new MainMenuBar(actions));
  onFx(() -> ((MenuItem) bar.getMenus().getFirst().getItems().getFirst()).fire());
  assertTrue(actions.signedOut());
}
```

`RecordingActions` is a test double implementing `MainMenuBar.Actions`, recording calls and returning the ids it was constructed with.

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -q test -Dtest=MainMenuBarTest` then `mvn -q verify -Dit.test=ShellIT -DskipTests`
Expected: FAIL — `reopen`, `resetToDefaults`, `reopenableIds` and `MainMenuBar` do not exist.

- [ ] **Step 3: Implement reopen, reset and the menubar**

In `DockLayoutManager.build`, call `snapFX.setDefaultCloseBehavior(DockCloseBehavior.HIDE)` before docking anything. `reopen(id)` returns false when `panes.create(id)` is null; otherwise it `restore`s the node when `nodeById(id)` finds one, and docks a fresh node at the id's default position when it does not — share that position table with `dockDefaults()` so the two cannot disagree. Both paths end with `decorateHeaders()`, since a restored view is a new view.

`MainMenuBar` builds three menus from the bundle: **File** (`menu.signOut`, `menu.exit`), **View** (one item per `actions.reopenable()`, rebuilt on `onShowing`, then a separator and `menu.resetLayout`), **Help** (`menu.about`, an `Alert` with `app.title` and the version). Reopen items are labelled with the same `navigation.*` strings `DockLayoutManager.title(id)` uses — extract that mapping to `public static String titleOf(String layoutId)` so the two cannot drift.

```properties
menu.file=File
menu.view=View
menu.help=Help
menu.signOut=Sign out
menu.exit=Exit
menu.resetLayout=Reset layout
menu.about=About
```

`DesktopApp` puts the bar at the top of the `BorderPane` from Task 5 and implements `Actions`: `resetLayout` sets the returned `Parent` as the centre, `exit` calls `Platform.exit`, `signOut` is Task 7's.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q verify`
Expected: PASS, including the five original `ShellIT` tests — `closingEveryPaneDoesNotLeaveTheNextLaunchBlank` is the one HIDE could change.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add a File/View/Help menubar; close a pane by hiding it so View can bring it back, and offer a layout reset"
```

---

### Task 7: Login screen, and sign-in again

The window opens on a Sign in button instead of throwing the user at a browser. Signing out and in again rebuilds the panes, which is also how a session with different roles finally gets the right layout.

**Files:**
- Create: `src/main/java/com/example/desktop/ui/common/LoginPane.java`
- Create: `src/main/java/com/example/desktop/SessionPanes.java`
- Create: `src/test/java/com/example/desktop/ui/common/LoginPaneTest.java`
- Create: `src/test/java/com/example/desktop/SignOutIT.java`
- Modify: `src/main/java/com/example/desktop/DesktopApp.java`, `src/main/resources/i18n/messages.properties`

**Interfaces:**
- Consumes: `DesktopConfig`, `StatusBar`, `MainMenuBar`, `DockLayoutManager` from Tasks 2–6.
- Produces:
  - `new LoginPane(Runnable onSignIn)` extends `BorderPane`; `public void setBusy(boolean)`, `public void showMessage(String)`, `public String messageText()`, `public Button signInButton()`
  - `SessionPanes`, package-visible in `com.example.desktop`: `SessionPanes(FormService forms, FormDataService data, AppState state)` builds the four panes (the editor only when `state.admin()` holds) and exposes `AppState state()`, `PaneFactory paneFactory()`, `FormListPane list()`, `FormFillPane fill()`, `SubmissionsPane submissions()`, `FormEditorPane editor()` (null for a non-admin), `boolean isCurrent()`, `void retire()`, and `void runIfCurrent(Runnable block)` — the guard every `setOnSucceeded` and `setOnFailed` handler wraps its body in
  - `DesktopApp.showLogin(Stage)`, `DesktopApp.showMainWindow(Stage, AuthSession)` and `DesktopApp.signOut(Stage)`, package-visible so a test can drive the window's two states without `Application.launch`
  - `DesktopApp.layoutManager()` and `DesktopApp.panes()`, package-visible accessors for the current session

Sign-in stays the real PKCE flow over the system browser (spec 4.2); the pane only decides *when* it starts. No `WebView`, no new dependency.

- [ ] **Step 1: Write the failing tests**

```java
// LoginPaneTest extends UiTest
@Test
void theButtonStartsTheFlowOnce() {
  AtomicInteger starts = new AtomicInteger();
  LoginPane pane = onFx(() -> new LoginPane(starts::incrementAndGet));
  onFx(() -> pane.signInButton().fire());
  assertEquals(1, starts.get());
}

@Test
void theButtonIsDisabledWhileSigningIn() {
  LoginPane pane = onFx(() -> new LoginPane(() -> {}));
  onFx(() -> pane.setBusy(true));
  assertTrue(fxValue(() -> pane.signInButton().isDisabled()));
  assertEquals(Messages.get("login.inProgress"), fxValue(pane::messageText));
}

@Test
void aFailedSignInLeavesTheButtonUsableWithTheReason() {
  LoginPane pane = onFx(() -> new LoginPane(() -> {}));
  onFx(() -> { pane.setBusy(true); pane.setBusy(false); pane.showMessage("Connection refused"); });
  assertFalse(fxValue(() -> pane.signInButton().isDisabled()));
  assertEquals("Connection refused", fxValue(pane::messageText));
}

// SignOutIT extends UiTest — no backend; an injected AuthSession, as spec section 5.3 prescribes.
// The panes' own refresh calls fail against the unreachable default URL and land in their banners,
// which is harmless here: what is under test is the window, not the API.
class SignOutIT extends UiTest {

  @Test
  void signingOutGoesBackToTheLoginScreen() {
    DesktopApp app = new DesktopApp();
    Stage stage = onFx(() -> { Stage s = new Stage(); s.setScene(new Scene(new StackPane())); return s; });

    onFx(() -> app.showMainWindow(stage, session("USER")));
    onFx(() -> app.signOut(stage));

    assertInstanceOf(LoginPane.class, fxValue(() -> stage.getScene().getRoot()));
  }

  @Test
  void signingInAsAnAdminAfterAUserRebuildsTheLayoutWithTheEditor() {
    // The carried-forward finding from the polish plan: whether the editor exists was settled when
    // the window was built, so a new session with other roles used to keep the old panes.
    DesktopApp app = new DesktopApp();
    Stage stage = onFx(() -> { Stage s = new Stage(); s.setScene(new Scene(new StackPane())); return s; });

    onFx(() -> app.showMainWindow(stage, session("USER")));
    assertFalse(fxValue(() -> app.layoutManager().layoutIdsInOrder()
        .contains(DockLayoutManager.FORM_EDITOR)));

    onFx(() -> app.signOut(stage));
    onFx(() -> app.showMainWindow(stage, session("USER", "ADMIN")));

    assertTrue(fxValue(() -> app.layoutManager().layoutIdsInOrder()
        .contains(DockLayoutManager.FORM_EDITOR)));
    assertNotNull(fxValue(() -> app.panes().editor()));
  }

  @Test
  void aRequestThatFinishesAfterASignOutDoesNotWriteIntoTheNewSession() {
    // Review Focus, sixth case: a load started before the sign-out completes afterwards, on a
    // pane that is no longer on screen, and must not push its status or its form into the new one.
    DesktopApp app = new DesktopApp();
    Stage stage = onFx(() -> { Stage s = new Stage(); s.setScene(new Scene(new StackPane())); return s; });

    onFx(() -> app.showMainWindow(stage, session("USER")));
    SessionPanes retired = app.panes();

    onFx(() -> app.signOut(stage));
    onFx(() -> app.showMainWindow(stage, session("USER")));
    SessionPanes current = app.panes();

    assertFalse(fxValue(retired::isCurrent));
    assertTrue(fxValue(current::isCurrent));
    onFx(() -> retired.runIfCurrent(() -> current.state().status().set("from the old session")));
    assertEquals("", fxValue(() -> current.state().status().get()));
  }

  /** An unsigned JWT with the given bare role names, as ShellIT builds one. */
  private static AuthSession session(String... roles) { ... }
}
```

`session(String...)` is `ShellIT.stateWithRoles`' token-building half, returning the `AuthSession` rather than an `AppState`; copy it rather than making `ShellIT`'s helpers public.

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -q test -Dtest=LoginPaneTest` then `mvn -q verify -Dit.test=SignOutIT -DskipTests`
Expected: FAIL — `LoginPane`, `SessionPanes` and `DesktopApp`'s two window states do not exist.

- [ ] **Step 3: Implement `LoginPane` and the two window states**

`LoginPane` centres a `Label` and a Sign in `Button` over a `BusyOverlay`, reusing `ui/common` rather than inventing a spinner. Bundle keys:

```properties
login.signIn=Sign in
login.prompt=Sign in to continue
```

`login.inProgress` and `login.failed` already exist; keep their current wording.

`DesktopApp.start` loads the config and calls `showLogin(stage)`. `showLogin` scenes a `LoginPane` whose `onSignIn` runs the existing `PkceLoginFlow` `Task` — the body that is in `start()` today — setting the pane busy, and on failure shows the reason in the pane instead of the fatal `Alert`, since the user can now retry. `reportFatal` stays for a failure with no pane to show it in.

`showMainWindow` builds everything fresh: `AppState`, `ApiClient`, the services, a `SessionPanes`, a **new** `SnapFX` and a **new** `DockLayoutManager` (SnapFX binds to the stage it is given in `initialize`, so it cannot be reused), the menubar and the status bar. That rebuild is what makes a second sign-in re-evaluate the roles.

`SessionPanes.runIfCurrent` is the guard: `DesktopApp` keeps the live instance in an `AtomicReference`, `isCurrent()` compares against it, `retire()` is called by `signOut`, and every `setOnSucceeded`/`setOnFailed` body in `DesktopApp` — `loadForm`'s included — is wrapped in it. `signOut` saves the layout, retires the panes, clears the session and calls `showLogin(stage)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q verify`
Expected: PASS

- [ ] **Step 5: Launch it by hand**

Run: `mvn -q test-compile exec:java`
Expected: a window with a Sign in button; clicking it opens the browser; `admin`/`admin` shows the editor tab, the status bar names the user, the panes sit as Task 3 arranged them. Sign out, sign in as `user`/`user`, and the editor tab is gone without a restart.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Open on a login screen instead of an unasked-for browser window; rebuild the whole main window on sign-in so a new session's roles re-dock the panes"
```

---

### Task 8: A dev stack that stays up

Today `Launcher` owns both the stack and the app, so quitting the app tears the containers down and the next run pays the startup again. Split them: one `main` holds the stack, another runs the app against it.

**Files:**
- Create: `src/test/java/com/example/desktop/StackLauncher.java`
- Modify: `src/test/java/com/example/desktop/Launcher.java`
- Create: `src/test/java/com/example/desktop/StackPropertiesTest.java`
- Modify: `pom.xml`, `.claude/skills/dynamic-form-desktop/SKILL.md`

**Interfaces:**
- Consumes: `DesktopConfig.load()` from Task 2 — specifically its `-Dconfig.file` override.
- Produces:
  - `StackLauncher.main(String[])` — starts `DynamicFormStack`, writes the properties file, blocks until interrupted
  - `StackLauncher.writeProperties(Path file, String backendUrl)` — static, package-visible, writes a `backend.url=...` properties file and returns the path

The stack's backend port is random (`backendUrl()`) and there is no `withReuse`, so the URL has to be published somewhere. It goes to `target/dev-stack.properties`, which is exactly the shape `DesktopConfig` reads, so the app is pointed at it with `-Dconfig.file=target/dev-stack.properties` and no new mechanism is needed. Testcontainers' Ryuk reaps the containers when `StackLauncher`'s JVM dies, which is the wanted behaviour: the stack lives as long as that terminal.

- [ ] **Step 1: Write the failing test**

```java
class StackPropertiesTest {
  @TempDir Path dir;

  @Test
  void theWrittenFileIsOneDesktopConfigCanRead() throws IOException {
    Path file = StackLauncher.writeProperties(dir.resolve("dev-stack.properties"), "http://localhost:32769");
    assertEquals("http://localhost:32769", DesktopConfig.load(file, new Properties()).backendUrl());
    assertTrue(DesktopConfig.load(file, new Properties()).ignored().isEmpty());
  }

  @Test
  void writingTwiceReplacesTheOldUrl() throws IOException {
    Path file = dir.resolve("dev-stack.properties");
    StackLauncher.writeProperties(file, "http://localhost:1");
    StackLauncher.writeProperties(file, "http://localhost:2");
    assertEquals("http://localhost:2", DesktopConfig.load(file, new Properties()).backendUrl());
  }
}
```

No container starts here: the writer and the reader are what can disagree, and a stale URL from a previous run is the failure that wastes an afternoon.

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -q test -Dtest=StackPropertiesTest`
Expected: FAIL — `StackLauncher` does not exist.

- [ ] **Step 3: Implement `StackLauncher`**

Start the stack as `Launcher` does today — `withAuthConfigFile("oauth2-server.yaml")`, `withLogConsumer(...)` — write `target/dev-stack.properties`, print both URLs and the exact command to run the app, then block (`Thread.currentThread().join()`), closing the stack in a shutdown hook. `Launcher` keeps working unchanged apart from the rename and reusing `writeProperties`, so the one-command path stays for anyone who wants it.

- [ ] **Step 4: Declare the two exec executions**

In `pom.xml`, keep the plugin's existing default configuration (`Launcher`, test classpath) so `mvn test-compile exec:java` is unchanged, and add two named executions:

- `stack` → `com.example.desktop.StackLauncher`, `classpathScope` test
- `app` → `com.example.desktop.DesktopApp`, `classpathScope` test

Documented commands, in two terminals:

```
mvn -q test-compile exec:java@stack
mvn -q test-compile exec:java@app -Dconfig.file=target/dev-stack.properties
```

- [ ] **Step 5: Verify by hand**

Run both commands. Expected: the app signs in and lists the seeded forms; quitting the app leaves the containers up; the second `exec:java@app` starts in seconds; `Ctrl+C` in the first terminal stops them.

- [ ] **Step 6: Run the whole suite**

Run: `mvn -q verify`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Split the dev launcher: StackLauncher holds the containers and publishes its backend URL as a config file the app reads; keep the combined Launcher"
```

---

### Task 9: Record the decisions in the spec

The spec is the project's memory; four decisions were made on 2026-10-24 and one of its own statements now needs qualifying.

**Files:**
- Modify: `docs/plans/DynamicFormDesktopPlan.md`

- [ ] **Step 1: Rewrite section 9 as resolved items**

Each line in *Further development* that this plan implements becomes a statement of what was decided and where it lives, not a question. Carry over the four owner decisions: the renames (all three, plus the paths moving onto the services), the login screen with a Sign in button, the minimal menubar, and the three-dots menu shipping float and close only.

- [ ] **Step 2: Amend section 6's service-name warning**

Section 6 says not to call anything `FormService`/`FormDataService`, reasoning about `com.example.backend.service`. Add that the warning binds *the api jar*: `com.example.desktop.api.FormService` collides with nothing, and the client uses those names as of this plan.

- [ ] **Step 3: Answer the three "is it possible" questions in the spec itself**

- A login dialog with our own username and password fields is **not possible** against this auth server: it needs the password grant, which OAuth 2.1 removed and Spring Authorization Server does not implement. A pre-login screen that starts the browser flow is what was built; an embedded `WebView` remains possible but RFC 8252 argues against it and it costs `javafx.web`.
- A menubar was **wanted**, because a closed pane had no way back.
- A background dev launcher is **possible**: a separate JVM holding the stack, publishing its random backend port as a config file, with Ryuk reaping on exit.

- [ ] **Step 4: Fix the duplicate heading number**

The spec has two `## 8` headings — *Open questions* and *Further development*. Renumber *Further development* to `## 9` and update the reference in section 7 and in the polish plan's carried-forward notes.

- [ ] **Step 5: Note what is still deferred**

In section 9: duplicate panel and the `form-fill:{formKey}` multi-instance work (spec 4.4, its own plan), `jpackage`, and open questions 1, 3 and 4 (Cognito, a remembered login, release-versus-snapshot in CI).

- [ ] **Step 6: Commit**

```bash
git add docs/plans/DynamicFormDesktopPlan.md docs/superpowers/plans/2026-10-24-desktop-polish.md
git commit -m "Record the shell and config decisions in the spec; scope section 6's service-name warning to the api jar; renumber the duplicate section 8"
```

---

## Exit Check

Run: `mvn clean verify`
Expected: every unit test and every `*IT` green from a clean checkout, with `spotless:check` passing, given Docker is running and `mvn initialize` has been run once.

Then, by hand, with a layout saved by the previous build already in place:

```
mvn -q test-compile exec:java@stack
mvn -q test-compile exec:java@app -Dconfig.file=target/dev-stack.properties
```

This plan is complete when:

- the window opens on a Sign in button, and the browser opens only when it is clicked;
- the backend URL, locale and layout path come from `~/.dynamic-form-desktop/config.properties`, a bad entry is reported in the status bar and never stops startup, and `-Dbackend.url` still wins;
- a fresh layout puts forms over submissions on the left and the form view at about two thirds on the right, while a layout saved earlier keeps its own proportions;
- every pane header carries a three-dots menu with float and close, and no float button, after a drag as well as on first paint;
- the status bar names the signed-in user and reports load counts and submits;
- View brings a closed pane back, both within a session and after a restart, and resets the layout;
- signing out and in as a different user re-docks the panes for that user's roles;
- the stack survives quitting the app;
- `FormService`, `FormDataService` and `DesktopApp` are the names in the tree, `ApiPaths` is gone, and the spec says why.

## Carried-forward notes for the next plan

- **Duplicate panel and multiple fill panes** (`form-fill:{formKey}`, spec 4.4). `PaneHeaderMenu` has the menu to hang it on; what is missing is dynamic ids in `createNode`, `attachPaneContent`, `stripPanesTheFactoryDeclines` and `reopen`, all four of which iterate a fixed list of four ids today. Double-click in the list opens a new tab; the three-dots menu gains *Duplicate panel*.
- **The 401 reauthenticator still reuses the panes.** Task 7 fixes the sign-out path, not this one: a re-login triggered by an expired token keeps the window it had, which is right for the same user and wrong if the roles changed.
- **`jpackage`**, if this stops being a demo: needs spec open question 2 (target OS) and one runner per platform.
- **Spec open questions 1, 3 and 4** remain: Cognito support, a login remembered across restarts, and whether CI pins a release or floats `-SNAPSHOT`.
- **A second locale** would prove `Messages.setLocale` does something. There is one bundle today, so Task 2's locale key is wiring without a visible effect beyond SnapFX's own strings.
