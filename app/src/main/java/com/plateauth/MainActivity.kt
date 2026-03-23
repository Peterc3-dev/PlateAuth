package com.plateauth

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcV
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "PlateAuth"
        private const val READER_FLAGS = (
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        )
        private const val CAPTURE_DURATION_MS = 15_000L
    }

    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var statusView: TextView
    private lateinit var btnCapturePlate: Button
    private lateinit var btnCaptureBare: Button
    private lateinit var btnCaptureAir: Button
    private lateinit var btnCompare: Button
    private lateinit var btnEnroll: Button
    private lateinit var btnAuthenticate: Button

    private val handler = Handler(Looper.getMainLooper())
    private var isCapturing = false
    private var captureLabel = ""
    private var captureStartTime = 0L
    private val captureEvents = mutableListOf<CaptureEvent>()
    private val signatures = mutableMapOf<String, List<CaptureEvent>>()
    private var enrolledProfile: SignatureProfile? = null

    data class CaptureEvent(
        val timestampMs: Long,
        val eventType: String,
        val techList: List<String>,
        val tagId: String,
        val details: String
    )

    data class SignatureProfile(
        val avgEventsPerSecond: Double,
        val avgTimeBetweenEvents: Double,
        val eventCount: Int,
        val techDistribution: Map<String, Int>,
        val captureCount: Int,
        val label: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.log_view)
        scrollView = findViewById(R.id.scroll_view)
        statusView = findViewById(R.id.status_view)
        btnCapturePlate = findViewById(R.id.btn_capture_plate)
        btnCaptureBare = findViewById(R.id.btn_capture_bare)
        btnCaptureAir = findViewById(R.id.btn_capture_air)
        btnCompare = findViewById(R.id.btn_compare)
        btnEnroll = findViewById(R.id.btn_enroll)
        btnAuthenticate = findViewById(R.id.btn_authenticate)

        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            setStatus("ERROR: No NFC hardware found")
            return
        }
        nfcAdapter = adapter

        if (!nfcAdapter.isEnabled) {
            setStatus("NFC is OFF — enable in Settings")
        } else {
            setStatus("NFC ready. Choose a capture position.")
        }

        btnCapturePlate.setOnClickListener { startCapture("plate_side") }
        btnCaptureBare.setOnClickListener { startCapture("bare_side") }
        btnCaptureAir.setOnClickListener { startCapture("baseline_air") }
        btnCompare.setOnClickListener { compareSignatures() }
        btnEnroll.setOnClickListener { enrollPlateProfile() }
        btnAuthenticate.setOnClickListener { startCapture("auth_attempt") }

        log("PlateAuth initialized")
        log("Device: ${android.os.Build.MODEL}")
        log("Android: ${android.os.Build.VERSION.RELEASE}")
        log("")
        log("PROTOCOL:")
        log("1. Tap 'Plate' — hold phone NFC ring flat against plate")
        log("2. Tap 'Bare' — hold against opposite side of head")
        log("3. Tap 'Air' — hold phone in empty space")
        log("4. Tap 'Compare' to see signature differences")
        log("5. 'Enroll' saves plate signature, 'Auth' tests against it")
        log("")

        loadEnrolledProfile()
    }

    override fun onResume() {
        super.onResume()
        if (::nfcAdapter.isInitialized && nfcAdapter.isEnabled) {
            val options = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 200)
            }
            nfcAdapter.enableReaderMode(this, this, READER_FLAGS, options)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::nfcAdapter.isInitialized) nfcAdapter.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (!isCapturing || tag == null) return
        val now = System.currentTimeMillis()
        val relative = now - captureStartTime
        val techList = tag.techList?.toList() ?: emptyList()
        val tagId = tag.id?.joinToString("") { "%02X".format(it) } ?: "none"
        val details = extractTagDetails(tag)

        val event = CaptureEvent(relative, "tag_discovered", techList, tagId, details)
        synchronized(captureEvents) { captureEvents.add(event) }
        runOnUiThread {
            log("  [${relative}ms] TAG: id=$tagId techs=${techList.joinToString(",")}")
        }
    }

    private fun extractTagDetails(tag: Tag): String {
        val details = StringBuilder()
        try {
            NfcA.get(tag)?.let { nfcA ->
                nfcA.connect()
                details.append("NfcA[atqa=${nfcA.atqa?.joinToString("") { "%02X".format(it) }}")
                details.append(",sak=${nfcA.sak},maxTx=${nfcA.maxTransceiveLength}]")
                nfcA.close()
            }
        } catch (e: Exception) { details.append("NfcA[fail:${e.message}]") }
        try {
            NfcB.get(tag)?.let { nfcB ->
                nfcB.connect()
                details.append("NfcB[appData=${nfcB.applicationData?.joinToString("") { "%02X".format(it) }}]")
                nfcB.close()
            }
        } catch (e: Exception) { details.append("NfcB[fail:${e.message}]") }
        try {
            NfcV.get(tag)?.let { nfcV ->
                nfcV.connect()
                details.append("NfcV[dsfId=${nfcV.dsfId},respFlags=${nfcV.responseFlags}]")
                nfcV.close()
            }
        } catch (e: Exception) { details.append("NfcV[fail:${e.message}]") }
        try {
            IsoDep.get(tag)?.let { iso ->
                iso.connect()
                details.append("IsoDep[maxTx=${iso.maxTransceiveLength}]")
                iso.close()
            }
        } catch (e: Exception) { details.append("IsoDep[fail:${e.message}]") }
        return if (details.isEmpty()) "no_tech_data" else details.toString()
    }

    private fun startCapture(label: String) {
        if (isCapturing) { log("[!] Capture already running"); return }
        captureLabel = label
        captureEvents.clear()
        captureStartTime = System.currentTimeMillis()
        isCapturing = true
        setStatus("CAPTURING: $label — hold 15 sec")
        log("\n=== CAPTURE START: $label ===\n")

        val pollLogger = object : Runnable {
            var lastCount = 0; var tick = 0
            override fun run() {
                if (!isCapturing) return
                tick++
                val count = synchronized(captureEvents) { captureEvents.size }
                if (tick % 2 == 0) log("  [${tick/2}s] events: $count (+${count - lastCount})")
                lastCount = count
                handler.postDelayed(this, 500)
            }
        }
        handler.post(pollLogger)
        handler.postDelayed({ stopCapture() }, CAPTURE_DURATION_MS)
    }

    private fun stopCapture() {
        isCapturing = false
        val events = synchronized(captureEvents) { captureEvents.toList() }
        signatures[captureLabel] = events

        log("\n=== CAPTURE COMPLETE: $captureLabel ===")
        log("Total events: ${events.size}")
        if (events.isNotEmpty()) {
            val rate = events.size.toDouble() / (CAPTURE_DURATION_MS / 1000.0)
            log("Rate: %.2f events/sec".format(rate))
            if (events.size > 1) {
                val gaps = events.zipWithNext().map { (a, b) -> b.timestampMs - a.timestampMs }
                log("Avg gap: %.1fms | StdDev: %.1fms".format(gaps.average(), stdDev(gaps.map { it.toDouble() })))
            }
        } else {
            log("No tag events — this is expected baseline data.")
        }
        log("")
        saveCapture(captureLabel, events)
        if (captureLabel == "auth_attempt") runAuthentication(events)
        setStatus("Done. ${events.size} events captured.")
    }

    private fun compareSignatures() {
        log("\n══════ SIGNATURE COMPARISON ══════\n")
        if (signatures.isEmpty()) { log("No captures yet."); return }
        for ((label, events) in signatures) {
            log("--- $label ---")
            log("  Events: ${events.size}")
            if (events.isNotEmpty() && events.size > 1) {
                val gaps = events.zipWithNext().map { (a, b) -> b.timestampMs - a.timestampMs }
                log("  Rate: %.2f/sec | Avg gap: %.1fms | StdDev: %.1fms".format(
                    events.size / (CAPTURE_DURATION_MS / 1000.0), gaps.average(), stdDev(gaps.map { it.toDouble() })
                ))
                log("  Techs: ${events.flatMap { it.techList }.groupingBy { it }.eachCount()}")
            }
            log("")
        }
    }

    private fun enrollPlateProfile() {
        val events = signatures["plate_side"]
        if (events == null) { log("Capture plate side first."); return }
        val profile = buildProfile("plate_enrolled", events)
        enrolledProfile = profile
        val prefs = getSharedPreferences("plate_auth", MODE_PRIVATE)
        val json = JSONObject().apply {
            put("avgEventsPerSecond", profile.avgEventsPerSecond)
            put("avgTimeBetweenEvents", profile.avgTimeBetweenEvents)
            put("eventCount", profile.eventCount)
            put("captureCount", profile.captureCount)
            put("label", profile.label)
            put("techDistribution", JSONObject(profile.techDistribution.mapValues { it.value }))
        }
        prefs.edit().putString("enrolled_profile", json.toString()).apply()
        log("\n=== ENROLLED ===\nRate: %.2f/sec | Gap: %.1fms | Events: %d\n".format(
            profile.avgEventsPerSecond, profile.avgTimeBetweenEvents, profile.eventCount
        ))
        setStatus("Profile enrolled.")
    }

    private fun runAuthentication(events: List<CaptureEvent>) {
        val profile = enrolledProfile ?: run { log("No profile enrolled."); return }
        val attempt = buildProfile("auth_attempt", events)

        val rateDiff = Math.abs(profile.avgEventsPerSecond - attempt.avgEventsPerSecond)
        val gapDiff = Math.abs(profile.avgTimeBetweenEvents - attempt.avgTimeBetweenEvents)
        val countDiff = Math.abs(profile.eventCount - attempt.eventCount)

        val score = listOf(rateDiff < 2.0, gapDiff < 500.0, countDiff < 20).count { it }

        log("\n══════ AUTH RESULT ══════")
        log("Enrolled: %.2f/sec, %.1fms gap, %d events".format(
            profile.avgEventsPerSecond, profile.avgTimeBetweenEvents, profile.eventCount
        ))
        log("Attempt:  %.2f/sec, %.1fms gap, %d events".format(
            attempt.avgEventsPerSecond, attempt.avgTimeBetweenEvents, attempt.eventCount
        ))
        log("Score: $score/3")
        when {
            score >= 2 -> { log(">>> MATCH <<<"); setStatus("AUTHENTICATED") }
            score == 1 -> { log(">>> PARTIAL — inconclusive <<<"); setStatus("INCONCLUSIVE") }
            else -> { log(">>> REJECTED <<<"); setStatus("REJECTED") }
        }
        log("")
    }

    private fun buildProfile(label: String, events: List<CaptureEvent>) = SignatureProfile(
        avgEventsPerSecond = events.size / (CAPTURE_DURATION_MS / 1000.0),
        avgTimeBetweenEvents = if (events.size > 1)
            events.zipWithNext().map { (a, b) -> (b.timestampMs - a.timestampMs).toDouble() }.average() else 0.0,
        eventCount = events.size,
        techDistribution = events.flatMap { it.techList }.groupingBy { it }.eachCount(),
        captureCount = 1,
        label = label
    )

    private fun loadEnrolledProfile() {
        val json = getSharedPreferences("plate_auth", MODE_PRIVATE)
            .getString("enrolled_profile", null) ?: return
        try {
            val obj = JSONObject(json)
            val techJson = obj.optJSONObject("techDistribution") ?: JSONObject()
            val techMap = mutableMapOf<String, Int>()
            techJson.keys().forEach { techMap[it] = techJson.getInt(it) }
            enrolledProfile = SignatureProfile(
                obj.getDouble("avgEventsPerSecond"), obj.getDouble("avgTimeBetweenEvents"),
                obj.getInt("eventCount"), techMap, obj.getInt("captureCount"), obj.getString("label")
            )
            log("[+] Loaded enrolled profile")
        } catch (e: Exception) { log("[!] Profile load failed: ${e.message}") }
    }

    private fun saveCapture(label: String, events: List<CaptureEvent>) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(getExternalFilesDir(null), "plate_captures").apply { mkdirs() }
            val file = File(dir, "${ts}_${label}.json")
            val json = JSONObject().apply {
                put("label", label); put("timestamp", ts); put("device", android.os.Build.MODEL)
                put("duration_ms", CAPTURE_DURATION_MS); put("event_count", events.size)
                put("events", JSONArray().apply {
                    events.forEach { e -> put(JSONObject().apply {
                        put("timestamp_ms", e.timestampMs); put("event_type", e.eventType)
                        put("tech_list", JSONArray(e.techList)); put("tag_id", e.tagId)
                        put("details", e.details)
                    })}
                })
            }
            file.writeText(json.toString(2))
            log("[+] Saved: ${file.name}")
        } catch (e: Exception) { log("[!] Save failed: ${e.message}") }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        Log.d(TAG, msg)
    }

    private fun setStatus(msg: String) { runOnUiThread { statusView.text = msg } }

    private fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return Math.sqrt(values.map { (it - mean) * (it - mean) }.average())
    }
}
