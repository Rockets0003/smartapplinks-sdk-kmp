package com.smartapplinks.track

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object TrackSDK {
    private val queue = mutableListOf<Map<String, String>>()
    private var apiKey: String = ""
    private var ingestUrl: String = ""
    private var deviceId: String = ""
    private var sessionId: String = ""
    private var sessionStart: String = ""
    private var sessionNumber: Int = 0
    private var optedOut: Boolean = false
    private var initialized: Boolean = false
    private var currentScreen: String = ""
    private var batchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val client = HttpClient()
    private const val MAX_BUFFER = 1000
    private const val BATCH_INTERVAL_MS = 30_000L

    fun initialize(apiKey: String, ingestUrl: String, deviceId: String, sessionNumber: Int) {
        if (initialized) return
        this.apiKey = apiKey
        this.ingestUrl = ingestUrl
        this.deviceId = deviceId
        this.sessionNumber = sessionNumber
        this.sessionId = Uuid.random().toString()
        this.sessionStart = Clock.System.now().toString()
        this.initialized = true
        batchJob = scope.launch {
            while (isActive) { delay(BATCH_INTERVAL_MS); flush() }
        }
        enqueue("lifecycle", "app_launch")
        enqueue("session", "session_start")
    }

    fun trackScreen(screenName: String) {
        if (optedOut || !initialized) return
        if (screenName == currentScreen) return
        val previous = currentScreen
        currentScreen = screenName
        enqueue("screen_view", screenName, mapOf("from_screen" to previous))
    }

    fun event(name: String, properties: Map<String, String> = emptyMap()) {
        if (optedOut || !initialized) return
        enqueue("event", name, properties)
    }

    fun logError(error: Throwable) {
        if (optedOut || !initialized) return
        enqueue("error", "handled_error", mapOf(
            "error_type" to (error::class.simpleName ?: "Unknown"),
            "message" to (error.message ?: ""),
            "stack_trace" to (error.stackTraceToString().take(4096))
        ))
    }

    fun onForeground() {
        if (optedOut || !initialized) return
        sessionId = Uuid.random().toString()
        sessionStart = Clock.System.now().toString()
        enqueue("lifecycle", "app_foreground")
        enqueue("session", "session_start")
    }

    fun onBackground() {
        if (optedOut || !initialized) return
        enqueue("lifecycle", "app_background")
        enqueue("session", "session_end")
        scope.launch { flush() }
    }

    fun optOut() { optedOut = true; queue.clear(); batchJob?.cancel() }
    fun optIn() { optedOut = false }
    val isOptedOut: Boolean get() = optedOut

    private fun enqueue(type: String, name: String, properties: Map<String, String> = emptyMap()) {
        if (queue.size >= MAX_BUFFER) queue.removeFirst()
        val event = mutableMapOf(
            "event_id" to Uuid.random().toString(),
            "type" to type,
            "name" to name,
            "timestamp" to Clock.System.now().toString(),
        )
        properties.forEach { (k, v) -> event["prop_$k"] = v }
        queue.add(event)
    }

    suspend fun flush() {
        if (apiKey.isEmpty() || queue.isEmpty() || optedOut) return
        val batchSize = minOf(100, queue.size)
        val events = queue.take(batchSize).toList()
        queue.subList(0, batchSize).clear()
        val batchId = Uuid.random().toString()
        val now = Clock.System.now().toString()
        val eventsJson = events.joinToString(",") { event ->
            val props = event.entries
                .filter { it.key.startsWith("prop_") }
                .joinToString(",") { "\"${it.key.removePrefix("prop_")}\":\"${escapeJson(it.value)}\"" }
            """{"event_id":"${event["event_id"]}","type":"${event["type"]}","name":"${escapeJson(event["name"] ?: "")}","timestamp":"${event["timestamp"]}","properties":{$props}}"""
        }
        val body = """{"api_key":"$apiKey","batch_id":"$batchId","sent_at":"$now","sdk":{"name":"smartapplinks-sdk-kmp","version":"1.0.0"},"device":{"device_id":"$deviceId","platform":"${getPlatform()}","os":"${getPlatform()}","os_version":"","device_model":"","screen_width":0,"screen_height":0,"locale":"","timezone":""},"app":{"app_version":"","build_number":"","bundle_id":"","framework":"kmp","framework_version":""},"session":{"session_id":"$sessionId","session_start":"$sessionStart","session_number":$sessionNumber},"events":[$eventsJson]}"""
        try {
            val response = client.post(ingestUrl) {
                contentType(ContentType.Application.Json)
                header("X-Track-Key", apiKey)
                header("X-Request-ID", batchId)
                setBody(body)
            }
            when (response.status.value) {
                401, 403 -> { batchJob?.cancel() }
                429 -> queue.addAll(0, events)
                in 500..599 -> queue.addAll(0, events)
            }
        } catch (e: Exception) {
            queue.addAll(0, events)
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

expect fun getPlatform(): String
