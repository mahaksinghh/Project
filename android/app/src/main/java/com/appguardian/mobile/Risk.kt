package com.appguardian.mobile

data class Finding(val permission: String, val weight: Int, val explanation: String)

data class Assessment(val score: Int, val level: String, val findings: List<Finding>)

private val rules = listOf(
    Finding("READ_SMS", 25, "May read text messages, including sensitive codes if Android grants access."),
    Finding("RECEIVE_SMS", 15, "May receive incoming text messages if Android grants access."),
    Finding("READ_CONTACTS", 15, "May read address book entries if granted."),
    Finding("ACCESS_FINE_LOCATION", 15, "May access precise device location if granted."),
    Finding("RECORD_AUDIO", 15, "May use the microphone if granted."),
    Finding("CAMERA", 10, "May use the camera if granted."),
    Finding("QUERY_ALL_PACKAGES", 15, "Declares broad visibility into installed packages."),
    Finding("REQUEST_INSTALL_PACKAGES", 20, "Can request package installation subject to Android controls."),
    Finding("SYSTEM_ALERT_WINDOW", 20, "Requests special access to draw over other apps."),
)

fun assess(permissions: List<String>): Assessment {
    val declared = permissions.toSet()
    val findings = rules.filter { "android.permission.${it.permission}" in declared }
    val score = findings.sumOf { it.weight }.coerceAtMost(100)
    val level = when {
        score >= 50 -> "High"
        score >= 20 -> "Moderate"
        else -> "Low"
    }
    return Assessment(score, level, findings)
}
