package com.music.bitchord.ui.player

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The player's backdrop in the manner of Apple Music's Automix: soft, glowing
 * fields of colour taken from the cover, never the cover itself.
 *
 * Each cover is reduced, once, to a palette of six colours (its average and five
 * that stand apart from one another). The shader draws a few soft blobs of those
 * colours, so there is no picture in it to turn, only colour to move.
 *
 * It stands still between mixes. While a mix runs the blobs orbit and the whole
 * field twists about the centre, with a speed and a twist that swell towards the
 * middle of the mix and die away to nothing at its ends, and the colours flow
 * from the palette of the cover being left to that of the next one. A mix ends
 * with the new cover's palette in both slots, so the frame after it is the frame
 * it ended on; and a track skipped by hand eases to the new colours over a
 * moment instead of cutting.
 *
 * The covers arrive already tiny and blurred (the same bitmaps the lyrics
 * backdrop uses) and their palettes are made as soon as they do, which is when
 * the next song's cover is prefetched, so a mix starts with its colours ready.
 * There is no texture in the shader at all, so it costs next to nothing.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun FluidArtBackdrop(
    from: ImageBitmap?,
    to: ImageBitmap?,
    progress: () -> Float,
    animate: Boolean,
    scrim: Boolean,
    modifier: Modifier = Modifier,
) {
    // A shader the device's compiler turns down leaves the dark backdrop, not a
    // crash in the player.
    val shader = remember { runCatching { RuntimeShader(FLUID_AGSL) }.getOrNull() }

    // The last covers that were ready: a new cover decodes over these rather than
    // blanking the backdrop for the beat it takes.
    var heldFrom by remember { mutableStateOf<ImageBitmap?>(null) }
    var heldTo by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(from) { if (from != null) heldFrom = from }
    LaunchedEffect(to) { if (to != null) heldTo = to }
    val bitmapA = from ?: heldFrom ?: to ?: heldTo
    val bitmapB = to ?: heldTo ?: bitmapA
    val palA = remember(bitmapA) { bitmapA?.let(::extractPalette) ?: DefaultPalette }
    val palB = remember(bitmapB) { bitmapB?.let(::extractPalette) ?: DefaultPalette }

    // What is on screen, kept so that a change of cover can start from it.
    val shown = remember { FloatArray(PALETTE_FLOATS).also { DefaultPalette.copyInto(it) } }
    val settle = remember { Settle() }
    remember(palA) {
        settle.from = shown.copyOf()
        settle.seconds = 0f
        0
    }

    val progressNow by rememberUpdatedState(progress)
    var phase by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate, palA) {
        if (!animate) {
            settle.seconds = SETTLE_SECONDS
            tick++
            return@LaunchedEffect
        }
        while (true) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                val p = progressNow().coerceIn(0f, 1f)
                phase += dt * PHASE_SPEED * sin(PI.toFloat() * p)
                settle.seconds += dt
                tick = now
                val mixing = p > 0f && p < 1f
                if (!mixing && settle.seconds >= SETTLE_SECONDS) break
            }
            // Nothing is moving: ask for no more frames until a mix starts.
            snapshotFlow { progressNow().let { it > 0f && it < 1f } }.first { it }
        }
    }

    Box(modifier.fillMaxSize().background(Backdrop)) {
        Canvas(Modifier.fillMaxSize()) {
            tick // redraw with the frame clock
            if (shader != null) {
                val p = progressNow().coerceIn(0f, 1f)
                val s = p * p * (3f - 2f * p)
                val k = (settle.seconds / SETTLE_SECONDS).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val before = settle.from
                for (i in 0 until PALETTE_FLOATS) {
                    val target = palA[i] + (palB[i] - palA[i]) * s
                    shown[i] = if (before != null && k < 1f) before[i] + (target - before[i]) * k else target
                }
                shader.setFloatUniform("res", size.width, size.height)
                shader.setFloatUniform("uPhase", phase)
                shader.setFloatUniform("uSurge", sin(PI.toFloat() * p))
                for (c in 0 until PALETTE_SIZE) {
                    shader.setFloatUniform("c$c", shown[c * 3], shown[c * 3 + 1], shown[c * 3 + 2])
                }
                drawRect(ShaderBrush(shader))
            }
            if (scrim) {
                // Lyrics and the queue sit on this as dense white text.
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.34f),
                        0.55f to Color.Black.copy(alpha = 0.48f),
                        1f to Color.Black.copy(alpha = 0.64f),
                    ),
                )
            }
        }
    }
}

private val Backdrop = Color(0xFF121212)

/** Radians per second of the blobs' orbit, at the peak of a mix. */
private const val PHASE_SPEED = 1.3f

/** How long a change of cover outside a mix takes to ease across. */
private const val SETTLE_SECONDS = 0.8f

private class Settle {
    var from: FloatArray? = null
    var seconds: Float = SETTLE_SECONDS
}

private const val PALETTE_SIZE = 6
private const val PALETTE_FLOATS = PALETTE_SIZE * 3

/** What the backdrop shows before any cover has arrived. */
private val DefaultPalette = FloatArray(PALETTE_FLOATS) { 0.16f }

/**
 * Six colours of a cover, as r, g, b floats in 0..1: its average first, then
 * five that stand apart from one another (favouring the ones with colour in
 * them), ordered dark to light so two covers' palettes line up by brightness
 * when one flows into the other.
 */
private fun extractPalette(image: ImageBitmap): FloatArray = runCatching {
    val source = image.asAndroidBitmap()
    val soft = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    val grid = 8
    val small = Bitmap.createScaledBitmap(soft, grid, grid, true)
    val px = IntArray(grid * grid)
    small.getPixels(px, 0, grid, 0, 0, grid, grid)
    val n = px.size
    val r = FloatArray(n) { ((px[it] shr 16) and 0xFF) / 255f }
    val g = FloatArray(n) { ((px[it] shr 8) and 0xFF) / 255f }
    val b = FloatArray(n) { (px[it] and 0xFF) / 255f }
    val chroma = FloatArray(n) { max(r[it], max(g[it], b[it])) - min(r[it], min(g[it], b[it])) }

    val avg = floatArrayOf(r.average().toFloat(), g.average().toFloat(), b.average().toFloat())

    // Farthest-point picks, weighted towards colourful cells.
    val chosen = ArrayList<Int>()
    chosen += (0 until n).maxByOrNull { chroma[it] } ?: 0
    while (chosen.size < PALETTE_SIZE - 1) {
        var best = -1
        var bestScore = -1f
        for (i in 0 until n) {
            if (i in chosen) continue
            var nearest = Float.MAX_VALUE
            for (j in chosen) {
                val dr = r[i] - r[j]
                val dg = g[i] - g[j]
                val db = b[i] - b[j]
                nearest = min(nearest, dr * dr + dg * dg + db * db)
            }
            val score = nearest * (0.35f + chroma[i])
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        chosen += if (best >= 0) best else 0
    }
    val colours = ArrayList<FloatArray>()
    for (i in chosen) colours += floatArrayOf(r[i], g[i], b[i])
    colours.sortBy { 0.299f * it[0] + 0.587f * it[1] + 0.114f * it[2] }

    val out = FloatArray(PALETTE_FLOATS)
    out[0] = avg[0]; out[1] = avg[1]; out[2] = avg[2]
    for ((k, c) in colours.withIndex()) {
        out[(k + 1) * 3] = c[0]
        out[(k + 1) * 3 + 1] = c[1]
        out[(k + 1) * 3 + 2] = c[2]
    }
    out
}.getOrDefault(DefaultPalette)

private const val FLUID_AGSL = """
uniform float2 res;
uniform float uPhase;
uniform float uSurge;
uniform float3 c0;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float3 c4;
uniform float3 c5;

float2 rot2(float2 v, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(c * v.x - s * v.y, s * v.x + c * v.y);
}

float blobW(float2 s, float2 home, float2 w, float o) {
    float2 c = home + 0.20 * float2(cos(uPhase * w.x + o), sin(uPhase * w.y + o * 1.7));
    float2 d = (s - c) * float2(1.0, 0.62);
    return exp(-dot(d, d) / 0.085);
}

half4 main(float2 fc) {
    float2 uv = fc / res;
    float2 s = uv - 0.5;

    // The whole field twists about the middle while a mix runs.
    float fall = 1.0 - clamp(length(s) * 1.6, 0.0, 1.0);
    s = rot2(s, uSurge * 2.0 * fall * fall);

    float w1 = blobW(s, float2(-0.24, -0.30), float2(0.9, 1.1), 0.0);
    float w2 = blobW(s, float2(0.26, -0.18), float2(1.2, 0.8), 1.3);
    float w3 = blobW(s, float2(-0.20, 0.10), float2(1.0, 1.4), 2.6);
    float w4 = blobW(s, float2(0.24, 0.28), float2(0.8, 1.2), 3.9);
    float w5 = blobW(s, float2(0.02, 0.44), float2(1.4, 0.9), 5.2);

    float base = 0.30;
    float3 c = (c0 * base + c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4 + c5 * w5) /
        (base + w1 + w2 + w3 + w4 + w5);

    float lum = dot(c, float3(0.299, 0.587, 0.114));
    c = mix(float3(lum), c, 1.45);
    c *= 0.86;
    float v = 1.0 - smoothstep(0.25, 1.05, length(uv - 0.5) * 1.25);
    c *= mix(0.78, 1.0, v);
    float n = fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453);
    c += (n - 0.5) / 200.0;
    return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
}
"""