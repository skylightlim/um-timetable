package com.skylightlim.umtimetable

import org.json.JSONObject

/**
 * A course page arranged as Spectrum's own page arranges it in a browser, from [SpectrumSync.courseContents].
 * Spectrum's courses differ (checked October 2026 against all of the user's):
 * - Subsections (Moodle 5's mod_subsection) come from the web service after the last section; on the page
 *   each sits inside its section, where its link is.
 * - Flexible sections nests sections in sections ([CourseLayout] says which in which), and shows some as
 *   a link to a page of their own.
 * - A course may show one section per page: General in full, then each section as a card opening its page.
 * - Sections whose parent section is gone (deleted) aren't shown, as on Spectrum's page.
 * - Another group's sections and activities stay on the page, greyed, with what they wait for; a hidden
 *   section shows only its name and "Not available". Empty sections show their name.
 * A [Node.link] is a section shown as a card that opens a page of its own.
 */
object CourseOutline {
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
        val onePerPage = course.optInt("display") == 1
        // A subsection's own section, by the subsection it belongs to.
        val delegated = sections.filter { it.optString("component") == "mod_subsection" }.associateBy { it.optInt("itemid") }
        val own = sections.filter { it.optString("component").isEmpty() }
        // A section whose parent is gone (deleted) isn't on Spectrum's page either.
        fun parentOf(s: JSONObject) = layout?.optJSONObject(s.optInt("id").toString())?.optInt("parent") ?: 0
        val built = mutableSetOf<Int>() // a section is built once, whatever the layout says
        fun node(s: JSONObject, top: Boolean): Node {
            built += s.optInt("id")
            val items = s.optJSONArray("modules")?.objects().orEmpty().mapNotNull { m ->
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
            val link = if (layout != null) layout.optJSONObject(s.optInt("id").toString())?.optBoolean("link") == true
            else top && onePerPage && s.optInt("number") > 0
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
