package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.export1
import com.aditya.clashtimer.core.SampleExports.export2
import com.aditya.clashtimer.core.SampleExports.export3
import com.aditya.clashtimer.core.SampleExports.times
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeTimesTest {

    @Test fun assetLoads() {
        assertTrue("got ${times.size}", times.size > 140)
        assertEquals(1166400L, times.timeTo(1000027, 12)) // Inferno Tower → 12: 13d 12h
        assertEquals(1123200L, times.timeTo(107000008, 5)) // Logger → 5: 13d (guardian)
        assertEquals(288000L, times.timeTo(102000040, 8)) // Cake-A-Pult Damage module → 8
        assertEquals(1296000L, times.timeTo(4000008, 13)) // Dragon → 13: 15d
        // Supercharge levels restart at 1 in the data; only the normal track is kept.
        assertEquals(43200L, times.timeTo(1000027, 1))
        assertNull(times.timeTo(1000027, 99))
    }

    /** Every running upgrade in the real exports has a full time, and it is never shorter than what was left. */
    @Test fun everySampleJobHasAPlausibleFullTime() {
        for (e in listOf(export1, export2, export3)) for (job in e.jobs) {
            val full = times.fullTime(job)
            assertTrue("no time for ${job.key}", full != null)
            assertTrue("${job.key}: full $full < remaining ${job.remainingAtT0}", full!! >= job.remainingAtT0)
        }
    }

    @Test fun parsesFlatMapToo() {
        val t = UpgradeTimes.fromJson("""{"5":{"1":10,"2":20,"x":3},"y":{}}""")
        assertEquals(1, t.size)
        assertEquals(20L, t.timeTo(5, 2))
        assertNull(t.fullTime(Job(JobCategory.BUILDER, JobSource.BUILDING, 5, 2, 9, false))) // level 3 unknown
    }
}
