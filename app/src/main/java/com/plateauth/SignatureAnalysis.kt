package com.plateauth

/**
 * Pure, framework-free signature analysis logic for PlateAuth.
 *
 * This object holds the statistical core of the app (timing statistics, profile
 * construction, and the authentication scoring rule). It deliberately has NO
 * Android dependencies so it can be unit-tested on the JVM without a device or
 * emulator. [MainActivity] delegates to these functions.
 */
object SignatureAnalysis {

    /** Capture window length in milliseconds. Mirrors MainActivity.CAPTURE_DURATION_MS. */
    const val CAPTURE_DURATION_MS: Long = 15_000L

    /** Authentication thresholds (kept here so the scoring rule is testable). */
    const val RATE_THRESHOLD: Double = 2.0
    const val GAP_THRESHOLD_MS: Double = 500.0
    const val COUNT_THRESHOLD: Int = 20

    /**
     * Population standard deviation of [values].
     *
     * Returns 0.0 for fewer than two values (a single point has no spread).
     */
    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return Math.sqrt(values.map { (it - mean) * (it - mean) }.average())
    }

    /**
     * Consecutive gaps (deltas) between sorted-by-arrival event timestamps in ms.
     * For a list of N timestamps this returns N-1 gaps; fewer than two timestamps
     * yields an empty list.
     */
    fun gaps(timestampsMs: List<Long>): List<Long> =
        timestampsMs.zipWithNext().map { (a, b) -> b - a }

    /** Average gap between events in ms, or 0.0 when there are not enough events. */
    fun avgGapMs(timestampsMs: List<Long>): Double {
        val g = gaps(timestampsMs)
        return if (g.isEmpty()) 0.0 else g.map { it.toDouble() }.average()
    }

    /**
     * Events observed per second over the fixed capture window.
     *
     * @param eventCount number of events captured
     * @param durationMs capture window length (defaults to [CAPTURE_DURATION_MS])
     */
    fun eventsPerSecond(eventCount: Int, durationMs: Long = CAPTURE_DURATION_MS): Double {
        require(durationMs > 0) { "durationMs must be positive" }
        return eventCount / (durationMs / 1000.0)
    }

    /** Tech-tag frequency distribution across all events. */
    fun techDistribution(perEventTechLists: List<List<String>>): Map<String, Int> =
        perEventTechLists.flatten().groupingBy { it }.eachCount()

    /**
     * Build a numeric profile summary from event timestamps and per-event tech lists.
     */
    fun buildProfile(
        label: String,
        timestampsMs: List<Long>,
        perEventTechLists: List<List<String>>,
        durationMs: Long = CAPTURE_DURATION_MS,
    ): Profile = Profile(
        avgEventsPerSecond = eventsPerSecond(timestampsMs.size, durationMs),
        avgTimeBetweenEvents = avgGapMs(timestampsMs),
        eventCount = timestampsMs.size,
        techDistribution = techDistribution(perEventTechLists),
        captureCount = 1,
        label = label,
    )

    /**
     * Number of matching criteria (0..3) between an enrolled profile and an attempt.
     * Each of rate, gap, and event-count within its threshold counts as one point.
     */
    fun matchScore(enrolled: Profile, attempt: Profile): Int {
        val rateDiff = Math.abs(enrolled.avgEventsPerSecond - attempt.avgEventsPerSecond)
        val gapDiff = Math.abs(enrolled.avgTimeBetweenEvents - attempt.avgTimeBetweenEvents)
        val countDiff = Math.abs(enrolled.eventCount - attempt.eventCount)
        return listOf(
            rateDiff < RATE_THRESHOLD,
            gapDiff < GAP_THRESHOLD_MS,
            countDiff < COUNT_THRESHOLD,
        ).count { it }
    }

    /** Verdict for a score in 0..3, matching the UI's MATCH / PARTIAL / REJECTED states. */
    fun verdict(score: Int): Verdict = when {
        score >= 2 -> Verdict.MATCH
        score == 1 -> Verdict.INCONCLUSIVE
        else -> Verdict.REJECTED
    }

    enum class Verdict { MATCH, INCONCLUSIVE, REJECTED }

    data class Profile(
        val avgEventsPerSecond: Double,
        val avgTimeBetweenEvents: Double,
        val eventCount: Int,
        val techDistribution: Map<String, Int>,
        val captureCount: Int,
        val label: String,
    )
}
