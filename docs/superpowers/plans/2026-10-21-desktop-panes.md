# Dynamic Form Desktop Panes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the foundation's placeholder panes with the real UI — a form list, a dynamically rendered fill pane covering all nine field types, a submissions table, and an admin form editor — so the desktop client reaches feature parity with the React frontend.

**Architecture:** Each pane is a self-contained JavaFX `Node` that reads and writes shared observable state (`AppState`) and calls the existing `FormApi`/`FormDataApi` off the FX thread. Field rendering is a factory keyed on `FieldDto.type`, returning a control paired with a typed value accessor, so the panes never switch on type themselves. UI behaviour is tested with TestFX running headless under Monocle.

**Tech Stack:** Java 21, JavaFX 21.0.12, SnapFX 0.8.0, TestFX 4.0.18 (`testfx-core`, `testfx-junit5`), openjfx-monocle 21.0.2, JUnit 5.14.4, plus the foundation's `ApiClient`/`FormApi`/`FormDataApi` and the `dynamic-form-api` DTOs.

**Spec:** `docs/DynamicFormDesktopPlan.md` — this plan covers phases 4-6 and the parts of sections 4.3 and 4.5 the foundation deferred. The foundation (phases 0-3) is `docs/superpowers/plans/2026-10-19-desktop-foundation.md`; read its *Outcome and follow-up work* section before starting.

---

## Global Constraints

Everything in the foundation plan's Global Constraints still holds. In addition:

**Dependencies**
- Pinned: `org.testfx:testfx-core:4.0.18`, `org.testfx:testfx-junit5:4.0.18`, `org.testfx:openjfx-monocle:21.0.2`, all `test` scope.
- No new production dependency. The panes use JavaFX controls only — no extra UI toolkit, no Spring, no declarative HTTP client.

**Code rules**
- No pane constructs an `ApiClient`. Each takes the `FormApi`/`FormDataApi` it needs, injected.
- Panes never reference each other. They read and write `AppState` and nothing else.
- Every API call runs off the FX thread and returns via `Platform.runLater`. A pane that blocks the FX thread is a defect regardless of how fast the call is.
- All `org.snapfx.*` imports stay in `ui/dock`. Panes know nothing about docking.
- No pane switches on `FieldDto.type`. That knowledge lives only in `FieldControlFactory`.
- User-visible strings come from the `Messages` bundle, never inline literals.

**The wire format is fixed and must not be "improved"** (foundation plan, spec section 2). The backend coerces nothing, so the client decides the JSON type of every submitted value:
- `text`, `email`, `tel`, `textarea` → `String`
- `number` → **`String`** (`"42"`), matching the React client, not a JSON number
- `date` → `String` in `yyyy-MM-dd`, and `""` when the picker is empty
- `select`, `radio` → the chosen option's `value` as a `String`
- `checkbox` with options → `List<String>` of the selected values; with no options → `Boolean`

**Test data**
- Seeded forms cover eight of the nine types: `contact` has text, email, tel, select, textarea, radio; `feedback` adds date and checkbox (4 options); `survey` repeats text, email, select, textarea, radio. **No seeded form has a `number` field**, so any test needing one creates a form through `FormApi` as `admin`.
- Integration tests reuse the foundation's `StackFixture` and `TokenClient`. One stack per test JVM.

**Headless UI tests**
- `*IT` classes that drive the UI extend the TestFX JUnit 5 support and run under Monocle via
  `-Dtestfx.headless=true -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw`.
- Those properties are set in the failsafe configuration, not expected from the command line, so `mvn verify` works unchanged locally and in CI.

## Review Focus

Input classes the spec implies but no task's tests exercise. Each has its test added to the owning task.

1. **A `FieldDto.type` the client does not know** — a newer backend adds a type, or a hand-written form carries a typo. One unrenderable field must not blank the whole pane; the rest of the form still has to work. → Task 3.
2. **A `select`, `radio` or `checkbox` field whose `options` is null or empty** — `FieldDto.options` is nullable and nothing in the backend forbids it. Rendering must not throw. → Task 3.
3. **A stored submission whose `data` keys no longer match the form** — a field was renamed or removed after the submission was made. Opening it for edit must show what it can and not lose the rest. → Task 6.
4. **A `ValidationErrorDto` whose `field` is null or names no visible control** — the backend sends class-level errors with a null `field`. The message must reach the user somewhere rather than being silently dropped. → Task 4.
5. **A form deleted, or its definition changed, while a fill pane is open on it** — the admin editor makes this easy to hit. Submitting must fail with a readable message, not an unhandled 404. → Task 7.

---

## File Structure

```
src/main/java/com/example/desktop/
  state/AppState.java                        Task 1  observable selection + session
  i18n/Messages.java                         Task 1  ResourceBundle wrapper
  ui/field/FieldControl.java                 Task 2  control + typed value accessor
  ui/field/FieldControlFactory.java          Task 2  type -> FieldControl (text-like)
                                             Task 3  option types, unknown types
  ui/forms/FormFillPane.java                 Task 4  renders a FormDto, submits
  ui/forms/FormListPane.java                 Task 5  list, refresh, selection
  ui/data/SubmissionsPane.java               Task 6  table, view/edit/delete
  ui/forms/FormEditorPane.java               Task 7  admin form CRUD
  ui/common/ErrorBanner.java                 Task 4  one place errors are shown
  ui/common/BusyOverlay.java                 Task 4  off-thread call feedback
  ui/dock/DockLayoutManager.java             Task 5  modified: real panes replace labels
  App.java                                   Task 5  modified: wires the panes
src/main/resources/
  i18n/messages.properties                   Task 1  mirrors the frontend's key paths
src/test/java/com/example/desktop/
  ui/UiTest.java                             Task 1  TestFX base: headless, FX thread helpers
  state/AppStateTest.java                    Task 1
  i18n/MessagesTest.java                     Task 1
  ui/field/FieldControlFactoryTest.java      Tasks 2, 3
  ui/forms/FormFillPaneTest.java             Task 4
  it/ReadPathIT.java                         Task 5  list -> fill -> submit, against the stack
  it/SubmissionsIT.java                      Task 6
  it/AdminEditorIT.java                      Task 7
  it/ShellIT.java                            Task 8  layout persistence, role gating, recovery
```

---

### Task 1: UI test harness, AppState and i18n

**Files:**
- Modify: `pom.xml` (TestFX, Monocle, failsafe system properties)
- Create: `src/main/java/com/example/desktop/state/AppState.java`
- Create: `src/main/java/com/example/desktop/i18n/Messages.java`
- Create: `src/main/resources/i18n/messages.properties`
- Test: `src/test/java/com/example/desktop/ui/UiTest.java`
- Test: `src/test/java/com/example/desktop/state/AppStateTest.java`
- Test: `src/test/java/com/example/desktop/i18n/MessagesTest.java`

**Interfaces:**
- Consumes: `AuthSession` (foundation Task 7).
- Produces:
  - `AppState` with `ObjectProperty<AuthSession> session()`, `StringProperty selectedFormKey()`, `ObjectProperty<FormDataDto> selectedSubmission()`, and `ReadOnlyBooleanProperty admin()` derived from the session.
  - `Messages.get(String key)` and `Messages.format(String key, Object... args)`, backed by `ResourceBundle` `i18n.messages`. A missing key returns the key itself rather than throwing, so a typo is visible but never fatal.
  - `UiTest` — the base class every UI test extends. Starts the JavaFX toolkit once per JVM and offers exactly four helpers, which Tasks 2-8 use throughout:
    - `void onFx(Runnable block)` — runs on the FX thread and waits for it
    - `<T> T onFx(Supplier<T> block)` — the same, returning the block's result
    - `<T> T fxValue(Supplier<T> read)` — reads control state on the FX thread
    - `void waitUntil(BooleanSupplier condition)` — polls until true or fails after a timeout, for anything that completes on a background thread
    - `Stage stage()` — a headless stage the layout tests can build into

- [x] **Step 1: Write the failing tests**

```java
class AppStateTest {

  @Test
  void adminFollowsTheSessionItIsGiven() {
    AppState state = new AppState();

    assertFalse(state.admin().get(), "no session means no admin actions");
    state.session().set(session(Set.of("USER")));
    assertFalse(state.admin().get());
    state.session().set(session(Set.of("ADMIN", "USER")));
    assertTrue(state.admin().get());
  }

  @Test
  void selectingAFormNotifiesListeners() {
    AppState state = new AppState();
    List<String> seen = new ArrayList<>();
    state.selectedFormKey().addListener((obs, old, now) -> seen.add(now));

    state.selectedFormKey().set("feedback");

    assertEquals(List.of("feedback"), seen);
  }
}
```

```java
class MessagesTest {

  @Test
  void resolvesAKeyMirroringTheFrontend() {
    assertEquals("Submit", Messages.get("form.submit"));
  }

  @Test
  void interpolatesArguments() {
    // The frontend writes this as "{{label}} is required"; MessageFormat uses a positional {0}.
    assertEquals("Email is required", Messages.format("form.required", "Email"));
  }

  @Test
  void anUnknownKeyReturnsTheKeyRatherThanThrowing() {
    assertEquals("no.such.key", Messages.get("no.such.key"));
  }
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dtest='AppStateTest,MessagesTest' test`
Expected: FAIL with "cannot find symbol" for `AppState` and `Messages`.

- [x] **Step 3: Add the test dependencies and headless configuration to `pom.xml`**

Add `testfx-core`, `testfx-junit5` and `openjfx-monocle` in `test` scope with the pinned versions. Add the four headless system properties to the `maven-failsafe-plugin` configuration's `<systemPropertyVariables>` so `mvn verify` needs no command-line flags.

- [x] **Step 4: Implement `AppState`, `Messages` and `messages.properties`**

`admin()` is a `BooleanBinding` over `session()`, not a field that callers must remember to update.

`messages.properties` mirrors the key paths in
`dynamic-form/frontend/public/locales/en/translation.json` (95 keys across `navigation`, `home`, `forms`,
`submissions`, `submissionDetail`, `form`, `editForm`, `content`, `fieldEditor`, `common`). Copy the key paths
and the English text, converting i18next's `{{name}}` placeholders to `MessageFormat`'s positional `{0}`.
Mirroring the paths is what lets a translation be lifted from either project later.

- [x] **Step 5: Run the tests to verify they pass**

Run: `mvn -Dtest='AppStateTest,MessagesTest' test`
Expected: PASS, 5 tests.

- [x] **Step 6: Implement `UiTest` and prove the harness runs headless**

Write a throwaway assertion inside `UiTest` itself — construct a `Label` on the FX thread and read its text back through `fxValue` — so the base class is exercised by its own test run.

Run: `mvn -Dtest=UiTest test`
Expected: PASS. If the toolkit fails to start, Monocle is not on the classpath or the system properties did not reach the JVM; fix that before Task 2, because every later task depends on it.

- [x] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/example/desktop/state src/main/java/com/example/desktop/i18n \
  src/main/resources/i18n src/test/java/com/example/desktop/state \
  src/test/java/com/example/desktop/i18n src/test/java/com/example/desktop/ui/UiTest.java
git commit -m "Add AppState, i18n bundle and the headless TestFX harness"
```

---

### Task 2: FieldControlFactory — the text-like types

**Files:**
- Create: `src/main/java/com/example/desktop/ui/field/FieldControl.java`
- Create: `src/main/java/com/example/desktop/ui/field/FieldControlFactory.java`
- Test: `src/test/java/com/example/desktop/ui/field/FieldControlFactoryTest.java`

**Interfaces:**
- Consumes: `UiTest` (Task 1), `Messages` (Task 1).
- Produces:
  - `FieldControl` with `Node node()`, `Object value()`, `void setValue(Object value)`, `void showError(String message)`, `void clearError()`, and `String fieldName()`.
  - `FieldControlFactory.create(FieldDto field)` → `FieldControl`.
  - This task covers `text`, `email`, `tel`, `textarea`, `number`, `date`. Task 3 adds the option types.

- [x] **Step 1: Write the failing tests**

```java
class FieldControlFactoryTest extends UiTest {

  @Test
  void textEmailAndTelAllRenderATextFieldReturningAString() {
    for (String type : List.of("text", "email", "tel")) {
      FieldControl control = onFx(() -> FieldControlFactory.create(field("a", type)));

      assertInstanceOf(TextField.class, control.node(), type);
      onFx(() -> control.setValue("hello"));
      assertEquals("hello", fxValue(control::value), type);
    }
  }

  @Test
  void textareaRendersATextArea() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("notes", "textarea")));

    assertInstanceOf(TextArea.class, control.node());
  }

  @Test
  void numberIsSentAsAStringToMatchTheReactClient() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("rating", "number")));

    onFx(() -> control.setValue("42"));

    assertEquals("42", fxValue(control::value));
  }

  @Test
  void numberRejectsNonNumericTyping() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("rating", "number")));

    onFx(() -> ((TextField) control.node()).setText("12ab3"));

    assertEquals("123", fxValue(control::value), "the formatter must drop non-digits");
  }

  @Test
  void dateUsesIsoAndAnEmptyPickerIsAnEmptyString() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("purchaseDate", "date")));

    assertEquals("", fxValue(control::value), "a blank date is \"\", as the React client sends");
    onFx(() -> control.setValue("2026-01-02"));
    assertEquals("2026-01-02", fxValue(control::value));
    assertEquals(LocalDate.of(2026, 1, 2), fxValue(() -> ((DatePicker) control.node()).getValue()));
  }

  @Test
  void showErrorMarksTheControlAndClearErrorUndoesIt() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("a", "text")));

    onFx(() -> control.showError("Required"));
    assertTrue(fxValue(() -> control.node().getStyleClass().contains("field-error")));

    onFx(control::clearError);
    assertFalse(fxValue(() -> control.node().getStyleClass().contains("field-error")));
  }

  private static FieldDto field(String name, String type) {
    return FieldDto.builder().name(name).label(name).type(type).build();
  }
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dtest=FieldControlFactoryTest test`
Expected: FAIL with "cannot find symbol" for `FieldControl` and `FieldControlFactory`.

- [x] **Step 3: Implement `FieldControl` and the text-like half of `FieldControlFactory`**

`number` uses a `TextField` with a `TextFormatter` whose filter accepts only digits, an optional leading minus and one decimal point — and whose `value()` is still a `String`, because that is the wire format. `date` uses a `DatePicker`, formatting with `DateTimeFormatter.ISO_LOCAL_DATE` and mapping null to `""`.

`showError` adds a `field-error` style class and sets the control's tooltip to the message; `clearError` removes both. Keep the class name in a constant — Task 4 asserts on it.

- [x] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dtest=FieldControlFactoryTest test`
Expected: PASS, 6 tests.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/ui/field src/test/java/com/example/desktop/ui/field
git commit -m "Add FieldControl and the text, number and date controls"
```

---

### Task 3: FieldControlFactory — option types and unknown types

**Files:**
- Modify: `src/main/java/com/example/desktop/ui/field/FieldControlFactory.java`
- Test: `src/test/java/com/example/desktop/ui/field/FieldControlFactoryTest.java` (extend)

**Interfaces:**
- Consumes: Task 2's `FieldControl`.
- Produces: `select`, `radio` and `checkbox` support on the same `create(FieldDto)` entry point, plus defined behaviour for an unknown type and for missing options.

**Decision recorded here:** a `checkbox` **with** options renders a group and its value is a `List<String>` of the selected values — including when there is exactly one option. A `checkbox` with **no** options renders a single `CheckBox` whose value is a `Boolean`. This diverges from the React client for the one-option case, where it sends the option's value string or `false`; no seeded form has a one-option checkbox, and one consistent rule inside the desktop is worth more than bug-for-bug fidelity to a shape the frontend itself renders inconsistently (its read-only view treats the value as a boolean). Covered by the first two tests below. Steps 1-4 cover Review Focus items 1 and 2.

- [x] **Step 1: Write the failing tests**

```java
  @Test
  void aCheckboxWithOptionsCollectsTheSelectedValuesAsAList() {
    FieldControl control =
        onFx(() -> FieldControlFactory.create(optionField("features", "checkbox", "f1", "f2", "f3")));

    onFx(() -> control.setValue(List.of("f1", "f3")));

    assertEquals(List.of("f1", "f3"), fxValue(control::value));
  }

  @Test
  void aCheckboxWithoutOptionsIsASingleBoolean() {
    FieldControl control = onFx(() -> FieldControlFactory.create(field("newsletter", "checkbox")));

    assertInstanceOf(CheckBox.class, control.node());
    assertEquals(false, fxValue(control::value));
    onFx(() -> control.setValue(true));
    assertEquals(true, fxValue(control::value));
  }

  @Test
  void selectRendersAComboBoxOverTheOptionValues() {
    FieldControl control =
        onFx(() -> FieldControlFactory.create(optionField("quality", "select", "good", "poor")));

    onFx(() -> control.setValue("poor"));

    assertEquals("poor", fxValue(control::value));
  }

  @Test
  void radioRendersOneToggleGroupAndReturnsTheSelectedValue() {
    FieldControl control =
        onFx(() -> FieldControlFactory.create(optionField("urgency", "radio", "low", "high")));

    onFx(() -> control.setValue("high"));

    assertEquals("high", fxValue(control::value));
  }

  @Test
  void anUnselectedSelectOrRadioIsAnEmptyString() {
    for (String type : List.of("select", "radio")) {
      FieldControl control =
          onFx(() -> FieldControlFactory.create(optionField("a", type, "x", "y")));

      assertEquals("", fxValue(control::value), type);
    }
  }

  @Test
  void anOptionTypeWithNullOrEmptyOptionsStillRenders() {
    // FieldDto.options is nullable and the backend does not forbid an empty list.
    for (String type : List.of("select", "radio", "checkbox")) {
      assertDoesNotThrow(
          () -> onFx(() -> FieldControlFactory.create(field("a", type))), type + " with null options");
      assertDoesNotThrow(
          () -> onFx(() -> FieldControlFactory.create(optionField("a", type))), type + " with no options");
    }
  }

  @Test
  void anUnknownTypeRendersADisabledPlaceholderInsteadOfThrowing() {
    // A newer backend may add a type this build does not know; one bad field must not blank the form.
    FieldControl control = onFx(() -> FieldControlFactory.create(field("mystery", "signature")));

    assertNotNull(control.node());
    assertTrue(fxValue(() -> control.node().isDisabled()));
    assertEquals("", fxValue(control::value), "an unrenderable field contributes no value");
  }

  private static FieldDto optionField(String name, String type, String... values) {
    return FieldDto.builder().name(name).label(name).type(type)
        .options(Arrays.stream(values).map(v -> new FieldOptionDto(v, v.toUpperCase())).toList())
        .build();
  }
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dtest=FieldControlFactoryTest test`
Expected: FAIL — the new cases fail; the six from Task 2 still pass.

- [x] **Step 3: Implement the option types and the unknown-type fallback**

`select` is a `ComboBox<FieldOptionDto>` with a cell factory showing `label` and a value of the option's
`value`. `radio` is a `ToggleGroup` of `RadioButton`s in a `VBox`, each carrying its option as user data.
`checkbox` branches on whether options are present, per the decision above. A null `options` list is treated as
empty everywhere.

An unknown type returns a `FieldControl` wrapping a disabled `TextField` whose prompt text names the type, and
whose `value()` is `""`. `FormFillPane` (Task 4) must then skip it when building the submission map — assert
that there, not here.

- [x] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dtest=FieldControlFactoryTest test`
Expected: PASS, 13 tests.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/ui/field src/test/java/com/example/desktop/ui/field
git commit -m "Add select, radio and checkbox controls; define unknown-type behaviour"
```

---

### Task 4: FormFillPane

**Files:**
- Create: `src/main/java/com/example/desktop/ui/forms/FormFillPane.java`
- Create: `src/main/java/com/example/desktop/ui/common/ErrorBanner.java`
- Create: `src/main/java/com/example/desktop/ui/common/BusyOverlay.java`
- Test: `src/test/java/com/example/desktop/ui/forms/FormFillPaneTest.java`

**Interfaces:**
- Consumes: `FieldControlFactory` (Tasks 2-3), `FormDataApi` (foundation Task 6), `AppState` and `Messages` (Task 1).
- Produces:
  - `FormFillPane(FormDataApi api, AppState state)` extending `BorderPane`, with `void show(FormDto form)`, `Map<String,Object> collect()` and `void submit()`.
  - Also on `FormFillPane`, and relied on by Tasks 5, 6 and 7 as well as by its own tests: `void setValues(Map<String,Object> data)` (also how the submissions pane populates an existing submission), `FieldControl controlFor(String fieldName)` returning null when there is none, `void showErrors(List<ValidationErrorDto> errors)` and `String bannerText()`.
  - `ErrorBanner` with `void show(String message)`, `void showAll(List<ValidationErrorDto> errors)` and `void clear()`.
  - `BusyOverlay` with `void setBusy(boolean busy)`.

- [x] **Step 1: Write the failing tests**

`collect()` is the seam that makes the wire format testable without a backend. Step 1 covers Review Focus item 4.

```java
class FormFillPaneTest extends UiTest {

  @Test
  void collectBuildsTheReactWireFormatForEveryFieldType() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(allTypesForm()));

    onFx(() -> pane.setValues(Map.of(
        "aText", "x", "aNumber", "42", "aDate", "2026-01-02",
        "aSelect", "s1", "aRadio", "r1", "aChecks", List.of("c1", "c3"))));

    Map<String, Object> data = fxValue(pane::collect);

    assertEquals("x", data.get("aText"));
    assertEquals("42", data.get("aNumber"));          // string, not a number
    assertEquals("2026-01-02", data.get("aDate"));
    assertEquals(List.of("c1", "c3"), data.get("aChecks"));
  }

  @Test
  void collectOmitsAnUnknownFieldTypeRatherThanSendingAnEmptyValue() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(field("mystery", "signature"), field("aText", "text"))));

    Map<String, Object> data = fxValue(pane::collect);

    assertFalse(data.containsKey("mystery"));
    assertTrue(data.containsKey("aText"));
  }

  @Test
  void aRequiredFieldLeftBlankIsRejectedBeforeAnyRequestIsMade() {
    FormDataApi failIfCalled = apiThatFails();
    FormFillPane pane = onFx(() -> new FormFillPane(failIfCalled, new AppState()));
    onFx(() -> pane.show(formWith(requiredField("name", "text"))));

    onFx(pane::submit);

    assertTrue(fxValue(() -> pane.controlFor("name").node().getStyleClass().contains("field-error")));
  }

  @Test
  void serverValidationErrorsAreShownAgainstTheNamedControls() {
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(field("email", "email"))));

    onFx(() -> pane.showErrors(List.of(new ValidationErrorDto("email", "Must be an email", "Email"))));

    assertTrue(fxValue(() -> pane.controlFor("email").node().getStyleClass().contains("field-error")));
  }

  @Test
  void aValidationErrorWithNoMatchingControlIsShownInTheBanner() {
    // The backend sends class-level errors with a null field; dropping them would leave the user
    // staring at a form that was rejected for no visible reason.
    FormFillPane pane = onFx(() -> new FormFillPane(null, new AppState()));
    onFx(() -> pane.show(formWith(field("email", "email"))));

    onFx(() -> pane.showErrors(List.of(
        new ValidationErrorDto(null, "Form is not accepting submissions", "Closed"),
        new ValidationErrorDto("notAField", "Stale field", "Unknown"))));

    String banner = fxValue(pane::bannerText);
    assertTrue(banner.contains("not accepting submissions"), banner);
    assertTrue(banner.contains("Stale field"), banner);
  }
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dtest=FormFillPaneTest test`
Expected: FAIL — `FormFillPane` does not exist.

- [x] **Step 3: Implement `ErrorBanner`, `BusyOverlay` and `FormFillPane`**

Add the test seams the tests above use: `setValues(Map)`, `controlFor(String)`, `showErrors(List)` and
`bannerText()`. They are part of the pane's API, not test-only hacks — the editor and submissions panes reuse
`setValues` to populate an existing submission.

`submit()` validates client-side first (`required`, and a basic email pattern for `type=email`), and only then
calls `FormDataApi.submit` **off the FX thread** inside a `Task`, with `BusyOverlay` set while it runs. On
`ApiException` with a 400, route the errors through `showErrors`; on any other failure show the banner.
`collect()` skips controls whose field type was unknown.

- [x] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dtest=FormFillPaneTest test`
Expected: PASS, 5 tests.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/ui/forms src/main/java/com/example/desktop/ui/common \
  src/test/java/com/example/desktop/ui/forms
git commit -m "Add FormFillPane with client-side validation and server error mapping"
```

---

### Task 5: FormListPane and the real shell

**Files:**
- Create: `src/main/java/com/example/desktop/ui/forms/FormListPane.java`
- Modify: `src/main/java/com/example/desktop/ui/dock/DockLayoutManager.java`
- Modify: `src/main/java/com/example/desktop/App.java`
- Test: `src/test/java/com/example/desktop/it/ReadPathIT.java`

**Interfaces:**
- Consumes: `FormApi`, `FormFillPane` (Task 4), `AppState` (Task 1), `StackFixture`/`TokenClient` (foundation Task 5).
- Produces:
  - `FormListPane(FormApi api, AppState state)` extending `BorderPane`, with `void refresh()` and `List<String> formKeys()`. Selecting a row sets `AppState.selectedFormKey()`; it holds no reference to any other pane.
  - `PaneFactory` — a functional interface `Node create(String nodeId)`, returning null for an id it will not build (which is how a non-admin gets no editor). It replaces `DockLayoutManager`'s private `placeholder(...)`.
  - `DockLayoutManager(SnapFX, LayoutStore, AppState, PaneFactory)` — the `AuthSession` parameter becomes `AppState`, whose `admin()` it reads for gating. Node ids are unchanged, so a layout saved by the foundation build keeps working.

- [x] **Step 1: Write the failing test**

```java
class ReadPathIT extends UiTest {

  @Test
  void listingAFormThenFillingAndSubmittingItCreatesASubmission() {
    FormApi formApi = formApi("user", "user");
    FormDataApi dataApi = formDataApi("user", "user");
    AppState state = new AppState();
    FormListPane list = onFx(() -> new FormListPane(formApi, state));
    FormFillPane fill = onFx(() -> new FormFillPane(dataApi, state));
    state.selectedFormKey().addListener((obs, old, key) -> fill.show(formApi.get(key)));

    onFx(list::refresh);
    waitUntil(() -> fxValue(list::formKeys).contains("contact"));

    onFx(() -> state.selectedFormKey().set("contact"));
    waitUntil(() -> fxValue(() -> fill.controlFor("name") != null));
    onFx(() -> fill.setValues(contactValues()));
    onFx(fill::submit);

    waitUntil(() -> dataApi.list().stream().anyMatch(d -> d.getFormKey().equals("contact")));
  }

  @Test
  void theListShowsEverySeededForm() {
    FormListPane list = onFx(() -> new FormListPane(formApi("user", "user"), new AppState()));

    onFx(list::refresh);

    waitUntil(() -> fxValue(list::formKeys).containsAll(List.of("contact", "feedback", "survey")));
  }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `mvn -Dit.test=ReadPathIT verify`
Expected: FAIL — `FormListPane` does not exist.

- [x] **Step 3: Implement `FormListPane`**

A `ListView<FormListItemDto>` with a cell factory showing the title, a refresh button, and `refresh()` loading
off the FX thread. Expose `formKeys()` for the tests. Sort by title for display — `GET /api/forms` has no
defined order, so the pane imposes one rather than showing whatever arrives.

- [x] **Step 4: Wire the real panes into the shell**

Replace `DockLayoutManager`'s `placeholder(...)` calls with a `PaneFactory` interface the caller implements;
`App` supplies it, building each pane with the APIs it needs. `App` also sets `AppState.session()` after login
and connects `selectedFormKey()` to `FormFillPane.show(...)` — loading the `FormDto` off the FX thread.

Keep the four node ids exactly as they are. A user upgrading from the foundation build has a saved layout
keyed on them.

- [x] **Step 5: Run the test to verify it passes**

Run: `mvn -Dit.test=ReadPathIT verify`
Expected: PASS, 2 tests.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/example/desktop/ui src/main/java/com/example/desktop/App.java \
  src/test/java/com/example/desktop/it/ReadPathIT.java
git commit -m "Add FormListPane; replace the placeholder panes with the real ones"
```

---

### Task 6: SubmissionsPane

**Files:**
- Create: `src/main/java/com/example/desktop/ui/data/SubmissionsPane.java`
- Test: `src/test/java/com/example/desktop/it/SubmissionsIT.java`

**Interfaces:**
- Consumes: `FormDataApi`, `FormApi`, `FormFillPane` (for the edit view), `AppState`.
- Produces:
  - `SubmissionsPane(FormDataApi dataApi, FormApi formApi, AppState state)` extending `BorderPane`, with `void refresh()`, `void filterBySelectedForm(boolean on)` and `List<FormDataDto> rows()`.
  - Also, used by its own tests and by whatever wires the pane's toolbar: `boolean deleteEnabled()`, `boolean editEnabled()`, `void select(long id)`, `void openForEdit(long id)` and `FieldControl editControlFor(String fieldName)`.
  - `AuthSession.username()` — added to the foundation's class, reading `preferred_username` with a `sub` fallback, which is the rule the backend uses to decide ownership.

- [x] **Step 1: Write the failing test**

Step 1 covers Review Focus item 3.

```java
class SubmissionsIT extends UiTest {

  @Test
  void aUserSeesOnlyOwnSubmissionsAndAnAdminSeesAll() {
    FormDataDto mine = formDataApi("user", "user").submit("contact", contactValues());
    SubmissionsPane userPane = pane("user", "user");
    SubmissionsPane adminPane = pane("admin", "admin");

    onFx(userPane::refresh);
    onFx(adminPane::refresh);

    waitUntil(() -> fxValue(userPane::rows).stream().allMatch(d -> d.getSubmittedBy().equals("user")));
    waitUntil(() -> fxValue(adminPane::rows).stream().anyMatch(d -> d.getId().equals(mine.getId())));
  }

  @Test
  void filteringByTheSelectedFormNarrowsTheRowsClientSide() {
    // GET /api/form-data has no formKey parameter, so the pane has to filter what it is given.
    FormDataApi api = formDataApi("user", "user");
    api.submit("contact", contactValues());
    api.submit("feedback", feedbackValues());
    AppState state = new AppState();
    SubmissionsPane pane = onFx(() -> new SubmissionsPane(api, formApi("user", "user"), state));
    onFx(pane::refresh);
    waitUntil(() -> fxValue(pane::rows).size() >= 2);

    onFx(() -> state.selectedFormKey().set("feedback"));
    onFx(() -> pane.filterBySelectedForm(true));

    assertTrue(fxValue(pane::rows).stream().allMatch(d -> d.getFormKey().equals("feedback")));
  }

  @Test
  void deleteIsOfferedToAnAdminAndNotToAUser() {
    assertFalse(fxValue(() -> pane("user", "user").deleteEnabled()));
    assertTrue(fxValue(() -> pane("admin", "admin").deleteEnabled()));
  }

  @Test
  void anAdminIsNotOfferedEditOnSomeoneElsesSubmission() {
    // PUT /api/form-data/submission/{id} is owner-only with no admin escape, so offering the
    // action would guarantee a 403.
    FormDataDto theirs = formDataApi("user", "user").submit("contact", contactValues());
    SubmissionsPane adminPane = pane("admin", "admin");
    onFx(adminPane::refresh);
    waitUntil(() -> fxValue(adminPane::rows).stream().anyMatch(d -> d.getId().equals(theirs.getId())));

    onFx(() -> adminPane.select(theirs.getId()));

    assertFalse(fxValue(adminPane::editEnabled));
  }

  @Test
  void openingASubmissionWhoseFieldsNoLongerMatchTheFormShowsWhatItCan() {
    // A field was renamed after the submission was stored: the known values must still appear and
    // the unknown key must not blow up the edit view.
    FormDataApi api = formDataApi("user", "user");
    Map<String, Object> stale = new LinkedHashMap<>(contactValues());
    stale.put("removedField", "orphan");
    FormDataDto saved = api.submit("contact", stale);
    SubmissionsPane pane = pane("user", "user");
    onFx(pane::refresh);

    assertDoesNotThrow(() -> onFx(() -> pane.openForEdit(saved.getId())));
    assertEquals("Tester", fxValue(() -> pane.editControlFor("name").value()));
  }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `mvn -Dit.test=SubmissionsIT verify`
Expected: FAIL — `SubmissionsPane` does not exist.

- [x] **Step 3: Implement `SubmissionsPane`**

A `TableView<FormDataDto>` with columns id, formKey, submittedAt and submittedBy. Rows load off the FX thread.
`filterBySelectedForm` applies a `FilteredList` predicate against `AppState.selectedFormKey()` — the endpoint
has no filter, so this is client-side by necessity. Edit reuses a `FormFillPane` populated through `setValues`,
ignoring data keys the form no longer declares. Expose `deleteEnabled()`, `editEnabled()`, `select(long)`,
`openForEdit(long)` and `editControlFor(String)` for the tests.

Gate delete on `AppState.admin()`, and gate edit on the submission being the caller's own — compare
`submittedBy` against the session's user. Add `String username()` to `AuthSession`, reading
`preferred_username` and falling back to `sub`, which is the same rule the backend uses for ownership.

- [x] **Step 4: Run the test to verify it passes**

Run: `mvn -Dit.test=SubmissionsIT verify`
Expected: PASS, 5 tests.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/ui/data src/main/java/com/example/desktop/auth \
  src/test/java/com/example/desktop/it/SubmissionsIT.java
git commit -m "Add SubmissionsPane with client-side filtering and ownership-aware actions"
```

---

### Task 7: FormEditorPane

**Files:**
- Create: `src/main/java/com/example/desktop/ui/forms/FormEditorPane.java`
- Test: `src/test/java/com/example/desktop/it/AdminEditorIT.java`

**Interfaces:**
- Consumes: `FormApi`, `AppState`, `FieldControlFactory` (for the preview), `Messages`.
- Produces: `FormEditorPane(FormApi api, AppState state)` extending `BorderPane`, with `void editNew()`, `void edit(FormDto form)`, `FormDto collect()`, `void save()`, and — for its tests and for populating the editor from a definition — `void setDefinition(FormDto form)` and `String errorText()`.

- [x] **Step 1: Write the failing test**

Step 1 covers Review Focus item 5.

```java
class AdminEditorIT extends UiTest {

  @Test
  void anAdminCreatesAFormWithEveryFieldTypeAndItComesBack() {
    // No seeded form has a number field, so the only way to cover all nine types is to make one.
    FormApi api = formApi("admin", "admin");
    FormEditorPane editor = onFx(() -> new FormEditorPane(api, adminState()));

    onFx(() -> { editor.editNew(); editor.setDefinition(allNineTypesForm()); editor.save(); });

    waitUntil(() -> api.list().stream().anyMatch(f -> f.getFormKey().equals("it-all-types")));
    FormDto saved = api.get("it-all-types");
    assertEquals(
        List.of("text", "email", "tel", "number", "date", "textarea", "select", "radio", "checkbox"),
        saved.getFields().stream().map(FieldDto::getType).toList());
    api.delete("it-all-types");
  }

  @Test
  void serverValidationOnABadFormKeyIsShownAgainstTheKeyField() {
    FormEditorPane editor = onFx(() -> new FormEditorPane(formApi("admin", "admin"), adminState()));

    onFx(() -> { editor.editNew(); editor.setDefinition(formWithKey("Not A Key")); editor.save(); });

    waitUntil(() -> fxValue(editor::errorText).contains("formKey"));
  }

  @Test
  void aPlainUserCannotSaveAndIsToldWhy() {
    FormEditorPane editor = onFx(() -> new FormEditorPane(formApi("user", "user"), userState()));

    onFx(() -> { editor.editNew(); editor.setDefinition(formWithKey("it-denied")); editor.save(); });

    waitUntil(() -> !fxValue(editor::errorText).isBlank());
    assertFalse(formApi("admin", "admin").list().stream()
        .anyMatch(f -> f.getFormKey().equals("it-denied")));
  }

  @Test
  void deletingAFormThatAFillPaneIsOpenOnFailsReadablyRatherThanUnhandled() {
    FormApi adminApi = formApi("admin", "admin");
    adminApi.create(formWithKey("it-doomed"));
    FormFillPane fill = onFx(() -> new FormFillPane(formDataApi("user", "user"), new AppState()));
    onFx(() -> fill.show(adminApi.get("it-doomed")));

    adminApi.delete("it-doomed");
    onFx(() -> fill.setValues(Map.of("a", "x")));
    onFx(fill::submit);

    waitUntil(() -> !fxValue(fill::bannerText).isBlank());
  }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `mvn -Dit.test=AdminEditorIT verify`
Expected: FAIL — `FormEditorPane` does not exist.

- [x] **Step 3: Implement `FormEditorPane`**

Key, title and description fields plus an editable list of field rows, each with name, label, type (a
`ComboBox` over the nine types), a required toggle, and an options sub-editor shown only for `select`, `radio`
and `checkbox`. Add `setDefinition(FormDto)`, `errorText()` and `collect()` for the tests.

`save()` calls `create` or `update` off the FX thread and maps a 400's `ValidationErrorDto`s onto the matching
inputs, falling back to the banner for anything unmatched. The whole pane is only docked for an admin (Task 5),
but `save()` must still handle a 403 gracefully rather than assuming the gate held.

- [x] **Step 4: Run the test to verify it passes**

Run: `mvn -Dit.test=AdminEditorIT verify`
Expected: PASS, 4 tests.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/example/desktop/ui/forms src/test/java/com/example/desktop/it/AdminEditorIT.java
git commit -m "Add FormEditorPane for admin form create, update and delete"
```

---

### Task 8: Close the foundation's unverified behaviour

**Files:**
- Test: `src/test/java/com/example/desktop/it/ShellIT.java`
- Modify: whatever the tests expose as broken

**Interfaces:**
- Consumes: `DockLayoutManager`, `LayoutStore`, `AppState`, `AuthSession`.
- Produces: no new production API. This task converts the four items the foundation plan lists as unverified into tests, now that a headless UI harness exists.

The foundation plan's *Outcome and follow-up work* section lists these as verified by nobody. A manual launch on 2026-10-21 confirmed the panes render, the layout matches the spec and the Editor appears for an admin; the rest is still untested.

- [x] **Step 1: Write the failing tests**

```java
class ShellIT extends UiTest {

  @TempDir Path layoutDir;

  @Test
  void aLayoutSurvivesSaveAndReload() {
    LayoutStore store = new LayoutStore(layoutDir.resolve("layout.json"));
    String first = onFx(() -> { DockLayoutManager m = manager(store, adminState()); m.build(stage()); return m.currentLayout(); });

    store.save(first);
    String reloaded = onFx(() -> { DockLayoutManager m = manager(store, adminState()); m.build(stage()); return m.currentLayout(); });

    assertEquals(first, reloaded);
  }

  @Test
  void theEditorPaneIsAbsentForANonAdmin() {
    DockLayoutManager manager = onFx(() -> manager(new LayoutStore(layoutDir.resolve("u.json")), userState()));

    onFx(() -> manager.build(stage()));

    assertNull(fxValue(() -> manager.nodeById(DockLayoutManager.FORM_EDITOR)));
  }

  @Test
  void anAdminsSavedLayoutLoadedByANonAdminHasTheEditorStripped() {
    LayoutStore store = new LayoutStore(layoutDir.resolve("shared.json"));
    onFx(() -> { DockLayoutManager m = manager(store, adminState()); m.build(stage()); m.saveLayout(); });

    DockLayoutManager asUser = onFx(() -> manager(store, userState()));
    onFx(() -> asUser.build(stage()));

    assertNull(fxValue(() -> asUser.nodeById(DockLayoutManager.FORM_EDITOR)));
  }

  @Test
  void closingEveryPaneDoesNotLeaveTheNextLaunchBlank() {
    // The C1 regression: SnapFX serializes an empty graph as "{}" and loadLayout accepts it.
    LayoutStore store = new LayoutStore(layoutDir.resolve("empty.json"));
    DockLayoutManager first = onFx(() -> manager(store, adminState()));
    onFx(() -> first.build(stage()));
    onFx(() -> { first.closeAllPanes(); first.saveLayout(); });

    DockLayoutManager second = onFx(() -> manager(store, adminState()));
    onFx(() -> second.build(stage()));

    assertNotNull(fxValue(() -> second.nodeById(DockLayoutManager.FORM_LIST)),
        "a blank saved layout must fall back to the defaults");
  }
}
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `mvn -Dit.test=ShellIT verify`
Expected: FAIL — `currentLayout()`, `nodeById(String)` and `closeAllPanes()` do not exist.

- [x] **Step 3: Add the accessors and fix whatever the tests catch**

Add `currentLayout()`, `nodeById(String)` and `closeAllPanes()` to `DockLayoutManager` (the last one iterating
the graph's nodes and removing each, which is what a user does with the close buttons). If a test fails for a
real reason rather than a missing accessor, fix the production code — these four behaviours were shipped on
reasoning alone and this task exists because that is not evidence.

- [x] **Step 4: Run the tests to verify they pass**

Run: `mvn -Dit.test=ShellIT verify`
Expected: PASS, 4 tests.

- [x] **Step 5: Run the whole suite**

Run: `mvn clean verify`
Expected: every unit test and every `*IT` green.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/example/desktop/ui/dock src/test/java/com/example/desktop/it/ShellIT.java
git commit -m "Cover layout persistence, role gating and blank-layout recovery with tests"
```

---

## Exit check for the whole plan

Run: `mvn clean verify`
Expected: all unit tests and all `*IT` pass from a clean checkout, given Docker is running and
`scripts/install-snapfx.sh` has been run once. This is spec phases 4-6's combined exit check: all nine field
types render and submit, a submission can be viewed, edited and deleted under the right roles, and an admin can
create, update and delete forms.

Then launch by hand once — the one thing no test replaces:

```
mvn -q test-compile exec:java -Dexec.mainClass=com.example.desktop.Launcher -Dexec.classpathScope=test
```

Expect the form list populated, a form rendering all its field types, a submission appearing in the table after
submit, and the editor present for `admin` and absent for `user`.

## Carried-forward notes for the next plan

- Phase 7 remains: i18n coverage beyond the bundle this plan adds, error and busy UX polish, the `jpackage`
  installer, and the CI additions for it.
- The twelve deferred minor findings from the foundation review are listed in that plan's *Outcome and
  follow-up work* section and are untouched here. `m7` (the CI GHCR login step failing on a fork PR) and `m5`
  (a test that does not exercise the branch it is named for) are the two worth doing first.
- Multiple fill panes (`form-fill:{formKey}`) and `floatNode` for "open in window" — spec section 4.4. This
  plan keeps one fill pane, so the node factory still needs the prefix parse when that lands.
- Re-login on 401 (spec section 4.2 step 5). `ApiClient` surfaces a readable "unauthorized" today; nothing
  retries. With a 1 hour token this will bite a long session.
- `number` is sent as a string to match the React client. If the frontend is ever fixed to send a real JSON
  number, both have to change together or stored data becomes mixed-type.
- The single-option `checkbox` divergence recorded in Task 3: the desktop sends a one-element array where
  React sends the value string or `false`. Revisit if a form with a one-option checkbox is ever created.
- `AuthSession.username()` is added in Task 6 for the ownership check. It reads `preferred_username` with a
  `sub` fallback, mirroring the backend's `SecurityUtils`; if the backend's rule changes, this must follow.

---

## Outcome and follow-up work

All eight tasks were executed (commits `e5ed77f`..`e6b8441` on `main`), followed by `0892f7c` for the
whole-branch review. `mvn clean verify` passes from clean on `0892f7c` — **67 unit tests and 30 integration
tests, 0 failures, 0 errors** (run 2026-10-23, Docker up, all three containers started).

Three test classes grew past what their task specified, all in the review commit: `FormFillPaneTest` 5 → 9,
`SubmissionsIT` 5 → 6, `ShellIT` kept its 4 but had its layout fixture replaced, because the original could not
fail. `FieldControlFactoryTest` (13) and `AdminEditorIT` (4) match the plan as written.

### Fixed after the whole-branch review

From `0892f7c`. The two criticals both broke a path the plan's own tests passed through:

- **C2 — a rejected submission gave the user nothing.** The `field-error` style class was defined in no
  stylesheet, the banner was cleared before validating, and every client-side error was placed on an invisible
  mark. Pressing Submit on a blank required field changed nothing on screen and made no request. The backend
  validates no submission data, so this was the only validation there is. Adds the missing stylesheet, attaches
  it in `App`, and summarises problems in the banner.
- **C1 — editing a submission deleted every stored value the current form cannot render.** `collect()` built
  the PUT body from the live controls and the backend replaces `data` wholesale, so a renamed field's answer was
  gone for good. The edit now overlays the controls on the stored data. The failure was proved by reverting the
  fix: the orphaned key came back null.
- **I2** a null `FieldDto.type` NPE'd out of the factory — Review Focus item 1 covered an *unknown* type, not a
  null one. **I3** two clicks created two submissions, which `BusyOverlay`'s javadoc wrongly claimed to prevent.
  **I4** the Edit action populated a pane that was never on screen, so phase 5 view/edit did not work. **I5** an
  admin could never load an existing definition, so phase 6 was create-only. **I6** a form deleted since the
  last refresh failed silently. **I7** the layout persistence test could not fail, because its fixture was the
  default layout.
- Minors fixed in passing: stale error tooltips, the wrong message for a malformed email, a scalar `checkbox`
  value dropped on read, and a raw style literal never cleared.

### Still unverified

- **The hand launch in the plan's exit check has not been done for these panes.** The 2026-10-21 launch recorded
  in the foundation plan exercised the *placeholder* panes; nothing has confirmed the real ones render, that a
  submission appears in the table after submit, or that the editor is present for `admin` and absent for `user`
  in a live window. I4 and I5 were found by review, not by a test that watches the screen, so this is the
  remaining gap.

### Deferred minor findings

`0892f7c` states "Eleven minors remain deferred, listed in the ledger" — **that ledger was never written**, and
those findings are not recoverable from the repo. The list below is a **fresh review** of
`src/main/java/com/example/desktop/ui` (2026-10-24, the polish plan's Task 9), not a recovery of the lost one.
It happens to be eleven items; nothing should be read into that, and the four minors `0892f7c` fixed are gone
from it. Roughly in the order worth doing.

| # | Where | Issue |
|---|-------|-------|
| p1 | `SubmissionsPane:132,238,265`, `FormListPane:388`, `FormEditorPane:248` | A failure whose `getMessage()` is null reaches `banner.show(null)`, which renders as empty and hides the banner — so a delete or a load that fails with, say, an NPE tells the user nothing at all. Fall back to `common.error` whenever the message is blank, as the `cause == null` branch beside it already does |
| p2 | `FormEditorPane:210`, `SubmissionsPane:247` | Deleting a form, and deleting a submission, are irreversible and offered with no confirmation — one click on a selected row is enough. Add a confirm step |
| p3 | `FormFillPane:198`, `FormEditorPane:191`, `SubmissionsPane:125`, `FormListPane:381` | Success and "nothing here" messages are shown in `ErrorBanner`, which carries the `.error-banner` style class, so a successful submit is styled as a failure. Either a neutral/success variant or a separate status line |
| p4 | `SubmissionsPane:80,82,84` | Three `AppState` listeners are registered in the constructor and never removed, so an undocked pane stays reachable from `AppState` and leaks. Use weak listeners, or give the pane a `dispose()` the dock layer calls |
| p5 | `FormEditorPane:191` | A successful **create** reports `form.updateSuccess`. The create path needs its own key |
| p6 | `SubmissionsPane:166,204` | `select(long)` searches the filtered `shown` list while `openForEdit(long)` searches `all`, so with a filter on, a row can be opened but not selected. Pick one |
| p7 | `FormFillPane:87,226` | `form.getFields()` is dereferenced without a null check in both `show` and `validate`; `FieldDto.options` is guarded but this is not. A definition with null fields is an NPE rather than an empty form |
| p8 | `FormListPane:378` | `Comparator.comparing(FormListItemDto::getTitle)` throws if any title is null, which fails the whole list rather than one row |
| p9 | `FormListPane:352` | The refresh button's tooltip is `forms.subtitle` — a page subtitle pressed into service as an action label. It needs its own key |
| p10 | `FormFillPane:259` | `showErrors` collects unplaced errors into a `LinkedHashSet`, so two distinct fields carrying an identical message collapse into one banner line |
| p11 | `FieldControl:36` | `ERROR_TOOLTIP_KEY` is public interface API for what is purely `showError`'s bookkeeping, and it is declared between the method declarations rather than with the other constant |

None was graded critical or important: p1 is the closest, since it hides a failure, but every `ApiException`
carries a detail and only a throwable with no message at all reaches it.

The foundation plan's deferred minors are separate. As of 2026-10-24 `m1`, `m2`, `m3`, `m4`, `m5`, `m6`, `m7`,
`m8`, `m9`, `m10`, `m11`, `m12` and `m13` are all closed — see
`docs/superpowers/plans/2026-10-24-desktop-polish.md`.
