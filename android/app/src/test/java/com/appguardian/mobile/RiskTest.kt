package com.appguardian.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskTest {
    @Test fun duplicateAndCustomPermissions() {
        val result = assess(listOf("android.permission.READ_SMS", "android.permission.READ_SMS", "custom.CAMERA"))
        assertEquals(25, result.score)
        assertEquals("Moderate", result.level)
        assertEquals(1, result.findings.size)
    }

    @Test fun noMatches() {
        assertEquals(Assessment(0, "Low", emptyList()), assess(emptyList()))
    }

    @Test fun capsScore() {
        val permissions = listOf("READ_SMS", "RECEIVE_SMS", "READ_CONTACTS", "ACCESS_FINE_LOCATION",
            "RECORD_AUDIO", "CAMERA", "SYSTEM_ALERT_WINDOW").map { "android.permission.$it" }
        assertEquals(100, assess(permissions).score)
        assertEquals("High", assess(permissions).level)
    }
}
