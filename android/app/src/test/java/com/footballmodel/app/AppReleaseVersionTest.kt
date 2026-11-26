package com.footballmodel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseVersionTest {
    @Test fun semanticVersionRemainsPrimaryOrdering() {
        assertTrue(AppReleaseVersion.fromRelease("v3.16", "").isNewerThan("3.15", 83))
        assertFalse(AppReleaseVersion.fromRelease("v3.14", "Version-Code: 999").isNewerThan("3.15", 83))
    }

    @Test fun sameVersionNameUsesPublishedVersionCode() {
        assertTrue(AppReleaseVersion.fromRelease("v3.15", "Version-Code: 84").isNewerThan("3.15", 83))
        assertFalse(AppReleaseVersion.fromRelease("v3.15", "Version-Code: 82").isNewerThan("3.15", 83))
        assertFalse(AppReleaseVersion.fromRelease("v3.15", "").isNewerThan("3.15", 83))
    }

    @Test fun parsesVersionCodeFromReleaseNotes() {
        assertEquals(91L, AppReleaseVersion.fromRelease("v4.0", "Notes\nVersion-Code: 91\n").versionCode)
        assertEquals(null, AppReleaseVersion.fromRelease("v4.0", "No metadata").versionCode)
    }
}
