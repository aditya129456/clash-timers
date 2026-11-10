package com.aditya.clashtimer.core

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Loads the anonymized village exports in `src/test/resources/fixtures`. They have the structure
 * and timer values of real exports; 1 and 2 are ~16 h apart, 3 and 4 ~2 h apart.
 */
object SampleExports {
    private fun find(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, name)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("$name not found above ${System.getProperty("user.dir")}")
    }

    /** First of [candidates] found walking up from the working directory. */
    fun file(vararg candidates: String): File {
        for (c in candidates) runCatching { return find(c) }
        error("none of ${candidates.toList()} found")
    }

    val names: NameMap by lazy {
        NameMap.fromJson(file("src/main/assets/names.json", "app/src/main/assets/names.json").readText())
    }

    val times: UpgradeTimes by lazy {
        UpgradeTimes.fromJson(file("src/main/assets/upgrade_times.json", "app/src/main/assets/upgrade_times.json").readText())
    }

    fun text(name: String): String =
        checkNotNull(SampleExports::class.java.getResource("/fixtures/$name")) { "fixture $name not found" }.readText()

    fun parse(name: String): VillageExport =
        when (val r = ExportParser.parse(text(name))) {
            is ParseResult.Success -> r.export
            is ParseResult.Invalid -> error("$name invalid: ${r.reason}")
        }

    val export1: VillageExport by lazy { parse("export_1.json") }
    val export2: VillageExport by lazy { parse("export_2.json") }
    val export3: VillageExport by lazy { parse("export_3.json") }
    val export4: VillageExport by lazy { parse("export_4.json") }

    private val FMT = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm:ss")

    /** e.g. "Thu 15 Oct 13:36:18" in UTC. */
    fun utc(epochSec: Long): String = FMT.format(Instant.ofEpochSecond(epochSec).atZone(ZoneOffset.UTC))
}
