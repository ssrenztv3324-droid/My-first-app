package com.example

import com.example.data.local.PinSecurity
import com.example.data.local.StorageStatsProvider
import com.example.data.local.VideoQualityPreset
import com.example.service.MotionDetector
import com.example.ui.formatDurationHms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun motionSensitivityThresholdsDecreaseWithHigherSensitivity() {
        val low = MotionDetector.thresholdForSensitivity(1)
        val med = MotionDetector.thresholdForSensitivity(2)
        val high = MotionDetector.thresholdForSensitivity(3)
        assertTrue(low > med && med > high)
    }

    @Test
    fun storageFormatterFormatsMbAndGbAccurately() {
        assertEquals("720.0 MB", StorageStatsProvider.formatBytes(720L * 1024L * 1024L))
        assertEquals("2.00 GB", StorageStatsProvider.formatBytes(2L * 1024L * 1024L * 1024L))
    }

    @Test
    fun twoHourCycleFormatsProperly() {
        assertEquals("02:00:00", formatDurationHms(7200L))
        assertEquals("00:05:09", formatDurationHms(309L))
    }

    @Test
    fun balanced32GbPresetStaysUnderOneGigabytePerTwoHours() {
        assertTrue(VideoQualityPreset.BALANCED_480P.approxMbPer2Hours < 1024)
        assertTrue(VideoQualityPreset.COMPACT_360P.approxMbPer2Hours < 512)
    }

    @Test
    fun pinVerificationAndCooldownEscalateAfterThreeFailures() {
        val salt = PinSecurity.generateSalt()
        val hash = PinSecurity.hashPin("4829", salt)
        assertTrue(PinSecurity.verifyPin("4829", salt, hash))
        assertFalse(PinSecurity.verifyPin("1234", salt, hash))

        assertEquals(0L, PinSecurity.cooldownSecondsForFailures(1))
        assertEquals(0L, PinSecurity.cooldownSecondsForFailures(2))
        assertEquals(30L, PinSecurity.cooldownSecondsForFailures(3))
        assertEquals(60L, PinSecurity.cooldownSecondsForFailures(4))
        assertEquals(120L, PinSecurity.cooldownSecondsForFailures(5))
    }
}
