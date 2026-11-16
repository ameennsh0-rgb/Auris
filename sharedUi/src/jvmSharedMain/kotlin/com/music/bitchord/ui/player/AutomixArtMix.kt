package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * The cover-art handoff that rides along with an Automix blend, in the manner
 * of Apple Music: as one song mixes into the next, the old sleeve eases back
 * and dissolves while the new one settles forward into its place, instead of
 * the picture simply swapping.
 *
 * ## How it is driven
 *
 * Nothing here knows where the blend is — the player only publishes whether
 * one is running — so the motion is a fixed-length ease that starts at the
 * moment the cover changes during (or just after) a blend. A cover that
 * changes any other way (a manual skip, a queue tap) is left to the ordinary
 * fade-in, and so are two tracks off the same album: they share a cover, the
 * player never builds a new [PlayerArtwork] for the second one, and there is
 * nothing to animate.
 *
 * The old cover is drawn *above* the new one and faded out, rather than the new
 * one faded in below it. Cross-fading two partly transparent layers over the
 * sleeve's dark backing dips the picture mid-way; fading one out over an opaque
 * one never does.
 */
@Stable
internal class AutomixArtMix {
    /** The cover being left behind, while it is still on its way out; null otherwise. */
    var outgoing: ImageRequest? by mutableStateOf(null)
        internal set

    /** 0 as the blend of covers begins, 1 as it ends. Only meaningful while [outgoing] is set. */
    val progress = Animatable(1f)

    /** Memory of the last cover and the last time a blend was running, kept across recompositions. */
    internal class Tracker {
        var previous: PlayerArtwork? = null
        var mixingUntilMs: Long = 0L
    }
}

/** The new cover, a little oversized at first and settling into its frame as the handoff runs. */
internal fun Modifier.automixIncoming(mix: AutomixArtMix): Modifier = graphicsLayer {
    if (mix.outgoing == null) return@graphicsLayer
    val settle = 1f - mix.progress.value
    val scale = 1f + INCOMING_OVERSIZE * settle * settle
    scaleX = scale
    scaleY = scale
}

private const val INCOMING_OVERSIZE = 0.09f
private const val OUTGOING_SHRINK = 0.12f
private const val OUTGOING_DRIFT = 0.035f
private const val DURATION_MS = 1500

/** How long after a blend ends a cover change still counts as part of it. */
private const val AFTER_MIX_GRACE_MS = 2500L

private val ArtMixEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private val SleeveCorners = RoundedCornerShape(8.dp)

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

/**
 * Builds the handoff state for [art]. When [art] changes to a different cover
 * while [mixing] (or just after), [AutomixArtMix.outgoing] holds the cover
 * being left and [AutomixArtMix.progress] runs from 0 to 1.
 */
@Composable
internal fun rememberAutomixArtMix(art: PlayerArtwork, mixing: Boolean): AutomixArtMix {
    val mix = remember { AutomixArtMix() }
    val tracker = remember { AutomixArtMix.Tracker() }
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceNow by rememberUpdatedState(reduceAnimation)

    // "Still counts as mixing" is open-ended while a blend runs and closes a
    // little after it, because the cover can change a beat either side of the
    // flag flipping.
    LaunchedEffect(mixing) {
        tracker.mixingUntilMs = if (mixing) Long.MAX_VALUE else nowMs() + AFTER_MIX_GRACE_MS
    }

    LaunchedEffect(art) {
        val previous = tracker.previous
        tracker.previous = art
        if (previous == null || previous === art) return@LaunchedEffect
        val from = previous.url
        val to = art.url
        if (from == null || to == null || from == to) return@LaunchedEffect
        if (reduceNow || nowMs() > tracker.mixingUntilMs) return@LaunchedEffect

        mix.outgoing = previous.request
        try {
            mix.progress.snapTo(0f)
            mix.progress.animateTo(1f, tween(DURATION_MS, easing = ArtMixEasing))
        } finally {
            // Also runs when a newer cover cancels this one; the next run
            // starts from 0 again, so no reset of [progress] is needed here.
            mix.outgoing = null
        }
    }
    return mix
}

/**
 * The outgoing cover, laid over the new one for the length of the handoff. Draws
 * nothing when there is no handoff running.
 */
@Composable
internal fun AutomixOutgoingArt(mix: AutomixArtMix, modifier: Modifier = Modifier) {
    val request = mix.outgoing ?: return
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = mix.progress.value
                val scale = 1f - OUTGOING_SHRINK * p
                scaleX = scale
                scaleY = scale
                translationX = -size.width * OUTGOING_DRIFT * p
                // Held opaque for the first stretch, so the old sleeve is
                // still plainly there as it starts to recede, then gone.
                alpha = (1f - ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)).let { it * it }
                // Rounded in its own space, so the corners shrink with it.
                shape = SleeveCorners
                clip = true
            },
    )
}
