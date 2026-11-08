// 设计令牌：主题入口
//
// ShuaatiTheme 做两件事：
//   1. 把全工程统一的一套语义色（ShuaatiColors）通过 CompositionLocal 发给所有页面；
//   2. 把它同时映射成 Material3 的 ColorScheme + Typography + Shapes，
//      这样 Material 自带组件（对话框、底部导航、开关）也自动跟着令牌走。
//
// 深浅色只跟随系统（isSystemInDarkTheme），刻意不提供手动切换开关。
// 动效令牌（时长/缓动/系统关动画开关）在 Motion.kt，这里一并注入。
package com.shuaati.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** 当前生效的一套语义色；由 ShuaatiTheme 注入，默认给浅色保证预览不崩 */
val LocalShuaatiColors = staticCompositionLocalOf { LightColors }

/**
 * 取当前语义色。页面里统一这么用：
 * ```
 * val c = appColors()
 * Text("错题本", color = c.error)
 * ```
 */
@Composable
@ReadOnlyComposable
fun appColors(): ShuaatiColors = LocalShuaatiColors.current

/**
 * 全局主题。
 * @param dark 是否深色；默认跟随系统。测试或预览可以显式传值，页面本身不提供开关。
 */
@Composable
fun ShuaatiTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors

    CompositionLocalProvider(LocalShuaatiColors provides colors) {
        // 动效令牌也在这里注入：系统关掉动画时，所有页面拿到的时长一律为 0。
        // 放在 MaterialTheme 外层，Material 自带组件（对话框、导航栏）里我们自己的动画也能读到。
        ProvideMotion {
            MaterialTheme(
                colorScheme = colors.toColorScheme(),
                typography = ShuaatiTypography,
                shapes = ShuaatiShapes,
                content = content,
            )
        }
    }
}

/** 把语义色映射到 Material3 的 ColorScheme，让 Material 自带组件也用同一套色 */
private fun ShuaatiColors.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onAccent,
        primaryContainer = primarySoft,
        onPrimaryContainer = primaryDeep,
        // 主色系之外的两个强调色也用语义色，避免 Material 默认的紫/粉混进来
        secondary = primary,
        onSecondary = onAccent,
        secondaryContainer = primarySoft,
        onSecondaryContainer = primaryDeep,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = card,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerHigh = card,
        surfaceContainerHighest = card,
        error = error,
        onError = onAccent,
        errorContainer = errorSoft,
        onErrorContainer = error,
        outline = border,
        outlineVariant = border,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onAccent,
        primaryContainer = primarySoft,
        onPrimaryContainer = primaryDeep,
        secondary = primary,
        onSecondary = onAccent,
        secondaryContainer = primarySoft,
        onSecondaryContainer = primaryDeep,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = card,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surface,
        surfaceContainerHigh = card,
        surfaceContainerHighest = card,
        error = error,
        onError = onAccent,
        errorContainer = errorSoft,
        onErrorContainer = error,
        outline = border,
        outlineVariant = border,
    )
}

/** 形状也统一到三档圆角：标签 8 / 按钮 12 / 卡片 16 */
private val ShuaatiShapes = Shapes(
    extraSmall = Radius.labelShape,
    small = Radius.labelShape,
    medium = Radius.buttonShape,
    large = Radius.cardShape,
    extraLarge = Radius.cardShape,
)
