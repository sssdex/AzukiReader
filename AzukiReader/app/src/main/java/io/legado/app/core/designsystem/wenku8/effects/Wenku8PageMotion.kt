package io.legado.app.core.designsystem.wenku8.effects

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.core.designsystem.wenku8.chrome.Wenku8Motion
import io.legado.app.domain.model.settings.hasBackgroundImage
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.theme.LegadoTheme
import kotlin.math.abs
import kotlin.math.min

private val ArchivePlaneEase = CubicBezierEasing(.18f, .72f, .18f, 1f)

/** archive_motion.dart: opaque incoming diagonal, shallow travel, retained outgoing sheet. */
private fun Modifier.archivePlane(clock: State<Float>, direction: Float, outgoing: Boolean = false): Modifier =
    drawWithContent {
        val p = ArchivePlaneEase.transform(clock.value.coerceIn(0f, 1f))
        if (outgoing) {
            translate(10.dp.toPx() * direction * p, -4.dp.toPx() * p) { this@drawWithContent.drawContent() }
        } else if (p >= 1f) drawContent()
        else if (p > 0f) {
            val slant = min(180.dp.toPx(), size.width * .46f)
            val edge = (size.width + slant) * p
            fun x(value: Float) = if (direction >= 0f) value else size.width - value
            val viewport = Path().apply {
                moveTo(x(0f), 0f); lineTo(x(edge), 0f)
                lineTo(x(edge - slant), size.height); lineTo(x(0f), size.height); close()
            }
            clipPath(viewport) {
                translate(-24.dp.toPx() * direction * (1f - p), 12.dp.toPx() * (1f - p)) {
                    this@drawWithContent.drawContent()
                }
            }
        }
    }


/** Owns the whole scaffold clock, so its masthead and body assemble together. */
@Composable
fun Wenku8ScreenMotion(content: @Composable () -> Unit) {
    val parent = LocalArchiveReveal.current
    val enabled = LegadoTheme.isWenku8 && rememberWenku8Effects().motion && parent == null
    val clock = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(enabled) {
        if (enabled) clock.animateTo(1f, tween(Wenku8Motion.Enter, easing = LinearEasing))
        else clock.snapTo(1f)
    }
    CompositionLocalProvider(LocalArchiveReveal provides (parent ?: clock.asState().takeIf { enabled })) {
        Box(Modifier.fillMaxSize().then(if (enabled) Modifier.archivePlane(clock.asState(), 1f) else Modifier)) {
            content()
        }
    }
}

/** Retains the same destination subtrees; only the active and outgoing planes paint. */
@Composable
fun Wenku8MainPager(
    state: PagerState,
    key: (Int) -> Any,
    pageNestedScrollConnection: NestedScrollConnection,
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    beyondViewportPageCount: Int = 4,
    content: @Composable (Int) -> Unit,
) {
    if (!LegadoTheme.isWenku8) {
        HorizontalPager(state, modifier, key = key, pageNestedScrollConnection = pageNestedScrollConnection,
            userScrollEnabled = userScrollEnabled, beyondViewportPageCount = beyondViewportPageCount) { content(it) }
        return
    }
    val motion = rememberWenku8Effects().motion
    val selected = state.currentPage
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val identities = (0 until state.pageCount).map(key)
    // A destination and its zeroed clock must be published in the same composition.
    // Reusing a clock and resetting it in LaunchedEffect first paints the new page at 1,
    // then erases it at 0 on the next frame.
    val history = remember(identities) { ArchivePageHistory(selected) }
    val frame = remember(selected, motion, rtl, identities) {
        val old = history.page
        val changing = old != selected && motion
        ArchivePageFrame(
            outgoing = if (changing) old else -1,
            direction = (if (selected > old) 1f else -1f) * if (rtl) -1f else 1f,
            clock = Animatable(if (changing) 0f else 1f),
        )
    }
    SideEffect { history.page = selected }
    val clock = frame.clock
    val direction = frame.direction
    val parent = LocalArchiveReveal.current
    val foreground = remember(parent, clock, motion) {
        derivedStateOf { if (motion) minOf(parent?.value ?: 1f, clock.value) else 1f }
    }
    val settledForeground = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(frame) {
        // 新页面用 200ms 的斜向擦除从背景里推出来；旧页面不再留在下面（见下面 visible）。
        if (frame.outgoing >= 0) clock.animateTo(1f, tween(Wenku8Motion.Page, easing = LinearEasing))
    }
    val fallback = if (LocalAppUiConfiguration.current.theme.hasBackgroundImage(LegadoTheme.isDark)) Modifier
        else Modifier.background(LegadoTheme.colorScheme.background)
    Box(modifier.then(fallback).then(if (userScrollEnabled) Modifier.pointerInput(state, rtl) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
            onHorizontalDrag = { change, amount -> distance += amount; change.consume() },
            onDragEnd = {
                if (abs(distance) >= 48 * density) {
                    val step = (if (distance < 0f) 1 else -1) * if (rtl) -1 else 1
                    val target = (state.currentPage + step).coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                    state.requestScrollToPage(target)
                }
            },
        )
    } else Modifier)) {
        repeat(state.pageCount) { page ->
            androidx.compose.runtime.key(key(page)) {
                val active = page == selected
                // 只画当前页：以前会把上一个页面留在底下做「斜向擦除」的底衬，
                // 慢设备上擦除没走完时会看到两个页面的文字叠在一起。
                val visible = active
                val clockState = clock.asState()
                Box(Modifier.fillMaxSize()
                    .zIndex(if (active) 2f else 0f)
                    .graphicsLayer { alpha = if (visible) 1f else 0f }
                    .then(if (visible && motion) Modifier.archivePlane(clockState, direction, false) else Modifier)
                    .then(if (!active) Modifier.clearAndSetSemantics {} else Modifier)
                    .focusProperties { onEnter = { if (!active) cancelFocusChange() } }.focusGroup()
                    // A full-page hit plane prevents events falling through blank areas to retained pages.
                    .pointerInput(active, motion) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (!active || motion && clock.value < 1f) event.changes.forEach { it.consume() }
                            }
                        }
                    },
                ) {
                    CompositionLocalProvider(LocalArchiveReveal provides if (active) foreground else settledForeground) { content(page) }
                }
            }
        }
    }
}

private class ArchivePageHistory(var page: Int)

@Stable
private data class ArchivePageFrame(
    val outgoing: Int,
    val direction: Float,
    val clock: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
)

/** NavDisplay 负责二级页的转场（见 `archiveSheetTransform`：不透明横向浅推）。
 * 这里给场景内容一个已完成的 reveal 时钟，避免 scaffold 自己再叠一次 420ms 斜向擦除。
 */
@Composable
fun Wenku8SceneMotion(transition: Transition<EnterExitState>, content: @Composable () -> Unit) {
    val settled = remember { mutableFloatStateOf(1f) }
    CompositionLocalProvider(LocalArchiveReveal provides settled) { content() }
}
