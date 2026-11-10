package com.aditya.clashtimer.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CrashReportTest {
    private val info = CrashReport.Info(
        versionName = "1.0.1", versionCode = 2, r8 = "on",
        androidRelease = "14", sdkInt = 34, manufacturer = "ACME", model = "Phone",
        process = "com.aditya.clashtimer", thread = "main",
        epochMs = 1_793_000_000_000, zone = ZoneId.of("UTC"),
    )

    private fun nested(): Throwable =
        IllegalStateException("Widget refresh failed: no state.json", java.io.IOException("disk full"))

    @Test fun headerAndTrace() {
        val text = CrashReport.build(info, nested())
        assertTrue(text.startsWith(CrashReport.TITLE + "\n"))
        assertTrue(text.contains("App: 1.0.1 (2), R8 minify: on"))
        assertTrue(text.contains("Phone: ACME Phone, Android 14 (API 34)"))
        assertTrue(text.contains("Process: com.aditya.clashtimer, thread: main"))
        assertTrue(text.contains("Time: 2026-10-26 07:33:20 +00:00"))
        assertTrue(text.contains("Retrace: tools/retrace.sh 1.0.1 <this file>"))
        assertTrue(text.contains("java.lang.IllegalStateException: Widget refresh failed: no state.json"))
        assertTrue(text.contains("Caused by: java.io.IOException: disk full"))
        assertTrue(text.contains("at com.aditya.clashtimer.crash.CrashReportTest.nested"))
    }

    @Test fun noRetraceLineWhenNotMinified() {
        val text = CrashReport.build(info.copy(r8 = "off"), nested())
        assertTrue(text.contains("R8 minify: off"))
        assertFalse(text.contains("Retrace:"))
        assertTrue(CrashReport.build(info.copy(r8 = ""), nested()).contains("R8 minify: unknown"))
    }

    @Test fun summaryAndTime() {
        val text = CrashReport.build(info, nested())
        assertEquals("IllegalStateException: Widget refresh failed: no state.json", CrashReport.summary(text))
        assertEquals("2026-10-26 07:33:20 +00:00", CrashReport.time(text))
        // No message: just the short class name; dots in a message are left alone.
        assertEquals("StackOverflowError", CrashReport.summary(CrashReport.build(info, StackOverflowError())))
        assertEquals(
            "IllegalArgumentException: bad id 1000.5",
            CrashReport.summary(CrashReport.build(info, IllegalArgumentException("bad id 1000.5"))),
        )
    }

    @Test fun longTracesAreCut() {
        fun deep(n: Int): Throwable = if (n == 0) RuntimeException("x".repeat(100)) else RuntimeException("level $n", deep(n - 1))
        val text = CrashReport.build(info, deep(2_000))
        assertTrue(text.length <= CrashReport.MAX_CHARS + 100)
        assertTrue(text.endsWith("(cut at ${CrashReport.MAX_CHARS} characters)"))
    }
}
