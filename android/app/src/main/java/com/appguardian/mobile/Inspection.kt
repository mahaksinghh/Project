package com.appguardian.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class Inspection(val packageName: String, val sha256: String, val permissions: List<String>) {
    val assessment = assess(permissions)
}

@Suppress("DEPRECATION")
fun inspect(context: Context, uri: Uri): Inspection {
    val file = File.createTempFile("inspection-", ".apk", context.cacheDir)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open file.")
        input.use { stream ->
            file.outputStream().use { output ->
                var size = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    size += read
                    if (size > 100L * 1024 * 1024) throw IOException("Choose an APK under 100 MiB.")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_PERMISSIONS)
            ?: throw IOException("Android could not read this APK. Choose a standalone APK.")
        return Inspection(
            info.packageName,
            digest.digest().joinToString("") { "%02x".format(it) },
            info.requestedPermissions.orEmpty().distinct().sorted(),
        )
    } finally {
        file.delete()
    }
}

fun share(inspection: Inspection, address: String, key: String) {
    require(key.matches(Regex("[0-9a-f]{64}"))) { "Enter a 64-character workspace key." }
    val origin = URI(address.trim())
    require(origin.scheme == "https" && origin.host != null && origin.userInfo == null &&
        origin.query == null && origin.fragment == null && origin.path in listOf("", "/")) {
        "Enter an HTTPS API origin."
    }
    val body = JSONObject()
        .put("consent", true)
        .put("package_name", inspection.packageName)
        .put("sha256", inspection.sha256)
        .put("permissions", JSONArray(inspection.permissions))
        .toString()
        .toByteArray(Charsets.UTF_8)
    val connection = origin.resolve("/v1/reports").toURL().openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        if (status != 201) throw IOException("Sharing failed (HTTP $status).")
    } finally {
        connection.disconnect()
    }
}
