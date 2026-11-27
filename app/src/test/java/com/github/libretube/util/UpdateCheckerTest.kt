package com.github.libretube.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun parsesReleaseTags() {
        assertEquals(listOf(32, 1, 81), parseVersion("v32.1.81"))
        assertEquals(listOf(33, 0), parseVersion("v33.0"))
        assertEquals(listOf(32, 1, 72), parseVersion("32.1.72"))
    }

    @Test
    fun detectsNewerReleases() {
        // newer build of the same versionName
        assertTrue(isNewerVersion("v32.1.81", "32.1.72"))
        // milestone tag with a newer versionName
        assertTrue(isNewerVersion("v33.0", "32.1.99"))
    }

    @Test
    fun ignoresSameOrOlderReleases() {
        assertFalse(isNewerVersion("v32.1.72", "32.1.72"))
        assertFalse(isNewerVersion("v32.1.71", "32.1.72"))
        // versionName components take precedence over the build number
        assertFalse(isNewerVersion("v32.1.99", "33.0.72"))
        assertFalse(isNewerVersion("v32.1.72", "32.1.81"))
        // a milestone tag isn't newer than a build of the same versionName:
        // its missing build number counts as zero
        assertFalse(isNewerVersion("v33.0", "33.0.80"))
        // the reverse holds: a build of the same versionName is newer than the milestone
        assertTrue(isNewerVersion("v33.0.80", "v33.0"))
    }
}
