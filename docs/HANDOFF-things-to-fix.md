# Handoff: the "Things to fix" branch (`fix/things-to-fix`)

What this branch changed, so the next agent can pick it up without reading the whole repo. It was made in a
cloud session from the **public snapshot** (`origin/main` at "UM Timetable 1.7.0-beta.1"), not from the
user's local `main`. To use it: fetch the branch and rebase or cherry-pick its commits onto local `main`
(one commit per item, in the order below).

**Not built or run.** The session had no Android SDK, so no Gradle build, no unit tests and no phone check
ran. Each commit was type-checked instead: all of `src/main` and `src/debug` compiled with the Kotlin
compiler (2.4.20) against Robolectric's Android 17 `android-all` jar and a generated stub `R`. That
catches type and API errors, but not resource errors (AAPT), lint, R8 or behaviour. So first:
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
