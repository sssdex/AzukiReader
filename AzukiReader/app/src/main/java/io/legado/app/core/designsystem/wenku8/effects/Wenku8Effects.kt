package io.legado.app.core.designsystem.wenku8.effects

import io.legado.app.domain.model.settings.ThemeSettings
import io.legado.app.domain.model.settings.Wenku8PerformanceEffect
import io.legado.app.domain.model.settings.allows
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.legado.app.core.designsystem.wenku8.chrome.Wenku8Motion
import io.legado.app.ui.theme.LegadoTheme

/** Platform capabilities only. No alternate preference owner or idle animation clock. */
@Stable
data class Wenku8Effects(
    val motion: Boolean, val spatial: Boolean, val optics: Boolean,
    val blur: Boolean = optics, val edgeBlur: Boolean = optics, val shelf3d: Boolean = spatial,
)

fun resolveWenku8Effects(theme: ThemeSettings, saver: Boolean, lowRam: Boolean, animatorsEnabled: Boolean): Wenku8Effects =
    Wenku8Effects(
        motion = animatorsEnabled && !saver && !theme.wenku8ReducedEffects && theme.allows(Wenku8PerformanceEffect.Motion),
        spatial = animatorsEnabled && !saver && !lowRam && !theme.wenku8ReducedEffects && theme.allows(Wenku8PerformanceEffect.Motion),
        // 立体书架是静态绘制（没有持续动画），省电模式下关掉只会让书架突然变成平铺卡片，
        // 看起来像「书架丢了」；低内存设备仍然降级。
        shelf3d = !lowRam && theme.allows(Wenku8PerformanceEffect.Shelf3d),
        optics = !saver && !lowRam && theme.allows(Wenku8PerformanceEffect.Glass),
        blur = !saver && !lowRam && theme.allows(Wenku8PerformanceEffect.Blur),
        edgeBlur = !saver && !lowRam && theme.allows(Wenku8PerformanceEffect.EdgeBlur),
    )

val LocalWenku8Effects = staticCompositionLocalOf { Wenku8Effects(true, true, true) }

@Composable
fun rememberWenku8Effects(): Wenku8Effects = LocalWenku8Effects.current

@Composable
fun observeWenku8Effects(): Wenku8Effects {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
    var saver by remember { mutableStateOf(power.isPowerSaveMode) }
    var animatorsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { saver = power.isPowerSaveMode }
        }
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { animatorsEnabled = ValueAnimator.areAnimatorsEnabled() }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return resolveWenku8Effects(io.legado.app.ui.theme.LocalAppUiConfiguration.current.theme, saver, lowRam, animatorsEnabled)
}

/** One finite clock for a loaded page/batch. Lazy rows joining after completion stay static. */
val LocalArchiveReveal = staticCompositionLocalOf<State<Float>?> { null }

@Composable
fun Wenku8RevealScope(identity: Any?, ready: Boolean = true, content: @Composable () -> Unit) {
    val enabled = LegadoTheme.isWenku8 && rememberWenku8Effects().motion
    val parent = LocalArchiveReveal.current
    val clock = remember(identity) { Animatable(if (enabled) 0f else 1f) }
    val combined = remember(parent, clock) { derivedStateOf { minOf(parent?.value ?: 1f, clock.value) } }
    LaunchedEffect(identity, ready, enabled) {
        if (!enabled) clock.snapTo(1f)
        else if (ready && clock.value < 1f) clock.animateTo(1f, tween(Wenku8Motion.Enter, easing = LinearEasing))
    }
    CompositionLocalProvider(LocalArchiveReveal provides if (enabled) (if (ready) combined else parent) else null, content = content)
}

@Composable
fun Modifier.archiveReveal(
    order: Int = 0,
    start: Float = .08f + order.coerceIn(0, 7) * .055f,
    end: Float = 1f,
    travelX: Dp = 0.dp,
    travelY: Dp = 0.dp,
): Modifier {
    if (!LegadoTheme.isWenku8) return this
    val clock = LocalArchiveReveal.current ?: return this
    return drawWithContent {
        val p = Wenku8Motion.Ease.transform(((clock.value - start) / (end - start)).coerceIn(0f, 1f))
        if (p >= 1f) drawContent()
        else if (p > 0f) translate(travelX.toPx() * (1f - p), travelY.toPx() * (1f - p)) {
            clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
        }
    }
}

/** Uses the control's real interaction source, preserving keyboard/semantics/cancellation. */
@Composable
fun Modifier.wenku8Press(source: MutableInteractionSource): Modifier {
    if (!LegadoTheme.isWenku8) return this
    val effects = rememberWenku8Effects()
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && effects.motion) .975f else 1f,
        tween(if (pressed) Wenku8Motion.Contact else Wenku8Motion.Enter,
            easing = if (pressed) Wenku8Motion.Ease else CubicBezierEasing(.2f, 1.08f, .34f, 1f)), label = "archive-contact")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
