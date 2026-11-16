package com.music.bitchord.ui.player

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.PI
import kotlin.math.sin

/**
 * The player's backdrop as the cover itself, swirled: four copies of it at
 * different sizes, two turning in place and two orbiting, twisted around the
 * centre, with the colours of the next cover flowing into those of this one as
 * an Automix blend progresses.
 *
 * The covers arrive already blurred (the same tiny bitmaps the lyrics backdrop
 * uses), so one pass of one shader is all it takes: the twist moves smooth
 * colour around, and there is no screen-sized blur to pay for.
 *
 * The swirl and its speed swell towards the middle of a blend and settle at its
 * ends, and the animation clock is advanced by that speed rather than scaled by
 * it, so nothing jumps as it changes. Two covers are never positioned
 * differently: only colours move between them, so the frame after a blend ends -
 * with the new cover in both slots - is the frame the blend ended on.
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
    val texA = remember(bitmapA) { bitmapA?.let(::bitmapShader) }
    val texB = remember(bitmapB) { bitmapB?.let(::bitmapShader) }

    var clock by remember { mutableFloatStateOf(0f) }
    val progressNow by rememberUpdatedState(progress)
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            val speed = 1f + 1.6f * sin(PI.toFloat() * progressNow().coerceIn(0f, 1f))
            clock += dt * speed
        }
    }

    Box(modifier.fillMaxSize().background(Backdrop)) {
        Canvas(Modifier.fillMaxSize()) {
            val a = texA
            val b = texB
            if (shader != null && a != null && b != null && bitmapA != null && bitmapB != null) {
                shader.setInputShader("texA", a)
                shader.setInputShader("texB", b)
                shader.setFloatUniform("res", size.width, size.height)
                shader.setFloatUniform("sizeA", bitmapA.width.toFloat(), bitmapA.height.toFloat())
                shader.setFloatUniform("sizeB", bitmapB.width.toFloat(), bitmapB.height.toFloat())
                shader.setFloatUniform("uT", progressNow().coerceIn(0f, 1f))
                shader.setFloatUniform("uClock", clock)
                shader.setFloatUniform("uSwirl", 1f)
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun bitmapShader(image: ImageBitmap): BitmapShader =
    BitmapShader(image.asAndroidBitmap(), Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
        setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
    }

private const val FLUID_AGSL = """
uniform shader texA;
uniform shader texB;
uniform float2 res;
uniform float2 sizeA;
uniform float2 sizeB;
uniform float uT;
uniform float uClock;
uniform float uSwirl;

float2 rot2(float2 v, float a) {
    float c = cos(a);
    float s = sin(a);
    return float2(c * v.x - s * v.y, s * v.x + c * v.y);
}

// (u, v, weight) of one square layer. Its edges are soft, so the stack has no seams.
float3 layerQ(float2 p, float scale, float ang, float2 off) {
    float2 q = rot2(p - off, ang) / scale + 0.5;
    float2 e = smoothstep(float2(0.0), float2(0.22), q) * smoothstep(float2(0.0), float2(0.22), 1.0 - q);
    return float3(q, e.x * e.y);
}

half4 main(float2 fc) {
    float2 uv = fc / res;
    float aspect = res.x / res.y;
    float2 p = (uv - 0.5) * float2(1.0, 1.0 / aspect);
    float R = length(float2(0.5, 0.5 / aspect));

    // The swirl swells towards the middle of a blend.
    float peak = sin(3.14159265 * uT);
    float falloff = 1.0 - clamp(length(p) / R, 0.0, 1.0);
    float theta = uSwirl * (1.0 + 1.5 * peak) * falloff * falloff;
    float2 q = rot2(p, theta);

    float t = uClock;
    float base = max(1.25, 1.35 / aspect);
    float2 q0 = rot2(q, 0.05 * t) / base + 0.5;
    float3 l1 = layerQ(q, 0.80, -0.14 * t, float2(0.0));
    float a3 = 0.38 * t;
    float3 l2 = layerQ(q, 0.50, 0.32 * t, 0.20 * float2(cos(a3), sin(a3)));
    float a4 = -0.62 * t + 2.1;
    float3 l3 = layerQ(q, 0.25, -0.55 * t, 0.30 * float2(cos(a4), sin(a4 * 1.2)));

    float3 ca = float3(texA.eval(q0 * sizeA).rgb);
    ca = mix(ca, float3(texA.eval(l1.xy * sizeA).rgb), l1.z);
    ca = mix(ca, float3(texA.eval(l2.xy * sizeA).rgb), l2.z);
    ca = mix(ca, float3(texA.eval(l3.xy * sizeA).rgb), l3.z);

    float3 cb = float3(texB.eval(q0 * sizeB).rgb);
    cb = mix(cb, float3(texB.eval(l1.xy * sizeB).rgb), l1.z);
    cb = mix(cb, float3(texB.eval(l2.xy * sizeB).rgb), l2.z);
    cb = mix(cb, float3(texB.eval(l3.xy * sizeB).rgb), l3.z);

    float s = smoothstep(0.0, 1.0, uT);
    float3 c = mix(ca, cb, s);

    float lum = dot(c, float3(0.299, 0.587, 0.114));
    c = mix(float3(lum), c, 1.5);
    c *= 0.88;
    float v = 1.0 - smoothstep(0.25, 1.05, length(uv - 0.5) * 1.25);
    c *= mix(0.74, 1.0, v);
    float n = fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453);
    c += (n - 0.5) / 200.0;
    return half4(half3(c), 1.0);
}
"""