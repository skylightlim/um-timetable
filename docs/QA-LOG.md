# QA log

UI fixes and the full check after resetting the app. Newest first. Each entry says what was wrong,
what changed, and how it was checked.

## How pages are checked without taking over the phone

Debug builds have `DebugRenderReceiver`. It draws one page into a PNG inside the app, so nothing
shows on the phone's screen. It draws the page content only (no top bar or tabs), at the phone's
width, from cached data:

```sh
adb shell am broadcast -n com.skylightlim.umtimetable/.DebugRenderReceiver --es view files [--ei course 151] [--ei day 1] [--ez dark true]
adb shell run-as com.skylightlim.umtimetable cat cache/render/files.png > files.png
```

`view` is day, week, due, news or files (the Courses tab). Screenshots of the real screen can catch a
video call's picture-in-picture window; use the renderer, or `uiautomator dump` for text and bounds.

## 13 October 2026 (Week: the now line was hard to see)

**Problem:** the Week grid's now line was a 2 dp light-yellow line drawn *under* the class blocks: hidden
during a class in progress (when it matters most), and about 1.7:1 against the white card in the light
theme. Only a small dot marked it.

**Change** (`WeekGrid.onDraw`): today's line is drawn over the classes, 2.5 dp in `accentText` (dark
gold in light, yellow in dark; about 6:1 on white), on a halo in the card's colour so it reads on any
course colour, with a bigger dot. A faint guide continues across the other days, under their classes.
A time pill (accent, e.g. "17:47") sits on the hour axis; the hour label it would cover is skipped.
The key's "Now" swatch matches.

**Checked:** Week rendered off-screen in light and dark at 17:47 on a Tuesday: line over GIG1017's
block, pill on the axis, guide across the week. Release build installed.

## 13 October 2026 (signed out several times: why)

**Asked:** the app signed out several times today; accidental taps or a bug?

**Found** (Android's events log, the app's files, the package's install time): every one had a cause
outside the app's own logic: app data cleared twice (the reset checks; "stop … due to clear data"),
the Courses tab's **Sign out** link once (Spectrum's data and settings wiped with the app running; it's
the only code that does that), and the move to the beta (uninstall, then a fresh install at 16:59).

**Weak spots fixed anyway:**
- **Sign out asks first** ("Sign out of Spectrum?", saying what it removes; the timetable stays). It
  was a bare link under "Import timetable from MAYA instead", just above the tab bar.
- **A Keystore hiccup no longer signs the user out:** `SecureStore.get` deleted the token on *any*
  decryption error. Now only a value the key can never open (wrong key, key gone or invalidated) is
  dropped; a passing error keeps it for the next try, and errors are logged. `get` no longer makes a
  new key (which would have orphaned the stored token).
- **The app remembers why it last signed out** (`SpectrumStore.noteSignOut`, in prefs that signing out
  doesn't clear); the Connect Spectrum card says "Signed out today 16:03: you tapped Sign out." or
  "…: the phone could no longer open the saved sign-in." Clearing data or reinstalling erases it too,
  so a card with no such line after a sign-out means one of those.

## 13 October 2026 (1.1.0-beta.1: first public beta)

- **Version** 1.1.0-beta.1 (versionCode 2).
- **Signing:** a release key of its own (RSA 4096, alias `umtimetable`) in `~/.android/`, outside the
  project and never committed; `~/.android/um-timetable-release.properties` names it, and every build
  type is signed with it when it's there. Certificate SHA-256
  `bf:c0:58:27:a2:a7:6b:c7:d5:c8:cb:3c:38:8b:05:b2:3d:47:7a:08:40:d9:d6:3c:07:ab:a9:73:d7:b2:94:20`.
  Builds signed with the old debug key can't be updated to it: uninstall once (the app's data and the
  home-screen widget go with it).
- **Public repository, personal details removed:** the user's own occurrences, the days, times and
  rooms of their classes, and lecturers' names in docs, tests and comments are replaced with made-up
  ones (tests still pass: same cases, made-up names). Published as a fresh history; the full history
  stays in a local branch (`private-history`) that is never pushed.
- README: unofficial-app note, install steps, where data goes, known gaps. `THIRD_PARTY_NOTICES.md`:
  the bundled fonts (SIL OFL 1.1) and the icon's AR PL UKai glyphs.

## 13 October 2026 (motion build hid the page and tab bar; fixed)

**Problem:** after the motion build the app showed only the top bar and day strip. The strip's new
track of slots was MATCH_PARENT inside a plain FrameLayout, which then took the whole remaining
height (`uiautomator dump`: strip frame 336–2826 px), pushing the page and tab bar off screen.
**Fix:** `Motion.RowFrame`: as tall as its last child (the row); the track and pill behind are fitted
to that height. Also: the label the pill leaves now changes colour in 90 ms (it was light text on
the bare slot for a moment).
**Checked** on screen with frame-by-frame screen recordings: the strip pill glides (and turns yellow on
today), the tab pill glides with the new page rising in, a tap on Monday slides its page in from the
left, a full swipe carries the page off and brings the next day in from the right, a short drag
springs back. **Not yet seen:** the class sheet (drag, close), the theme reveal, Show more and folders.
Note: always confirm the app is in front before every `adb input` or screen recording; once the user
had switched apps mid-test and the input went to the other app.

**Also found:** Spectrum was signed out. The events log shows the app's data cleared at 15:58 (the
user's reset test); the timetable from TimeEdit was saved at 15:59; then the Courses tab's Sign out
(the only sign-out in the app) cleared the token and caches at 16:03, before the motion build
(16:12). Not a bug; sign in again.

## 13 October 2026 (motion: springs)

**Asked for:** more animation, smooth and springy.

**Before:** one 220 ms fade-slide between days, a spinning refresh icon, the system's slide-up for
the class sheet. Tabs, the day strip, Show more, folders, the news fold and the theme switch all
changed instantly.

**Change** (`Motion.kt`, no library: a damped-spring interpolator; durations follow Android's animator
scale, so "Remove animations" lands everything at once):
- **Selection glides** (the signature): the tab bar and the day strip each have one pill that springs
  from item to item (Week chip: the pill scales to its width); the labels it leaves and reaches change
  colour with it. The day strip is now a track of slots, the pill, and transparent chips.
- **Days follow the finger:** the page tracks a sideways drag; past 60 dp or a flick it moves on and the
  next day slides in from that side, otherwise it springs back (`SwipeScrollView`).
- **Pages arrive:** a new tab's parts rise 16 dp in a cascade (28 ms apart, none waiting more than
  240 ms) from half visible; minute ticks and syncs don't animate. (First version animated only the
  first 8 parts: on Courses each course is a row plus a divider, so only 3 courses rose; fixed.)
- **Class sheet** (`BottomSheet.kt`): springs up over a fading scrim; its handle works: drag down (or
  from anywhere once scrolled to the top) and it follows, closing past a quarter or on a flick, else
  springing back; scrim tap, close and Back slide it away in 200 ms. Full screen, edge to edge.
- **In place:** Show more, folders and the news fold grow and shrink (`TransitionManager`, critically
  damped); the ▴/▾ text glyphs became the drawn chevron, turning on a spring.
- **Feedback:** anything tappable sinks under the finger (7 dp's worth: cards ~2 %, small chips up to
  8 %) and springs back; badges pop in when their count changes; the lit tab's icon hops.
- **Theme:** the new theme spreads in a circle from the theme pill over a picture of the old one.

**Checked:** builds, 19 JVM tests; release installed (animator scale 1.0 on the phone).
**Not yet seen on screen:** all of it; the user tries it. Then read dropped frames with
`adb shell dumpsys gfxinfo com.skylightlim.umtimetable` (reset at install, 16:13).

## 13 October 2026 (one SiswaMail sign-in: the timetable from TimeEdit)

**Asked for:** sign in once with SiswaMail, and the app fetches the rest itself.

**Found** (`SsoProbe`, debug): MAYA has no single sign-on (its "Microsoft Live" option is commented
out; students type their SiswaMail password into MAYA's own form), so the app can't reach MAYA
without the password. TimeEdit can: with TimeEdit signed out and Microsoft's session from the
Spectrum sign-in, a hidden page clicked TimeEdit's "Log in" and "Sign in with SSO" and got in with
no typing. TimeEdit has everything MAYA had (day, time, type, room) plus lecturers, and Spectrum's
groups give the occurrence of each course.

**Change:**
- Each full sync saves the user's occurrence per course from their Spectrum groups (`occurrences`).
- `timeedit-lookup.js` looks six weeks ahead (a mid-semester break doesn't empty it), skips exams,
  and returns each class's room, weeks on, term ("2026/S1") and TimeEdit's module name.
- `TimeEditTimetable` builds the timetable: weekly classes only (on in 2+ of the weeks), Mon–Fri,
  Spectrum's course names, the app's names and colours kept, the app's lecturer kept when TimeEdit
  lists none; `compare` lists what would change.
- "Lecturers from TimeEdit" became **"Timetable from TimeEdit"**: it signs in by itself
  (`TimeEditLookup.SignIn`) and reads the timetable once signed in. A first timetable is used
  straight away; otherwise a dialog lists what changes. After the first Spectrum sign-in, a new app
  opens it by itself. Courses: "Get timetable from TimeEdit", "Import timetable from MAYA instead"
  ("Find lecturers" is part of it now). Empty Day, Week and widget point to the Spectrum sign-in.
- Rooms: TimeEdit's "MAKMAL GABUNG (MAKMAL MIKRO 8)" → MM8 (the room in brackets), "ONLINE LECTURE"
  → Online; full names title-case after a bracket ("Makmal Gabung (Makmal Mikro 8)").
- Bug found on the way: the sign-in's retries used `web.postDelayed`, which never runs for a WebView
  that isn't on screen; now a main-thread Handler (also for the lookup's time-out).

**Checked** on the phone, off-screen (`DebugSpectrumReceiver --es sync full`, then `--es timeedit
build`, new; nothing saved): occurrences from Spectrum matched the timetable's 7/7. With TimeEdit
signed out: signed in by itself in 18 s, built in 21 s. Against the MAYA-based timetable: 11/11
classes the same (day, time, type, course, room, lecturer) after the room fixes (3 rooms differed
only in naming before). 19 JVM tests. Release build installed.
**Not yet seen:** the visible flow right after a real first sign-in (reset check, step 4).

## 13 October 2026 (Tuesday's WIX1002 card taller than the others)

**Problem:** on Tuesday the WIX1002 tutorial card had about 38dp of empty space under the lecturer.
Day cards had a minimum height of 46dp per class hour (`TimetableScreens.slot`), but a card's content
already needs about 100dp, so 2-hour classes (all the user's others) were sized by content (92dp
minimum) and only the 3-hour tutorial reached its minimum (138dp). Measured on the phone
with `uiautomator dump`: 209 px below the lecturer line vs 96 px on WIX2001; 113 px = 38dp at 480 dpi.

**Change:** no duration-based minimum; every card fits its content. The start and end times beside
the card and the "Free · 1 h" gaps show the length.

**Checked:** Tuesday rendered off-screen: the three cards are the same height. Release build installed.

## 13 October 2026 (git, tests, old web page deleted)

- **Git:** the project is a git repository, committed under the user's usual GitHub identity. `.gitignore` leaves out build output,
  `local.properties`, APKs and `backups/` (pre-git snapshots, still on disk).
- **Old web page deleted** (`web/index.html`). The README, comments
  and the MAYA import dialog ("your web page and backups stay as they are") no longer mention it.
  Its published copy (claude.ai artifact) is untouched.
- **Tests:** `./gradlew testDebugUnitTest`, 14 JVM tests, about 2 s: attendance matching with the
  real names from all 7 courses, MAYA names/term/rooms, TimeEdit names, course codes. Checked that
  they catch a regression: putting back the WIX1001 bug (a class-type mismatch costing −2 instead of
  ruling the activity out) fails `AttendanceMatchTest.wix1001`.

## 13 October 2026 (attendance link for another occurrence)

**Problem:** during the WIX1002 tutorial the app offered "Tutorial T4 By … - Attendance", another
group's (the user's tutorial is a different occurrence and lecturer, same hour). The app showed every
attendance session in Spectrum's calendar. T4's activity isn't restricted to its group, so its session
was listed for everyone; the user's own occurrence's activity never creates calendar events, so it was missing.

**Change:**
- Each sync fetches the attendance activities the user can see in the courses with a class or a
  session today (`core_course_get_contents`, attendance only; refetched on full syncs, reused by
  the background checks; saved as `attendance-activities`).
- `AttendanceMatch` picks the user's activity for each class from its name: an occurrence number
  decides; otherwise the room, lecturer (name or initials), weekday and lecture/tutorial; a name for
  another weekday or kind of class can't be that class's.
- Calendar sessions of another group's activity are left out. One that may be course-wide
  ("Attendance of Lectures", "… - all groups") stays.
- A class whose own session isn't in the calendar gets its activity's page on the Due tab:
  "Attendance · class at 17:00 … Spectrum's calendar doesn't list today's session. Tap to check your
  class's attendance page." These don't notify, don't light the widget or badge, and set no alarm.
- Removed the dead `canMark` field (from the old mod_attendance path).

**Checked** on the phone: `DebugSpectrumReceiver --es attendance check` (new) over all 7 courses gave
the right activity for every class (WIX2001: none, both course-wide), after two fixes (WIX1001's
lecture had picked the tutorials' "OCC 4, 7, 10, 14"; GIG1003's generic "Attendance of Lectures" was
called another group's). The real background sync (`--es attendance sync`, new): "3 for your classes
(2 not in the calendar), 1 for other groups left out": WIX2001's listed session, WIX1002 → the user's
occurrence's page, GIG1017 → theirs. Due tab render checked. Release build installed.
**Not yet seen:** a live listed session of the user's own (the next WIX1002 lecture).

## 13 October 2026 (courses had codes but no names)

**Problem:** after the reset every course was named by its code (WIX1001 "WIX1001") on Day, Week,
the class sheet, Courses and the widget. MAYA has no course names; the import takes them from the
cached Spectrum course list, and the timetable was imported before Spectrum's first sync. Nothing
filled them in later.

**Change:** every Spectrum sync (`SpectrumSync.run`) now names the courses the timetable knows only
by code, from Spectrum's list (`MayaImport.fillNames`; names already set are kept). A re-import and
the Courses tab also treat a name that equals its code as missing. `titleCase` keeps UM's own
spelling: EMBRACE@UMLEADER → Embrace@UMLeader (was "Embrace@umleader"); a lone "Um" → "UM".

**Checked** on the phone with the cached course list (`DebugSpectrumReceiver --es names fill`, new):
"named 7 courses from Spectrum"; Day and Courses renders show the names. Release build installed.

## 13 October 2026 (new icon: UM colours, 文鼎 font)

**Change:** the launcher icon was 马大牛逼 in Noto Sans CJK SC Black, yellow on near-black. It is now
a seal (印章, four characters 2×2), laid out like UM's crest: the blue field (#223E99, the crest's
blue), a red seal (#DA2128, UM's guideline red, Pantone 2035 C, where the crest has its hibiscus),
a gold rim (#BB9632, the crest's border) and characters in UM yellow (#FFD900, Pantone Medium Yellow),
in AR PL UKai CN (文鼎 PL 楷书, the free Arphic font). The colours come from the crest on um.edu.my
and the UM colour guideline paper (MALAYA Blue/Yellow/Red; gold isn't in it). The themed icon is the
seal's rim and characters. `android/icon/seal.py` generates both layers; the characters get a 0.9-unit
stroke so the brush strokes hold up at launcher size.

**Checked:** previews at launcher size in circle and squircle masks; drawn on the phone by Android
itself (`DebugRenderReceiver --es view icon`, new) with its circle mask, plus the themed layer.
Release build installed.

## 13 October 2026 (assignment upload: "Choose file" did nothing)

**Problem:** on an assignment's upload page in the app, Spectrum's **Choose file** didn't respond;
in Chrome it opens the file manager. Android's WebView ignores a page's file button unless the app
opens a picker itself (`WebChromeClient.onShowFileChooser`), and the web screens had no
`WebChromeClient`.

**Change** (`WebScreen`, so all four web screens): the file button opens the system picker
(`ACTION_GET_CONTENT`, any file type, like Chrome; several files when the page allows it). The chosen
files go back to the page; cancelling leaves the page as it was.

**Checked:** the phone's Files picker (DocumentsUI) answers that request. Release build installed.
**Not yet checked:** a real upload. The user tries it on their next submission.

## 13 October 2026 (the timetable is no longer built in)

**Problem:** after clearing the app's data it still showed the timetable. The build copied
`web/index.html` (with the hand-entered timetable) into the app, and `Timetable.load` fell back to
it when the app had no timetable of its own. So a cleared app showed the old hand-entered version
(an outdated lecturer name and room codes), and a reset couldn't test what
a new user sees.

**Change:** the build no longer includes `web/` (`build.gradle.kts`); `Timetable.load` uses the app's
own timetable or an empty one. The MAYA import takes known course names, short names, colours and
lecturers from the app's current timetable only (so a first import gets Spectrum's names and palette
colours, and no lecturers until TimeEdit). TimeEdit refuses politely with no timetable. The published
web page keeps its own copy.

**Checked** on the cleared phone (debug renders): Day shows "No timetable yet", Week an empty grid,
Courses signed out shows Connect Spectrum plus the MAYA and TimeEdit links. Release build reinstalled.

## 13 October 2026 (UX pass from the critique)

The critique (impeccable, single-context; 26/40) is archived in `.impeccable/critique/`. The user chose
which findings to act on; everything below is on the phone (release build).

### Week merged into Day; Grades takes its tab (done)

- Tabs are now **Day · Grades · Due · News · Courses**. The week grid is a **Week** chip after
  Friday in Day's strip (view `week` still exists, so the widget's grid tap and the MAYA/TimeEdit
  returns still land on it). The Week page lost its second "Courses" list.
- **Grades** (`SpectrumScreens.grades`, `SpectrumSync.fetchGrades`): every course's grade items
  from `gradereport_user_get_grade_items`. Courses with marks first, newest mark on top; mark, range
  and percentage; feedback; tap opens the activity on Spectrum. A notification per new or changed
  mark (channel "Marks"; the first sync only records). Tab badge for marks not yet seen.
  Unmarked attendance items are hidden: they're mostly other occurrences' (GIG1017 has OCC 1–15).
  GUL1001 answers "Cannot view grades".
- Grades load **beside** the sync: the first version waited for them and one course's report
  (GIG1003) took Spectrum 30 s, holding the whole sync, attendance included, to 31 s. Now the sync
  takes 1.4 s and grades follow (1.1 s that time); a course that fails keeps its saved items.

### News: other occurrences folded (done)

Each announcement records `forMe` from its `groupid` against the user's group per course
(`core_group_get_course_user_groups`). Identical group copies keep the user's own copy. Others sit
behind one row, "For other occurrences · 3 announcements", that opens in place; they don't notify
and don't count in the badge. First sync: 10 announcements, 3 for other occurrences.

### Day opens on the next class day (done)

`homeDay(timetable, now)`: today while a class is still to come, else the next weekday with
classes. Followed on resume and every minute until the user picks a day; tapping Day again (or
opening Day from the widget) goes back to it. At 00:04 on Tuesday it opened Tuesday with the status
card "Next · in 7 h 56 min".

### Consistency (done)

- Course names: the timetable's own name for a known code, else Spectrum's in title case
  (Courses, course pages, Due, Grades). "Signed in as" drops Spectrum's " ." and capitals.
- Round 10dp course dots everywhere (Courses, Grades, class sheet).
- Screen-reader labels: "Friday, 1 class", not "1 classes".

### Tap targets (done)

48dp: refresh (38dp circle in a 48dp target), theme pill (touch delegate on the top bar), Show
more, text links, module and file buttons, the day strip, the class sheet's close button. Checked
with `uiautomator dump`: strip chips 59×48dp, Week 68×48dp, tabs 72×53dp.

### Incident: SpectrumSync.kt emptied, rebuilt (resolved)

A Python one-liner opened the file for writing before reading it (`open(p,'w').write(open(p).read())`),
leaving it empty. No git. It was rebuilt from its full text read earlier in the session plus every
edit since, then checked: the readable strings compiled into the new build are a superset of the
last good build's, differing only by the intended `syncGrades` / `onGrades` additions. **Never write a
file in the same expression that reads it.** The project should go into git.

### Reset check additions

- Grades tab: signed out (connect card), first sync (no notifications), a new mark notifies once.
- News: other-occurrence row, badge counts only yours.
- Day after the last class; tap Day to return; Week chip; widget's grid tap opens Week.

## 12 October 2026 (speed and code size)

### Speed (fixed)

Measured on the phone with `DebugRenderReceiver --ei bench 30` (each page built, laid out and drawn
off-screen; averages):

| | Debug, before | Debug, after code fixes | Release + AOT, after |
|---|---|---|---|
| "Signed in?" check | 5.3 ms (Keystore decrypt) | 0.001 ms | |
| Read news.json | 3.8 ms | 0.03 ms | |
| Day page | 11.4 ms | 7.6 ms | 1.8–3.7 ms |
| Week page | 9.4 ms | 7.4 ms | 1.6–3.7 ms |
| Due page | 14.1 ms | 2.0 ms | 0.4–0.9 ms |
| News page | 27.5 ms | 25.6 ms | 3.8–8.2 ms |
| Courses page | 14.1 ms | 5.8 ms | 1.2–2.8 ms |
| Widget, per size | 7.3 ms | 1.6 ms | 1.0 ms |
| Cold start (`am start -W`) | ~350 ms (8 Oct) | | 113–121 ms |
| APK | 3.8 MB | | 0.48 MB |

Ranges are the phone's CPU cores: the same build varies about 2× between runs.

- `SecureStore` decrypted the token through the Keystore (about 5 ms) on every call, and
  `signedIn()` was called several times per page. It now checks presence without decrypting
  (`SecureStore.has`) and keeps a decrypted value in memory.
- `SpectrumStore.array()` re-read and re-parsed its file on every redraw (the tab badges read
  news and attendance each time). Parsed files are kept until the file changes.
- `RichText`: converted announcement text is reused (text without images), decoded images are kept
  in memory (no pop-in on a rebuilt page), and the pattern, formatter and regexes are built once.
- Release build: R8 shrinking, resources shrunk, compiled ahead of time with `cmd package compile`.
- Tried and reverted: greedy line breaking (no measurable change).

### Show more and folders jumped to the top (fixed)

Tapping **Show more** on an announcement, or opening a folder on a course page, rebuilt the whole
page and scrolled back to the top. Both now open in place (`RichText.view`, `SpectrumScreens` folder
toggle).

### Code size (ponytail pass, applied)

- The four web screens (Spectrum sign-in, Spectrum pages, MAYA, TimeEdit) share `WebScreen`:
  title bar, status line, action button, progress bar, back through page history, links to other
  apps. 753 → 529 lines including the base.
- Deleted: the old web-page bridge `SpectrumStore.pageState()` and `SpectrumSync.isRunning`; the
  `mod_attendance_get_courses_with_today_sessions` branch and its `walk()` (Spectrum exposes no
  mod_attendance functions); the `mod_forum_get_forum_discussions_paginated` fallback; `Ui.pad`.
- Shared instead of copied: `Timetable.builtIn()` (MAYA import), `Notify.openApp` (widget), one
  `startOfDay`.

### First run: MAYA import unreachable before Spectrum sign-in (fixed)

The Day tab tells a new user to "open the Courses tab and tap Import timetable from MAYA", but the
Courses tab only showed "Connect Spectrum" until signing in. The MAYA and TimeEdit links now show
when signed out too. **Not yet tested signed out**: check it in the reset pass.

### Announcement authors ended in " ." (fixed)

Spectrum's display names come as "DR. LEILA M. HADDAD ." (a made-up name); the trailing " ." is dropped.

### Added to the reset check

- All four web screens after the `WebScreen` change: Spectrum sign-in (token caught, account
  checked), a Spectrum page from a course (downloads, external links open the browser), MAYA
  (Read this page enables after sign-in), TimeEdit (Find lecturers enables, dialog).
- Courses tab while signed out: MAYA and TimeEdit links present.
- Show more / Show less and course folders: open in place, no jump.

## 12 October 2026

### Courses list: colour marker and course code alignment (fixed)

- **Problem:** the colour marker was a 12dp rounded square, vertically centred on the whole row.
  When a course name wrapped to two lines (GIG1003, WIX1003, WIX2001), the marker sat between the
  lines while the course code sat on the first line. The "›" was a text glyph that sat about 7 px
  below the text's centre. The code and name already shared a baseline (both end at the same pixel).
- **Change** (`SpectrumScreens.courses`, `courseDot`, `res/drawable/ic_chevron.xml`):
  - The marker is a 10dp round dot. The row is top-aligned, and the dot has its own baseline so it
    lines up with the first line of text, centred on the code's capital letters.
  - The code and name keep their shared baseline.
  - The chevron is a drawn 8×16dp icon, centred in the row, no wider than the old glyph, so
    "PHILOSOPHY AND CURRENT AFFAIRS" still fits on one line.
  - Vertical padding went from 13dp to 17dp, so single-line rows stay 53dp tall. Before, the "›"
    glyph's line height set the row height.
- **Checked:** rendered in light and dark themes. Measured on GIG1003: dot 26–55 px, code capitals
  26–57 px, name's first line 24–57 px. Single-line rows 159 px apart (was 160).
- **Open:** the Week tab's legend (`TimetableScreens.kt:234`) and the class sheet header
  (`TimetableScreens.kt:375`) still use the 12dp rounded square. Decide whether they should match.

## For the reset check

To go through after the app is reset (sign out and clear its data), in order:

1. Spectrum sign-in, then the first sync: courses, Due, News, attendance.
2. News: only the past 14 days; the note and **Open Courses** link at the end.
3. Courses: list layout (above); each course page opens; files download; an assignment's **Choose file** opens the picker.
4. Right after the first Spectrum sign-in, "Timetable from TimeEdit" opens by itself, signs in
   without typing and fills in the timetable (11 classes, lecturers, rooms, `term`); the app shows Week.
5. "Get timetable from TimeEdit" again: the dialog says it already matches. MAYA import still works
   (fallback).
6. Day and Week tabs, class sheets, the widget at several sizes, light and dark themes.
7. Notifications: announcements, deadlines, attendance (background data was unblocked by 13 Oct).
