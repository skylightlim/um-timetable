# QA log

UI fixes and the full check after resetting the app. Newest first. Each entry says what was wrong,
what changed, and how it was checked.

## How pages are checked without taking over the phone

Debug builds have `DebugRenderReceiver`. It draws one page into a PNG inside the app, so nothing
shows on the phone's screen. It draws the page content only (no top bar or tabs), at the phone's
width, from cached data:

```sh
adb shell am broadcast -n com.skylightlim.umtimetable/.DebugRenderReceiver --es view files [--ei course 151] [--ei day 1] [--ez dark true] [--ei dow 2 --ei min 700]
adb shell run-as com.skylightlim.umtimetable cat cache/render/files.png > files.png
```

`view` is day, week, due, news or files (the Courses tab). `dow` (0 Sunday … 6 Saturday) and `min`
(minutes after midnight) pretend it's another time, e.g. during a class. Screenshots of the real screen can catch a
video call's picture-in-picture window; use the renderer, or `uiautomator dump` for text and bounds.

## 14 October 2026 (News as in redesign 1m; pull to sync)

**Asked for:** News laid out as 1m, synced as on Grades.

**Change** (`SpectrumScreens.news`, `segmented`, `lead`, `newsRow`, `newsTop`, `newsBody`):
- NEWS heading with 1m's switch beside it: "For me · 7" / "All · 10", the chosen side filled yellow.
  For me leaves out posts made only to other occurrences' groups (they used to sit behind a fold at
  the end; the fold is gone). All shows them, marked "other occurrence". The choice is kept
  (`ui` prefs `newsAll`).
- The newest post is a lead story: NEW tag while unseen, code, when; the subject in Big Shoulders 30sp;
  the author; the text (5 lines, Show more) and its files as bordered rows.
- Older posts are ruled rows (lines between them only, as on Due): NEW, code, when and a chevron; the
  subject (16sp, as before); the author. A row opens its text and files in place.
- No sync button, summary line or top-bar date; pull to sync was already on. The two-weeks note and
  Open Courses link stay at the end.

**Checked:** rendered from the synced announcements (7 for me, 10 in all), light and dark.

## 14 October 2026 (Due as in redesign 1i, in the app's fonts; pull to sync)

**Asked for:** Due laid out as 1i but in the app's current fonts; no "Wednesday 14 Oct" in the top bar;
sync as on Grades (pull down, no button, last sync shown in the pull).

**Change** (`SpectrumScreens.due`, `attendance`, `whenDue`, `span`):
- DUE heading alone, a rule under it. The sync button and the "2 upcoming · synced" line are gone;
  pull to sync was already on for Due.
- Attendance as 1i's band: yellow (accent, dark ink) while a session is open to mark: "ATTENDANCE
  OPEN · UNTIL 13:00", "Mark WIX2001 lecture" (Big Shoulders 26sp; the class kind comes from the
  timetable class at that time), an arrow. Before it opens, or when Spectrum's calendar doesn't list
  the session, the same band on the surface colour ("Attendance at 14:00 · in 50 min", "Check …" with
  the explanation).
- Deadlines as 1i's ruled rows, flat and soonest first (no day groups): an 80dp column with how long
  is left (Big Shoulders 34sp number with a 16sp unit: "40 min", "12 h", "3 d"; "Late" in red once
  overdue), yellow when under 24 h, and when it's due in mono 11sp ("today 23:59", "Wed 17:00",
  "24 Oct"), a vertical rule, then dot and code, the name (16sp) and "Assignment · course" as before.
- No date in the top bar on Due.
- The debug renderer's `--es view due --ez sample true` draws made-up deadlines and attendance.
- Follow-up (user: "the d should be larger"): number and unit are one 34sp text ("2 d", "12 h", as in
  1i) instead of a 16sp unit; "40 min" is wider than the 76dp column at 34sp, so that text alone is
  scaled down to fit (about 29sp).
- Follow-up (user: "like 1i, the 2d basically has the whole cell; the grid only inside"): the countdown
  is sized to the column's width, at most 56sp ("2 d" 56sp, "12 h" about 54sp, "40 min" about 29sp),
  and centred in the row's height. Rules only between rows and down the middle: none under the
  heading or under the last row. A first try gave the column match-parent height, which cut off the
  date under a tall countdown; it now wraps its content (so the row grows) with centre gravity.

**Checked:** rendered from the synced data (two deadlines) and the sample, light and dark. "today
00:45" wrapped in a 76dp column: widened to 80dp.

## 13 October 2026 (Grades: other occurrences' items hidden, fold chevron, award icon)

**Asked for:** hide other occurrences' items; the fold's arrow looked cut off; the Grades tab icon
from 1j.

**Found (arrow):** the chevron is an 8×16dp drawable in an 8dp-wide box, turned 90° to point up or
down, so it's 16dp wide. The row ends right at the box, so the row clipped its ends.

**Change:**
- An unmarked grade item whose name names occurrences, none of them the user's, is left out
  (`AttendanceMatch.othersOnly`, reading "OCC3", "(Occ15)", "(OCC 9)", "OCC 15 - …" with the same
  parser as attendance). The user's occurrence comes from the Spectrum groups (`occurrences`), else the
  timetable. Items naming no occurrence stay, and marked items always show. On the phone, WIX1002's
  unmarked items went from 11 to 4. Covered in `AttendanceMatchTest.gradeItemsForOtherOccurrences`.
- The fold's chevron has a 16×16dp box.
- `ic_tab_grades`: 1j's award ribbon (a circle with two ribbon tails) instead of the rosette with a star.

**Checked:** Grades rendered with the folds open; the icon's path rendered on its own. The tab bar
itself isn't in the renderer.

## 13 October 2026 (Grades: unmarked items open in place)

**Asked for:** the unmarked courses to be tappable too.

**Change** (`SpectrumScreens.unmarked`): a course under NOT MARKED YET opens in place (chevron turns,
the page moves along, as the News fold does) to list its items: name, kind, and a chevron when it has
a Spectrum page, which a tap opens. A course with marks has the same fold, "Not marked yet · 2 items",
under its cards; its course total is a line of its own above it. The open state is kept like the
other folds (`expanded`). The debug renderer's `--ez open true` draws every fold open.

**Found:** WIX1002 lists 11 unmarked items, most of them other occurrences' submissions ("Lab 3 -
OCC3 Submission", "OCC 15 - Tutorial and Lab Submission"). Not filtered yet; the user's call.

**Checked:** rendered with every fold open, from the synced data (light) and made-up marks (dark).

## 13 October 2026 (Grades as in redesign 1j, pull to sync, no attendance)

**Asked for:** Grades laid out as 1j; no "3 marks · 1 new · synced 11:32" line; sync by pulling the
page down, with a refresh animation and the last sync time there; no "Tuesday 13 Oct" in the top bar
on Grades; no attendance in Grades.

**Change:**
- `SpectrumScreens.grades` / `courseMarks`: the GRADES heading alone (no summary, no sync button). Each
  course with marks: a ruled line with dot, code, name (one line) and "2 of 4" in mono, then a card
  per mark, newest first: name with a yellow NEW tag until seen, "Quiz · marked today" under it, the
  mark (Big Shoulders 28sp, accent) and "/ 10" on one baseline, and the feedback if any. Under the
  cards: "Course total 85% · not marked yet: …" when there is one. Then NOT MARKED YET: one ruled
  line per course with its number of items. The percentage under each mark is gone, as in 1j.
- Attendance grade items (`SpectrumSync.isAttendance`) are left out of Grades, the Grades badge and
  the "New mark" notifications (one would open a tab that doesn't show it).
- Pull to sync (`SwipeScrollView.onPull`, `PullIndicator`): on Grades, Due, News and Courses when
  signed in, the page at the top follows a downward drag at half speed. Behind it, a refresh icon
  turns with the pull, with "Pull to sync" / "Release to sync" and "Last synced 11:32". Past 72dp it
  vibrates (the swipe threshold click); let go there and it syncs. The page then waits lowered while
  the icon spins ("Syncing…"), shows "Synced" for 0.6 s when the sync ends, and springs back up.
  Changing tab puts the page straight back. Due, News and Courses keep their sync buttons too.
- Top bar: no date on Grades (Due, News and Courses still show today's date).

- Not marked yet leaves out courses with nothing left to mark: on the phone two courses had only
  attendance items and showed "0 items".

**Found:** with attendance left out, Spectrum has no released marks for the user yet; the "3 marks"
Grades used to show were attendance.

**Checked:** Grades rendered off-screen in light and dark, from the synced data and from made-up marks
(`DebugRenderReceiver --es view grades --ez sample true`: NEW tags, a scale word, feedback, a course
total, unmarked items). The pull gesture can't be rendered; try it on the phone.

## 13 October 2026 (class sheet as in redesign 1f, with Open course page)

**Asked for:** the sheet a course opens (from the Week page's Courses list) with the course name large
and aligned as in 1f, an Open course page button, and no "Tap a session to open that day" line.

**Change** (`ClassSheet`, every class sheet, from a class or a course):
- Header: 12dp colour dot, code, "Occurrence N", close. The course name below it in Big Shoulders 34sp.
- Facts on a ruled two-column grid as in 1f: labels in an 84dp column, a 1px line above the grid and
  under each row, values baseline-aligned with their labels (the room's large code sits at the top).
- "Every week · 5 h", then the sessions (day, time, type, room); the one the sheet is about is
  outlined in the course colour. A session still opens its day; the line saying so is gone.
- "Open course page" (with an arrow) opens the course in the Courses tab (`Host.showCourse`). It's
  there when Spectrum has synced a course with that code.
- `ClassSheet.build` makes the content; the debug renderer draws it: `--es view sheet --es code
  WIX1002 [--ei session 1]`.

**Checked:** the course sheet (light) and a tutorial's sheet (dark) rendered off-screen.

## 13 October 2026 (Week page: Courses list as on the web page, full names in the grid, Week pill)

**Asked for:** the dark pill to cover the whole W2 OF 14 chip; no WEEK heading; full course names in
grid blocks that have room for them; the class count kept alone and merged into the section below,
in the style the course summary had before Week moved under Day (the web page's Courses list).

**Found:** the Week chip ("W2" / "OF 14") is shorter than the day chips (day, date, dots). Each chip
wrapped its own content, and the pill takes the height of the chip it moves to, so a strip of white
slot showed under it.

**Change:**
- Strip chips all match the strip's height (match parent; the row evens them out), so the pill
  covers any chip, the Week chip included.
- Week page: no heading (the dates are in the top bar, the week of the semester on the chip). The grid
  comes first, then COURSES (Big Shoulders 28sp) with "11 classes a week" on the same line, then one
  row per course as on the old web page: dot, code and name, "Occ 10 · Lecture 2 h · Tutorial 1 h ·
  online" underneath, and the week's hours large on the right. A row opens the course's class sheet.
  The hours table from the previous change is gone.
- Week grid blocks: the name takes every line that fits between the code and the room. It shows the
  full name where there's room, the short name if the full one doesn't fit, and is cut short only
  when neither fits. One-hour blocks still have no room for a name.

**Bad process (my fault, fixed):** the first build of the course list set `baselineAlignedChildIndex`
before adding the children, which throws. The off-screen renderer crashed twice in a row, so Android
marked the app a bad process: no background starts (widget updates, alarms, sync job) until the app is
opened by hand. Neither reinstalling nor `cmd jobscheduler run -f` clears it; only a user launch does.
The release build never had the bug. Check `dumpsys activity processes | grep -A1 "Bad processes"`
after any crash in a debug receiver.

## 13 October 2026 (week of the semester, hours a week per course, no day summary line)

**Asked for:** the Week chip to show which week of the semester it is, without its calendar icon or
"WEEK"; the Week page's "11 classes · …" line moved down; each course's hours a week, split into
lectures and tutorials; the "2 classes · 2 h · 08:00–14:00" line removed from every day.

**Change:**
- `AcademicCalendar.kt`: UM's bachelor-degree calendar 2026/2027, as amended by the Senate on
  22 July 2026 (aasd.um.edu.my, Attachment D, July 2026). Semester I teaching runs 5 Oct – 29 Nov
  and 7 Dec – 17 Jan, with the mid-semester break between them, then revision week and exams. The
  earlier Attachment J had a 6 + 8 week split; the amendment is 8 + 6. `week(date)` gives teaching week
  n of 14 (the break isn't counted), Break, Revision or Exams, or null outside the table. Add the
  2027/2028 calendar when UM publishes it. `AcademicCalendarTest` covers it.
- Week chip: "W2" (Big Shoulders 22sp) over "OF 14", for the strip's week; "Break", "Revision" or
  "Exams" in those weeks; "Week" outside the calendar.
- Week page: the big WEEK heading stays. The class count line sits under the grid. Below it, an
  "Hours a week" table: one row per course (dot, code, name underneath) with Lecture, Tutorial and
  Total columns (a column for any other kind of class too), "–" for none, and an All courses row. A
  row opens that course's class sheet.
- Day: no summary line. A day without classes says "No classes".

## 13 October 2026 (bigger animated title, 1a free-time rows, no vibration on some day swipes)

**Asked for:** a larger day name at the top with an animation; free time between classes as in 1a; the
user felt no vibration swiping from today to Monday or Wednesday.

**Vibration, found:** the phone's vibrator history (`dumpsys vibrator_manager`) showed day swipes
playing a TICK and then a CLICK about 15 ms later. The TICK was the "came back before the threshold"
tap, played at the start of a swipe. `SwipeScrollView` reset its "past the threshold" flag only in
`onTouchEvent`'s DOWN, which never arrives when the finger lands on a card or the Now block (the card
takes the DOWN; the scroll view takes over at the first sideways move). So after a swipe dragged past
60dp, the flag stayed set. The next swipe then played that stray TICK, and a quick flick that went
straight from the taken-over move to lifting the finger played nothing: its "bump it skipped" CLICK
is only played when the flag is clear.

**Change:**
- `SwipeScrollView`: the flag is cleared in `onInterceptTouchEvent`'s DOWN too, so every swipe starts
  clean: one CLICK when the drag passes 60dp, or when a flick lets go short of it.
- Top bar title: Big Shoulders 34sp (was 24sp). When it changes, the old title slips away and the new
  one's words (day, date, Today chip) come in one after another, 45 ms apart, from the side the day
  moved to; on another tab they rise instead (`Motion.retitle`). The minute's redraw doesn't animate it.
- Free time (`DayScreen.gap`): "Free · 2 h", a 1px line, "12:00–14:00", in mono 12sp, muted, inset 16dp
  to line up with the cards' contents. During the gap it reads "Free now · 2 h … next in 40 min" in
  the accent colour with an accent line.

## 13 October 2026 (Day: class cards from redesign 1b, strip chips centred)

**Asked for:** centre the day name, date and dots in each strip chip; class cards as in 1b, keeping
every detail (occurrence, lecturer).

**Change** (`DayScreen.slot`, `MainActivity.renderTop`):
- A class card is one row, as in 1b: start and end times (mono 14/12sp) inside the card, then
  "CODE · TYPE · Occ N" (mono 11sp), the full course name (15sp) and the lecturer (13sp, italic
  "Lecturer not listed" when missing), and the room on the right (Big Shoulders 30sp). Lecture cards
  are filled with the course colour, tutorials tinted with a colour border, as before.
- The class in progress keeps its yellow ring. Its "Ends in" label and bar are gone: the Now block
  above shows the same countdown and is always on the same page.
- Free gaps lost their 46dp indent (there's no time column outside the cards any more); 8dp between
  rows, as in 1b.
- Strip chips centre their contents.

**Checked:** Day rendered off-screen during a class, in light and dark. The strip isn't in the renderer.

## 13 October 2026 (Day: the yellow Now block and dated day strip from redesign 1b)

**Asked for:** the "Now" section of redesign option 1b, with the course name where 1b shows the room;
no semester label; the day name moved to the top with its date; dates in the day strip, keeping the
coloured dots.

**Change** (`MainActivity.renderTop`, `DayScreen`, `TimetableScreens.kt`):
- The top bar shows the chosen day and its date ("Tuesday 13 Oct", Big Shoulders 24sp) with the Today
  chip, or the week's dates on Week ("12–16 Oct"). Other tabs show today's date as a small label. The
  "UM · Sem 1 2026/27" label is gone (the timetable still keeps `term`).
- Day no longer has the big day-name heading; its summary line ("3 classes · 6 h · …") opens the page.
- The status card is now 1b's yellow block: Now / Next / Next class with the time left on the right,
  the course name large (Big Shoulders 40sp, up to 3 lines), then code · type · time with the room on
  the right, the progress bar in ink during a class, and the "Then …" line.
- Each day chip shows the short day, the date (Big Shoulders 22sp) and the class dots, left-aligned as
  in 1b, 60dp tall. The Week chip shows WEEK and its icon.
- Which week the dates belong to: the week Day opens on (`daysFromToday`). After the week's last class
  and at the weekend the strip shows next week, so Day doesn't open on a past date. The Week grid
  only marks today when the strip's week is this week. `HomeDayTest` covers this.

**Checked:** Day rendered off-screen (in class, before a class, the evening before) in light and dark.
The top bar and strip aren't in the renderer; check them on the phone.

## 13 October 2026 (vibration too light: four strengths)

**Problem:** the user found the vibration too light. The vibrator history showed why: the platform's
haptic constants played as the prebaked TICK, about the lightest effect there is, with no way to
make it stronger.

**Change** (`Haptics.kt`): vibrations are composed from the vibrator's primitives (this phone supports
CLICK, TICK, THUD, SPIN, QUICK_RISE/FALL, LOW_TICK) at the strength the user picks: **Off, Light (0.4),
Medium (0.7, the default), Strong (1.0)**. Selection and crossing a drag threshold: a click; back: a
click, then a softer tap 50 ms later; coming back before the threshold: a tap. Phones without
primitives get the predefined tick, click, double click or heavy click. Played as touch feedback, so
Android's touch-feedback setting still applies. Needs `android.permission.VIBRATE` (granted at install).
The Courses row is now **Vibration · [level] [three-bar meter]**: each tap steps Off → Light → Medium →
Strong → Off, plays the new strength and pops the meter. The first version's switch, if turned off,
reads as Off.

**Checked:** the row rendered off-screen in light and dark ("Medium", two bars). A test vibration from
the debug hook (`--es haptic tick|back|cross|uncross`, new) reached Android's vibrator service with
usage TOUCH and no error; it was ignored only because the app was in the background, as Android does.
The strengths themselves need a hand on the phone.

## 13 October 2026 (vibration feedback; the now line yellow again)

- **Now line:** back to the app's yellow (the user found the dark gold ugly), 3 dp with a bigger dot,
  still over the classes on a halo; the guide across the other days is yellow too.
- **Vibration** (`Haptics.kt`, the platform's haptic constants, so Android's touch-feedback setting
  applies too): a selection tick for a new tab, day or course page and the theme switch; a click for
  going back (Back, or tapping the lit tab to return to its start); a threshold bump when a day swipe or
  the class sheet's drag crosses the point where letting go would switch or close (and back). A short
  flick that switches without crossing gets its bump on release. Opening the app on a page (widget,
  notification, a returning screen) doesn't vibrate. Android 14+ gets the newer constants
  (SEGMENT_TICK, GESTURE_THRESHOLD_*, TOGGLE_ON); 12–13 fall back to CLOCK_TICK.
- **Vibration switch** in Courses, above Sign out ("Vibration · A light tap when you change page, go
  back, or swipe days"), on by default; the whole row toggles; read as a switch by TalkBack.
- Apps can't vibrate when the user switches to another app, so "switch" was taken as moving around
  inside this one.

**Checked:** Courses and Week rendered off-screen in light and dark; the phone's touch feedback is on.
The vibrations themselves need a hand on the phone.

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
