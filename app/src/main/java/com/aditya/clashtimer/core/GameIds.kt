package com.aditya.clashtimer.core

/** Raw `data` IDs from the village export that the app logic depends on. */
object GameIds {
    const val TOWN_HALL = 1000001
    const val BUILDERS_HUT = 1000015
    const val BOBS_HUT = 1000064

    // Helper IDs, confirmed against clash-of-clans-data@0.19.1 (`data/home/other/helpers`).
    const val HELPER_BUILDERS_APPRENTICE = 93000000
    const val HELPER_LAB_ASSISTANT = 93000001
    const val HELPER_PROSPECTOR = 93000002

    // Not in the data package; Alchemist by elimination (only four helpers exist). Unused by the app.
    const val HELPER_ALCHEMIST_GUESS = 93000003
}
