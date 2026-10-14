# UM Timetable

A UM student's timetable and Spectrum companion: an Android app with a home-screen widget.

> **Unofficial.** Not made by or affiliated with Universiti Malaya. It signs in to UM's own systems
> (Spectrum, TimeEdit, optionally MAYA) as you, inside the app, and keeps what it reads on your phone.

## Beta

Download the APK from [Releases](../../releases) (Android 12 or newer) and open it on your phone;
Android asks once to allow installs from your browser or file app. After that the app updates itself:
when a newer release is out it offers it (at most every 12 hours, or Settings › Check for updates),
downloads it, and hands it to Android's installer. The first time, Android asks you to allow 马大牛逼
to install apps.

1. Open **Courses** and tap **Connect Spectrum**. Sign in with your SiswaMail (Microsoft); the app
   never sees your password, only the token Spectrum's own mobile sign-in gives it.
2. The app then gets your timetable from TimeEdit by itself, with the same sign-in, and shows your
   week. Add the widget from your home screen's widget list.
3. Due, News, Grades and attendance reminders come from Spectrum; turn on notifications when asked.

Where your data goes: your sign-in goes only to Microsoft and UM. The token, your timetable, courses,
announcements and marks stay in the app's private storage on your phone; nothing is sent anywhere
else, and there's no server. Signing out deletes them. The update check asks GitHub for this project's
list of releases, without signing in and without anything about you.

Known gaps in this beta: the app follows Semester 1 2026/27 classes as TimeEdit lists them;
attendance links for sessions Spectrum's calendar doesn't list are a best guess from activity names;
exams and public holidays aren't shown yet.

On the phone the app is called **马大牛逼** (label and widget name in `res/values/strings.xml`); its icon
is the cow (牛) and the horse (马) from the pull-to-sync duel holding up an empty shield in UM's shape, the
way the tigers hold up Malaysia's coat of arms, in the shield's gold on UM blue. `android/icon/crest.py`
draws it into `ic_fg.xml` / `ic_mono.xml` (the blue is `@color/ic_bg`), and the status-bar icon, the shield
alone, into `ic_notify.xml`.
The project and package (`com.skylightlim.umtimetable`) keep the name UM Timetable.

Research on UM's data sources (Spectrum, MAYA, TimeEdit, The Course Planner, UMscout) and where
lecturer names come from: [docs/FINDINGS.md](docs/FINDINGS.md).
UI fixes and the reset check are logged in [docs/QA-LOG.md](docs/QA-LOG.md). Fonts and other third-party
credits: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

- `android/`: the app (native Android screens) and the widget. A new or cleared app starts empty
  and gets everything from one SiswaMail sign-in: Spectrum, then the timetable from TimeEdit
  (`files/timetable.json`). MAYA's import is the manual fallback.
- The first version was a phone web page (`web/index.html`) with a hand-entered timetable. It was
  deleted on 13 October 2026.

## Build and install

For everyday use, install the **release** build and compile it ahead of time:

```sh
cd android
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.skylightlim.umtimetable   # wait for "Success"
```

All three builds are signed with the release key (`~/.android/um-timetable-release.properties`,
outside the project; without it, this computer's debug key), so any of them installs over another and keeps the
app's data (timetable, Spectrum sign-in):

| Build | Use | Notes |
|---|---|---|
| `release` | Everyday | R8-shrunk (about 480 KB), compiled ahead of time; cold start about 120 ms |
| `debug` | Debugging | Debug tools (`src/debug`), `run-as`, WebView inspection; Android never compiles a debuggable app ahead of time, so it runs 2–3× slower |
| `benchmark` | Timing | Release code plus the debug tools: `adb shell am broadcast -n com.skylightlim.umtimetable/.DebugRenderReceiver --ei bench 30` logs per-page times |

Two traps:

- Don't send a broadcast to the app while `cmd package compile` is still running: Android refuses to
  start it. If a background start of the app crashes, Android marks it a "bad process" and stops
  starting it in the background (widget ticks and alarms included) until the app is opened once.
- Every build that lists a receiver in its manifest must contain its class (built-in Kotlin only
  compiles `kotlin.srcDirs`; a `java.srcDirs` entry is ignored).

The widget updates itself after an install, so there's no need to remove and re-add it.

To publish a release the app can update to: raise `versionCode` (and `versionName`) in
`android/app/build.gradle.kts`, build the release, and make a GitHub release tagged `v` + versionCode
(`v3`) with the APK attached. Pre-releases count. The release's title is the version shown in the app's
update card, and the first lines of its notes are shown under it (1.2.0-beta.1 shows Markdown marks as
written, so keep the first lines plain).

## Tests

The rules that read names run as JVM tests in a few seconds, without the phone:

```sh
cd android && ./gradlew testDebugUnitTest
```

They cover which attendance activity is the user's (`AttendanceMatchTest`, with the real activity
names of all seven courses from 13 Oct 2026), the MAYA import's names, semester label and rooms
(`MayaImportTest`), the timetable built from TimeEdit's answer (`TimeEditTimetableTest`), and
TimeEdit's lecturer names, Spectrum's course codes and occurrence groups (`NamesTest`). When a
lecturer names something in a new way, add the name to a test. MAYA's page parser isn't covered:
it uses Android's HTML decoder, which doesn't run in JVM tests.


## How the widget works

- `Timetable.kt` works out the status: the class in progress or the next one,
  the countdown, and how much class is left today.
- `WidgetRenderer.kt` lays out the status and the week grid for the widget's exact size.
  Below about 260dp tall, the grid is dropped and only the status shows.
- `TimetableWidget.kt` re-renders once a minute on a non-wakeup exact alarm. While the
  screen is off the alarm waits, so it costs no battery; it fires as soon as the screen
  turns on.
- Debug builds include `WidgetPreviewActivity` to render the widget at any size, time
  and theme over adb (see `android/app/src/debug/AndroidManifest.xml`).

## Spectrum (app only)

Tabs: **Day** (with a Week chip for the Mon–Fri grid), **Grades**, **Due**, **News** and
**Courses**. All but Day are fed by Spectrum (UM's Moodle).
It is a personal replacement for UMscout that keeps everything on the phone:

- Sign-in uses Moodle's official mobile flow (`admin/tool/mobile/launch.php`, service
  `moodle_mobile_app`) inside `LoginActivity`; the `moodlemobile://token=` reply is
  checked against our passport. The token is stored AES-GCM encrypted with an Android
  Keystore key (`SecureStore`) and is only ever sent to spectrum.um.edu.my.
- `SpectrumSync` fetches courses, announcements from the past 30 days (news forums; older ones
  are on each course page; other occurrences' behind News's For me / All switch), deadlines (calendar action events), today's
  attendance sessions, and grades (alongside the sync, never holding it up); it runs every 30 min (`SpectrumSyncJob`),
  every 10 min during timetabled classes (`AttendanceWatch`), and sets reminders 24 h and
  2 h before each deadline (`Reminders`).
- The app's screens are native Android views (`MainActivity`, `TimetableScreens.kt`,
  `SpectrumScreens.kt`), with no WebView. Spectrum HTML (announcements, course pages) is
  shown with Android's own HTML renderer, which runs no scripts (`RichText.kt`); images load
  natively with the token.
- Files download to `Download/UM Spectrum/<course>/` through MediaStore.
- **Motion** (`Motion.kt`): everything moves on a hand-written damped spring (no library), and every
  duration follows Android's animator scale. The tab bar's and day strip's selection pills glide,
  pages follow a sideways drag, the class sheet (`BottomSheet.kt`) springs and drags, and tappable
  things sink under the finger (`Motion.Presser`, fed from `dispatchTouchEvent`).
- **The timetable** comes from TimeEdit, UM's timetabling system: every weekly class of the
  user's courses and occurrences, with rooms and lecturers. The occurrences come from the user's
  Spectrum groups (saved each sync as `occurrences`). After the first Spectrum sign-in, a new app
  opens `TimeEditActivity` by itself; `TimeEditLookup.SignIn` clicks TimeEdit's own "Log in" and
  "Sign in with SSO", and Microsoft lets it through with the same SiswaMail session, so the user
  types nothing. The lookup runs inside TimeEdit's page (`assets/timeedit-lookup.js`);
  `TimeEditTimetable` builds the timetable and, when the app already has one, shows what would
  change before using it. "Get timetable from TimeEdit" in Settings (the top bar's sliders button) does the same any time; "Import
  timetable from MAYA instead" is the fallback (MAYA has no single sign-on: the user signs in there).

Signing out deletes the token and cached data on the phone. To also revoke the token on
Spectrum's side, open https://spectrum.um.edu.my/user/managetoken.php and reset it.

## Performance (8 Oct 2026, Pixel 9 Pro, debug build)

| | Before | After |
|---|---|---|
| Full Spectrum sync | 2.5–3.7 s (requests one after another) | 1.0–1.6 s (announcements, deadlines and attendance in parallel; one request per forum at once) |
| Cold start to a fully drawn screen | ~520 ms (WebView page) | ~350 ms (native views) |
