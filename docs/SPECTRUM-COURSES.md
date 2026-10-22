# Spectrum course pages: not the same across courses

Checked 22–23 October 2026 (Spectrum is Moodle 5.2.1+, theme Edwiser RemUI): each of seven first-year
courses opened in a signed-in laptop browser, its page structure read and screenshotted, and compared
with what the web service gives the app (`core_course_get_contents`) and with the app's course page
drawn off-screen (`DebugRenderReceiver --es view files --ei course N [--ei section S]`).

## The short of it

Lecturers set up their courses in four different ways, and the web service flattens all of them into
one list of sections. A browser shows each its own way; the app now does the same ([CourseOutline],
[CourseLayout]).

| Course | Format | How the browser shows it | Sections (shown) | Subsections | Restricted to other groups | Also |
|---|---|---|---|---|---|---|
| WIX1001 | Topics | one page | 9 (9) | 0 | 3 activities | a label that's only a dividing line |
| WIX1002 | Topics | one page | 31 (31) | 1 | 15 activities, 1 section | 7 hidden sections ("Not available"); a podcast player; a table |
| WIX1003 | Topics | one page | 45 (37) | 17, one per lecturer | 11 activities, 1 subsection | 21 hidden sections; 8 hidden subsections |
| WIX2001 | Topics | one page | 6 (6) | 0 | none | text-heavy; two mistyped Teams links; a pasted WhatsApp address |
| GUL1001 | Topics | **one section per page**: General, then a card per section | 89 (76) | 13 | 65 sections (one per other group), shown with their summaries | 4 tables |
| GIG1017 | **Flexible sections** | one page, one section inside another | 33 (33) | 17, per lecturer | 10 activities | the first section's name hidden |
| GIG1003 | **Flexible sections** | 19 sections at the top; 23 open as pages of their own | 121 (19 at the top) | 0 | 35 activities, mostly on the lecturers' pages | three levels deep; 4 orphaned sections (parent deleted); 5 email links; 3 tables |

"Sections (shown)": in the web service's reply, and on the course page in a browser.

## Each difference, what the browser does, and what the app does now

1. **Subsections (Moodle 5's `mod_subsection`)**, in four courses. The web service lists a subsection's
   own section after the course's last one (`component: mod_subsection`, `itemid` = the subsection),
   and its parent section has only a link to it. The browser shows it inside the parent, where the link is.
   *Before:* the app showed the link as a plain button and the content piled up at the bottom of the
   page. *Now:* set in where it belongs, under its own name, with a line down its side. Hidden
   subsections are left out, as in the browser.

2. **Flexible sections (`format_flexsections`)**, GIG1003 and GIG1017. Sections sit inside sections,
   and some are shown as a link to a page of their own (GIG1003: each lecturer's material). The web
   service says neither which is inside which nor which is a link, and Spectrum's Flexible sections
   offers the Moodle app nothing (`tool_mobile_get_plugins_supporting_mobile` doesn't list it), so the
   official Moodle app shows these courses flat too. *Before:* GIG1003 was 64 sections in one list, every
   lecturer's material for every occurrence. *Now:* the layout is read the way Spectrum's own page reads
   it, `core_courseformat_get_state`, in a hidden page on the app's Spectrum session ([CourseLayout],
   about 1.3 s; kept a day). The web service token can't call it ("Access control exception"). Inner
   sections are set in; linked ones are cards (name, summary, "2 activities · 6 sections") opening a
   page of their own. A section whose parent was deleted isn't shown, as in the browser. If the layout
   can't be read (signed out of Microsoft), the course shows all its sections at the top.

3. **One section per page** (`coursedisplay` 1), GUL1001. The browser shows General in full, then a
   card per section (name, summary, "Activities: 3", an arrow) opening its page. *Before:* everything in
   one long page. *Now:* the same cards, opening a section page; Back returns to where you were.
   `coursedisplay` comes from `core_course_get_courses_by_field`'s `courseformatoptions`.

4. **Restricted and hidden things.** The browser keeps another group's sections and activities on the
   page, greyed, with "Not available unless: You belong to GROUP 5" (long lists folded under Show more),
   and their section summaries in full. A hidden section shows its name and "Not available". Empty
   sections show their name. *Before:* the web service sends `uservisible` as a boolean and the app read
   it as a number, so another group's activities showed as ordinary buttons (opening a page that said
   "not available"); restricted sections without a summary, empty and hidden sections were left out.
   *Now:* as in the browser, greyed with a padlock, not opening, the conditions folded at two lines.

5. **Links.** Every link the browser offers is now tappable in the app:
   - email links (`mailto:`) were dropped (only web links were kept); they open the mail app now;
   - two links whose address a lecturer pasted with its label (`href="Join: https://teams…"`) go
     nowhere in a browser; the app opens the meeting inside them;
   - an audio player (a podcast in a label) was dropped, its address left as plain text; it's a
     "▶ Listen" link now, and video players and embedded pages (`<iframe>`, none on these courses yet)
     become "▶ Watch" and "Open" links the same way;
   - addresses pasted into the text without a link (WhatsApp groups) are only text in a browser; the
     app makes them links (24 across the seven courses);
   - table rows ran their cells together ("ClassWhenWhere…"); each row is now a line, cells joined by " · ";
   - a label that's only a dividing line (`<hr>`) was dropped; it's a line now.
   Checked across all seven courses: every link in a section summary, text, description or restriction
   (142) and every activity (315) has a target in the app.

## What still differs, on purpose or for now

- The app's own type, cards and colours, not the theme's; lecturers' font sizes and centring are partly
  kept (Android's HTML renderer), colours not always.
- Players and embedded pages open outside the page (a link) instead of playing in it.
- Sections can't be collapsed; everything on a page is shown open (the app's rule: nothing behind Show
  more on a course page, except long restriction lists).
- Activity icons are by kind (file, folder, link, other), not each activity type's own.
- A Flexible sections course needs the hidden page's sign-in for its layout; the first open of such a
  course waits for it (about a second or two).
- Text is the web service's, unfiltered (`moodlewssettingfilter` off): Spectrum's filters would turn
  YouTube links into players the app can't show. Spectrum doesn't turn plain addresses into links, so
  nothing is lost.

## Checking again (a new semester, a new course)

In a signed-in browser, `core_courseformat_get_state` (POST `lib/ajax/service.php?sesskey=…`, the
course page's own call) gives every section's `parentid`, `showaslink`, `component` and `itemid`; compare
them with the app's arrangement. On the phone, a debug build: `DebugSpectrumReceiver --ei course N`
fetches a course page as the app does (format, layout and all), then `DebugRenderReceiver --es view
files --ei course N [--ei section S]` draws it.

[CourseOutline]: ../android/app/src/main/kotlin/com/skylightlim/umtimetable/CourseOutline.kt
[CourseLayout]: ../android/app/src/main/kotlin/com/skylightlim/umtimetable/CourseLayout.kt
