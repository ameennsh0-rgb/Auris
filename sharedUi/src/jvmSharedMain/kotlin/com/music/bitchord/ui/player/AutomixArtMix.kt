package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.music.bitchord.data.settings.MixBlend

/**
 * The cover art's part in an Automix blend, in the manner of Apple Music: the
 * cover of the track that is coming bleeds in over the length of the mix,
 * instead of the picture swapping when the player moves on to the next song.
 *
 * ## Why it can't just follow the player's cover
 *
 * The player changes its current song halfway through a blend, when the
 * incoming track becomes the louder one. A cover that follows it therefore
 * changes in the middle of the mix, all at once. So while a blend runs the
 * sleeve stops following the player and draws the handoff itself: the cover
 * being left and the next cover in the queue, cross-dissolved from the moment
 * the mix starts to the moment it ends, over the blend's own length (read off
 * [MixBlend.fadeMs]). When the blend ends the sleeve goes back to the player's
 * cover, which by then is the cover it has just finished drawing.
 *
 * The next cover is loaded ahead of time, whenever there is a next song, so
 * the blend starts on a picture that is already decoded rather than waiting on
 * one.
 *
 * Two tracks off the same album share a cover; there is nothing to bleed
 * between them and the sleeve is left alone. So is a blend with no length to
 * pace to being unknown - it falls back to a fixed span - and one with the
 * "reduce animation" setting on.
 */
@Stable
internal class AutomixArtMix {
    /** The cover being left behind; null when no blend is being drawn. */
    var outgoing: ImageRequest? by mutableStateOf(null)
        internal set

    /** The cover of the next song, the one bleeding in; null when no blend is being drawn. */
    var incoming: ImageRequest? by mutableStateOf(null)
        internal set

    /** The remote artwork behind [outgoing] and [incoming], so other layers can load the same two covers. */
    var outgoingRemote: String? by mutableStateOf(null)
        internal set
    var incomingRemote: String? by mutableStateOf(null)
        internal set

    /** 0 as the blend of covers begins, 1 as it ends. */
    val progress = Animatable(0f)

    /** Whether the sleeve is being drawn by the blend rather than by the player's cover. */
    val active: Boolean get() = outgoing != null && incoming != null
}

/** The player's own cover, hidden for as long as the blend is drawing the sleeve in its place. */
internal fun Modifier.automixHidden(mix: AutomixArtMix): Modifier = graphicsLayer {
    if (mix.active) alpha = 0f
}

/** Used when a blend doesn't say how long it lasts. */
private const val FALLBACK_DURATION_MS = 6000

/** How quickly a blend that ends early is wound up, so the sleeve isn't left half-mixed. */
private const val WIND_UP_MS = 350

private val ArtMixEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val SleeveCorners = RoundedCornerShape(8.dp)

/**
 * Builds the blend state. [art] is the cover on screen now and [next] that of
 * the next song in the queue; [mixing] and [blend] come from the Automix engine.
 */
@Composable
internal fun rememberAutomixArtMix(
    art: PlayerArtwork,
    next: PlayerArtwork,
    artRemote: String?,
    nextRemote: String?,
    mixing: Boolean,
    blend: MixBlend?,
): AutomixArtMix {
    val mix = remember { AutomixArtMix() }
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val artNow by rememberUpdatedState(art)
    val nextNow by rememberUpdatedState(next)
    val blendNow by rememberUpdatedState(blend)
    val reduceNow by rememberUpdatedState(reduceAnimation)
    val artRemoteNow by rememberUpdatedState(artRemote)
    val nextRemoteNow by rememberUpdatedState(nextRemote)

    // Held in the image cache ahead of the blend. The painter itself is never
    // drawn; asking for it is what starts the load.
    rememberAsyncImagePainter(model = next.request)

    LaunchedEffect(mixing) {
        if (mixing) {
            val from = artNow
            val to = nextNow
            val fromUrl = from.url
            val toUrl = to.url
            if (reduceNow || fromUrl == null || toUrl == null || fromUrl == toUrl) return@LaunchedEffect
            val durationMs = (blendNow?.fadeMs ?: 0L).takeIf { it > 0L }?.toInt() ?: FALLBACK_DURATION_MS
            mix.outgoing = from.request
            mix.incoming = to.request
            mix.outgoingRemote = artRemoteNow
            mix.incomingRemote = nextRemoteNow
            mix.progress.snapTo(0f)
            mix.progress.animateTo(1f, tween(durationMs, easing = ArtMixEasing))
        } else if (mix.active) {
            // The blend ended - or was dropped - with the covers part-way; wind
            // them up, then hand the sleeve back to the player's cover.
            mix.progress.animateTo(1f, tween(WIND_UP_MS))
            mix.outgoing = null
            mix.incoming = null
            mix.outgoingRemote = null
            mix.incomingRemote = null
        }
    }
    return mix
}

/**
 * The cover while a blend is drawing it, in Apple Music's choreography: the
 * old cover holds at full strength for the first quarter of the mix, dissolves
 * into the next one across the middle half, and the next one has it to itself
 * for the last quarter. Over the same stretch the old cover recedes a little
 * (to 96%) while the new one settles up to full size, which gives the swap some
 * weight instead of a flat fade. Draws nothing when no blend is running.
 *
 * No blur here: the swirling backdrop behind carries the soft look, and plain
 * layers are the cheapest thing a GPU can composite. The old cover fades out
 * over an opaque new one rather than the new one fading in beneath it:
 * cross-fading two partly transparent layers dips the picture mid-way over the
 * dark backing behind, and this never does.
 *
 * [hero] draws it as the full-bleed banner rather than the card: no corners,
 * no breathing scale (the banner is cropped to the stage), and [imageModifier]
 * carries the banner's own crop so both covers sit exactly where it does.
 */
@Composable
internal fun AutomixBleedArt(
    mix: AutomixArtMix,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    imageModifier: Modifier = Modifier,
) {
    val incoming = mix.incoming ?: return
    val outgoing = mix.outgoing ?: return
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (hero) Modifier else Modifier.graphicsLayer {
                    shape = SleeveCorners
                    clip = true
                },
            ),
    ) {
        AsyncImage(
            model = incoming,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!hero) {
                        val s = BREATH_MIN + (1f - BREATH_MIN) * dissolve(mix.progress.value)
                        scaleX = s
                        scaleY = s
                    }
                }
                .then(imageModifier),
        )
        AsyncImage(
            model = outgoing,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val d = dissolve(mix.progress.value)
                    alpha = 1f - d
                    if (!hero) {
                        val s = 1f - (1f - BREATH_MIN) * d
                        scaleX = s
                        scaleY = s
                    }
                }
                .then(imageModifier),
        )
    }
}

/** How far the dissolve has got: 0 until a quarter of the mix, 1 from three quarters, smooth between. */
private fun dissolve(p: Float): Float {
    val x = ((p - 0.25f) / 0.5f).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** The size the cover recedes to, and the new one settles up from. */
private const val BREATH_MIN = 0.96f