# Handoff: the "Things to fix" branch (`fix/things-to-fix`)

What this branch changed, so the next agent can pick it up without reading the whole repo. It was made in a
cloud session from the **public snapshot** (`origin/main` at "UM Timetable 1.7.0-beta.1"), not from the
user's local `main`. To use it: fetch the branch and rebase or cherry-pick its commits onto local `main`
(one commit per item, in the order below).

**Not built with Gradle or run on the phone.** The session had no Android SDK. Instead, each commit was
type-checked: all of `src/main` and `src/debug` compiled with the Kotlin compiler (2.4.20) against
Robolectric's Android 17 `android-all` jar and a generated stub `R`. From item 2 on, the JVM tests in
`src/test` were also compiled and run with JUnit 4 against that jar. That catches type and API errors and
the tested logic, but not resource errors (AAPT), lint, R8 or behaviour on screen. So first run
`cd android && ./gradlew testDebugUnitTest assembleRelease`, then try each item on the phone as listed under
"Try by hand". The QA log (`docs/QA-LOG.md`) has an entry per item, with the same caveat.

## 1. Settings as a tab of their own

- `MainActivity.TABS` ends with `settings` ("Settings", `R.drawable.avd_tab_settings`, new: three
  sliders whose rows slide over and back). `PAGE_TITLES` has it; `render()` draws `settingsPage()`.
- `SettingsSheet.build(h, context, signedIn, moved, restyle)` draws the tab's page. There's no `close`
  parameter, handle or close button any more. `SettingsSheet.Open` and `show()` are deleted, along with
  MainActivity's `settings` field.
- `openSettings()` (long press on the title, pull up on the Courses list) now does `setView("settings")`.
- Look and theme: the page's `restyle` sets `spreadFrom` (the tapped view) and `settingsMoved`, then
  calls `setLook`/`setScheme`, which restyle from `spreadFrom ?: titleBox`. `settingsPage()` uses
  `settingsMoved` once.
- `SpectrumScreens.summary(h, "settings")`: the account name (or "Not signed in") and the version.
- Debug renderer: `view settings` draws the page with the usual 16dp page padding.
- Try by hand: the tab bar, the icon's animation, changing look and theme, Back from Settings.

## 2. The attendance log in the app

- `AttendanceLog` (in `AutoAttendance.kt`) gains `Entry(time, text)` with `problem`, `read(c)`,
  `parse(text)` (a line without a time carries on the one before it) and `isProblem(text)` (the regex
  `PROBLEM`: `failed`, `"error":`, `ran out of time`, `no attendance activity`, `notifications are off`).
  Tested in `AttendanceLogTest`.
- `AttendanceLogPage` (new file): `build(h)` draws the page, `summary(c)` gives the row's and title's line.
  Problems / All are stored in `uiPrefs` as `logAll`. It shows at most 200 steps, and Copy log copies the
  file.
- Settings' Attendance section: an "Attendance log" row (`SettingsSheet.build(..., openLog)`).
- `MainActivity`: `settingsLog`/`settingsY` state. The log is drawn in place of Settings, with its own title
  and line in `showTitle`. Back (`stepBack`) or tapping the Settings tab again runs `logBack()`, and leaving
  the tab closes the log (`go`).
- Try by hand: the row's summary, the page with a real log (lots of lines: is it smooth?), Copy log, Back.

## 3. Duplicated Due items

- Cause, from the code (not confirmed on the user's data): Due listed every action event, and one activity
  can have several (quiz/lesson open + close, "expectcompletionon" + due, group overrides). Some courses
  also have an assignment copy per occurrence.
- `DueList` (new file, tested in `DueListTest`): `distinct(items)` gives one row per `activityKey`
  (`courseId/module/instance`). It prefers event type due, then close, then deadline, with
  expectcompletionon last; among those, the next still to come, else the last passed. Then it merges rows
  with the same course, name and due time. `forMe(items, occOf)` drops items named for other occurrences
  only (`AttendanceMatch.othersOnly`).
- `SpectrumSync.fetchDue(c, api, courses)` now stores `courseId`, `module`, `instance` and `eventtype` on
  each due item, runs both filters, and logs the counts (`due: N events, …`).
- Try by hand: sync, then look at Due and logcat's `due:` line.
