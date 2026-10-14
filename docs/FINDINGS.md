# UM data sources: what we found

Research notes from building UM Timetable's Spectrum, MAYA and TimeEdit features, 7–12 October
2026 (Semester 1, 2026/27). Read this before working on lecturer names, timetable import, or
anything that talks to a UM system.

## At a glance

| Source | Sign-in | Occurrence | Day and time | Room | Lecturer | Course name | Used by the app |
|---|---|---|---|---|---|---|---|
| **Spectrum** (UM's Moodle) | SiswaMail, in-app | Yes, via groups | No | No | Course-wide list only | Yes | Yes: Due, News, Courses, attendance alerts |
| **MAYA** (SITS e:Vision) | SiswaMail username and password typed into MAYA's own form (no SSO), in-app | Yes | Yes | Yes, full Malay names | **No** | No | Yes: timetable import |
| **TimeEdit** (`cloud.timeedit.net/my_um`) | SiswaMail ("Sign in with SSO"); the app signs in by itself with the Spectrum sign-in's Microsoft session | Yes | Yes | Yes, codes and full names | **Yes**, per class | Yes, Malay and English | Yes: the timetable and lecturers |
| **The Course Planner** (student website) | None | Yes | Yes | Yes, short codes | **Yes** where TimeEdit had one (snapshot) | Yes | Not yet |
| **UMscout** (third-party app) | — | — | — | — | Shows lecturers, from its server | — | No; analysed for reference |

**Lecturer names come from TimeEdit** (solved 12 Oct). Neither Spectrum nor MAYA can say who
teaches *your* class; TimeEdit can, and students can sign in to it with SiswaMail.

## Spectrum (`spectrum.um.edu.my`, Moodle)

**Mobile sign-in is supported.** `lib/ajax/service-nologin.php` →
`tool_mobile_get_public_config` (no login) reports `enablemobilewebservice: 1`,
`typeoflogin: 3` (embedded browser) and one identity provider, "OpenID Microsoft 365".

- The app signs in like the official Moodle app: open
  `admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=<random>&urlscheme=moodlemobile`
  in a WebView and catch the redirect `moodlemobile://token=<base64>`. The payload is
  `md5(wwwroot + passport):::token[:::privatetoken]`; check the first part against our passport.
- Calls go to `webservice/rest/server.php` with `wstoken`, `wsfunction`, `moodlewsrestformat=json`.
  Files need `webservice/pluginfile.php/…?token=…`; plain `pluginfile.php` URLs (used inside
  course text) must be rewritten to that form.

**Functions that work for a student** (the useful ones): `core_webservice_get_site_info`,
`core_course_get_enrolled_courses_by_timeline_classification`, `core_enrol_get_users_courses`,
`core_course_get_contents`, `mod_forum_get_forums_by_courses`, `mod_forum_get_forum_discussions`,
`core_calendar_get_action_events_by_timesort`, `core_calendar_get_calendar_events`,
`core_group_get_course_user_groups`, `core_course_get_courses_by_field`,
`core_enrol_get_enrolled_users`, `core_user_get_users_by_field`,
`core_course_get_course_module_by_instance`.
**No `mod_attendance_*` functions are exposed**, so attendance sessions come from calendar events.

**Attendance: one activity per occurrence or class, told apart only by name** (13 Oct). The calendar
lists another group's session when its activity isn't restricted to a group (WIX1002: another
group's "Tutorial T4 By … - Attendance", at the same hour as the user's own tutorial), and leaves
out sessions created without a calendar event (the user's own occurrence's activity had none in
five weeks). `core_course_get_contents` with options `modname=attendance`, `excludecontents=1`
lists a course's attendance activities with `instance`, `uservisible` and `availabilityinfo` ("Not
available unless: You belong to GROUP 15 2026/2027_1"); most other groups' are restricted, but not all.
Names seen (the shapes are real; numbers, days, rooms and names here are changed): "OCC 3",
"Attendance (OCC5 Only)", "Attendance (Tutorials, OCC 4, 5, 7, 10)", "OCC/Group 11 Attendance | …
Wednesday, Venue: Dewan Kuliah 3 (H09)", "Attendance GIG1003 APK for Occ 11 (AP Dr Tan)", "K (L)
Attendance (Wed 10am-12pm) @ DK3 - KAM" (room and lecturer initials), "Attendance (Lectures)",
"Attendance WIX2001 2026/2027 - all groups". `AttendanceMatch` scores names against the user's classes
(occurrence, room, lecturer, weekday, kind); on the user's 7 courses it found their activity for every
class except WIX2001's, whose two activities are both course-wide.

**Occurrence: reliable.** `core_group_get_course_user_groups` (courseid 0) gives one group per
course, e.g. name `GROUP 10 2026/2027_1`, idnumber `2026@2027@1@10` (session@semester@occurrence).
Matched the timetable for 7/7 courses.

**Lecturers: not per class.** Tried four ways on 8 Oct:

| Method | Result |
|---|---|
| Course contacts (`core_course_get_courses_by_field`) | Every lecturer of the course: WIX1002 has 10, WIX1001 7, GUL1001 over 50. Yours is in the list, but not marked. |
| Members of your group (`core_enrol_get_enrolled_users` with `groupid`) | One WIX2001 occurrence group: 89 people, all students. Lecturers aren't put in occurrence groups. |
| Group description | Just repeats the group name. |
| Who created the attendance calendar events | Matched the real lecturer 2 of 6 times; usually a coordinator. Event titles sometimes carry initials, e.g. "K (L) Attendance (Wed 10am-12pm) @ DK3 - KAM" (changed). |

**Course page content.** Text blocks (labels) keep their HTML in each module's `description`;
section text is in the section's `summary`. A folder's `description` is its inline file list, so
skip it. Label images are `pluginfile.php` URLs that need the token.

**Announcements.** "Post a copy to all groups" creates one identical discussion per group, and
students in visible-groups courses see every copy (10 copies of WIX1002's "Important
Announcement"). Dedupe by course + subject + text.

**Groups and grades (13 Oct).** `core_group_get_course_user_groups` (courseid 0) returns one group per
course: `id`, `courseid`, `name`, `idnumber`. Each discussion's `groupid` (-1 = all participants)
says which group it was posted to; WIX1002 had 3 of 10 announcements for other occurrences.
`gradereport_user_get_grade_items` gives per item `graderaw`, `grademax`, `percentageformatted`,
`gradeformatted`, `feedback`, `gradedategraded`, `cmid`, `itemmodule` (assign, quiz, attendance) and
`gradeishidden`; GUL1001 refuses ("Cannot view grades"); GIG1003's report once took 30 s. Attendance
modules are graded items, so attendance shows up as marks; GIG1017 has one attendance item per OCC.
`gradereport_overview_get_course_grades` gives course totals only. The sign-in can also call
`message_popup_get_popup_notifications`, `core_calendar_get_calendar_monthly_view` and `core_search_*`.

**Course pages are reused across years.** WIX1002's Announcements forum (63263) holds 82
discussions going back to 2025, and 37 of them were posted in the two weeks to 12 Oct (10 once
copies are merged). A fixed "newest 30" missed some; News now pages through 50 at a time until a
page reaches past 14 days (`SpectrumSync.NEWS_DAYS`). `mod_forum_get_forum_discussions` lists
pinned discussions first, then by latest post (`timemodified`), so pinned ones don't end the paging.

Course ids this semester: WIX1001 150, WIX1002 151, WIX1003 152, WIX2001 141, GIG1003 25836,
GIG1017 27335, GUL1001 27337.

## MAYA (`maya.um.edu.my`, SITS e:Vision)

- Front page redirects to `sitsvision/wrd/siw_lgn`: username (`MUA_CODE.DUMMY.MENSYS.1`) and
  password form. Students type their **SiswaMail username and password into MAYA's own form**; MAYA
  checks them itself. **No single sign-on** (checked 13 Oct): the "Or login using…" block with
  "Microsoft Live" is inside an HTML comment, and nothing on the page points to Microsoft, SAML or
  OpenID. So a SiswaMail session from Spectrum or TimeEdit can't sign the app in to MAYA; only the
  password can. **No public timetable.**
- **Way to the timetable:** UM's MAYA guide (umsitsguide.um.edu.my, "MAYA PORTAL" PDF, 29 Sep 2026):
  "MAYA Portal > Timetable > My Timetable" (once modules are registered); "Timetable > Timetable
  Search" is the TimeEdit viewer. Since 14 Oct `MayaActivity` takes that path by itself after the user
  signs in. The real menu's markup hasn't been seen: it was tested only against a stand-in page.
- Timetable page: **`SIW_XTTB`, title "Timetable View"**, list layout, about 3 weeks on screen.
  - Day rows: `div.sitsjqttdaylistrow[data-jqtt-date="20261006…"]`.
  - Classes: `li.sitsjqtteventcontainer` with `HH:MM - HH:MM` and labelled lines
    `Occurrence`, `Code`, `Semester`, `Activity` (LECTURE/TUTORIAL), `Campus`, `Room`. The same
    text is repeated in the `tt-tooltip` attribute.
  - The small `table.sv-table` on the page is a month date picker, not the timetable.
  - **No lecturer and no course name anywhere.** The details popup only repeats the tooltip;
    there's no extra request. `g_ttb_show_options` = `{"BTNS":"N","CAL":"N","PERS":"N","VIEW":"N","MTH":"N","AYR":"N","LCA":"Y"}`.
- Room names are full and partly Malay: "Dewan Kuliah 2 FSKTM (280)", "MAKMAL MIKRO 7 FSKTM",
  "Makmal Mikro 4 FSKTM (60)", "Dewan Kuliah 3 H09". Online classes have "N/A".
  `MayaImport.shortRoom` maps them to DK2, MM7, MM4, DK3 H09, and N/A to Online.
- **Blind test (8 Oct):** imported into an emptied app and compared with the hand-entered
  timetable. Day, time, type and occurrence matched 11/11. Rooms matched 8/11; the other three
  were naming only (Online → N/A twice, a Course Planner room code → MAYA's full hall name).
  Lecturers 0/11.
- The Course Planner's room codes ("BSBLH…") and MAYA's hall names ("Dewan Kuliah … H…") name the
  same GUL1001 venues.
- **Semester line (12 Oct):** the import now reads it to set the timetable's `term` label, but its
  exact wording is still unseen; `MayaImport.term` accepts "S1", "SEM 1", "Semester 1" or "1",
  takes a session year if there is one, and otherwise works it out from the class dates. Each
  import logs the values (`MAYA Semester lines: […]`); check the log after the Sem 2 import.

## TimeEdit (`cloud.timeedit.net/my_um/web/students/`)

UM's timetabling system, and the only source with **a lecturer per class**. Verified with a live
student session on 12 Oct.

**Sign-in.** `…/students/` answers HTTP 412 with a "TE Auth" page until you're signed in. TEAuth
sends you to `www.timeedit.net/deep-link?customerSignature=cloud_my_um&appId=TE_VIEWER…`. UM's
organisation (`66a39ae27cea2561470bc5e1`, region SGP, from the public
`organizations.timeedit.net/v1/organizations/global`) has two sign-in options
(`auth.timeedit.net/v1/organizations/<id>/auth-configs/public`): "TE IdP" (TimeEdit accounts) and
**"SSO"** (SAML2), which goes to Microsoft tenant `a63bb1a9-48c2-448b-8693-3317b00ca7fb`, the
same tenant as Spectrum's "OpenID Microsoft 365". **Students are let in**: the account shows as
`<matric>@siswa365.um.edu.my`.

**Signing in without the user (13 Oct, `SsoProbe`, debug):**
- TimeEdit's own session lasts at least 14 h: a hidden page was let straight in.
- With TimeEdit signed out (its cookies expired, its sites' storage cleared; Microsoft's kept), the
  hidden page got back in **without typing anything**: the student page's sign-in page first tries a
  token silently (hidden `www.timeedit.net/authorization.html` frame, 5 s), then shows **Log in** →
  `www.timeedit.net/deep-link` → **Sign in with SSO** → Microsoft let it through (no account picker,
  password or MFA) → `successful-authentication` → `my_um/product` → student page, HTTP 200.
- So with a Microsoft session from the Spectrum sign-in, the app can sign in to TimeEdit on its own:
  two clicks on TimeEdit's pages. The app keeps Microsoft's cookies until Spectrum sign-out
  (`removeAllCookies`). How long Microsoft's session lasts is unknown.

**The timetable from TimeEdit (13 Oct):** for the user's 7 courses and occurrences (from their
Spectrum groups), TimeEdit's classes over six weeks gave the same 11 weekly classes as the MAYA
import: day, time, type, course, room and lecturer all equal. TimeEdit's room names differ from
MAYA's in three: online classes are "ONLINE LECTURE" (MAYA: none), and the WIX1002 tutorial is in
"MAKMAL GABUNG (MAKMAL MIKRO 8)" (MAYA: Makmal Mikro 8). The offering's middle parts ("2026/S1")
give the semester; whether Semester 2 is "2026/S2" (session year) is unverified. Signing in (with
TimeEdit signed out) took 18 s, mostly TimeEdit's silent-token wait (5 s) and its sign-in app.

**Requests made outside the page fail.** Copying the WebView's cookies (`CookieManager`) into
`HttpURLConnection` got the 412 login page on the first request; the cause wasn't pinned down. The
app therefore runs its requests inside TimeEdit's own page (`assets/timeedit-lookup.js`) and gets
the answer over a `WebMessagePort` posted only to `https://cloud.timeedit.net`.

The student viewer only offers object types 15 (Module) and 5 (Module Offering) for search. The
lookup, per course (all under `/my_um/web/students/`):

| Step | Request | What it gives |
|---|---|---|
| Find the module | `objects.html?max=20&fr=t&partajax=t&im=f&sid=4&l=en_US&objects=&types=15&part=t&media=html&search_text=WIX1002` | `[data-idonly="37091"][data-name="WIX1002, ASAS-ASAS PENGATURCARAAN"]` |
| Its classes | `ri.json?h=f&sid=4&p=0.d,3.w&objects=37091.15&ox=0&types=0&fe=0&h2=f&l=en_EN` | `columnheaders` Module, Module Offering, Student Group, Activity, Activity Type (exam), Room. Offerings are `WIX1002/2026/S1/<occ>` (one class can serve several); Activity is LECTURE/TUTORIAL; Room is the full name ("DEWAN KULIAH 2 FSKTM"). Dates `dd/MM/yyyy`. |
| One class's objects | `ri.html?h=f&sid=3&types=4&fe=0&h2=f&l=en_EN&id=<reservation>&fr=t&step=0&ef=2&nocache=2` | Rows `<tr data-object="<id>.<type>">`; the Lecturer row is type 3 (`data-object="11484.3"`, text = staff code `00006193`) |
| The lecturer | `objects/11484/o.json?fr=t&types=15&sid=5&l=en_US` | "Title" (`PROF. TS. DR.`, `ASSOCIATE PROF. DR.`, `MADAM`, `MR`), "Full Name", "Code", "E-mail", "Job Title" |

Other views: `sid=5` gives every related object as one comma-joined column (staff code, LEC/TUT,
room code like WDK2, offerings) but no names; `sid=7` is `sid=4` plus Occurrence Size. TimeEdit
drops apostrophes ("MA AROF" for Ma'arof).

**Blind test (12 Oct):** all 11 classes found (36 requests, about 3–5 s on the phone). 10 named the
same person as the hand-entered timetable; for the WIX1001 lecture TimeEdit names someone else
(the hand-entered name was the WIX1001 tutorial's lecturer).

[damnitjoshua/um-timetable-sdk](https://github.com/damnitjoshua/um-timetable-sdk) (MIT, 2025)
maps staff codes to names by fetching `o.json` for every id from 8,600 to 13,700. The detail
page's `data-object` makes that unnecessary.

## The Course Planner (`thecourseplanner.vercel.app`)

A student-made Next.js site (built with v0). **It's where the original timetable image came
from**: its default theme is `charcoal-yellow`, the image's file name. It credits
`damnitjoshua/um-timeedit-timetable-toolkit` and links to UMscout.

- **`/api/modules`: public, no login.** One 1.4 MB JSON array, 2,625 modules:

  ```json
  {"moduleCode": "WIX1002", "mavName": "FUNDAMENTALS OF PROGRAMMING", "level": "…",
   "occurrences": {"10": [{"dayStartDuration": "Wednesday / 10:00 - 12:00", "room": "DK3",
     "lecturer": "PROF. TS. DR. KAMAL ARIFFIN BIN MA AROF", "activityType": "LECTURE"}]}}
  ```

  Missing lecturers are the literal string `"Unknown Lecturer"`.
- Also used by the site: a CSV at
  `https://amqvbl0yv9izuzg7.public.blob.vercel-storage.com/course_timetable%20%288%29.csv`
  (columns Faculty, Module Code, Module Name, Date, Day, Start Time, End Time, Room,
  Lecturer Name, Occurrence, Activity Type), plus `/api/credits` and `/api/exam-data`.
- **Coverage for this timetable:** 7 of 11 classes have a lecturer, and all 7 match the
  hand-entered names exactly. "Unknown Lecturer" for WIX1001 lecture and tutorial, the WIX1002
  tutorial, and GIG1017: exactly the four you filled in yourself.
- Downloading the whole list doesn't tell the site which courses you take.
- Risks: a third party's site, and a snapshot that lags TimeEdit.

## UMscout (third-party, `com.limziyang.spectrumscout`, v2.5.4)

Analysed from the installed APK on 7 October; kept here because it explains how a
"just sign in" app gets this data.

- Flutter/FlutterFlow. Backend: Supabase `ficelzzerhucunhklmth.supabase.co` (edge functions
  `generateICS`, `delete-user`), plus `chat.umscout.ai` (AI agent) and `past-year.umscout.ai`.
  Google AdMob, Firebase Analytics, Crashlytics and Messaging, RevenueCat subscriptions.
- **Spectrum:** signs in through SiswaMail in a WebView, reads the `MoodleSession` cookie, and
  creates a web-service token through `user/managetoken.php` (it can also reset tokens). Its
  privacy policy says it collects "Usernames, Passwords, Spectrum cookies" and that students
  share cookies "for crawling announcements" on its server.
- **Timetable:** "Fetch from Maya" writes a `fetch_from_maya_request` row with your matric
  number; **its server** fetches MAYA. Each saved class has a `metadata` object with `venue` and
  `teachers`; the lecturer names are server-supplied, most likely from TimeEdit. Rechecked 12 Oct
  (APK on the phone): the app has no TimeEdit address and no lecturer source of its own; classes
  are `calendar_events` rows (`…,metadata,color,occ`), also cached in a local SQLite table.
- **Play Store Data safety claims "no data collected or shared"**, contradicting its own privacy
  policy and its ad and analytics SDKs.
- UM's approval letter (18 Nov 2024, from the Chief Digital Officer) is conditional on a
  security scan and a revised user agreement; whether those were done isn't public.

## Lecturer names in the app

"Find lecturers on TimeEdit" (Courses tab, under the MAYA import) opens `TimeEditActivity`. After
the user signs in, "Find lecturers" looks up every course of the app's timetable, matches
TimeEdit's weekly slots by course, day, start, end and type, and asks before saving:

- classes with no lecturer are filled in;
- names that refer to the same person (`TimeEditLookup.samePerson`: most of the shorter name's
  words, ignoring titles and bin/binti) are left as they are, so hand-written spellings stay;
- different people are listed, and replaced only on "Use TimeEdit's names".

The MAYA importer still carries existing names over for exact matches, so the usual order is
MAYA import, then Find lecturers. Fallbacks if TimeEdit ever stops working: The Course Planner's
`/api/modules` snapshot, or choosing from Spectrum's course contacts.

## Current state (12 October 2026)

- 12 Oct: "Find lecturers" ran on the phone (11/11 found, 2.8 s) and, at the user's choice, changed
  the WIX1001 lecture's lecturer to TimeEdit's. The old web page still had the hand-entered name
  (`web/index.html`, deleted on 13 Oct).

Earlier (8 October):


- The app runs on its own timetable (`files/timetable.json` on the phone): the MAYA import,
  with all 11 lecturer names copied back from the backup by exact match (day, start, end,
  course, type). Rooms are MAYA's: Online for GIG1003/GIG1017, a lecture hall for GUL1001.
- The old web page carried the original hand-entered timetable (`web/index.html`, deleted on 13 Oct).
- MAYA pages are read in memory only and never saved. The 9 test captures and the Spectrum
  debug dumps were deleted from the phone on 8 Oct; the app keeps only its normal caches
  (`no_backup/spectrum/`: courses, news, due, attendance, opened course pages).

## Platform notes (Android)

- **Background network on Android 17 (API 37):** the app's background data was off
  (`REJECT_METERED_BACKGROUND`) and cached apps also get `APP_BACKGROUND` blocking. Background
  checks fail with "Unable to resolve host" while the phone itself is online; anything the
  app does while on screen works. The app sets `bgBlocked` and shows a settings tip.
- **Back gesture (Android 16+):** `onBackPressed()` isn't called for apps targeting 36+.
  Register an `OnBackInvokedCallback` only while there's somewhere to go back to.
- **Widgets:** `setViewLayoutHeight`/`setViewLayoutMargin` and size-mapped `RemoteViews` need API 31.
- **Native screens (8 Oct):** the main screen moved from a WebView to native views; cold start to
  a fully drawn screen went from ~520 ms to ~350 ms. Gotcha hit on the way: an empty `View` used
  as a spacer with `WRAP_CONTENT` height takes all the height it's offered (a short page's whole
  screen), so spacers must be height 0 (`Ui.spacer()`).
- **Testing narrow screens:** headless Chromium won't go below about 500 px wide; put the page
  in a 427 px `<iframe>` instead. Wrap headless runs in `timeout`.
- **Debug-only tools** (in `app/src/debug`):
  - `DebugSpectrumReceiver`: `--ei course <id> [--ez offline true]`, `--es timetable clear|restore|apply`
    (apply reads `files/timetable-pending.json`), `--es maya import` (reads a page you push to
    `no_backup/maya/page.json` as `{"html": "…"}`)
  - `DebugSpectrumCallActivity`: one foreground web-service call, saved to `no_backup/spectrum/debug-<out>.json`;
    `--es fn sync` runs a timed full sync
  - `MainActivity` (debug builds only): `--ez debugShowWhenLocked true` to screenshot over the lock
    screen; `--ei course <id>` opens a course page
  - `WidgetPreviewActivity`: renders the widget at any size, time and theme
