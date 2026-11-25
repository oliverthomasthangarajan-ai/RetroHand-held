package com.retrohandheld.emulator

import java.io.File

enum class ConsoleSystem(
    val displayName: String,
    val shortName: String,
    val extensions: List<String>,
    val coreCandidates: List<String>,
    val isDualScreen: Boolean = false
) {
    GB(
        displayName = "Game Boy",
        shortName = "GB",
        extensions = listOf("gb"),
        coreCandidates = listOf("libgambatte_libretro_android.so", "gambatte_libretro_android.so")
    ),
    GBC(
        displayName = "Game Boy Color",
        shortName = "GBC",
        extensions = listOf("gbc"),
        coreCandidates = listOf("libgambatte_libretro_android.so", "gambatte_libretro_android.so")
    ),
    GBA(
        displayName = "Game Boy Advance",
        shortName = "GBA",
        extensions = listOf("gba"),
        coreCandidates = listOf("libmgba_libretro_android.so", "mgba_libretro_android.so")
    ),
    NDS(
        displayName = "Nintendo DS",
        shortName = "NDS",
        extensions = listOf("nds"),
        coreCandidates = listOf("libmelonds_libretro_android.so", "melonds_libretro_android.so", "libmelondsds_libretro_android.so"),
        isDualScreen = true
    ),
    N3DS(
        displayName = "Nintendo 3DS",
        shortName = "3DS",
        extensions = listOf("3ds", "cci", "cia", "cxi"),
        coreCandidates = listOf("libazahar_libretro.so", "azahar_libretro.so", "libcitra_libretro_android.so", "citra_libretro_android.so"),
        isDualScreen = true
    );

    fun findInstalledCore(nativeLibDir: String): File? {
        val dir = File(nativeLibDir)
        return coreCandidates.map { File(dir, it) }.firstOrNull { it.exists() }
    }
}
