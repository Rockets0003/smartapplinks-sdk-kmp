package com.smartapplinks.track

import android.os.Build
import android.content.res.Resources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone

actual fun getPlatform(): String = "android"

actual fun getDeviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

actual fun getOsVersion(): String = Build.VERSION.RELEASE

actual fun getScreenWidth(): Int = Resources.getSystem().displayMetrics.widthPixels

actual fun getScreenHeight(): Int = Resources.getSystem().displayMetrics.heightPixels

actual fun getLocale(): String = Locale.getDefault().toLanguageTag()

actual fun getTimezone(): String = TimeZone.getDefault().id

actual fun getAppVersion(): String = try {
    // Will be populated when running in an actual app context
    ""
} catch (_: Exception) { "" }

actual fun getBuildNumber(): String = ""

actual fun getBundleId(): String = ""

actual suspend fun httpPost(url: String, body: String, apiKey: String, requestId: String): String {
    return withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Track-Key", apiKey)
        connection.setRequestProperty("X-Request-ID", requestId)
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val responseBody = if (code in 200..299) {
            connection.inputStream.bufferedReader().readText()
        } else {
            connection.errorStream?.bufferedReader()?.readText() ?: "Error $code"
        }
        connection.disconnect()
        "$code: $responseBody"
    }
}
