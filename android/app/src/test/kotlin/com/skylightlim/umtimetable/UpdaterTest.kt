package com.skylightlim.umtimetable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which GitHub release the updater offers, from the list GitHub's API gives. */
class UpdaterTest {
    private fun release(tag: String, name: String = tag, prerelease: Boolean = false, draft: Boolean = false, apk: Boolean = true) = """
        {"tag_name": "$tag", "name": "$name", "prerelease": $prerelease, "draft": $draft, "body": "## What's new\n- Something",
         "assets": [${if (apk) """{"name": "UM-Timetable-$name.apk", "size": 600000, "browser_download_url": "https://github.com/x/$tag.apk"}""" else """{"name": "notes.txt", "size": 1, "browser_download_url": "https://github.com/x/notes.txt"}"""}]}
    """

    @Test fun highestVersionCodeWins() {
        val r = Updater.pick("[${release("v3", "1.2.0-beta.1", prerelease = true)}, ${release("v4", "1.3.0")}, ${release("v2", "1.1.0")}]")!!
        assertEquals(4L, r.code)
        assertEquals("1.3.0", r.name)
        assertEquals("https://github.com/x/v4.apk", r.url)
        assertEquals(600000L, r.size)
    }

    @Test fun preReleasesCountDraftsDont() {
        assertEquals(3L, Updater.pick("[${release("v3", "1.2.0-beta.1", prerelease = true)}, ${release("v5", draft = true)}]")!!.code)
    }

    @Test fun onlyVTagsWithAnApk() {
        // The first release's tag ("v1.1.0-beta.1") isn't a version code; a release without an APK can't be installed.
        assertNull(Updater.pick("[${release("v1.1.0-beta.1")}, ${release("v7", apk = false)}, ${release("release-8")}]"))
        assertNull(Updater.pick("[]"))
    }
}
