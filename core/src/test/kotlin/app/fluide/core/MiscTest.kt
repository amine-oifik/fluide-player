package app.fluide.core

import app.fluide.core.device.DeviceClassifier
import app.fluide.core.device.DeviceTier
import app.fluide.core.display.DisplayModeInfo
import app.fluide.core.display.DisplayModeSelector
import app.fluide.core.net.Backoff
import app.fluide.core.net.CircuitBreaker
import app.fluide.core.net.HttpFailure
import app.fluide.core.text.SearchNormalizer
import app.fluide.core.zap.ChannelNumberInput
import app.fluide.core.zap.PreloadLevel
import app.fluide.core.zap.PreloadPlanner
import app.fluide.core.zap.ZapGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun doublesAndCaps() {
        val backoff = Backoff(baseMs = 250, maxMs = 4_000)
        assertEquals(listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 4_000L), (1..6).map { backoff.delayFor(it) })
    }

    @Test
    fun addsBoundedJitter() {
        val backoff = Backoff(baseMs = 1_000, maxMs = 1_000, jitterMs = 250)
        repeat(200) { assertTrue(backoff.delayFor(1) in 1_000L..1_250L) }
    }

    @Test
    fun hugeAttemptDoesNotOverflow() {
        assertEquals(60_000L, Backoff(5_000, 60_000).delayFor(10_000))
    }
}

class CircuitBreakerTest {
    @Test
    fun opensAfterRepeatedFailuresThenProbes() {
        val breaker = CircuitBreaker(failureThreshold = 3, windowMs = 60_000, openDurationsMs = longArrayOf(30_000, 120_000))
        repeat(3) { breaker.onFailure("h", it * 1_000L) }
        assertFalse(breaker.allow("h", 5_000))
        assertTrue(breaker.allow("h", 33_000)) // half-open probe
        assertFalse(breaker.allow("h", 33_001)) // only one probe at a time
        breaker.onFailure("h", 34_000) // probe failed: open longer
        assertFalse(breaker.allow("h", 100_000))
        assertTrue(breaker.allow("h", 155_000))
        breaker.onSuccess("h")
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state("h", 155_001))
    }

    @Test
    fun spreadOutFailuresDoNotOpen() {
        val breaker = CircuitBreaker(failureThreshold = 3, windowMs = 10_000)
        repeat(5) { breaker.onFailure("h", it * 20_000L) }
        assertTrue(breaker.allow("h", 100_000))
    }
}

class HttpFailureTest {
    @Test
    fun classifiesStatusCodes() {
        assertEquals(HttpFailure.ACCESS_DENIED, HttpFailure.fromStatus(403))
        assertFalse(HttpFailure.fromStatus(401).retryable)
        assertEquals(HttpFailure.RATE_LIMITED, HttpFailure.fromStatus(429))
        assertTrue(HttpFailure.fromStatus(503).retryable)
        assertEquals(HttpFailure.CLIENT_ERROR, HttpFailure.fromStatus(400))
    }
}

class ZapTest {
    @Test
    fun firstPressIsImmediateBurstWaitsForDwell() {
        val gate = ZapGate(dwellMs = 300, burstWindowMs = 600)
        assertEquals(0L, gate.onPress(1_000))
        assertEquals(300L, gate.onPress(1_200))
        assertEquals(300L, gate.onPress(1_500))
        assertEquals(0L, gate.onPress(5_000))
    }

    @Test
    fun numberEntryCommitsEarlyWhenUnambiguous() {
        val numbers = setOf(1, 2, 7, 10, 11, 104, 1040)
        val longer = { prefix: String -> numbers.any { it.toString().length > prefix.length && it.toString().startsWith(prefix) } }
        val input = ChannelNumberInput()
        assertNull(input.onDigit(1, 0, longer)) // 1, 10, 11, 104... are possible
        assertNull(input.onDigit(0, 100, longer))
        assertNull(input.onDigit(4, 200, longer)) // 1040 still possible
        assertEquals(104, input.commitIfDue(1_800))
        assertEquals(7, input.onDigit(7, 2_000, longer)) // nothing longer starts with 7
        assertNull(input.onDigit(0, 3_000, longer)) // leading zero ignored
        assertEquals("", input.pending)
    }

    @Test
    fun plannerRespectsDeviceAndSourceLimits() {
        val plan = PreloadPlanner.plan(
            currentIndex = 0, channelCount = 50, lastZapDirection = 1, recallIndex = 10, focusedIndex = null,
            deviceMaxLevel = PreloadLevel.PLAYER, sourceAllowsExtraConnections = true,
        )
        assertEquals(PreloadLevel.PLAYER, plan.first { it.index == 1 }.level)
        assertEquals(PreloadLevel.MANIFEST, plan.first { it.index == 49 }.level) // previous wraps around
        assertEquals(PreloadLevel.MANIFEST, plan.first { it.index == 10 }.level)

        val restricted = PreloadPlanner.plan(0, 50, 1, 10, null, PreloadLevel.PLAYER, sourceAllowsExtraConnections = false)
        assertTrue(restricted.all { it.level == PreloadLevel.DNS })

        val goingDown = PreloadPlanner.plan(5, 50, -1, null, null, PreloadLevel.MANIFEST, true)
        assertEquals(PreloadLevel.MANIFEST, goingDown.first { it.index == 4 }.level)
        assertTrue(goingDown.none { it.index == 5 })
    }
}

class DeviceAndDisplayTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun classifiesDevices() {
        assertEquals(DeviceTier.A_LOW, DeviceClassifier.classify(gb, isLowRamDevice = false, videoDecoderInstances = 1).tier)
        assertEquals(DeviceTier.A_LOW, DeviceClassifier.classify(4 * gb, isLowRamDevice = true, videoDecoderInstances = 4).tier)
        assertEquals(DeviceTier.B_MID, DeviceClassifier.classify(2 * gb, false, 2).tier)
        assertEquals(DeviceTier.C_HIGH, DeviceClassifier.classify(3 * gb, false, 2).tier)
        assertEquals(PreloadLevel.DNS, DeviceClassifier.classify(gb, false, 1).maxPreload)
    }

    private val modes = listOf(
        DisplayModeInfo(1, 1920, 1080, 60f),
        DisplayModeInfo(2, 1920, 1080, 50f),
        DisplayModeInfo(3, 1920, 1080, 24f),
        DisplayModeInfo(4, 3840, 2160, 50f),
        DisplayModeInfo(5, 1920, 1080, 23.976f),
    )

    @Test
    fun picksMatchingRefreshRate() {
        val current = modes[0]
        assertEquals(2, DisplayModeSelector.select(25f, current, modes)) // 50 Hz, same resolution
        assertEquals(2, DisplayModeSelector.select(50f, current, modes))
        assertEquals(5, DisplayModeSelector.select(23.976f, current, modes))
        assertNull(DisplayModeSelector.select(30f, current, modes)) // 60 Hz already fits
        assertNull(DisplayModeSelector.select(25f, modes[1], modes)) // already 50 Hz
        assertNull(DisplayModeSelector.select(0f, current, modes))
    }
}

class SearchNormalizerTest {
    @Test
    fun removesAccentsCaseAndPunctuation() {
        assertEquals("tele matin", SearchNormalizer.normalize("Télé-Matin"))
        assertEquals("canal sport 1", SearchNormalizer.normalize("  CANAL+ Sport (1) "))
        assertEquals("oeuvre aeroport strasse", SearchNormalizer.normalize("Œuvre Aéroport Straße"))
        assertEquals("16 9", SearchNormalizer.normalize("16×9"))
        assertEquals("tv 5 monde", SearchNormalizer.normalize("TV 5 Monde"))
    }
}
