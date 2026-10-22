package com.skylightlim.umtimetable

import org.json.JSONObject

/**
 * A course page arranged as Spectrum's own page arranges it in a browser, from [SpectrumSync.courseContents].
 * The arrangement is Spectrum's own, for any course format: [CourseLayout] reads the description Moodle
 * builds the page from (which section sits in which, which opens as a page of its own, which activities
 * each section lists and in what order), and this follows it. Spectrum's courses differ (checked October
 * 2026, docs/SPECTRUM-COURSES.md):
 * - Subsections (Moodle 5's mod_subsection) come from the web service after the last section; on the page
 *   each sits inside its section, where its link is.
 * - Flexible sections nests sections in sections, and shows some as a link to a page of their own.
 * - A course may show one section per page (General in full, then a card per section opening its page),
 *   and Tiles, Grid and One Topic courses show each section on a page of its own too (as tiles or tabs).
 * - Another group's sections and activities stay on the page, greyed, with what they wait for; a hidden
 *   section shows only its name and "Not available". Empty sections show their name.
 * Without the layout (it couldn't be read yet) the web service's own order stands in, less what Spectrum
 * keeps off the page: activities hidden from it, and sections beyond the course's number of sections.
 * A [Node.link] is a section shown as a card that opens a page of its own.
 */
object CourseOutline {
    /** Formats that show each section after General on a page of its own: as tiles (Tiles, Grid) or as tabs (One Topic). */
    private val SECTION_PAGES = setOf("tiles", "grid", "onetopic")

    /** One activity or text on the page; a subsection's carries its section. */
    class Item(val module: JSONObject, val sub: Node?)

    class Node(val section: JSONObject, val items: List<Item>, val children: List<Node>, val link: Boolean) {
        val id get() = section.optInt("id")
        val number get() = section.optInt("number")
        val name: String get() = section.optString("name")
        /** Hidden from students: Spectrum shows its name and "Not available", nothing else. */
        val hidden get() = !section.optBoolean("visible", true)
        /** On the page but not open to this student (another group's): greyed, with what it waits for. */
        val restricted get() = !section.optBoolean("uservisible", true)
        /** Activities, as a section's card counts them ("Activities: 3"): everything on it, texts and subsections too. */
        val activities get() = items.size
    }

    /** The sections at the top of the course page, in Spectrum's order. */
    fun build(course: JSONObject): List<Node> {
        val sections = course.optJSONArray("sections")?.objects().orEmpty()
        val layout = course.optJSONObject("layout")
        val format = course.optString("format")
        // Every section after General opens as a page of its own: by the course's setting, or by its format.
        // (Flexible sections says which, section by section, in its layout.)
        val sectionPages = format != "flexsections" && (course.optInt("display") == 1 || format in SECTION_PAGES)
        fun info(s: JSONObject) = layout?.optJSONObject(s.optInt("id").toString())
        // A subsection's own section, by the subsection it belongs to.
        val delegated = sections.filter { it.optString("component") == "mod_subsection" }.associateBy { it.optInt("itemid") }
        // With the layout, the sections it has (a section whose parent is gone isn't on the page either);
        // without, those the web service doesn't mark as beyond the course's sections.
        val own = sections.filter { it.optString("component").isEmpty() && if (layout != null) info(it) != null else !it.optBoolean("orphan") }
        fun parentOf(s: JSONObject) = info(s)?.optInt("parent") ?: 0
        val built = mutableSetOf<Int>() // a section is built once, whatever the layout says
        fun node(s: JSONObject, top: Boolean): Node {
            built += s.optInt("id")
            val modules = s.optJSONArray("modules")?.objects().orEmpty()
            // The activities the page lists, in its order; without the layout, all but those kept off the page.
            val listed = info(s)?.optJSONArray("cms")?.let { cms -> (0 until cms.length()).mapNotNull { i -> modules.firstOrNull { it.optInt("cmid") == cms.optInt(i) } } }
                ?: modules.filter { it.optBoolean("onpage", true) }
            val items = listed.mapNotNull { m ->
                val sub = if (m.optString("kind") == "subsection") delegated[m.optInt("instance")] else null
                when {
                    sub == null -> Item(m, null)
                    // A hidden subsection isn't on the page at all.
                    !sub.optBoolean("visible", true) -> null
                    sub.optInt("id") in built -> null
                    else -> Item(m, node(sub, false))
                }
            }
            val children = own.filter { parentOf(it) == s.optInt("id") && it.optInt("id") !in built }.map { node(it, false) }
            val link = info(s)?.optBoolean("link") == true || (top && sectionPages && s.optInt("number") > 0)
            return Node(s, items, children, link)
        }
        return own.filter { parentOf(it) == 0 }.map { node(it, true) }
    }

    /** The section [id] anywhere on the page, nested or behind a link. */
    fun find(nodes: List<Node>, id: Int): Node? {
        for (n in nodes) {
            if (n.id == id) return n
            find(n.children + n.items.mapNotNull { it.sub }, id)?.let { return it }
        }
        return null
    }
}
