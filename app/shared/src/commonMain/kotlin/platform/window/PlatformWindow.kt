package me.him188.ani.app.platform.window

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.him188.ani.app.platform.Platform
import me.him188.ani.app.platform.currentPlatform
import me.him188.ani.app.platform.isSystemInFullscreen

/**
 * PC 上的 Window. Android 上没有
 */
expect class PlatformWindow

/**
 * 增加桌面端系统强制的窗口 padding.
 *
 * - Windows: 0
 * - macOS: 窗口可沉浸到标题栏内, 可在标题栏内绘制, 然后使用 padding 让内容放置在标题栏区域之外
 */
fun Modifier.desktopTitleBarPadding(): Modifier = composed {
    if (currentPlatform is Platform.MacOS) {
        if (!isSystemInFullscreen()) {
            return@composed Modifier.padding(top = MacosTitleBarHeight)
        }
    }
    Modifier
}

/**
 * @see desktopTitleBarPadding
 */
@Composable
fun WindowInsets.Companion.desktopTitleBar(): WindowInsets {
    if (currentPlatform is Platform.MacOS) {
        if (!isSystemInFullscreen()) {
            return MacosTitleBarInsets
        }
    }
    return ZeroInsets
}

private inline val MacosTitleBarHeight get() = 28.dp // 实际上是 22, 但是为了美观, 加大到 28
private val ZeroInsets = WindowInsets(0.dp)
private val MacosTitleBarInsets = WindowInsets(top = MacosTitleBarHeight)

operator fun WindowInsets.plus(other: WindowInsets): WindowInsets {
    return PlusWindowInsets(this, other) { a, b -> a + b }
}

@Immutable
private class PlusWindowInsets(
    private val a: WindowInsets,
    private val b: WindowInsets,
    private val function: (Int, Int) -> Int,
) : WindowInsets {
    override fun getBottom(density: Density): Int {
        return function(a.getBottom(density), b.getBottom(density))
    }

    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int {
        return function(a.getLeft(density, layoutDirection), b.getLeft(density, layoutDirection))
    }

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int {
        return function(a.getRight(density, layoutDirection), b.getRight(density, layoutDirection))
    }

    override fun getTop(density: Density): Int {
        return function(a.getTop(density), b.getTop(density))
    }
}

typealias PlatformWindowMP = PlatformWindow

val LocalPlatformWindow: ProvidableCompositionLocal<PlatformWindowMP> = staticCompositionLocalOf {
    error("No PlatformWindow provided")
}
