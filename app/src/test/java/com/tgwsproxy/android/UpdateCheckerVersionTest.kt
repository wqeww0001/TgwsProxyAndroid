package com.tgwsproxy.android

import com.tgwsproxy.android.UpdateChecker.isNewerForTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {
    @Test
    fun higherCoreVersionIsNewer() {
        assertTrue(isNewerForTest("2.5.4", "2.5.3"))
        assertFalse(isNewerForTest("2.5.2", "2.5.3"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(isNewerForTest("2.5.3", "2.5.3"))
    }

    @Test
    fun stableReleaseIsNewerThanItsBeta() {
        assertTrue(isNewerForTest("2.6.0", "2.6.0-beta.1"))
        assertFalse(isNewerForTest("2.6.0-beta.1", "2.6.0"))
    }

    @Test
    fun betaNumbersCompareNumerically() {
        assertTrue(isNewerForTest("2.6.0-beta.10", "2.6.0-beta.9"))
        assertFalse(isNewerForTest("2.6.0-beta.9", "2.6.0-beta.10"))
    }

    @Test
    fun vPrefixIsIgnored() {
        assertTrue(isNewerForTest("v2.6.0", "2.5.3"))
    }
}
