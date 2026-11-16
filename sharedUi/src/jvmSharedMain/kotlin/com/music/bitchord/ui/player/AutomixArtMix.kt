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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
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

private const val BLUR_PX = 36f

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
 * The sleeve while a blend is drawing it: the next cover underneath, sharpening
 * as it arrives, and the old one over it, softening as it fades away, so the two
 * run together rather than one being swapped for the other. Draws nothing when no
 * blend is running.
 *
 * The old cover is faded out over an opaque new one rather than the new one faded
 * in beneath it: cross-fading two partly transparent layers dips the picture
 * mid-way over the sleeve's dark backing, and this never does.
 */
@Composable
internal fun AutomixBleedArt(mix: AutomixArtMix, modifier: Modifier = Modifier) {
    val incoming = mix.incoming ?: return
    val outgoing = mix.outgoing ?: return
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                shape = SleeveCorners
                clip = true
            },
    ) {
        AsyncImage(
            model = incoming,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val blur = BLUR_PX * (1f - mix.progress.value)
                    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Clamp) else null
                },
        )
        AsyncImage(
            model = outgoing,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = mix.progress.value
                    alpha = 1f - p
                    val blur = BLUR_PX * p
                    renderEffect = if (blur > 0.5f) BlurEffect(blur, blur, TileMode.Clamp) else null
                },
        )
    }
}