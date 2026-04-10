package com.smartapplinks.track

import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object TrackSDK {
    private val queue = mutableListOf<Map<String, String>>()
    private var apiKey = ""
    private var ingestUrl = ""
    private var deviceId = ""
    private var sessionId = ""
    private var sessionStart = ""
    private var sessionNumber = 0
    private var optedOut = false
    private var initialized = false
    private var currentScreen = ""
    private var screenEnteredAt = 0L
    private var sessionScreenCount = 0
    private var sessionEventCount = 0
    private var batchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private const val MAX_BUFFER = 1000
    private const val BATCH_INTERVAL_MS = 30_000L

    /**
     * Initialize the SDK. Reads configuration from track.config.json in app resources.
     * Call once at app startup — no parameters needed.
     */
    fun initialize() {
        if (initialized) return
        val launchStart = epochMs()

        // Read config from resources
        val config = readConfigFile()
        if (config == null) {
            println("[SmartAppLinks SDK] track.config.json not found — run 'npx @hiskicks/smartapplinks-sdk init' to set up")
            return
        }

        this.apiKey = config["api_key"] ?: ""
        this.ingestUrl = config["ingest_url"] ?: ""

        if (apiKey.isEmpty()) {
            println("[SmartAppLinks SDK] No API key in config — run 'npx @hiskicks/smartapplinks-sdk activate'")
            return
        }
        if (ingestUrl.isEmpty()) {
            println("[SmartAppLinks SDK] No ingest URL in config")
            return
        }

        this.deviceId = Uuid.random().toString()
        this.sessionNumber = 1
        this.sessionId = Uuid.random().toString()
        this.sessionStart = now()
        this.initialized = true

        batchJob = scope.launch {
            delay(5_000L)
            flush()
            while (isActive) { delay(BATCH_INTERVAL_MS); flush() }
        }

        enqueue("lifecycle", "app_launch", mapOf("launch_duration_ms" to (epochMs() - launchStart).toString()))
        enqueue("session", "session_start", mapOf("session_number" to sessionNumber.toString()))
        println("[SmartAppLinks SDK] Initialized — ${getDeviceModel()} ${getPlatform()} ${getOsVersion()}")
    }

    /**
     * Initialize with explicit parameters (for testing or advanced use).
     */
    fun initialize(apiKey: String, ingestUrl: String) {
        if (initialized) return
        val launchStart = epochMs()
        this.apiKey = apiKey
        this.ingestUrl = ingestUrl
        this.deviceId = Uuid.random().toString()
        this.sessionNumber = 1
        this.sessionId = Uuid.random().toString()
        this.sessionStart = now()
        this.initialized = true

        batchJob = scope.launch {
            delay(5_000L)
            flush()
            while (isActive) { delay(BATCH_INTERVAL_MS); flush() }
        }

        enqueue("lifecycle", "app_launch", mapOf("launch_duration_ms" to (epochMs() - launchStart).toString()))
        enqueue("session", "session_start", mapOf("session_number" to sessionNumber.toString()))
        println("[SmartAppLinks SDK] Initialized — ${getDeviceModel()} ${getPlatform()} ${getOsVersion()}")
    }

    fun trackScreen(screenName: String) {
        if (optedOut || !initialized || screenName == currentScreen) return
        val previous = currentScreen
        val ms = epochMs()
        if (previous.isNotEmpty() && screenEnteredAt > 0) {
            val dur = ms - screenEnteredAt
            enqueue("screen_exit", previous, mapOf("duration_ms" to dur.toString(), "duration_seconds" to (dur / 1000).toString()))
        }
        currentScreen = screenName
        screenEnteredAt = ms
        sessionScreenCount++
        enqueue("screen_view", screenName, mapOf("from_screen" to previous, "screen_depth" to sessionScreenCount.toString()))
    }

    fun event(name: String, properties: Map<String, String> = emptyMap()) {
        if (optedOut || !initialized) return
        sessionEventCount++
        enqueue("event", name, properties + mapOf("current_screen" to currentScreen))
    }

    fun logError(error: Throwable) {
        if (optedOut || !initialized) return
        enqueue("error", "handled_error", mapOf("error_type" to (error::class.simpleName ?: "Unknown"), "message" to (error.message ?: ""), "stack_trace" to error.stackTraceToString().take(4096), "current_screen" to currentScreen))
    }

    fun onForeground() {
        if (optedOut || !initialized) return
        sessionId = Uuid.random().toString()
        sessionStart = now()
        sessionScreenCount = 0
        sessionEventCount = 0
        screenEnteredAt = epochMs()
        sessionNumber++
        enqueue("lifecycle", "app_foreground")
        enqueue("session", "session_start", mapOf("session_number" to sessionNumber.toString()))
    }

    fun onBackground() {
        if (optedOut || !initialized) return
        if (currentScreen.isNotEmpty() && screenEnteredAt > 0) {
            val dur = epochMs() - screenEnteredAt
            enqueue("screen_exit", currentScreen, mapOf("duration_ms" to dur.toString(), "duration_seconds" to (dur / 1000).toString()))
        }
        enqueue("lifecycle", "app_background")
        enqueue("session", "session_end", mapOf("screen_count" to sessionScreenCount.toString(), "event_count" to sessionEventCount.toString()))
        scope.launch { flush() }
    }

    fun optOut() { optedOut = true; queue.clear(); batchJob?.cancel() }
    fun optIn() { optedOut = false }
    val isOptedOut: Boolean get() = optedOut

    private fun now(): String {
        val s = Clock.System.now().toString()
        val base = s.substringBefore("Z").substringBefore("+")
        val parts = base.split(".")
        return if (parts.size == 2) "${parts[0]}.${parts[1].take(3)}Z" else "${parts[0]}.000Z"
    }
    private fun epochMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun enqueue(type: String, name: String, properties: Map<String, String> = emptyMap()) {
        if (queue.size >= MAX_BUFFER) queue.removeFirst()
        val event = mutableMapOf("event_id" to Uuid.random().toString(), "type" to type, "name" to name, "timestamp" to now())
        properties.forEach { (k, v) -> event["prop_$k"] = v }
        queue.add(event)
    }

    private fun readConfigFile(): Map<String, String>? {
        return try {
            val json = readResourceFile("track.config.json") ?: return null
            // Simple JSON parser — extract api_key and ingest_url
            val map = mutableMapOf<String, String>()
            val keyRegex = """"(\w+)"\s*:\s*"([^"]+)"""".toRegex()
            keyRegex.findAll(json).forEach { match ->
                map[match.groupValues[1]] = match.groupValues[2]
            }
            map
        } catch (e: Exception) {
            println("[SmartAppLinks SDK] Error reading config: ${e.message}")
            null
        }
    }

    suspend fun flush() {
        if (apiKey.isEmpty() || queue.isEmpty() || optedOut) return
        val batchSize = minOf(100, queue.size)
        val events = queue.take(batchSize).toList()
        queue.subList(0, batchSize).clear()
        val batchId = Uuid.random().toString()
        val ts = now()
        val eventsJson = events.joinToString(",") { e ->
            val props = e.entries.filter { it.key.startsWith("prop_") }.joinToString(",") { "\"${it.key.removePrefix("prop_")}\":\"${esc(it.value)}\"" }
            """{"event_id":"${e["event_id"]}","type":"${e["type"]}","name":"${esc(e["name"]!!)}","timestamp":"${e["timestamp"]}","properties":{$props}}"""
        }
        val body = """{"api_key":"$apiKey","batch_id":"$batchId","sent_at":"$ts","sdk":{"name":"smartapplinks-sdk-kmp","version":"1.1.0"},"device":{"device_id":"$deviceId","platform":"${getPlatform()}","os":"${getPlatform()}","os_version":"${esc(getOsVersion())}","device_model":"${esc(getDeviceModel())}","screen_width":${getScreenWidth()},"screen_height":${getScreenHeight()},"locale":"${esc(getLocale())}","timezone":"${esc(getTimezone())}"},"app":{"app_version":"${esc(getAppVersion())}","build_number":"${esc(getBuildNumber())}","bundle_id":"${esc(getBundleId())}","framework":"kmp","framework_version":""},"session":{"session_id":"$sessionId","session_start":"$sessionStart","session_number":$sessionNumber},"events":[$eventsJson]}"""
        try {
            val result = httpPost(ingestUrl, body, apiKey, batchId)
            println("[SmartAppLinks SDK] Sent ${events.size} events — $result")
        } catch (e: Exception) {
            queue.addAll(0, events)
            println("[SmartAppLinks SDK] Network error: ${e.message}")
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

expect fun getPlatform(): String
expect fun getDeviceModel(): String
expect fun getOsVersion(): String
expect fun getScreenWidth(): Int
expect fun getScreenHeight(): Int
expect fun getLocale(): String
expect fun getTimezone(): String
expect fun getAppVersion(): String
expect fun getBuildNumber(): String
expect fun getBundleId(): String
expect fun readResourceFile(name: String): String?
expect suspend fun httpPost(url: String, body: String, apiKey: String, requestId: String): String
