# Friendly phone UI and faster QR pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A main screen with one obvious next action, a three-page setup, no "engine" wording, and QR pairing in about 2 s.

**Architecture:** Pure Kotlin models (`Home`, `Setup.pages`) decide what the screens show and are unit-tested; `MainActivity` and `SetupActivity` only draw them with the existing `Ui` helpers. Pairing gets faster by ending the engine's pairing mode when the car leaves, and by the car page retrying fast while pairing and right after it.

**Tech Stack:** Kotlin Android Views (no new dependencies), JUnit; TypeScript page with Vitest.

**Spec:** `docs/superpowers/specs/2026-11-16-friendly-phone-ui-design.md`

## Global Constraints

- No Compose/Material/new Gradle dependencies.
- No "engine" in user-visible text. Words: Start/Stop (service), Pair/Unpair (cars), Connect/Disconnect (live car session).
- No new IEngine transactions (ids 1-13 unchanged).
- Builds: JDK 17 (`JAVA_HOME=/usr/lib/jvm/temurin-17-jdk`); `./gradlew :app:testDebugUnitTest :app:lintDebug` in `android/`; `npm test` and `npm run build` in `web/`. Chain build and install with `&&`.
- Pairing code stays out of logs, clipboard, and chat.

## Review Focus

1. Shizuku stops while the main screen is open: primary button must fall back to "Finish setup", never a dead "Pair". (Task 1 test `shizukuGoneMeansSetup`.)
2. User pressed Stop, then reopens the app: TSLink must not start by itself. (Task 3 manual check; `Prefs.engineWanted` keeps an explicit false.)
3. Pair pressed while TSLink is off: it starts, then the scanner opens once, not twice on later status updates. (Task 3: `pendingScan` cleared on first use.)
4. Car closes the pairing session before the code arrives (car page reloaded): pairing must keep waiting, not end. (Task 4: only end after `handedOver`.)
5. Fast retry must stop once pairing ends (cancel or timeout): back to normal backoff. (Task 5 test `retryMs undefined uses backoff`.)

---

### Task 1: Home model

**Files:**
- Create: `android/app/src/main/java/app/tslink/ui/Home.kt`
- Test: `android/app/src/test/java/app/tslink/ui/HomeTest.kt`

**Interfaces:**
- Consumes: `EngineStatus` (existing).
- Produces:
  - `enum class Primary { FINISH_SETUP, PAIR, DISCONNECT }`
  - `data class HomeView(val state: String, val hint: String?, val primary: Primary, val color: HomeColor)`; `enum class HomeColor { OK, WARN, MUTED }`
  - `object Home { fun view(setupReady: Boolean, status: EngineStatus): HomeView }`

- [ ] **Step 1: Write failing tests** (`HomeTest`):
  - `setupMissingMeansFinishSetup`: `view(false, Stopped).primary == FINISH_SETUP`, `state == "Finish setup to use TSLink"`.
  - `shizukuGoneMeansSetup`: `view(false, Running("Waiting for car", "ab", false)).primary == FINISH_SETUP`.
  - `offOffersPair`: `view(true, Stopped)` gives `state == "Off"`, `primary == PAIR`, `color == MUTED`.
  - `readyOffersPair`: `view(true, Running("Waiting for car", "ab", false))` gives `state == "Ready: waiting for the car"`, `hint == "In the car: join this phone's hotspot and open tslink.app"`, `primary == PAIR`, `color == WARN`.
  - `connectedOffersDisconnect`: `view(true, Running("Car connected (9.9.0.2)", "ab", true))` gives `state == "Connected to the car"`, `hint == null`, `primary == DISCONNECT`, `color == OK`.
  - `busyShowsStarting`: `view(true, Busy("Starting engine..."))` gives `state == "Starting..."`, `primary == PAIR`.
  - `noEngineWordAnywhere`: for all the views above, `"engine" !in (state + hint).lowercase()`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests app.tslink.ui.HomeTest`. Expected: compile FAIL.
- [ ] **Step 3: Implement `Home.view`.** A `Message` status keeps the last state, so `view` treats it like Stopped. The activity holds on to the last non-message status.
- [ ] **Step 4: Run again.** Expected: PASS.
- [ ] **Step 5: Commit** "Home model: one next action per state".

### Task 2: Setup pages model

**Files:**
- Modify: `android/app/src/main/java/app/tslink/ui/Setup.kt`
- Test: `android/app/src/test/java/app/tslink/ui/SetupTest.kt`

**Interfaces:**
- Produces:
  - `enum class SetupPage(val title: String) { SHIZUKU("Shizuku"), PHONE("Battery & hotspot"), OPTIONAL("Optional") }`
  - `fun Setup.page(id: StepId): SetupPage`. SHIZUKU_* map to SHIZUKU. BATTERY, HOTSPOT and the brand steps map to PHONE. NOTIFICATION_PERMISSION, CAR_NOTIFICATIONS and CAR map to OPTIONAL.
  - `fun Setup.shizukuStep(steps: List<SetupStep>): SetupStep`: the first Shizuku step that isn't OK, else the last Shizuku step.
  - `fun Setup.pageReady(page: SetupPage, steps: List<SetupStep>): Boolean`: every non-optional step on that page is OK (always true for OPTIONAL).
- [ ] **Step 1: Failing tests:**
  - `everyStepHasAPage`: every `StepId` maps to a page, and brand steps go to PHONE.
  - `shizukuStepIsFirstUnfinished`: with `fresh` it returns SHIZUKU_INSTALLED. With installed+running and allowed=false it returns SHIZUKU_ALLOWED. With `ready` it returns SHIZUKU_ALLOWED in state OK.
  - `pageReady`: SHIZUKU is ready only when allowed. PHONE needs battery and the hotspot tick. OPTIONAL is always ready.
- [ ] **Step 2: Run** `--tests app.tslink.ui.SetupTest`. Expected: FAIL.
- [ ] **Step 3: Implement** in `Setup.kt`.
- [ ] **Step 4: Run.** Expected: PASS (existing tests included).
- [ ] **Step 5: Commit** "Setup pages model".

### Task 3: Main screen

**Files:**
- Modify: `android/app/src/main/java/app/tslink/ui/MainActivity.kt` (layout and state; the existing helpers stay)
- Modify: `android/app/src/main/java/app/tslink/Prefs.kt:39` (`engineWanted` default `true`)
- Modify: `android/app/src/main/java/app/tslink/ui/Ui.kt` (only if a helper is missing, e.g. `expander(title): Pair<View, LinearLayout>`)

**Interfaces:**
- Consumes: `Home.view`, `Primary`, `HomeColor` (Task 1); `Setup.steps` + `Setup.allRequiredReady` for `setupReady`. Build the facts with a shared `SetupFacts` builder: move `SetupActivity.facts()` to `fun Context.setupFacts(): SetupFacts` in a new `SetupFacts.kt` under ui/.
- Produces: no API.

**Layout (top to bottom):**
1. Title "TSLink".
2. Status card:
   - dot, state and hint;
   - the message line, right under the button;
   - the primary button: FINISH_SETUP opens SetupActivity, PAIR calls `pairCar()`, DISCONNECT calls `stopEngine()` with label "Disconnect";
   - text button "Type the code instead": toggles the code view (monospace, 32 sp, hidden by default).
3. Options card:
   - "Notifications in the car" switch plus the Default/Always/Never control (existing);
   - "Start with the car" row with the car name and Choose/Change (existing logic).
4. "More" expander, collapsed:
   - hotspot segmented control with the line "Car hotspot is what the Tesla browser needs. Normal hotspot: only to switch back.";
   - "Unpair all cars": AlertDialog "Unpair all cars? Every car has to pair again with the QR code." Unpair calls `newCode()`;
   - "Stop TSLink" / "Start TSLink";
   - Device check, Log, Setup again, version, DEV line.

**Behavior:**
- `Prefs.engineWanted` defaults to true, so onCreate/onResume bind and start once Shizuku allows it. Stop sets it to false (existing).
- `pairCar()`: if not running, set `pendingScan = true` and call `startEngine()`. When `setStatus` parses `Running` with `pendingScan`, clear it and open the scanner. A start error clears `pendingScan`.
- Every user-visible string loses "engine". Examples:
  - "Start the engine first" becomes "Starting TSLink...";
  - `EngineStatus.Busy("Starting engine...")` is shown through `Home`;
  - Log dialog title "TSLink log".
- `setStatus` keeps the last non-Message status, then draws `Home.view(setupReady, last)`.

- [ ] **Step 1:** Implement the above. `./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 2:** Check that no user-visible copy says "engine": `grep -n '"[^"]*[Ee]ngine[^"]*"' MainActivity.kt SetupActivity.kt Setup.kt`. Only log tags and non-UI strings may remain.
- [ ] **Step 3: Manual on the Pixel at its normal size (screenshot):**
  - fresh state shows Finish setup;
  - Stop, reopen: stays Off;
  - Pair while Off: starts, then the scanner opens once;
  - connected shows Disconnect.
- [ ] **Step 4: Commit** "Main screen: one next action, no engine wording".

### Task 4: Paged setup

**Files:**
- Modify: `android/app/src/main/java/app/tslink/ui/SetupActivity.kt`

**Interfaces:**
- Consumes: `SetupPage`, `Setup.page`, `Setup.shizukuStep`, `Setup.pageReady` (Task 2); `Context.setupFacts()` (Task 3).

**Behavior:**
- One page at a time, with the header "Step n of 3 · <title>".
- SHIZUKU page: a single card for `Setup.shizukuStep(steps)`.
- Other pages: the existing step cards for their steps.
- Buttons: Back (hidden on page 1), and Next. Next is disabled until `pageReady`, but PHONE allows "Next" anyway, with the label "Skip for now" when not ready. On OPTIONAL the button says "Done" and finishes.
- Opens on the first page that isn't ready. The page survives rotation (`savedInstanceState` "page").
- [ ] **Step 1:** Implement. Build and lint as in Task 3.
- [ ] **Step 2: Manual on the Pixel:** Back/Next work, the Shizuku card shows Ready, Done returns to the main screen showing Pair.
- [ ] **Step 3: Commit** "Setup in three pages".

### Task 5: Faster pairing

**Files:**
- Modify: `android/app/src/main/java/app/tslink/engine/Engine.kt:225-243`
- Modify: `android/app/src/main/java/app/tslink/engine/EngineService.kt:58-89`
- Modify: `web/src/connection.ts`
- Modify: `web/src/main.ts:1100-1150`
- Test: `web/src/connection.test.ts`

**Interfaces:**
- Engine: `@Volatile var onCarLeft: (() -> Unit)?`, called in handover mode on `onSession(false)` only when `handedOver`.
- EngineService: `e.onCarLeft = { Thread { endPairing(gen) }.start() }`. Keep the 1.5 s thread as a fallback; `endPairing`'s gen check makes the second call a no-op.
- `ConnectionHooks.retryMs?: () => number | undefined`. When it returns a number, the loop waits that long instead of the backoff, and does not grow the backoff.
- main.ts:
  - `retryMs: () => pairingSecret ? 1000 : performance.now() < pairedQuietUntil ? 300 : undefined`;
  - `onPaired` calls `conn.restart()` right away (no 2.5 s timer). Closing the session is what tells the phone to switch;
  - `pairedQuietUntil = now + 8000`;
  - remove `PAIRED_SWITCH_MS`.
- [ ] **Step 1: Failing tests** in `connection.test.ts`, using the file's existing fake-session pattern:
  - `retryMs overrides backoff`: with `retryMs: () => 1000`, three failed attempts are 1000 ms apart.
  - `retryMs undefined uses backoff`: delays 1000, 2000, 4000.
- [ ] **Step 2: Run** `cd web && npx vitest run src/connection.test.ts`. Expected: FAIL.
- [ ] **Step 3:** Implement connection.ts, main.ts, Engine.kt and EngineService.kt.
- [ ] **Step 4: Run** `npm test && npm run build` and the Android build. Expected: all pass.
- [ ] **Step 5: Measure on the Pixel, before (main) and after.** Use logcat timestamps from tag TSLink: "pairing: waiting", then "pairing: code sent", then "pairing: done", then the next "session up". Use the local page at 127.0.0.1:4173 at Tesla size, or the emulator. Record scan→picture in the QA notes. Target ≤ 3 s from "pairing: waiting" to the normal session up.
- [ ] **Step 6: Commit** "Faster QR pairing: fast retries, switch when the car leaves".

### Task 6: Docs

**Files:** `README.md`, `docs/RELEASE-NOTES.md`, `docs/CAR-TEST.md`, and `docs/PROTOCOL.md` (pairing timing: the page closes after `paired`).
- [ ] Replace engine wording and the setup steps (5 steps, main-screen names). Commit "Docs: new phone UI words".

Then run the milestone passes as usual: code review, security, test coverage, simplify, and manual QA on the Pixel. QA notes go in `docs/qa/friendly-ui/notes.md`.
