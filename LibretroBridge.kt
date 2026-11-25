package com.retrohandheld.emulator

object LibretroBridge {
    init {
        System.loadLibrary("retrobridge")
    }

    external fun nativeLoadCore(corePath: String, systemDir: String, saveDir: String): Boolean
    external fun nativeLoadRom(romPath: String): Boolean
    external fun nativeRunFrame()
    external fun nativeSetInput(mask: Int)
    external fun nativeSetTouch(x: Int, y: Int, pressed: Boolean)
    external fun nativeSaveState(statePath: String): Boolean
    external fun nativeLoadState(statePath: String): Boolean
    external fun nativeGetVideoWidth(): Int
    external fun nativeGetVideoHeight(): Int
    external fun nativeGetSampleRate(): Int
    external fun nativeConsumeVideoFrame(): IntArray?
    external fun nativeConsumeAudioSamples(): ShortArray?
    external fun nativeUnload()

    const val BTN_B = 1 shl 0
    const val BTN_Y = 1 shl 1
    const val BTN_SELECT = 1 shl 2
    const val BTN_START = 1 shl 3
    const val BTN_UP = 1 shl 4
    const val BTN_DOWN = 1 shl 5
    const val BTN_LEFT = 1 shl 6
    const val BTN_RIGHT = 1 shl 7
    const val BTN_A = 1 shl 8
    const val BTN_X = 1 shl 9
    const val BTN_L = 1 shl 10
    const val BTN_R = 1 shl 11
}
