package com.skylightlim.umtimetable

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Course pages arranged as Spectrum's page arranges them, on made-up courses shaped like the real ones. */
class CourseOutlineTest {
    private fun module(cmid: Int, kind: String = "file", instance: Int = 0, restricted: Boolean = false) =
        JSONObject().put("cmid", cmid).put("kind", kind).put("name", "Item $cmid").put("instance", instance).put("restricted", restricted)

    private fun section(id: Int, number: Int, vararg modules: JSONObject, component: String = "", itemid: Int = 0, visible: Boolean = true, uservisible: Boolean = true) =
        JSONObject().put("id", id).put("number", number).put("name", "Section $number").put("summary", "")
            .put("component", component).put("itemid", itemid).put("visible", visible).put("uservisible", uservisible)
            .put("modules", JSONArray(modules.toList()))

    private fun course(vararg sections: JSONObject) = JSONObject().put("format", "topics").put("display", 0).put("sections", JSONArray(sections.toList()))

    private fun numbers(nodes: List<CourseOutline.Node>) = nodes.map { it.number }

    @Test fun subsectionsSitWhereTheirLinkIs() {
        // The web service lists a subsection's own section after the last one (number 9 here).
        val c = course(
            section(10, 0, module(1)),
            section(11, 1, module(2), module(3, "subsection", instance = 77), module(4)),
            section(12, 2, module(5, restricted = true)),
            section(19, 9, module(6), component = "mod_subsection", itemid = 77),
        )
        val top = CourseOutline.build(c)
        assertEquals(listOf(0, 1, 2), numbers(top))
        val week = top[1]
        assertEquals(listOf(2, 3, 4), week.items.map { it.module.optInt("cmid") })
        assertEquals(9, week.items[1].sub?.number)
        assertEquals(listOf(6), week.items[1].sub?.items?.map { it.module.optInt("cmid") })
        assertTrue(top[2].items.single().module.optBoolean("restricted")) // another group's: kept, marked
        assertEquals(3, week.activities)
    }

    @Test fun hiddenSubsectionsAreLeftOutAndHiddenSectionsStay() {
        val c = course(
            section(10, 0),
            section(11, 1, module(3, "subsection", instance = 77)),
            section(12, 2, visible = false, uservisible = false),
            section(19, 3, component = "mod_subsection", itemid = 77, visible = false, uservisible = false),
        )
        val top = CourseOutline.build(c)
        assertEquals(listOf(0, 1, 2), numbers(top)) // a hidden section stays: "Not available"
        assertTrue(top[2].hidden)
        assertTrue(top[1].items.isEmpty())
    }

    @Test fun flexibleSectionsNestAndLink() {
        val c = course(
            section(10, 0),
            section(11, 1),
            section(12, 2, module(1)),
            section(13, 3, module(2)),
            section(14, 4),
            section(15, 5),
            section(16, 6), // its parent was deleted: not on Spectrum's page
        ).put("format", "flexsections").put("layout", JSONObject()
            .put("10", JSONObject().put("parent", 0)).put("11", JSONObject().put("parent", 0).put("link", true))
            .put("12", JSONObject().put("parent", 11)).put("13", JSONObject().put("parent", 12).put("link", true))
            .put("14", JSONObject().put("parent", 0)).put("15", JSONObject().put("parent", 14))
            .put("16", JSONObject().put("parent", 99)))
        val top = CourseOutline.build(c)
        assertEquals(listOf(0, 1, 4), numbers(top))
        assertTrue(top[1].link)
        assertEquals(listOf(2), numbers(top[1].children))
        assertTrue(top[1].children[0].children.single().link)
        assertFalse(top[2].link)
        assertEquals(listOf(5), numbers(top[2].children))
        assertEquals(3, CourseOutline.find(top, 13)?.number)
        assertNull(CourseOutline.find(top, 16))
    }

    @Test fun onePerPageShowsGeneralThenCards() {
        val c = course(
            section(10, 0, module(1)),
            section(11, 1, module(2), module(3, "subsection", instance = 5)),
            section(12, 2, uservisible = false),
            section(13, 3, module(4), component = "mod_subsection", itemid = 5),
        ).put("display", 1)
        val top = CourseOutline.build(c)
        assertEquals(listOf(false, true, true), top.map { it.link })
        assertTrue(top[2].restricted)
        assertFalse(top[1].items[1].sub!!.link) // inside its page, a subsection is in place
        assertEquals(2, top[1].activities)
    }
}
