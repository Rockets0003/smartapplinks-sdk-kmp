package com.smartapplinks.track

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import platform.UIKit.UIDevice
import platform.UIKit.UIScreen
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun getPlatform(): String = "ios"
actual fun getDeviceModel(): String = UIDevice.currentDevice.model
actual fun getOsVersion(): String = UIDevice.currentDevice.systemVersion
actual fun getLocale(): String = NSLocale.currentLocale.languageCode ?: "en"
actual fun getTimezone(): String = NSTimeZone.localTimeZone.name
actual fun getAppVersion(): String = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String) ?: ""
actual fun getBuildNumber(): String = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String) ?: ""
actual fun getBundleId(): String = NSBundle.mainBundle.bundleIdentifier ?: ""
actual fun getScreenWidth(): Int = 0
actual fun getScreenHeight(): Int = 0

actual fun readResourceFile(name: String): String? {
    return try {
        val path = NSBundle.mainBundle.pathForResource(name.substringBeforeLast("."), ofType = name.substringAfterLast("."))
        path?.let { NSString.stringWithContentsOfFile(it, encoding = NSUTF8StringEncoding, error = null)?.toString() }
    } catch (_: Exception) { null }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual suspend fun httpPost(url: String, body: String, apiKey: String, requestId: String): String {
    return suspendCancellableCoroutine { continuation ->
        val nsUrl = NSURL.URLWithString(url) ?: run {
            continuation.resumeWithException(Exception("Invalid URL"))
            return@suspendCancellableCoroutine
        }
        val bodyData = (body as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod("POST")
            setValue("application/json", forHTTPHeaderField = "Content-Type")
            setValue(apiKey, forHTTPHeaderField = "X-Track-Key")
            setValue(requestId, forHTTPHeaderField = "X-Request-ID")
            setHTTPBody(bodyData)
            setTimeoutInterval(10.0)
        }
        NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            if (error != null) {
                continuation.resumeWithException(Exception(error.localizedDescription))
                return@dataTaskWithRequest
            }
            val code = (response as? NSHTTPURLResponse)?.statusCode ?: 0
            val text = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() } ?: ""
            continuation.resume("$code: $text")
        }.resume()
    }
}
