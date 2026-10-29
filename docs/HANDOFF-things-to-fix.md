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

## 4. Long press to dismiss a deadline

- `SpectrumStore.due(c)`: the stored due list less `dismissedDue(c)` (a string set of
  `DueList.activityKey`s). `dismissDue(c, item, gone)` adds or removes one, keeping only keys still listed.
  Every reader of what's due uses `due(c)`: `SpectrumScreens.summary("due")`, `MainActivity.weekDeadlines`,
  `Reminders.upcoming`, and `SpectrumStore.widgetAlert`.
- `SpectrumScreens.due()`: rows are built by `dueRow(h, item, now, faded)`. A long press on a row opens
  `popMenu(ui, row, menuCard(ui, ic_close, "Dismiss"))`. Dismissing hides the row and its line with
  `Motion.resize`, reschedules `Reminders`, updates the widget, then calls `h.redraw()`.
  `dismissedFold()` lists dismissed rows faded (alpha 0.45) under "Dismissed · N"; its open state is
  `expanded` key `due-dismissed`, and a long press there shows "Bring back".
- `pinMenu`/`pinCard` now go through the shared `popMenu`/`menuCard`.
- `SpectrumHost.redraw()` (default `render()`; MainActivity: `render(keepScroll = true)`, no entrance):
  redraws the page in place.
- Try by hand: dismissing the first, middle and last rows, the fold, Bring back, reminders.

## 5. News missing a GUL1001 announcement in the user's occurrence's section

- Likely cause (from the code and `SPECTRUM-COURSES.md`, not confirmed on the user's data): News read
  only `type == "news"` forums. GUL1001 has one section per occurrence, and an announcement in the user's
  occurrence's section there is probably a post in a forum inside that section.
- `NewsForums.pick(forums, occOf)` (new file, tested in `NewsForumsTest`) picks every course's news
  forum, any forum whose name holds the user's occurrence (`AttendanceMatch.occurrences`), and up to
  `PER_COURSE` (3) announcement-named forums naming no occurrence. Forums named for other occurrences are
  dropped.
- `SpectrumSync.fetchNews` now awaits the groups before picking forums, so `myOcc` is known. Each item has
  a `forum` field (empty for the Announcements forum), and `SpectrumScreens.author()` shows it.
- To confirm: sync and check logcat's `news:` line; use `DebugSpectrumReceiver --es call
  mod_forum_get_forums_by_courses` to see GUL1001's forum names. If the announcement turns out to be section
  text rather than a forum post, that's a separate feature (notify on changes to the user's section).

## 6. Grades and News fold out of each course on Courses

- Tabs are now `day`, `due`, `files` (Courses) and `settings`. `MainActivity.viewOf()` maps the old names
  "grades" and "news" (old notifications, the saved last page) to "files". `avd_tab_grades`,
  `avd_tab_news`, `ic_tab_grades` and `ic_tab_news` are deleted.
- `SpectrumScreens.courses(h, gradeItems)`: one `courseEntry()` per course. Its body is `courseRow(...)`,
  which opens `openCourse`. After a 1dp line comes a 56dp toggle (a fresh-count chip and a chevron)
  that opens the fold `foldKey(id)` = `course-fold-<id>` in `expanded`. `courseFold()` fills it lazily
  with the News label + `newsRow`s (only `forMe` items, via `myNews`), then the Grades label + `courseMarks`
  (it no longer draws a header line; it shows "N of M marked" or "No marks released yet"). Grades are
  filtered as the old Grades page did (no attendance, no unmarked items for other occurrences).
- Deleted: `SpectrumScreens.grades()`, `news()`, `olderNews()`, `courseLine()`, the News For me / All
  switch (`newsAll` pref, now unused) and summary's grades/news cases. `summary("files")` adds "N new";
  `newCount(h)` is that count and the Courses tab's badge.
- Seen: `SpectrumHost.markSeen(newsIds, gradeKeys)` (MainActivity: after 1.5 s, saved as before in
  `newsSeen`/`gradesSeen`) replaces `markNewsSeenSoon`/`markGradesSeenSoon`.
- Notifications: `Notify.openFold(c, courseId)` → `MainActivity.EXTRA_FOLD` → `openFold(id)`.
- Debug renderer: views `grades` and `news` are gone. `files --ez sample true` uses `sampleGrades` (now
  tied to the first three Spectrum course ids), `--ez open true` opens the folds, and the bench measures
  Courses with every fold open.
- Try by hand: both tap zones, the folds with real data, the badge and New, a notification.

## 7. Hours: from Week to each course's page and the Courses list

- `WeekScreen.courses()` is deleted, along with `Parts.coursesSlot` and `pending` (the redraw after a week
  swipe). `Parts` is now `(offSlot, gridBox)`.
- `Ui.kt`: `minutesByType(sessions)` (Lecture, Tutorial, then the rest by name), `hoursByType()` ("Lecture
  2 h · Tutorial 1 h") and `hoursShort()` ("L12 T5"; other kinds keep 3 letters, e.g. "Lab2"). Tested in
  `HoursTest`.
- `SpectrumScreens.hours(h, code)`: the course page's first block ("Hours" heading and a card); a tap
  calls `openSheet(course, null)`. Courses without timetable classes show nothing.
  `SpectrumScreens.totalHours(h)`: the Total row after the course list.
- The hours are the usual week's. Week used to take holidays off the shown week; that's gone with it.
- Try by hand: a course page's Hours card, the Total row, Week swipes.

## 8. Week until midnight

- `DayEnd` (top of `TimetableScreens.kt`, before `WeekScreen`): `late(c)`/`setLate(c, on)` (`uiPrefs`
  key `weekToMidnight`) and `minutes(c)` (19:00 or 24:00).
- `WeekGrid`: `endMin` and `hours` replace the fixed 19:00 and 11 hours: height, hour labels (`h % 24`,
  so midnight is "00"), lines, the now line, `halfHourAt`'s range, `room()`'s and `moveBox()`'s max end.
  Day's plan card uses `DayEnd.minutes` for its max end.
- Settings → Timetable → "Week until midnight" (a `Toggle`).
- Not changed: the widget (`WidgetRenderer.DAY_END`, still 19:00), `dayGlance`.
- Try by hand: the toggle, an evening plan, a week swipe's smoothness with the taller grid.

## 9. A course page's sections: a panel from the left, and folding sections

- `SectionsPanel.show(ui, code, color, entries, foldable, folded, foldAll)` (new file): a translucent
  `Dialog` (like `ConfirmDialog.present`, with its own Back handling) holding a scrim and a panel 320dp
  wide (at most 84% of the screen) that slides in from the left. `Entry(node, pinned, go)`.
- `SpectrumScreens.coursePage`: a named top section's heading is a toggle (`setOpen`, key
  `shut-<courseId>-<sectionId>` in `expanded` means folded) with a chevron; long press still pins.
  It collects `entries` and `foldKeys`, then calls `h.sectionsTab { SectionsPanel.show(...) }` when
  there's more than one section. A linked section's entry opens its page; any other folds open, then
  `h.scrollTo(box)` after a post.
- `SpectrumHost.sectionsTab(open)` and `scrollTo(v)` (defaults do nothing). In MainActivity,
  `sectionsTabView` (30×60dp, right-rounded, `ic_sections`, new) sits in the scroll's FrameLayout at
  START|CENTER_VERTICAL. `render()` clears `sectionsOpen` before building the page, and
  `showSectionsTab(hadTab)` shows it (sliding in only when it wasn't there before). `scrollTo` adds up
  `top`s to `content` and calls `smoothScrollTo`.
- Not done: a swipe from the left edge to open the panel (Android's back gesture uses that edge).
- Try by hand: the tab over the page's margin, the panel with a long course, jumping, Fold all, Back.
