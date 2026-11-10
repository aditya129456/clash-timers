package com.aditya.clashtimer.core

import com.aditya.clashtimer.core.SampleExports.names
import com.aditya.clashtimer.core.SampleExports.times
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget's player name is a setting, asked on first launch; empty or skipped = "Chief". */
class PlayerNameTest {

    private val state: AppState = (SyncLogic.apply(AppState(), SampleExports.text("export_2.json"))
        as SyncLogic.Outcome.Synced).state

    private fun title(s: AppSettings, st: AppState = state) =
        WidgetModelBuilder.build(st, s, names, times, st.export?.timestamp ?: 0).title

    @Test fun defaultIsChiefAndPromptIsPending() {
        val s = AppSettings()
        assertTrue(s.needsPlayerName)
        assertEquals("Chief", s.displayName)
        assertEquals("Chief TH18", title(s))
        assertEquals("Chief", title(s, AppState())) // no data yet
    }

    @Test fun skippedOrEmptyMeansChiefAndIsNotAskedAgain() {
        for (answer in listOf("", "   ")) {
            val s = AppSettings(playerName = AppSettings.cleanName(answer))
            assertFalse(s.needsPlayerName)
            assertEquals("Chief", s.displayName)
        }
    }

    @Test fun chosenNameIsShown() {
        val s = AppSettings(playerName = AppSettings.cleanName("  Aisha  "))
        assertEquals("Aisha", s.playerName)
        assertEquals("Aisha TH18", title(s))
    }

    @Test fun longNamesAreCut() {
        assertEquals(AppSettings.MAX_PLAYER_NAME, AppSettings.cleanName("A".repeat(40)).length)
    }

    @Test fun nameSurvivesJsonAndOldSettingsHaveNone() {
        val s = AppSettings(playerName = "Aisha")
        assertEquals(s, AppSettings.decode(s.encode()))
        // Settings saved by an older version (no playerName key) → the prompt shows once.
        assertTrue(AppSettings.decode("""{"liveCountdown":false}""").needsPlayerName)
    }
}
