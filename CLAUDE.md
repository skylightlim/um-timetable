# 马大牛逼 (UM Timetable)

An unofficial Android app for Universiti Malaya students, built for and with the user (the repo
owner). It shows the timetable (from TimeEdit) and Spectrum, UM's Moodle (Due, and Courses with each
course's news and grades), plus a home-screen widget, notifications and in-app updates from GitHub Releases. All of it
runs on one SiswaMail sign-in. The user's phone is a Pixel 9 Pro. They use the app every day and change
it a piece at a time.

- Code: `android/app/src/main/kotlin/com/skylightlim/umtimetable/`, plain Android views in Kotlin (no
  Compose, no androidx), minSdk 31. JVM tests in `android/app/src/test/`.
- Read first: `README.md` (what it is), `docs/FINDINGS.md` (UM's data sources: Spectrum, MAYA, TimeEdit;
  where lecturer names come from). `docs/SPECTRUM-COURSES.md`: how Spectrum's course pages differ (formats, subsections, one section per
  page) and how the app arranges them like a browser.
- Icons: `android/icon/crest.py` draws the launcher, themed and status-bar icons (see its docstring).
  Don't hand-edit `ic_fg.xml`, `ic_mono.xml` or `ic_notify.xml`.

## Find your way with graphify first

On the user's machine, `graphify-out/` holds a knowledge graph of this repo, with hooks in
`.claude/settings.json`; both stay out of git. Use it before opening files (see the graphify section at
the end), then read only the files and lines it points to.

## Each change

The user also makes their own changes and commits with `git add .`, so `.gitignore` keeps out everything
private or local (their `thingstodo.md`, `backups/`, the old `docs/QA-LOG.md`, `graphify-out/`, `.idea/`).

1. Make the change the user asked for, and nothing beyond it. Suggest anything extra in one line instead.
2. `cd android && ./gradlew testDebugUnitTest` must pass.
3. Commit that change alone. Say in the message what changed and what you really checked.
4. Check it on the user's phone, never in an emulator: install the **release** build there (below).
   Emulators are only for when the user asks, e.g. testing other Android versions, or for public
   screenshots, which must show the made-up student and never their real data.

## The user's phone

They often have the app open, or are on a video call shown in picture-in-picture.

- Install only once the app isn't in front, with the wait and the install in one command (a check
  printed first and acted on later is no gate: once a debug install landed while the user was in the
  app). Run it in the background while they're using it:
  `while adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | grep -q com.skylightlim.umtimetable; do sleep 10; done; adb install -r <apk>`
- Before any `adb shell input` or `screenrecord`, check right then that the app IS in front. Once a
  check went stale, and taps landed in their WhatsApp chat and video call.
- No screenshots unless they say calls and picture-in-picture are off. Read the screen with
  `uiautomator dump`, or draw pages off-screen with the debug renderer (below).
- Never `am start -S` or force-stop: it cancels the app's alarms and sync jobs. Never `adb logcat -c`:
  it also clears the crash buffer. Crash history: `adb shell dumpsys dropbox --print data_app_crash`.
- Install the release: `./gradlew assembleRelease`, then
  `adb install -r app/build/outputs/apk/release/app-release.apk`, then
  `adb shell cmd package compile -m speed -f com.skylightlim.umtimetable`. Don't broadcast to the app
  while `compile` runs. Every build type is signed with the release key, so debug and release install
  over each other and keep the data.
- Debug tools exist only in debug builds. To use them, install debug, check, then put the release back.
  - `DebugRenderReceiver` draws a page to `cache/render/<view>.png` (`<view>-dark.png` with `--ez dark true`) with nothing on screen. Views: day,
    week, due, files, sheet, settings, signout, signin, webbar, plan, pinmenu, tabicons, duel,
    duelloop, scene, icon (the launcher icon with the phone's mask). Options are in its KDoc. A still
    can't show motion: frame sheets (scene, duel, duelloop, tabicons) and poses (`--ef hopAt`,
    `--ei boxDay/boxAt`, `--ei weekShift`) stand in, and the report says what still needs a try by hand.
  - `--ei bench N` logs build, layout and draw times per page and for a week swipe (logcat `bench`).
    Run it two or three times after a debug install: the first run is about 5× slower (not compiled
    yet). A frame at the phone's 120 Hz is 8.3 ms; a week swipe costs about 4.6 ms.
  - `DebugSpectrumReceiver`: sync, attendance and TimeEdit checks, fake sign-in, raw Moodle calls (see
    its KDoc). Delete any raw dumps afterwards.
- Auto attendance logs every step to a file any build writes and adb reads without `run-as` (any
  student's phone, plugged in): `adb shell cat /sdcard/Android/data/com.skylightlim.umtimetable/files/attendance-log.txt`.
  `DebugSpectrumReceiver --es automark alarm --ei cmid N` tries the real path (an exact alarm starts
  the foreground service, app in the background) as a dry read, logged there too.
- If a debug receiver crashes, check `dumpsys activity processes | grep "Bad process"`. The widget and
  alarms stop until it's cleared: `adb shell am clear-bad-process com.skylightlim.umtimetable` does it
  without touching the screen (reinstalling doesn't), and the reinstall then re-arms the alarms.

## Publishing (public repo: github.com/skylightlim/um-timetable)

`main` is public and is the only branch: `git add . && git commit && git push`.

**Everything committed is public, forever.** Docs, tests, examples, screenshots and commit messages use a
made-up student, never the user's real occurrences, class times, lecturer names or marks.

To release a new version of the app, once the user asks:

1. Bump `versionCode` (+1) and `versionName` in `android/app/build.gradle.kts`, then commit and push. The
   user leaves the name to you: a minor version (1.4.0-beta.1) for new features, a patch for fixes only.
2. `cd android && ./gradlew assembleRelease`, and copy the APK to `UM-Timetable-<versionName>.apk`.
3. `gh release create v<versionCode> --prerelease --title <versionName> --target main
   UM-Timetable-<versionName>.apk`. The in-app updater reads the tag as the versionCode and shows the
   title and the notes in its update card. Write the notes for students: at most ten short, plain lines
   (the card shows ten, and 1.2.0-beta.1 shows Markdown marks as typed). When the user asks for a stable
   release (1.10.0 was the first), leave out `--prerelease` and `-beta` from the name.

## Things learned the hard way

- Android 12 (API 31) is supported: guard every newer API. 1.1.0-beta.1 crashed at launch on every
  Android 12 phone because of an API 33 `OnBackInvokedCallback` field.
- Spacer views need height 0 (`Ui.spacer()`). `View.postDelayed` never runs for an off-screen WebView;
  use a main-thread `Handler`. AGP's built-in Kotlin ignores `java.srcDirs` for extra dirs; use
  `kotlin.srcDirs`.
- The page inside `SwipeScrollView` has no background, so anything behind it shows through.
  `PullIndicator` clips itself to the strip the page has uncovered.
- The user's phone has the app's background data off and is often on mobile data: anything run from
  an alarm, job or receiver has no network then (a WebView load fails, and a script run on the error
  page never answers). Auto attendance runs in a short foreground service started by its exact alarm,
  which Android lets through ("Background started FGS: Allowed … ALARM_MANAGER_WHILE_IDLE").
- TimeEdit: native `HttpURLConnection` with WebView cookies gets HTTP 412, so lookups run in the
  page (`assets/timeedit-lookup.js` over a WebMessagePort). MAYA has no single sign-on: the user signs
  in themselves, then the app reads the page.
- `AcademicCalendar.kt` (UM's 2026/27 semester dates) and `Holidays.kt` (Kuala Lumpur's public holidays
  to December 2027, from the W.P. Kuala Lumpur column of kabinet.gov.my's yearly schedules, published
  each August) need next year's dates added. Spectrum's calendar has no holidays (checked October 2026).
- Drawings (`DuelView`, icons): prototype as SVG and render with `rsvg-convert` until it looks right,
  then port, generating the Kotlin path data from the prototype's shapes rather than retyping it. That's
  much faster than rebuilding the APK. Look at the drawing at real phone size (the view's dp × 3 px).
  Gold outlines on blue were clutter at launcher size; solid silhouettes read. The icon's pair are
  their own drawings (rearing silhouettes, outlines through points), not `DuelView`'s. Placed by the
  hooves they stand on, so the shield sits between them: hung from the shield by a front hoof, a
  rearing animal's body rose above it.
- `crest.py` (`uv run --with skia-pathops`): `Path.stroke` emits conics (call `convertConicsToQuads`
  before boolean ops), and a `qCurveTo` segment holds several quadratics with implied points
  (`decompose_quadratic_segment`).
- A view drawn outside its box (a hop, a lift) needs `clipChildren = false` on its parents and
  `clipToPadding = false` on any padded one: the padding clips too.
- Press feedback (`Motion.Presser`) presses the topmost view that takes touches. Overlay cards are
  `isClickable = true` (no listener needed), so pressing one leaves the scrim behind it alone.
- Springs (`Motion.Spring`) are damped like iOS's (0.78 and up: no swing back) and take a start speed:
  whatever a finger lets go of springs on with `toward(from, to, pxPerSecond)` from the tracker's
  speed, or it stalls before moving on. `SpringTest` checks they settle to rest by their duration.
- With "Remove animations" on, animator durations are zero: anything that must take time (holding Sign
  out) keeps time with a `Handler`, and its animation only shows it.
- The minute ticker redraws Day, Week and Due; state living in a page (the plan selection box) holds it
  off (`WeekGrid.holding`).
- For frequent moves, change only what changes: a full `render()` of Week cost about 17 ms, so a week
  swipe uses `WeekScreen.shift` (a new grid in a carousel, the strip's text rolled in place), and
  `WeekGrid` keeps class-name layouts across grids. `Timetable.load`, `SpectrumStore.array` and `AttendanceLog.read` are cached
  already: jank lies elsewhere. `FitText` fits to 98% of the room: at the exact width the last letter
  wrapped.
- Baselines in a row: `baselineAlignedChildIndex` must be set after the children are added (before, it
  throws), and a wrap-content LinearLayout measures its children unaligned, cutting off the lowest
  (Day's cards are a `BaselineRow` for this).
- Never write to a file you haven't read. Once a script opened a source file for writing before reading
  it and emptied it.

## The user's taste

The app's own typefaces (Big Shoulders, Instrument Sans, JetBrains Mono), not a design's. Lines only
between list rows, none above the first or below the last. Pull to sync instead of sync buttons. The
cow and the horse stay monochrome in the app. They pick redesign pieces one at a time and expect
exactly that piece to change.

Playful, physical motion (pops, hops, a carousel, text rolling over), kept smooth: they notice dropped
frames. Cancelled or past things fade; slashes drawn over classes looked bad (struck-through text in a
list is fine). Their swipe model: on Day a swipe steps Monday to Friday, then Week, and a swipe up opens
Week (down on Week goes back), going as it passes the line rather than on letting go; on Week a swipe
changes the week, and the Week tab, a double tap on the title, Back or another tab bring this week back.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
