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

## 15 October 2026 (Sign out: hold the button)

**Asked for:** on the Sign out question, the user should long press the Sign out button, and it should
be animated.

**Change** (`HoldButton` in ConfirmDialog.kt; `ConfirmDialog.card/show(hold = true)`):
- The button says "Hold to sign out" and takes the wider half of the card (Cancel the narrower). It
  stays on one line, shrinking if it must (`FitText` is now open for it).
- Pressed: a vibration, then a darker band sweeps across the yellow from the left, evenly over 1.1 s,
  while the button sinks slightly. At the end it pops, gives the threshold vibration and signs out.
- Let go or slide off early: the band drains back and the button springs up. A quick tap (under
  0.3 s) also shakes it sideways with a soft tick: the label says to hold.
- The time is kept by a timer, not the animation, so with "Remove animations" holding still works
  (nothing moves). TalkBack's double tap signs out at once ("Sign out. Hold to confirm").

**Checked:** it builds; unit tests pass. The card drawn off-screen on the phone: light at the phone's
width, and dark at 360dp with font size 1.3. The label fits on one line in both. The hold itself:
try it on the phone.

## 15 October 2026 (the chosen theme's dots hop)

**Asked for:** when the theme colour is changed, all the dots in the chosen theme should jump or bounce.

**Change** (`Motion.hop`, `SettingsSheet`): picking a theme redraws the still-open sheet in its colours
(spreading from the swatch), and the new swatch's five dots jump one after another, left to right, 70 ms
apart. Each one crouches, leaps (9dp for the big accent dot, 8dp for the course dots), lands with a
squash from its bottom edge, then makes a smaller second hop: about 0.7 s each, all done in about a
second. The swatch doesn't clip them; they stay inside its padding. Nothing hops with "Remove
animations" on.

**Checked:** it builds; unit tests pass. The sheet drawn off-screen on the phone (debug renderer, light
Gold and dark Blue) still shows the switches' pills and the swatches as before. The hop itself can't be
drawn off-screen: try it on the phone.

## 15 October 2026 (switches glide to the new option)

**Asked for:** changing Appearance or Vibration, the highlight just appeared on the new option; it
should move there.

**Change** (`Ui.segmented`, `SegmentPill`): the accent pill is drawn by the switch's row behind its
options, at an option's index that's fractional while it moves. A tap glides it to the option on the
app's bouncy spring; the new label turns to the ink colour as the pill arrives and the old one back to
grey (quickly, as the day strip's labels do). With a new look the Settings sheet is redrawn in the new
colours (previous entry): its Appearance switch starts at the old option and glides on, and the new
colours spread from the option tapped. News' For me / All switch is the same control.

**Checked:** it builds; unit tests pass. Tried on the phone: see the entry for this batch above.

## 15 October 2026 (Settings stays open)

**Asked for:** after a setting is changed or a button pressed, the Settings sheet shouldn't hide by
itself; only when the user hides it.

**Change:**
- No row or control closes the sheet any more. It goes only by its close button, a drag down, a tap
  outside or Back.
- A new look or theme still spreads over the page from the title. The sheet, still open, is redrawn in
  the new colours, spreading in a circle from what was tapped (the switch, the swatch) over a picture
  of the old sheet (`BottomSheet.replace`). Its navigation-bar icons follow. If the colours don't change
  (Auto to Light on a light phone), the sheet is left as it is.
- What a row opens (TimeEdit, MAYA, Android's notification settings, the sign-in) comes up over the
  sheet. Back in the app, the sheet is redrawn so it shows how it went: the timetable's class count,
  notifications On, the account name (`MainActivity.onResume`).
- Check for updates and Sign out show their card over the sheet. Sign out restarts the screen, so the
  sheet is dismissed with it (`onDestroy`) rather than left as a leaked window.

**Checked:** it builds; unit tests pass. Tried on the phone: see the entry for this batch above.

## 15 October 2026 (status-bar icon: the shield)

**Asked for:** the notification icon as the app icon's shield, without 马.

**Change:** `crest.py` also draws `ic_notify.xml`: the same UM shield outline, 16.6 × 20 in the
24-unit box with a 2-unit rim, white. `seal.py` is gone, so no font glyphs remain in the app's icons
and the AR PL UKai credit left THIRD_PARTY_NOTICES.

**Checked:** `preview-notify.svg` at 72 px (24dp on the phone) and larger, on a dark status-bar grey.

## 15 October 2026 (new launcher icon: the cow and the horse holding up UM's shield)

**Asked for:** the app icon as the duel's two animals standing like the tigers on Malaysia's coat of
arms, around a shield in UM's shape with nothing inside it, in the shield's colours.

**Change** (`android/icon/crest.py`, new; `ic_fg.xml`, `ic_mono.xml`): an empty shield with a gold rim
(flat top, straight sides for the top third, then curving to a point; UM's gold bar across it was tried
and dropped because it read as a box at launcher size). The cow stands on its left and the horse on its
right, leaning in 8° with a hoof on the rim. They are DuelView's shapes as solid gold silhouettes, like
the crest's yellow tigers: a blue gap round each piece, and blue eyes, spots, nostrils and smiles. Gold
outlines on blue were tried first and turned to clutter at icon size. Everything is gold (#BB9632) on
`ic_bg` (#223E99); skia-pathops resolves the drawing into one gold shape, so the themed icon is the same
shape. `seal.py` now only draws the status-bar icon, which is unchanged (regenerated byte-identical).

**Checked:** crest.py's previews (circle and squircle masks, at 144 and 96 px); on `Test_API37`,
`--es view icon` draws the adaptive icon with the system mask and its themed layer as expected. The
farthest point (the cow's tail tip) is 34.2 units from the centre: past the 33 safe zone, inside the
36 a round mask shows.

## 15 October 2026 (pull to sync: the duel pinned behind the page)

**Asked for:** the pull indicator pinned behind the page, so the swords crossing can be seen.

**Change** (`PullIndicator.follow`): it no longer slides down with the page. It stays at the top behind
it, clipped (`clipBounds`) to the strip the page has uncovered, because the page has no background of
its own. At half the pull the heads and blades show; at the 72dp threshold the whole duel does, blades
crossed. "Release to sync" comes into view just past it.

**Checked:** on `Test_API37` (fake sign-in, Due tab), a held `input motionevent` drag screenshotted at
half the pull, at the threshold, and after letting go; the indicator is hidden again once the page is back.

## 15 October 2026 (the cow and the horse redrawn after the icon sketch)

**Asked for:** the duel's cow and horse refined and minimal after a reference picture (a round,
upright cow and horse crossing swords), still monochrome, with fewer states that never show in the app.

**Found:** the pull indicator slides down with the page, so at the 72dp sync threshold only the bottom
~20dp of the 56dp duel is on screen, and none of it below ~70% of the pull. The sheathed and
blade-drawing phases (old progress 0–0.5) were never visible; the loop only ran 0.6–1.

**Change** (`DuelView`):
- Both are small and upright now, each a few outlined pieces (body with legs, head, arm, blade) in a
  120 × 60 box at 112 × 56dp. The cow is paper-white with solid spots, ears, eye patch and hooves, with
  faint horns and muzzle. The horse has a faintly tinted coat, a pale muzzle, a solid mane and tail. The
  tint is the ink mixed 14% into the page colour, so it follows light, dark and the accent themes.
- Fewer states: `progress` is only the swing, 0 en garde (blades up and apart) to 1 crossed. Each one
  steps in 4 and leans in 4°. The pose runs on progress², so the tips meet at 0.8 and the strike is fastest
  into the cross. The loop now runs the whole range (strike, hold, back to en garde), so every pose the
  pull can show also appears in the loader. The spark is three rays fanning up past the blade tips.
- Debug: `--es view duel` draws progress 0, 0.5, 0.8, 0.9, 1.

**Checked:** `duel.png` (light and dark) and `duelloop.png` on `Test_API37`.

## 14 October 2026 (1.2.0-beta.1 published; the update tried end to end)

**Published:** `main` on GitHub got one release commit with this tree, "UM Timetable 1.2.0-beta.1"
(a417c76), on top of 1.1.0-beta.1's, as that one was published. The local history had the user's real
occurrence in three places, now made up. The local `public` branch points at the release commit.
Pre-release `v3`, titled "1.2.0-beta.1", carries `UM-Timetable-1.2.0-beta.1.apk`. It was built from a
clean checkout of that commit: versionCode 3, signed with the release key (SHA-256 bf:c0:58…b2:94:20).

**Tried on `Test_API37`:** the same code built as versionCode 2.
- At launch, the update card offered 1.2.0-beta.1 by itself.
- Download showed the progress bar, then "Downloaded" and Install. The 605,181-byte APK passed the
  package and version check.
- Install opened Android's "Install unknown apps" page, with the toast. With it allowed, Back and
  Install again brought up Android's "Update this app?". Update.
- Play Protect then offered to scan the app ("Play Protect hasn't seen this app before"). Under "More
  details" was "Install without scanning". Expect this on phones with Play Protect.
- The app was then versionCode 3, 1.2.0-beta.1, and opened with its timetable. No crash.

**Fixed after:**
- The card showed the notes' Markdown as written ("**Android 12:**"). `Updater.plain` strips it:
  bold, code and link marks go, bullets become "•", and headings and blank lines are dropped. The
  card shows at most 10 lines, with the size under them.
- A downloaded APK is deleted once its version (or a later one) is installed.
- Both ship in the next release. 1.2.0-beta.1 shows Markdown marks in a later release's notes, so keep
  the first lines plain.

## 14 October 2026 (updates from GitHub Releases; 1.2.0-beta.1)

**Asked for:** an in-app updater backed by GitHub Releases. A release's tag is its version code ("v3" is
versionCode 3).

**Change:**
- `Updater` reads `/repos/skylightlim/um-timetable/releases`, not `/releases/latest`. GitHub's "latest"
  leaves out pre-releases, and every release so far is one, so the updater would never have found
  an update. It drops drafts and takes the highest `vN` tag that has an `.apk` asset (`Updater.pick`,
  tested in `UpdaterTest`).
- If that's newer than the installed versionCode, it offers it in the app's dialog card: the release's
  title, the first lines of its notes, its size, then Later or Download. A progress bar shows while
  it downloads.
- The APK goes into the app's external files folder (`updates/`), and older downloads are deleted
  first. It's checked to be this package at the release's versionCode, then handed to Android's
  installer (`ACTION_VIEW`) through `UpdateProvider`. That's a small read-only content provider: the
  app has no AndroidX, so no FileProvider.
- The first time, Android has to allow the app to install apps. Its settings page opens, and Install is
  tapped again on coming back.
- At launch it checks at most every 12 hours, and says nothing unless there's a newer release. "Later"
  hides that version until a manual check: Settings › About › Check for updates, whose subtitle is the
  installed version. Sign out deletes a downloaded update too.
- Version 1.2.0-beta.1, versionCode 3. The README says how to publish a release and that the check
  asks GitHub, signed out and without anything about the user.
- `ConfirmDialog` now has `frame`, `button` and `present` (shows any card, closes it and then runs the
  action), so the update card is the same card as Sign out's.

**Checked:**
- `UpdaterTest`: the highest code wins, pre-releases count, drafts don't, and tags that aren't `vN` or
  releases without an APK are skipped.
- On `Test_API37` against the real GitHub API (no `vN` release yet): Settings shows "Check for updates /
  Version 1.2.0-beta.1". Tapping it showed "Checking for updates…", then "You're up to date
  (1.2.0-beta.1)".

## 14 October 2026 (each tab's own summary under its title; Due's countdown fills its cell)

**Asked for:** the line under the title on most tabs said "Week 2 of 14 · Wednesday 14 Oct". Each page
should have its own summary instead. And Due's "1d" was too small (the "d" most), so it should fill
its cell.

**Change:**
- `SpectrumScreens.summary` counts what each page shows:
  - Grades: "5 marks · 2 new", or "No marks yet · 4 to be marked". It leaves out attendance and
    other occurrences' items, as the page does.
  - Due: "Attendance open · 3 due · next in 12h", "1 late". At most three parts.
  - News: "2 unread · latest today 14:02", or "All read · …". Posts for you only.
  - Courses: "7 courses · last synced 21:43".
  - "Not signed in" before sign-in, "Not synced yet" before the first sync.
  - Day and Week keep their lines: the day at a glance, the week's deadlines. The semester week is on
    the strip's W chip. `semesterWeek()` is gone.
- Due's countdown is one piece of text ("1d", "12h", "40min", "Late"), number and unit the same size,
  scaled to 96% of the cell's width on one line. At the exact width, "1d" broke into "1" and "d".
  "1d" comes out about 90sp and makes its row taller.
- The small line under a title wraps its text. With the title wrapping too (the previous fix), a
  full-width line got the title's width and was cut ("3 DUE ·").

**Checked** on `Test_API37` with made-up deadlines, announcements, courses, grade items and a sync
time: each tab's title and line, Day, Week, and the Due list ("Late", "12h", "1d", "10d", each across
its cell).

## 14 October 2026 (Day's titles all one size)

**Reported:** the day titles weren't the same size; all should be as big as Wednesday's.

**Wrong:** the title is the first child of a column that wraps its content, and by default a column's
child is full width. In its second pass the column measured the title again, at the column's width,
which was the title's own text width. `FitText` then fit "Wednesday 30 Sep" into that width, so
every shorter title shrank: Thursday a little, Friday most. Wednesday's is about as wide as the
sample, so it stayed big.

**Change:** titles wrap their text (`titleText` sets the layout params). `FitText` notes that it needs
a wrap-content or fixed width.

**Checked** on `Test_API37` (the phone's size, density and font scale): Thursday at launch, then
Friday, Monday, Wednesday and the Due tab's title are all the same height on screen.

## 14 October 2026 (thingstodo.md: everything but the meeting link and the bigger jobs)

**Settings without a button.** The top bar's settings button is gone.
- A long press on the title opens Settings, with the app's own "threshold" vibration. TalkBack lists
  it as the "Open settings" action.
- On the Courses list (not inside a course), pulling up from the end works like pull to sync
  (`SwipeScrollView.onPullUp`, `PullUpIndicator`). The page lifts at half speed over a turning gear,
  "Pull up for settings" becomes "Release for settings" past 72dp with a pop and a vibration, and
  letting go there opens Settings. It only starts when the list was already at its end when the
  finger went down, so it never catches a scroll mid-way.
- A new theme now spreads from the title.

**Day's title:** the full day name, "Wednesday 14 Oct". "Today" moved from a chip beside the title to
the start of the small line ("TODAY · 2 CLASSES · 08:00–13:00", "TODAY" in the accent), so the
title has the whole width. Titles shrink only when the longest title ("Wednesday 30 Sep") wouldn't
fit, the same for every day. At Pixel 9 Pro width all are 56sp; at 360dp about 47sp.

**Bugs:**
- White strips at the left and right when touching empty space on the Settings sheet. Press feedback
  picked the scrim behind the sheet (the only clickable thing under the finger) and shrank it, which
  uncovered the edges. `Motion.Presser` now stops at a scrolling panel (the sheet) and doesn't look
  at what's under it.
- A class card's edge looked like it went behind something near the end of its animation. The page
  container clipped to its 16dp side margins, so a card sliding in with another day, or springing
  back past full size after a press, was cut at an invisible line. The container no longer clips to
  its padding and the page doesn't clip its children. They're still clipped at the screen edge.
  Checked on a screen recording of a day swipe.
- Due's "2 d": the countdown is sized up to 56sp, and at that size the word space between the number
  and the unit gaped. The number is now full size and the unit half that, 2dp after it on the same
  baseline ("2d", "12h", "40min").

**Courses and Grades:** one `courseRow` for both. Courses' text is larger: the code 12 → 13sp, the
name 15 → 17sp, rows 60 → 64dp. Grades' "Not marked yet" courses use the same row (dot, code · Occ,
full name, the item count and the fold's chevron at the end), with lines between rows only. Their
items line up with the name.

**Themes:** Blue and Red changed only the accents and the course colours, so a theme looked nearly the
same. They now tint the page, cards, lines and greys too (`Neutrals`, OKLCH, the same lightness steps
as the plain greys). Blue is pale blue, navy in dark; Red is rose, maroon in dark. Text is 15.6:1 on
the page, muted ≥ 5.4:1, accent text ≥ 5.7:1. Gold keeps the plain greys, the app's look from the
start. Also, the Now block's "Then …" line used the course cards' dark ink, which was unreadable on
Blue's and Red's fills. It uses the block's own ink now (4.8–7.2:1).

**Notification icon:** the old launcher icon's week grid became the seal's rim with 马 (马大, UM)
inside. `seal.py` draws it too; the launcher icons it writes came out unchanged.

**Large font, narrow phone** (Android 12, 360dp, font size 1.3):
- "Courses" wrapped to "Course / s" in the tab bar. Tab labels stay on one line and shrink if they
  must (`FitText`).
- The Now block broke a long name inside a word ("Basic Entre / preneurship"). It shrinks until the
  longest word fits.
- The room on the Now block was cut in half when the class line wrapped. The row lined children up
  by baseline, and the wrapped text's first-line baseline pushed the room up out of the row. Now the
  bottoms line up.
- The week grid's text follows the system font size only up to 1.15×: its boxes are a fifth of the
  width and as tall as their hours. At 360dp long words are still hyphenated, as before. Fitting each
  block's words separately left the grid with mixed sizes, so it was dropped.

**Checked:**
- Screenshots on `Test_API37` (Pixel 9 Pro size) and `Android12_360dp` at 1.0 and 1.3 font scale:
  Day, Week, Due, Courses, Grades and Settings, with a made-up timetable, courses and grade items
  (debug fake sign-in, network off).
- The long press and the pull up both open Settings.
- Day in each theme, light and dark.
- `lintDebug` has 0 errors. That includes fixes to two older ones: the poster's break strategy
  constant, and a debug-only API 33 call.

## 14 October 2026 (MAYA: the app opens and reads the timetable after sign-in)

**Asked for:** after the user signs in to MAYA, the app should do the steps to get the timetable by
itself.

**Change** (`MayaActivity`):
- Signing in stays the user's: MAYA has no single sign-on.
- On each MAYA page after sign-in, the app looks for the way to the timetable. It follows the path
  in UM's MAYA guide (Timetable › My Timetable): a link to `SIW_XTTB`, else "My Timetable" (or
  "Jadual Waktu Saya"), else a timetable link that isn't the search or exams. It opens at most 3
  pages by itself.
- On the timetable page it checks twice a second, for up to 15 seconds, until the classes stop
  changing (MAYA draws them after the page loads), then reads the page.
- A first timetable is saved straight away, as TimeEdit's is. One that would replace the app's
  timetable shows the existing "Found N weekly classes" question first.
- If the app can't find the way, or nothing opens within 12 seconds, the status line says "Open
  Timetable › My Timetable in MAYA, then tap Read this page", and the button works as before.

**Checked** on `Test_API37` against a stand-in MAYA with made-up classes:
- The stand-in has a sign-in form, then a portal with a Timetable menu (My Timetable, Timetable
  Search) and an Exam Timetable link, then a Timetable View page that draws its classes 1.5 s
  after loading. It's served from the computer through `adb reverse tcp:8765 tcp:8765`, and opened
  with `DebugSpectrumReceiver --es mayaSite http://localhost:8765/` while the app is in front.
- Empty app: one tap on Login. The app opened My Timetable, read 3 classes over 2 weeks and saved
  them, about 2.5 s after sign-in.
- With a timetable: it read the page by itself, then asked "Found 3 weekly classes".
- A portal with no timetable link: it stopped and showed the "Open Timetable › My Timetable" line.

**Open:** the real MAYA menu hasn't been seen. The first real sign-in should check the log
(`MAYA: on "…", opening "…"`) and that the import happens by itself.

**Debug setup:** debug builds allow plain HTTP to localhost only (`src/debug/res/xml/
network_security_config.xml`). On the API 37 emulator the app's WebView never reached 10.0.2.2,
though the shell could, so the stand-in goes through `adb reverse` to localhost.

## 14 October 2026 (Sign out deletes everything; sign-in poster, Sign out card and Settings spacing)

**Asked for:** Sign out should delete all data and reset the app, with a short message ("all data
will be deleted, you can sign in again"). Also an audit of the first-run sign-in poster and the Sign
out section for spacing and alignment.

**Sign out** (`AppReset`): it now deletes everything the app keeps:
- the Spectrum sign-in and the Keystore key;
- the timetable;
- Spectrum's files;
- every preference file of the app's: theme, vibration, seen news and marks;
- the files and cache folders;
- the web pages' cookies, storage and cache, so TimeEdit, MAYA and Microsoft are signed out too;
- posted notifications and reminder alarms.

The widget updates to empty and the screen restarts at the first-run poster. Kept: WebView's own
preference files (not data), files saved to Downloads, and the "signed out: you tapped Sign out"
note. A sync still running can no longer write files back after sign out (`SpectrumStore.write`
checks the sign-in). The card now says "Sign out?" / "All your data in the app will be deleted.
You can sign in again any time."

**Audit findings and fixes:**
- Sign out card:
  - The labels sat at the left of the two equal buttons. They're centred now.
  - The padding was uneven: about 26dp over the title (the display face's line box sits above its
    capitals), 20dp at the sides and 16dp under the buttons. It's now 16dp top, so the title reads
    as about 20dp, and 20dp at the bottom. The body has 20dp under it.
  - The long body wrapped mid-phrase ("You can sign / in again"). Each sentence now has its own line.
- Settings sheet:
  - The space above a section label depended on what came before it: about 24dp after a control,
    about 34dp after two-line rows and about 45dp after the one-line Notifications row (60dp minimum
    height). Every label now sits 24dp under what's above it: rows keep 12dp padding (part of what
    can be tapped, 48dp minimum), and a label after rows takes the other 12dp.
  - The line under each section label is gone. Lines now run between rows only, as the user prefers
    for lists, so the one-row Account section has none.
- Sign-in poster:
  - On a tall phone (Pixel 9 Pro size) about 230dp of empty blue split the statement from the steps
    and button it introduces. The statement now sits at the bottom of its room, 24dp above the
    steps, so statement, steps and button read as one block, with the brand line alone at the top.
  - "place." sat alone on the last line. Balanced line breaks give "semester in / one place.".

**Checked:**
- Renders of the card (light, dark) and Settings (signed in light, signed out dark) at Pixel 9 Pro
  width, and the poster at that height.
- Sign out end to end on the `Test_API37` emulator: a fake debug sign-in (`--es fake signin`, network
  off), a made-up timetable and a dark Blue theme. Settings → Sign out → Sign out lands on the poster
  in the default theme. Left: `files/` and `no_backup/spectrum/` empty, `shared_prefs` holding only
  `account.xml` and WebView's two files, no crash.
- First try: the card ran Sign out (which restarts the screen) before its exit animation ended, then
  dismissed itself on the replaced screen and crashed ("not attached to window manager").
  `ConfirmDialog` now runs the action after the card has gone.

## 14 October 2026 (Android 12 phones: the app couldn't open)

**Asked for:** "make sure the app can work with most of the phone" (thingstodo.md).

**Wrong:** minSdk is 31 (Android 12), but the app used Android 13 back-gesture APIs without a version
check. Lint flagged them (NewApi). On an Android 12 emulator the published 1.1.0-beta.1 crashed at
launch: `NoClassDefFoundError` in `MainActivity.<init>`, from making an `OnBackInvokedCallback` (a
type Android 12 doesn't have) as a field. Every Android 12 phone was affected. Also on Android 12:
- Notifications were never posted. `Notify.allowed` asked for `POST_NOTIFICATIONS`, which Android 12
  doesn't have, so the check always said no. Settings showed "Off" and its button asked for a
  permission Android 12 can't grant.
- Android 12 to 14 don't draw apps behind the system bars by default (15+ do). The status bar was a
  grey strip, with white icons over the poster's grey that were hard to read.
- On a short screen (360 × 640dp) the poster's last line was cut off below the button.

**Change:**
- `MainActivity` and `WebScreen`: the back callback is made on first use, only on Android 13+.
  Android 12 steps back through `onBackPressed`, using the same `stepBack()` for the app and
  `goBack()` for web pages.
- `Notify.allowed`: on Android 12, only the app's notification switch counts. The Settings row opens
  the app's notification settings there, and sign-in no longer asks for the permission.
- `MainActivity` and `WebScreen` call `setDecorFitsSystemWindows(false)`, as Android 15+ does
  already. Their insets listeners already pad for the bars.
- `SignInScreen`: the statement takes the room the rest leaves. It autosizes from 64sp down to 28sp,
  so on a 640dp-tall screen it comes out smaller.

**Checked:**
- New emulators: `Android12_360dp` (API 31, Nexus 5 size: 360 × 640dp) and `Test_API37` (Pixel 9 Pro
  size). The user's own emulator was left alone.
- The 1.1.0-beta.1 APK from GitHub crashes at launch on API 31. The fixed build opens on both.
- Back works on both: Grades → Day → out of the app. Sign-in opens, and back closes it.
- No entries in the crash buffer on either emulator.
- Poster screenshots: whole on 360 × 640dp, unchanged at Pixel 9 Pro size.

**Open:** below Android 12 (minSdk 31) the app doesn't install at all; the widget's RemoteViews
layout APIs are why. Not checked yet: large font and display size settings, and the Day/Spectrum
tabs with data on a 360dp-wide screen.

## 14 October 2026 (the cow and the horse: loader and pull-to-sync mark)

**Asked for:** a monochrome cow and horse (after two reference pictures, any style) that draw swords
and fight, as the loading and refresh mark; their swords meeting means the loading has finished, or
the pull has gone far enough to sync. (马大牛逼: the horse and the cow are in the app's name.)

**Change** (`DuelView`, `PullIndicator`, `SpectrumScreens.loading`):
- `DuelView` draws both in one colour of rounded line art (2.2-unit strokes, the text colour, the page
  colour masking what's behind heads and necks) in a 136 × 60 box at 124 × 56dp. The cow at the left,
  facing right: horns, square muzzle with nostrils, a solid spot, tufted tail. The horse at the right,
  facing left: long head, solid mane, ear, saddle, flowing solid tail. Each raises a front hoof with a
  sword (pommel, grip, crossguard, blade).
- `progress` 0–1: 0.15–0.5 the blades come out of the hilts, held back over the shoulders; 0.5–1 the
  hooves rise, the animals lean in 2.5 units and the swords sweep forward until the blades cross at the
  middle (68, 11), where an eight-ray spark flies out and fades (420 ms) each time they cross.
- Pull to sync: the mark replaces the turning refresh icon; the pull's fraction of the 72dp threshold
  is the progress, so the blades cross (and the existing vibration bumps) exactly where letting go
  syncs. Syncing: `loop()` (swing in, clash with a spark, back to guard, every 0.9 s). Done:
  `finish()`, one last clash with its spark, then "Synced" and the page goes back up.
- Loading: "Syncing with Spectrum…" (Due, Grades, News, Courses before the first sync) and "Loading
  the course page…" show the duel fighting above the text while it's on screen.
- Debug: `--es view duel [--ez dark true]` draws the pull steps; `--es view duelloop` the loop and finish.

**Checked:** the pull steps (light, dark) and the loop. The first draft had the two nose to nose with
the swords hidden behind the horse's head; they now stand at the ends and the swords draw over all.

## 14 October 2026 (three accent themes in Settings)

**Asked for:** a choice of three themes in Settings, the current one as the default plus two more.

**Change** (`Scheme` in Ui.kt, `SettingsSheet`, `MainActivity.setScheme` / `restyle`): a Theme row under
Appearance with three swatch tiles, the chosen one outlined; choosing closes the sheet and the new
colours spread from the settings button, as Appearance does. Saved in the `ui` prefs (`scheme`). A
scheme sets the accent fill, the accent as text and the ink on the fill, in the light and the dark
palette; the neutrals and course colours stay. From UM's crest, built in OKLCH and checked:
- Gold (default, unchanged): fill #EFB810 / dark #F5C947, text #875A00 / #F5C947, ink #1B1606.
- Blue: fill #3559C3 / dark #96B6FE, text #2848A9 / #96B6FE, ink #F5F8FF / #09163F. Text on
  surfaces 8.1:1 (light) and 8.1:1 (dark); ink on the fill 5.9:1 and 8.7:1.
- Red: fill #D02C2E / dark #F68482, text #B71920 / #F68482, ink #FFF6F5 / #34080B. Text on
  surfaces 6.6:1 and 6.7:1; ink on the fill 4.8:1 and 7.2:1.
Notifications take the scheme's bright accent. The widget keeps Gold for now (its accent lives in XML
resources). The sign-in poster keeps UM's own colours. `--es scheme blue|red` renders in a scheme.

**Checked:** Settings rendered; Day in Blue and Red, light and dark.

Follow-up (user: "all other course code colours should change too, not only today's colour"): a
theme now recolours the courses as well. `Timetable.load` colours each course by its slot (the place of
its colour in the import palette, `MayaImport.PALETTE`, else its place in the list), from the theme's
set, so everything that reads a course's colour follows: cards, Week blocks, dots, sheets, Grades,
Due, News, Courses and the widget (updated on change). Gold keeps the import's colours. Blue: #B199F4
#449DF0 #54DDCE #68CA80 #E38FDB #2F9BB2 #CCE47F #35B9C0 #B0C2F9 (cool). Red: #D275D8 #EF546C #FDB171
#A3C75D #F6A9CD #CA6B49 #FDCE4E #FDB5AF #B699EB (warm). In OKLCH, with the course ink ≥ 5:1 on each and
the first seven ≥ 0.085 apart in OKLab (Gold's are 0.11; the first tries had blue/sky and coral/red
pairs at 0.06–0.07). The cache is per theme. The Theme tiles preview the accent and four course
colours. `--es scheme` also recolours the rendered timetable.

## 14 October 2026 (animated tab icons)

**Asked for:** the tab icons animated when their tab is chosen: Day's rearranging, Grades' in two parts,
a tick appearing on Due, News' ringing, Courses saving a file into the folder.

**Change** (`res/drawable/avd_tab_*.xml`, `MainActivity.renderTabs`): each tab icon is an animated
vector whose resting frame is its old `ic_tab_*` drawing; it plays once when its tab becomes the lit
one (by tap or swipe; not on opening the app; not with animations off), in place of the old hop.
- Day (≈400 ms): the three rows write themselves in, 70 ms apart (dot, then the line drawn from the
  left while the row slides 3 units in).
- Grades (420 ms): the medal comes down and the ribbon up, overshooting a little as they meet.
- Due (420 ms): the clipboard settles from 90%, then the tick draws itself.
- News (720 ms): the bell swings from its top (-16°, 14°, -9°, 5°) with the clapper lagging.
- Courses (640 ms): a page drops in from above, waits, then slides into the folder (clipped above the
  folder's top edge, so it vanishes behind it); the folder dips to 88% as it lands.
Delays are flat keyframes from 0 so nothing shows before its turn. `--es view tabicons` draws a
contact sheet of every animation, a frame each 80 ms (the first sheet showed the Courses page's edge
peeking in at the top for two frames; it now starts further up).

## 14 October 2026 (swipe across the tab bar)

**Asked for:** the bottom bar scrollable too, one section per swipe left or right.

**Change** (`Motion.RowFrame.onSwipe`, `MainActivity.buildShell`): a sideways drag on the tab bar
(past the touch slop and more sideways than up or down) is taken from the tabs; let go after 40dp, or
flick faster than 700dp/s, and it moves one tab: to the left goes to the next tab (Day → Grades →
Due → News → Courses), to the right the previous, as day swipes do. No wrap at the ends. Week counts
as Day. The new page and its title slide in from that side (as another day does), the pill glides,
and it vibrates as a tap on a tab does. A tap still opens its tab; a short sideways wobble does
nothing. The day strip (also a RowFrame) is unchanged.

**Checked:** builds; the gesture needs the phone.

## 14 October 2026 (Day and Week: a line under the title)

**Asked for:** something in the empty line under Day's and Week's titles (where the other tabs show
the week and date): the day at a glance for Day, the deadlines for Week.

**Change** (`dayGlance`, `MainActivity.weekDeadlines`, `smallLine`): Day shows "3 CLASSES ·
08:00–19:00" for the chosen day ("NO CLASSES" on a free one). Week shows the Spectrum deadlines still
to come in the strip's week, Monday to Sunday: "3 DEADLINES THIS WEEK", "NEXT WEEK" once the strip
has moved on, "NO DEADLINES …"; signed out it stays blank (still taking its height, so the strip
doesn't move). Both follow each redraw: a sync, a deadline passing, another day.

**Checked:** the three titles rendered with their lines (the Week line with a sample count).

## 14 October 2026 (day strip as low as the other tabs' first panel)

**Asked for:** the day strip lower, to match the panel on the other pages.

**Change:** Day's and Week's titles carry the other tabs' small line, invisible (`blankLine`), so all
top-bar titles are the same height; the strip sits 16dp under the title (was 10dp), as the Spectrum
tabs' pages start 10dp (the top bar's bottom padding) + 6dp (the page's) under theirs. Switching
between Day and Grades, the strip and Grades' first card start at the same height.

## 14 October 2026 (top bar: today's date on the Spectrum tabs, Day's title as large as theirs)

**Asked for:** today's date on the same line as "Week 2"; the date on Day as large as the other tabs'
titles.

**Change** (`pageTitle`, `dayTitle`, `weekTitle` in TimetableScreens.kt; `MainActivity.showTitle`):
- Grades, Due, News, Courses: the small line reads "WEEK 2 OF 14 · WEDNESDAY 14 OCT".
- Every top-bar title is Big Shoulders 56sp. Day's is two lines, the day's name and then its date
  with the Today chip, since "Wednesday 30 Sep" at 56sp (387dp) is wider than the 361dp beside the
  settings button; the second line is pulled up 6dp. Week's "28 Sep – 2 Oct" (296dp) stays on one line.
- `--es view title --es page day|week|<name>` renders each with its widest title.

**Checked:** the three rendered (light).

Follow-up (user: "one line only"): Day's title is one line again, still 56sp, with the short day name:
"Wed 14 Oct" and the Today chip ("Wed 30 Sep" and the chip take 313 of the 361dp). The full name at
56sp can't fit even without the chip.

## 14 October 2026 (Spectrum tabs: name and week of the semester in the top bar)

**Asked for:** suggestions 1 and 2 for the empty top of Grades, Due, News and Courses (the page's name
in the top bar, and the week of the semester), the text using more height, and the whole a little
lower.

**Change** (`MainActivity.showTitle`, `pageTitle`, `semesterWeek`):
- On those tabs the top bar shows the page's name in Big Shoulders 56sp (short words, so larger than
  Day's 40sp, as 1i and 1m's 56px headings) and under it "WEEK 2 OF 14" (or MID-SEMESTER BREAK,
  REVISION WEEK, EXAMS; nothing outside the calendar), from today's date. It changes with the same
  animation as the day title.
- The big heading inside each of those pages is gone (and with it `pageHeader`, `header` and the old
  sync button code); each page starts 6dp under the top bar. News' For me / All switch now runs
  full width at the top of the list.
- The top bar sits 10dp lower on every tab (22dp under the status bar, was 12dp), and the settings
  button keeps to the top of it.
- `--es view title --es page Grades` renders that top bar block with a stand-in settings button.

**Checked:** the title block over Grades and News, rendered (light).

## 14 October 2026 (top bar title larger)

**Asked for:** the "Wednesday 14 Oct" title even larger. Big Shoulders 40sp (was 34sp), for Day and
Week. Measured with the font: "Wednesday 30 Sep" (the widest) is 278dp at 40sp; with the Today chip
(59dp), gaps and the settings button it takes 383 of the phone's 395dp. 42sp would need 397dp.

## 14 October 2026 (Week: courses as on the Courses tab, totals at the bottom)

**Asked for:** the Week page's courses arranged as on the Courses tab, with the total hours and number
of classes at the bottom.

**Change** (`WeekScreen.courses`): courses in the Courses tab's order (by code) and its row layout: 12dp
dot, "WIX1002 · Occ 10" (code in the course colour, occurrence faint), the name (15sp semibold), then
"Lecture 2 h · Tutorial 3 h · online" muted, and the course's hours (Big Shoulders 22sp) on the right;
lines between courses only (the old list also ruled under the last one). The COURSES heading lost its
"11 classes a week"; under a 1.5dp line in the text colour, a Total row: "11 classes a week · Lecture
14 h · Tutorial 6 h" and "20 h" (Big Shoulders 26sp).

**Checked:** rendered in light and dark.

## 14 October 2026 (Sign out asks as in redesign 1q)

**Asked for:** a sign-out panel like 1q.

**Change** (`ConfirmDialog`, `MainActivity.signOut`): the platform AlertDialog is replaced by 1q's
card: centred over a 60% scrim, 20dp from the sides, rounded 22dp; "Sign out of Spectrum?" in Big
Shoulders 30sp; what happens in 15sp muted; Cancel (surface) and Sign out (yellow) side by side, 48dp
tall, labels left-aligned. It fades and springs in; Cancel, Back or a tap outside closes it and
changes nothing. The text keeps what the app really does (1q's mock says the timetable is deleted;
here it stays): "This removes your sign-in and the courses, announcements, deadlines and marks saved
on this phone. TimeEdit is signed out too. Your timetable stays, and you can sign in again any time."
`--es view signout` renders the card.

**Checked:** the card rendered in light and dark.

## 14 October 2026 (first-run sign-in poster as in redesign 1t)

**Asked for:** the sign-in screen like 1t.

**Change** (`SignInScreen`, `MainActivity.showWelcome` / `hideWelcome`, `AcademicCalendar.semesterName`):
- With no Spectrum sign-in and no timetable (a new or cleared app), a full-screen yellow poster lies
  over the app: "UM TIMETABLE" and the semester ("SEM 1 2026/27": the timetable's term, else the
  academic calendar's current or next semester) over a 1.5dp ink rule; "Sign in once. Get your whole
  week." in Big Shoulders 64sp; the three steps (1 SiswaMail · 2 TimeEdit week · 3 Due, News, Grades)
  with lines between them only (the user's preference; 1t also rules above and below); an ink "Sign in
  with SiswaMail" button with an arrow; "Unofficial. Nothing leaves your phone except to Microsoft and
  UM."; and, beyond 1t, a small underlined "Import from MAYA instead" (the fallback when TimeEdit
  can't be reached; Settings is under the poster).
- Dark status and navigation icons while it shows, in either theme. After a sign-in (or a MAYA or
  TimeEdit import) it lifts away upwards (320 ms) and the usual flow goes on: sync, then TimeEdit
  opens by itself.
- Signed out with a timetable kept (Sign out keeps it), the app opens as before, with sign-in cards on
  the Spectrum tabs.
- `--es view signin` renders it at screen height. `AcademicCalendarTest.semesterNames` covers the label.

**Checked:** rendered in light and dark (the accent differs slightly). The live flow needs a cleared
app: part of the reset check.

Follow-up (same night, at the user's request): no semester label (`AcademicCalendar.semesterName` and
its test removed again); no MAYA link (it's in Settings); the poster in UM's logo colours, sampled
from the logo: shield blue #3E4095 background, white "Sign in once." and yellow #FFDE20 "Your whole
semester in one place." (was "Get your whole week."), a yellow rule under UM TIMETABLE that starts with
a red #ED3237 notch (as the wordmark's U and M do), yellow step numbers with white labels and gold
#FF9F2D lines between them, a yellow button with blue text; light status and navigation icons. The
same in both themes.

Second follow-up (user: "reconsider the colour with the taste and impeccable skill; the red and
yellow bar is good"): the first UM version spent its accents everywhere (yellow headline, numbers,
bar and button, orange-gold lines next to the lemon yellow, pure and translucent white), and the
crest blue #3E4095 sits at hue 278° in OKLCH, so full-bleed it read violet. Now one field and one
accent, at the app icon's blue hue (266°): field #132B7C (oklch 0.33 0.14 266); headline and step
numbers #F1F5FE (11.6:1); "UM TIMETABLE", step labels and footnote #B8C7E9 (7.4:1); lines between
the steps #4E66A3; yellow #FFDE20 only for the red-notched bar (unchanged) and the button (its blue
text 9.5:1). The headline breaks after "Sign in once." instead of changing colour mid-sentence.

## 14 October 2026 (course pages: larger text, everything shown, bold section names)

**Asked for:** on a course page (GIG1003), larger text; all of Spectrum's information shown; section
headings such as "Course Information (Sem 1, 2026/2027)" bold and dark; the course name more visible.

**Change** (`SpectrumScreens.coursePage`, `moduleButton`, `fileButton`, `SpectrumSync.normalizeContents`):
- Course name under the code: Instrument Sans Bold 20sp in the text colour (was 13.5sp muted).
- Section names as Spectrum writes them (not upper-cased), Bold 18sp, text colour (were 11.5sp muted
  capitals).
- Nothing behind Show more: section summaries and text blocks in full at 16sp (were 6 lines at 14sp);
  activity descriptions in full at 15sp in the text colour (were 3 lines, 13sp, muted).
- Activity and file names 15.5sp semibold (were 14sp regular); dates 13.5sp (were 12sp).
- Spectrum's access rules under an activity (`availabilityinfo`, e.g. "Not available unless: You
  belong to (Missing group)") are kept by the sync and shown under it, 13.5sp muted, as Spectrum's
  own page shows them. GIG1003: 50 sections, 111 activities, 34 with access rules (mostly other
  occurrences' quizzes, which Spectrum lists for everyone).

**Checked:** GIG1003 fetched fresh (debug receiver, raw reply deleted) and rendered in light and dark.

## 14 October 2026 (Courses as in redesign 1n; a Settings sheet as in 1p)

**Asked for:** Courses as 1n, and "the other thing" (everything under the course list) in a new
Settings panel.

**Change:**
- Courses (`SpectrumScreens.courses`): the COURSES heading alone (no summary, no sync button, no
  top-bar date; pull to sync), then a ruled list (lines between rows only): 12dp dot, "WIX1002 · Occ 10"
  (code in the course colour, occurrence faint, from the Spectrum groups or the timetable), the name
  (15sp semibold), a chevron. A row opens the course page as before. Signed out: the sign-in card only.
- `SettingsSheet` (1p), from a sliders button at the right of the top bar, which replaces the
  Auto/Light/Dark pill: Settings (Big Shoulders 34sp) and a close button; APPEARANCE Auto / Light /
  Dark (the sheet goes and the theme spreads from the button, `Host.setLook`); TIMETABLE "Get timetable
  from TimeEdit" (with "11 classes a week · Sem 1 2026/27") and "Import from MAYA instead"; NOTIFICATIONS
  "Deadlines, marks and news" On/Off (Android owns it: off asks for the permission, on opens the app's
  notification settings); VIBRATION Off / Light / Medium / Strong (choosing plays the new strength);
  ACCOUNT the student's name, "Spectrum · last synced 00:37" and Sign out (still asks first), or Sign in.
- `Ui.segmented` is shared (News' For me / All, Appearance, Vibration) and restyles in place.
- The debug renderer draws the sheet: `--es view settings`.

**Checked:** Courses rendered (light) and Settings (light, dark). The top bar's button isn't in the
renderer.

Follow-up (user): Settings drops three explanations: "Reminders 24 h and 2 h before" under
notifications, "When you change page, go back, or swipe days." under vibration, and "Spectrum · last
synced …" under the account name (the last sync shows when pulling a Spectrum tab).

## 14 October 2026 (Show more crashed the app; News for my occurrence only, a month, same-size posts)

**Asked for:** For me to show only the user's occurrence, including course-wide posts that concern
it; the newest post no larger than the rest (as 1m's rows); a shorter drop-down animation; Show more
"does not work"; News covering a month.

**Found (Show more):** it crashed the app. The phone's crash log (`dumpsys dropbox --print
data_app_crash`) had four crashes at 00:24–00:28: `ClassCastException: TextView cannot be cast to
ViewParent` in `Motion.resize`, which walked up from the tapped view by casting it to `ViewParent`.
The folds pass a row (a ViewGroup, so it worked); Show more passes its TextView. The bug is in
1.1.0-beta.1 as published. The crashes were in the foreground, so the app wasn't marked bad.

**Change:**
- `Motion.resize` walks up from the view itself (`it.parent as? View`). The debug renderer's
  `--ez tapMore true` taps the first Show more before drawing; it now runs without crashing.
- Folds open and close faster: `Motion.QUICK` (about 250 ms, was SMOOTH's 420 ms) for the resize,
  `Motion.FOLD` (about 340 ms, was BOUNCY's 640 ms) for the chevron.
- `SpectrumSync.addressedToOthers`: a course-wide announcement whose subject names occurrences, none of
  them the user's (or, if the subject names none, whose first 160 characters do: "Dear OCC7, OCC8,
  and OCC9 Students") is `forMe: false`. Posts naming the user's occurrence or none at all stay.
  Group posts are unchanged. Because the sync sets it, the News badge and notifications follow too.
  Covered in `NamesTest.announcementsForOtherOccurrences`.
- `NEWS_DAYS` 14 → 30, and the News texts say "past month". After a sync: 10 announcements (nothing
  older yet: teaching began 5 Oct), 4 for other occurrences, For me · 6.
- News has no lead story: every post is the same ruled row, newest first, opening in place.

**Checked:** a full sync (debug receiver), then News rendered closed (light) and with everything open
and Show more tapped (dark).

Follow-ups the same night: post subjects bold and larger, as 1m's rows (Instrument Sans Bold 19sp, was
SemiBold 16sp); an opened post shows all its text with no Show more (`RichText.view` takes `lines` 0
for no limit), since tapping the row already asked for more.

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
