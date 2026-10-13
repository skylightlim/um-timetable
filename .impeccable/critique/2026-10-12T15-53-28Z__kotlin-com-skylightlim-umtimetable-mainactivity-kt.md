---
target: 马大牛逼 Android app (all tabs)
total_score: 26
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 3
timestamp: 2026-10-12T15-53-28Z
slug: kotlin-com-skylightlim-umtimetable-mainactivity-kt
---
⚠️ DEGRADED: single-context (sub-agents not started: this session only starts agents on an explicit user request; the detector scans web markup and had no native views to check)

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | Sync time, spinner and badges are clear; downloads only show a toast |
| 2 | Match System / Real World | 3 | Occ, DK2, MM7 read naturally; Spectrum course names arrive in ALL CAPS |
| 3 | User Control and Freedom | 3 | Back and in-place toggles work; Sign out is instant, no confirmation |
| 4 | Consistency and Standards | 2 | Two different lists titled "Courses"; round dots vs rounded squares; CAPS vs title case |
| 5 | Error Prevention | 3 | MAYA and TimeEdit ask before changing data; Sign out doesn't |
| 6 | Recognition Rather Than Recall | 2 | MAYA import, TimeEdit, notifications and Sign out hide at the bottom of Courses |
| 7 | Flexibility and Efficiency | 2 | Swipe days, strip, widget; no News filter or search |
| 8 | Aesthetic and Minimalist Design | 3 | Strong identity; News is padded with other occurrences' posts |
| 9 | Error Recovery | 3 | "Sign in again" and the background-data tip lead to a fix |
| 10 | Help and Documentation | 2 | Empty states guide; the first-run path was broken (fixed) |
| **Total** | | **26/40** | **Acceptable** |

## Design Specificity Verdict
Authored, not generic: room codes in Big Shoulders read like wayfinding signs, course codes in mono, one course-colour system across Day, Week, widget and web page, a ring around the live class, "Free · 2 h" gaps. Weak spots: the Week page's second "COURSES" list duplicates the Courses tab's name with different content, and News is a plain card stream.
Detector: 0 findings (web-markup scanner; nothing to scan in native views).

## Priority Issues
1. [P1] First run: Day says "open the Courses tab and tap Import timetable from MAYA", but Courses hid that link until Spectrum sign-in. FIXED 12 Oct; verify in the reset pass.
2. [P1] News shows announcements meant for other occurrences (e.g. "Online Tutorials and Lab Sessions for OCC7, OCC8, and OCC9" while the user is in another occurrence). Fix: compare each discussion's groupid with the user's group (core_group_get_course_user_groups); fold others into "Other occurrences (n)".
3. [P1] After the day's last class, Day still opens on today with every card faded, while the status card already points at tomorrow. Fix: once today's classes are over, open on the next class day.
4. [P2] Account and import actions live at the bottom of Courses; Sign out wipes data without asking. Fix: a settings sheet from the top bar (theme, imports, notifications, account) and a confirmation on Sign out.
5. [P2] Consistency: ALL CAPS Spectrum names next to title-case timetable names; rounded squares in Week and the class sheet vs round dots in Courses; Week's "COURSES" section vs the Courses tab.

## Persona Red Flags
- Jordan (first-timer, i.e. the reset): signed-out Courses was a dead end (fixed); "Find lecturers on TimeEdit" means nothing before an import.
- Casey (one-handed, between classes): refresh 38dp and the theme pill ~24dp sit at the top; Show more ~26dp tall.
- Sam (TalkBack): the week grid is one drawn view; its classes can't be focused or tapped one by one.

## Minor Observations
- Download errors show raw text ("HTTP 403").
- Course pages show "Loading the course page…" with no cached copy first.
- Week grid labels at 9–10sp are small.

## Questions to Consider
- What does a student open the app for at 23:50? Tomorrow, not today.
- Should News show only what concerns you, with the rest one tap away?
- If Day absorbs Week, what's worth a whole tab: grades, an inbox, or a calendar?
