package com.retrohandheld.emulator

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EmulatorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CORE_PATH = "extra_core_path"
        const val EXTRA_ROM_PATH = "extra_rom_path"
        const val EXTRA_SYSTEM_NAME = "extra_system_name"
        const val EXTRA_IS_DUAL_SCREEN = "extra_is_dual_screen"
    }

    private val running = AtomicBoolean(false)
    private val inputMask = AtomicInteger(0)
    private var emuThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val corePath = intent.getStringExtra(EXTRA_CORE_PATH) ?: ""
        val romPath = intent.getStringExtra(EXTRA_ROM_PATH) ?: ""
        val systemName = intent.getStringExtra(EXTRA_SYSTEM_NAME) ?: "Emulator"
        val isDualScreen = intent.getBooleanExtra(EXTRA_IS_DUAL_SCREEN, false)

        val sysDir = File(filesDir, "system").apply { mkdirs() }.absolutePath
        val saveDir = File(filesDir, "saves").apply { mkdirs() }.absolutePath
        val stateDir = File(filesDir, "states").apply { mkdirs() }
        val stateFile = File(stateDir, "${File(romPath).name}.state").absolutePath

        if (!LibretroBridge.nativeLoadCore(corePath, sysDir, saveDir) ||
            !LibretroBridge.nativeLoadRom(romPath)
        ) {
            Toast.makeText(this, "Failed to start core or load ROM.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val sampleRate = LibretroBridge.nativeGetSampleRate().coerceIn(8000, 96000)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }

        val frameState = mutableStateOf<Bitmap?>(null)

        running.set(true)
        emuThread = Thread {
            val frameDurationNs = 16_666_667L
            while (running.get()) {
                val startNs = System.nanoTime()
                LibretroBridge.nativeSetInput(inputMask.get())
                LibretroBridge.nativeRunFrame()

                val w = LibretroBridge.nativeGetVideoWidth()
                val h = LibretroBridge.nativeGetVideoHeight()
                val pixels = LibretroBridge.nativeConsumeVideoFrame()
                if (w > 0 && h > 0 && pixels != null && pixels.size >= w * h) {
                    val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                    frameState.value = bmp
                }

                val samples = LibretroBridge.nativeConsumeAudioSamples()
                if (samples != null && samples.isNotEmpty()) {
                    audioTrack?.write(samples, 0, samples.size)
                }

                val elapsed = System.nanoTime() - startNs
                val sleepMs = (frameDurationNs - elapsed) / 1_000_000L
                if (sleepMs > 1) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.also { it.start() }

        setContent {
            EmulatorScreen(
                title = systemName,
                isDualScreen = isDualScreen,
                frame = frameState.value,
                onButtonChange = { bit, pressed ->
                    val current = inputMask.get()
                    val next = if (pressed) current or bit else current and bit.inv()
                    inputMask.set(next)
                },
                onTouchScreen = { x, y, pressed ->
                    LibretroBridge.nativeSetTouch(x, y, pressed)
                },
                onSaveState = {
                    val ok = LibretroBridge.nativeSaveState(stateFile)
                    Toast.makeText(
                        this,
                        if (ok) "State saved" else "Save state not supported by core",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onLoadState = {
                    val ok = LibretroBridge.nativeLoadState(stateFile)
                    Toast.makeText(
                        this,
                        if (ok) "State loaded" else "No save state found",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onExit = { finish() }
            )
        }
    }

    override fun onDestroy() {
        running.set(false)
        emuThread?.join(500)
        audioTrack?.stop()
        audioTrack?.release()
        LibretroBridge.nativeUnload()
        super.onDestroy()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EmulatorScreen(
    title: String,
    isDualScreen: Boolean,
    frame: Bitmap?,
    onButtonChange: (Int, Boolean) -> Unit,
    onTouchScreen: (Int, Int, Boolean) -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onExit: () -> Unit
) {
    var drawOffsetX by remember { mutableFloatStateOf(0f) }
    var drawOffsetY by remember { mutableFloatStateOf(0f) }
    var drawWidth by remember { mutableFloatStateOf(1f) }
    var drawHeight by remember { mutableFloatStateOf(1f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D12))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadButton("L", LibretroBridge.BTN_L, onButtonChange, width = 44.dp, height = 34.dp)
                PadButton("R", LibretroBridge.BTN_R, onButtonChange, width = 44.dp, height = 34.dp)
                OutlinedButton(
                    onClick = onSaveState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Save", color = Color.White)
                }
                OutlinedButton(
                    onClick = onLoadState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Load", color = Color.White)
                }
                Button(
                    onClick = onExit,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Exit")
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (frame != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInteropFilter { event ->
                            if (!isDualScreen) return@pointerInteropFilter false
                            when (event.action) {
                                MotionEvent.ACTION_DOWN,
                                MotionEvent.ACTION_MOVE -> {
                                    val nx = ((event.x - drawOffsetX) / drawWidth).coerceIn(0f, 1f)
                                    val ny = ((event.y - drawOffsetY) / drawHeight).coerceIn(0f, 1f)
                                    val retroX = ((nx * 2f - 1f) * 32767f).toInt().coerceIn(-32767, 32767)
                                    val retroY = ((ny * 2f - 1f) * 32767f).toInt().coerceIn(-32767, 32767)
                                    onTouchScreen(retroX, retroY, true)
                                }
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL -> {
                                    onTouchScreen(0, 0, false)
                                }
                            }
                            true
                        }
                ) {
                    val scale = minOf(size.width / frame.width, size.height / frame.height)
                    val dstW = (frame.width * scale).toInt()
                    val dstH = (frame.height * scale).toInt()
                    val offsetX = ((size.width - dstW) / 2f).toInt()
                    val offsetY = ((size.height - dstH) / 2f).toInt()

                    drawOffsetX = offsetX.toFloat()
                    drawOffsetY = offsetY.toFloat()
                    drawWidth = dstW.toFloat().coerceAtLeast(1f)
                    drawHeight = dstH.toFloat().coerceAtLeast(1f)

                    drawImage(
                        image = frame.asImageBitmap(),
                        dstOffset = IntOffset(offsetX, offsetY),
                        dstSize = IntSize(dstW, dstH)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PadButton("▲", LibretroBridge.BTN_UP, onButtonChange)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PadButton("◀", LibretroBridge.BTN_LEFT, onButtonChange)
                    PadButton("▶", LibretroBridge.BTN_RIGHT, onButtonChange)
                }
                PadButton("▼", LibretroBridge.BTN_DOWN, onButtonChange)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PadButton("SELECT", LibretroBridge.BTN_SELECT, onButtonChange, width = 72.dp, height = 34.dp)
                PadButton("START", LibretroBridge.BTN_START, onButtonChange, width = 72.dp, height = 34.dp)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PadButton("X", LibretroBridge.BTN_X, onButtonChange, isCircle = true)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PadButton("Y", LibretroBridge.BTN_Y, onButtonChange, isCircle = true)
                    PadButton("A", LibretroBridge.BTN_A, onButtonChange, isCircle = true)
                }
                PadButton("B", LibretroBridge.BTN_B, onButtonChange, isCircle = true)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PadButton(
    label: String,
    bit: Int,
    onButtonChange: (Int, Boolean) -> Unit,
    width: androidx.compose.ui.unit.Dp = 48.dp,
    height: androidx.compose.ui.unit.Dp = 48.dp,
    isCircle: Boolean = false
) {
    var pressed by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .size(width, height)
            .pointerInteropFilter { event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        pressed = true
                        onButtonChange(bit, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        pressed = false
                        onButtonChange(bit, false)
                    }
                }
                true
            },
        shape = if (isCircle) CircleShape else RoundedCornerShape(10.dp),
        color = if (pressed) Color(0xFF7C4DFF) else Color(0xFF2B2B3C)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
