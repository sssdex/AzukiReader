package io.legado.app.feature.bookshelf.archive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

/** The same parallel projection and wave equations as archive_shelf_geometry.dart. */
internal object ArchiveShelfGeometry {
    const val depthX = .66f
    const val depthY = -.30f
    fun spine(width: Float) = (width * .043f).coerceIn(14f, 18f)
    fun cover(width: Float) = (width * .32f).coerceIn(100f, 128f)
    fun height(cover: Float) = (cover - 20f) * 166f / 118f + 54f
    fun baseY(cover: Float, height: Float) = -cover * depthY + height * .60f + 12f
    fun rail(distance: Float): Float = distance.coerceIn(-7f, 7f).let { it * 2.5f + it * it * .45f }
    fun weight(distance: Float): Float = if (abs(distance) >= 1.48f) 0f else cos(distance / 1.48f * PI.toFloat() / 2).let { it * it }
    fun origin(cover: Float, height: Float, weight: Float): Offset {
        val pull = cover * .84f * weight * (1.24f - .24f * weight)
        val lift = weight * weight * (3 - 2 * weight)
        return Offset(-pull * depthX, baseY(cover, height) - pull * depthY - height * .60f * lift)
    }
    fun contains(point: Offset, spine: Float, cover: Float, height: Float): Boolean {
        val dx = cover * depthX
        val dy = -cover * depthY
        val x = point.x
        val y = point.y
        if (x < 0 || x > spine + dx) return false
        // Upper rim: (0,dy) -> (dx,0) -> (dx+s,.1s). Bottom follows spine/cover faces.
        val top = if (x <= dx) dy - x * dy / dx else (x - dx) * .1f
        val bottom = if (x <= spine) dy + x * .1f + height else dy + spine * .1f - (x - spine) * dy / dx + height
        return y in top..bottom
    }
    fun bookIndex(lane: Int, count: Int) = ((lane % count) + count) % count
}

/**
 * 书脊竖排书名与下方书名的衬线字体：思源宋体 CN Medium 的 GB2312 子集
 * （`res/font/archive_serif.ttf`，SIL OFL 1.1，见 assets/licenses）。
 * 中文默认是黑体，换成宋体后纸质档案的感觉更对，字号也随之收小一档。
 */
private val ArchiveSerif = FontFamily(Font(R.font.archive_serif, FontWeight.Normal))

/** 封面上下编号的缩进：比上游各多收 8dp 左右，编号不再贴着书口。 */
private const val COVER_PAD_START_DP = 18
private const val COVER_PAD_TOP_DP = 12
private const val COVER_PAD_END_DP = 12
private const val COVER_PAD_BOTTOM_DP = 11

/** 书脊竖排书名：上游 9sp 黑体，换宋体后收小到 8.2sp（与阅读S 那版一致）。 */
private const val SPINE_TITLE_SP = 8.2f
private const val SPINE_TITLE_LINE_SP = 9.4f

/** 书架下方书名/作者：上游 17sp/12sp 黑体，宋体字面更宽更重，各收小一档。 */
private const val CAPTION_TITLE_SP = 16f
private const val CAPTION_AUTHOR_SP = 11f

/** Bounded visible pool over an unbounded logical lane, including a one-book static case. */
@Composable
fun Wenku8ArchiveShelf(
    count: Int,
    initialIndex: Int,
    key: (Int) -> Any,
    title: (Int) -> String,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((Int) -> Unit)? = null,
    onLongOpen: ((Int) -> Unit)? = null,
    author: (Int) -> String = { "" },
    focusedBook: (@Composable (Int) -> Unit)? = null,
    book: @Composable (Int) -> Unit,
) {
    if (count == 0) return
    val effects = io.legado.app.core.designsystem.wenku8.effects.rememberWenku8Effects()
    var focus by rememberSaveable { mutableFloatStateOf(initialIndex.coerceIn(0, count - 1).toFloat()) }
    // Only integer crossings invalidate composition; fractional motion belongs to layers.
    val activeLane by remember { derivedStateOf { archiveLaneRound(focus).toInt() } }
    val poolCenter by remember { derivedStateOf { floor(focus).toInt() } }
    val scope = rememberCoroutineScope()
    var settling by remember { mutableStateOf<Job?>(null) }
    val callback by rememberUpdatedState(onSettled)
    val currentOpen by rememberUpdatedState(onOpen)
    val currentLongOpen by rememberUpdatedState(onLongOpen)
    // 书架滑动震感：直接调 Vibrator（15ms / 振幅 150），比 Compose 的 tick 明显得多
    val hapticContext = LocalContext.current
    val haptics = remember(hapticContext) { ShelfHaptics(hapticContext) }
    var tickedLane by remember { mutableIntStateOf(Int.MIN_VALUE) }
    LaunchedEffect(activeLane) {
        if (tickedLane != Int.MIN_VALUE && tickedLane != activeLane) haptics.tick()
        tickedLane = activeLane
    }
    val entrance = remember { Animatable(if (effects.motion) 0f else 1f) }
    LaunchedEffect(effects.motion) { if (effects.motion) entrance.animateTo(1f, tween(720, easing = LinearEasing)) else entrance.snapTo(1f) }
    LaunchedEffect(count) { if (count == 1) focus = 0f }
    fun settle(target: Float? = null, velocity: Float = 0f, minimumVelocity: Float = 0f) {
        settling?.cancel()
        settling = scope.launch {
            val simulation = ArchiveShelfScrollSimulation(focus, velocity,
                minimumVelocity = minimumVelocity, destination = target)
            if (effects.motion) {
                if (target != null) animate(focus, target,
                    animationSpec = tween(420, easing = CubicBezierEasing(.2f, 1.06f, .35f, 1f))) { value, _ -> focus = value }
                else animate(0f, simulation.duration,
                    animationSpec = tween((simulation.duration * 1000).roundToInt(), easing = LinearEasing)) { time, _ ->
                    focus = simulation.position(time)
                }
            }
            focus = simulation.target
            callback(ArchiveShelfGeometry.bookIndex(archiveLaneRound(focus).toInt(), count))
        }
    }
    val previous = stringResource(R.string.wenku8_previous_book)
    val next = stringResource(R.string.wenku8_next_book)
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            val width = maxWidth.value
            val spine = ArchiveShelfGeometry.spine(width)
            val cover = ArchiveShelfGeometry.cover(width)
            val height = ArchiveShelfGeometry.height(cover)
            val pitch = if (effects.shelf3d) spine + 11f else cover + 24f
            val rowHeight = if (effects.shelf3d) ArchiveShelfGeometry.baseY(cover, height) + height + 60f else height + 28f
            val density = LocalDensity.current.density
            val footprint = spine + cover * ArchiveShelfGeometry.depthX
            val center = width / 2 - footprint / 2 + cover * .84f * ArchiveShelfGeometry.depthX
            val radius = ceil((width / 2 + footprint + cover) / pitch).toInt()
            val lanes = remember(count, poolCenter, radius) {
                if (count == 1) listOf(0) else ((poolCenter - radius)..(poolCenter + radius + 1)).toList()
            }
            fun position(lane: Int): Offset {
                val distance = lane - focus
                val crest = ArchiveShelfEase.transform(((entrance.value - .38f) / .62f).coerceIn(0f, 1f))
                val origin = ArchiveShelfGeometry.origin(cover, height, ArchiveShelfGeometry.weight(distance) * crest)
                val order = (distance + width / pitch / 2 + 2).coerceIn(0f, 16f)
                val arrival = ArchiveShelfEase.transform(((entrance.value - order * .018f) / .52f).coerceIn(0f, 1f))
                val assembly = 56 * (1 - arrival)
                val x = if (effects.shelf3d) center + distance * pitch + origin.x + assembly * .66f else (width - cover) / 2 + distance * pitch
                val y = if (effects.shelf3d) origin.y + ArchiveShelfGeometry.rail(distance) - assembly * .30f else 8f
                return Offset(x, y)
            }
            val currentPosition by rememberUpdatedState(::position)
            val currentLanes by rememberUpdatedState(lanes)
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).clipToBounds()
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(previous) { settle(archiveLaneRound(focus) - if (count > 1) 1 else 0); true },
                        CustomAccessibilityAction(next) { settle(archiveLaneRound(focus) + if (count > 1) 1 else 0); true })
                }
                .pointerInput(count, effects.shelf3d, effects.motion, width) {
                    fun hit(point: Offset): Int? = currentLanes.asReversed().firstOrNull { lane ->
                        val (x, y) = currentPosition(lane)
                        val local = Offset(point.x / density - x, point.y / density - y + if (effects.shelf3d) cover * .30f else 0f)
                        if (effects.shelf3d) ArchiveShelfGeometry.contains(local, spine, cover, height)
                        else local.x in 0f..cover && local.y in 0f..height
                    }
                    detectTapGestures(onPress = {
                        settling?.cancel()
                        entrance.snapTo(1f)
                        tryAwaitRelease()
                    }, onTap = { point -> hit(point)?.let { lane ->
                        if (abs(lane - focus) >= .03f) settle(lane.toFloat())
                        else currentOpen?.invoke(ArchiveShelfGeometry.bookIndex(lane, count))
                    } }, onLongPress = { point -> hit(point)?.let { lane ->
                        if (lane == archiveLaneRound(focus).toInt()) currentLongOpen?.invoke(ArchiveShelfGeometry.bookIndex(lane, count))
                    } })
                }
                .draggable(rememberDraggableState { delta -> if (count > 1) { settling?.cancel()
                        focus -= delta / density / pitch } },
                    Orientation.Horizontal, enabled = count > 1,
                    onDragStarted = { entrance.snapTo(1f) },
                    onDragStopped = { velocity -> settle(velocity = -velocity / density / pitch, minimumVelocity = 50f / pitch) })) {
                if (effects.shelf3d) Canvas(Modifier.fillMaxSize()) {
                    lanes.forEach { lane ->
                        val x = (center + (lane - focus) * pitch) * density
                        val y = (ArchiveShelfGeometry.baseY(cover, height) + height + ArchiveShelfGeometry.rail(lane - focus)) * density
                        val shadow = Path().apply {
                            moveTo(x, y); lineTo(x + spine * density, y + 1.4f * density)
                            lineTo(x + (spine + cover * .66f) * density, y - cover * .30f * density)
                            lineTo(x + cover * .66f * density, y - cover * .30f * density)
                            lineTo(x - 10 * density, y + 10 * density); close()
                        }
                        drawPath(shadow, Brush.linearGradient(listOf(ink.copy(alpha = .08f), Color.Transparent)))
                    }
                }
                lanes.forEach { lane ->
                    val index = ArchiveShelfGeometry.bookIndex(lane, count)
                    key(lane, key(index)) {
                        val focused = lane == activeLane
                        val label = title(index)
                        Box(Modifier.graphicsLayer {
                            val position = currentPosition(lane)
                            translationX = position.x * density
                            translationY = (position.y + if (effects.shelf3d) cover * -.30f else 0f) * density
                        }
                            .size((if (effects.shelf3d) footprint else cover).dp, (height + if (effects.shelf3d) cover * .30f + spine * .1f else 0f).dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = label
                                selected = focused
                                role = Role.Button
                                onClick { if (abs(lane - focus) >= .03f) settle(lane.toFloat()) else onOpen?.invoke(index); true }
                                if (onLongOpen != null) onLongClick { onLongOpen(index); true }
                            }

                            ) {
                            // Cover and spine share vertices; no perspective rotation approximation.
                            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(cover.dp, height.dp).drawWithContent {
                                if (effects.shelf3d) {
                                    val projection = Matrix().apply {
                                        this[0, 0] = .66f
                                        this[0, 1] = -.30f
                                        this[3, 0] = spine * density
                                        this[3, 1] = (cover * .30f + spine * .10f) * density
                                    }
                                    withTransform({ transform(projection) }) { this@drawWithContent.drawContent() }
                                } else drawContent()
                            }) {
                                Column(Modifier.fillMaxSize().background(paper).padding(
                                    start = COVER_PAD_START_DP.dp, top = COVER_PAD_TOP_DP.dp,
                                    end = COVER_PAD_END_DP.dp, bottom = COVER_PAD_BOTTOM_DP.dp)) {
                                    Text("NO.%03d".format(index + 1), fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = ink)
                                    Spacer(Modifier.height(5.dp))
                                    Box(Modifier.fillMaxWidth().weight(1f)) {
                                        // Match _LazyArchiveCover: distant volumes remain blank paper.
                                        if (abs(lane - activeLane) <= 1) {
                                            if (focused && focusedBook != null) focusedBook(index) else book(index)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("FILE / %03d".format(index + 1), fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = ink)
                                }
                            }
                            if (effects.shelf3d) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val s = spine * density
                                    val dx = cover * .66f * density
                                    val dy = cover * .30f * density
                                    val h = height * density
                                    val top = Path().apply {
                                        moveTo(0f, dy); lineTo(s, dy + s * .1f)
                                        lineTo(s + dx, s * .1f); lineTo(dx, 0f); close()
                                    }
                                    drawPath(top, Brush.linearGradient(listOf(line, paper)))
                                    drawPath(top, line, style = androidx.compose.ui.graphics.drawscope.Stroke(.4f * density))
                                    val face = Path().apply {
                                        moveTo(0f, dy); lineTo(s, dy + s * .1f)
                                        lineTo(s, dy + s * .1f + h); lineTo(0f, dy + h); close()
                                    }
                                    drawPath(face, Brush.horizontalGradient(listOf(paper, line.copy(alpha = .85f), paper)))
                                    drawPath(face, ink.copy(alpha = .18f), style = androidx.compose.ui.graphics.drawscope.Stroke(.4f * density))
                                    drawLine(ink.copy(alpha = .22f), Offset(3 * density, dy + 15 * density), Offset(s - 3 * density, dy + 15 * density), density)
                                }
                                Column(Modifier.offset(y = (cover * .30f).dp).requiredSize(spine.dp, height.dp)
                                    .drawWithContent {
                                        val shear = Matrix().apply { this[0, 1] = .10f }
                                        withTransform({ transform(shear) }) { this@drawWithContent.drawContent() }
                                    }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("%02d".format(index + 1), fontSize = 7.sp, fontFamily = FontFamily.Monospace, color = ink)
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        val glyphs = label.codePoints().toArray().take(7).map { String(Character.toChars(it)) }
                                        Text(glyphs.joinToString("\n") + if (label.codePointCount(0, label.length) > 7) "\n…" else "",
                                            fontFamily = ArchiveSerif,
                                            fontSize = SPINE_TITLE_SP.sp, lineHeight = SPINE_TITLE_LINE_SP.sp, color = ink,
                                            fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
                                    }
                                    Text("○", fontSize = 7.sp, color = ink)
                                }
                            }
                        }
                    }
                }
            }
        }
        val active = ArchiveShelfGeometry.bookIndex(activeLane, count)
        val labelHeight = with(LocalDensity.current) {
            (CAPTION_TITLE_SP.sp.toDp() * 1.35f * 2 + CAPTION_AUTHOR_SP.sp.toDp() * 1.4f)
        } + 16.dp
        Row(Modifier.fillMaxWidth().archiveShelfReveal(entrance.asState(), .52f).clickable { onOpen?.invoke(active) }
            .height(labelHeight)
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title(active), color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = ArchiveSerif, fontSize = CAPTION_TITLE_SP.sp,
                    lineHeight = (CAPTION_TITLE_SP * 1.35f).sp, fontWeight = FontWeight.Normal, minLines = 2, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(author(active), fontFamily = ArchiveSerif, fontSize = CAPTION_AUTHOR_SP.sp,
                    lineHeight = (CAPTION_AUTHOR_SP * 1.4f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().archiveShelfReveal(entrance.asState(), .72f), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            if (count > 1) IconButton(onClick = { settle(archiveLaneRound(focus) - 1) }) {
                Icon(Icons.AutoMirrored.Default.KeyboardArrowLeft, previous, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("%02d / %02d".format(active + 1, count), fontFamily = FontFamily.Monospace,
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (count > 1) IconButton(onClick = { settle(archiveLaneRound(focus) + 1) }) {
                Icon(Icons.AutoMirrored.Default.KeyboardArrowRight, next, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
