package com.plateauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the framework-free [SignatureAnalysis] logic.
 * These run on the local JVM (./gradlew test) with no device or emulator.
 */
class SignatureAnalysisTest {

    private val delta = 1e-9

    // ---- stdDev ----------------------------------------------------------

    @Test
    fun stdDev_emptyOrSingle_isZero() {
        assertEquals(0.0, SignatureAnalysis.stdDev(emptyList()), delta)
        assertEquals(0.0, SignatureAnalysis.stdDev(listOf(42.0)), delta)
    }

    @Test
    fun stdDev_identicalValues_isZero() {
        assertEquals(0.0, SignatureAnalysis.stdDev(listOf(5.0, 5.0, 5.0, 5.0)), delta)
    }

    @Test
    fun stdDev_knownValues_matchesPopulationFormula() {
        // population stddev of [2,4,4,4,5,5,7,9] is exactly 2.0
        val values = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        assertEquals(2.0, SignatureAnalysis.stdDev(values), delta)
    }

    // ---- gaps / avgGap ---------------------------------------------------

    @Test
    fun gaps_returnsConsecutiveDeltas() {
        assertEquals(listOf(100L, 50L, 250L), SignatureAnalysis.gaps(listOf(0L, 100L, 150L, 400L)))
    }

    @Test
    fun gaps_fewerThanTwo_isEmpty() {
        assertTrue(SignatureAnalysis.gaps(emptyList()).isEmpty())
        assertTrue(SignatureAnalysis.gaps(listOf(7L)).isEmpty())
    }

    @Test
    fun avgGap_fewerThanTwoEvents_isZero() {
        assertEquals(0.0, SignatureAnalysis.avgGapMs(emptyList()), delta)
        assertEquals(0.0, SignatureAnalysis.avgGapMs(listOf(123L)), delta)
    }

    @Test
    fun avgGap_computesMeanOfDeltas() {
        // deltas: 100, 50, 250 -> mean 133.333...
        assertEquals(400.0 / 3.0, SignatureAnalysis.avgGapMs(listOf(0L, 100L, 150L, 400L)), delta)
    }

    // ---- eventsPerSecond -------------------------------------------------

    @Test
    fun eventsPerSecond_defaultWindow() {
        // 30 events over the default 15s window -> 2.0 / sec
        assertEquals(2.0, SignatureAnalysis.eventsPerSecond(30), delta)
        assertEquals(0.0, SignatureAnalysis.eventsPerSecond(0), delta)
    }

    @Test
    fun eventsPerSecond_customWindow() {
        assertEquals(5.0, SignatureAnalysis.eventsPerSecond(10, durationMs = 2_000L), delta)
    }

    @Test(expected = IllegalArgumentException::class)
    fun eventsPerSecond_nonPositiveDuration_throws() {
        SignatureAnalysis.eventsPerSecond(5, durationMs = 0L)
    }

    // ---- techDistribution ------------------------------------------------

    @Test
    fun techDistribution_countsAcrossEvents() {
        val perEvent = listOf(
            listOf("NfcA", "IsoDep"),
            listOf("NfcA"),
            listOf("NfcB", "NfcA"),
        )
        val dist = SignatureAnalysis.techDistribution(perEvent)
        assertEquals(3, dist["NfcA"])
        assertEquals(1, dist["NfcB"])
        assertEquals(1, dist["IsoDep"])
    }

    // ---- buildProfile ----------------------------------------------------

    @Test
    fun buildProfile_populatesAllFields() {
        val timestamps = listOf(0L, 100L, 150L, 400L)
        val techs = listOf(listOf("NfcA"), listOf("NfcA"), listOf("NfcB"), listOf("NfcA"))
        val p = SignatureAnalysis.buildProfile("plate", timestamps, techs)

        assertEquals("plate", p.label)
        assertEquals(4, p.eventCount)
        assertEquals(1, p.captureCount)
        assertEquals(4.0 / 15.0, p.avgEventsPerSecond, delta)
        assertEquals(400.0 / 3.0, p.avgTimeBetweenEvents, delta)
        assertEquals(3, p.techDistribution["NfcA"])
        assertEquals(1, p.techDistribution["NfcB"])
    }

    @Test
    fun buildProfile_emptyEvents_isSafe() {
        val p = SignatureAnalysis.buildProfile("air", emptyList(), emptyList())
        assertEquals(0, p.eventCount)
        assertEquals(0.0, p.avgEventsPerSecond, delta)
        assertEquals(0.0, p.avgTimeBetweenEvents, delta)
        assertTrue(p.techDistribution.isEmpty())
    }

    // ---- matchScore / verdict --------------------------------------------

    private fun profile(rate: Double, gap: Double, count: Int) =
        SignatureAnalysis.Profile(rate, gap, count, emptyMap(), 1, "p")

    @Test
    fun matchScore_identicalProfiles_isThree() {
        val a = profile(2.0, 300.0, 40)
        assertEquals(3, SignatureAnalysis.matchScore(a, a))
    }

    @Test
    fun matchScore_allBeyondThresholds_isZero() {
        val enrolled = profile(2.0, 300.0, 40)
        val attempt = profile(10.0, 2000.0, 100)
        assertEquals(0, SignatureAnalysis.matchScore(enrolled, attempt))
    }

    @Test
    fun matchScore_partialMatch_countsEachCriterionIndependently() {
        val enrolled = profile(2.0, 300.0, 40)
        // rate within 2.0, gap beyond 500, count within 20 -> 2 points
        val attempt = profile(3.5, 1000.0, 50)
        assertEquals(2, SignatureAnalysis.matchScore(enrolled, attempt))
    }

    @Test
    fun matchScore_isBoundaryExclusive() {
        val enrolled = profile(0.0, 0.0, 0)
        // exactly at the thresholds -> NOT a match (strict less-than)
        val atBoundary = profile(
            SignatureAnalysis.RATE_THRESHOLD,
            SignatureAnalysis.GAP_THRESHOLD_MS,
            SignatureAnalysis.COUNT_THRESHOLD,
        )
        assertEquals(0, SignatureAnalysis.matchScore(enrolled, atBoundary))
    }

    @Test
    fun verdict_mapsScoreToState() {
        assertEquals(SignatureAnalysis.Verdict.MATCH, SignatureAnalysis.verdict(3))
        assertEquals(SignatureAnalysis.Verdict.MATCH, SignatureAnalysis.verdict(2))
        assertEquals(SignatureAnalysis.Verdict.INCONCLUSIVE, SignatureAnalysis.verdict(1))
        assertEquals(SignatureAnalysis.Verdict.REJECTED, SignatureAnalysis.verdict(0))
    }
}
