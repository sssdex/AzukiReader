package io.legado.app.feature.bookshelf.archive

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors

/**
 * 书架滑动震感。
 *
 * Compose 的 `LocalHapticFeedback` 最终走 `View.performHapticFeedback`，强度由系统的
 * 「触摸反馈」决定，通常只是很轻的一下，实机上横滑书架时基本感觉不到。
 * 这里直接调 [Vibrator]：每跨过一本书振一次，15ms、振幅 150。
 *
 * 与「阅读S（legado 档案式书架）」用的是同一组参数，方便两边的反馈手感一致。
 */
internal class ShelfHaptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    fun tick() {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        // vibrate() 是同步 binder 调用，放主线程会让快速横滑掉帧，改到单线程里排队
        executor.execute {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(TICK_MS, TICK_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(TICK_MS)
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 15L
        const val TICK_AMPLITUDE = 150
        val executor = Executors.newSingleThreadExecutor()
    }
}
