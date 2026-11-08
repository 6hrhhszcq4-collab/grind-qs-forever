// 设计令牌：颜色（全工程唯一允许出现 Color(0xFF…) 字面量的文件）
//
// 用法约定：
//   1. 页面与组件一律通过 `appColors()` 取语义色，不要自己写 Color(0xFF…)；
//   2. 语义名按「用途」取，不按「看相」取（用 success 而不是 green）；
//   3. 深浅两套值都写在这里，Theme.kt 跟随系统自动挑一套。
package com.shuaati.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 一套语义色。
 *
 * 字段含义：
 * - primary / primaryDeep / primarySoft：主色、主色深、主色浅背景（选中态、强调、按钮）
 * - onAccent：所有「亮色底」（主色、成功、错误）上的文字颜色，深浅色各一套，保证对比度
 * - success / successSoft：答对、正确选项、及格
 * - error / errorSoft：答错、错误选项、不及格
 * - warning / warningSoft：暂无答案、题库不足这类提醒
 * - background：页面底色
 * - surface：卡片 / 分组容器底色
 * - card：可点击条目（选项、列表条）底色，浅色纯白，深色比 surface 亮一档以区分层次
 * - fill：中性填充（选项字母底、答题卡未答格、禁用底）
 * - textPrimary / textSecondary：文字主 / 次
 * - border：描边、分割线
 * - isDark：当前是不是深色，组件里个别地方要微调用
 */
@Immutable
data class ShuaatiColors(
    val primary: Color,
    val primaryDeep: Color,
    val primarySoft: Color,
    val onAccent: Color,
    val success: Color,
    val successSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val background: Color,
    val surface: Color,
    val card: Color,
    val fill: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val isDark: Boolean,
)

/**
 * 浅色模式。
 * 主色 #1565C0 是深蓝，上面放白字（onAccent = 白）。
 */
val LightColors: ShuaatiColors = ShuaatiColors(
    primary = Color(0xFF1565C0),
    primaryDeep = Color(0xFF0D47A1),
    primarySoft = Color(0xFFE3F2FD),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF16A34A),
    successSoft = Color(0xFFDCFCE7),
    error = Color(0xFFDC2626),
    errorSoft = Color(0xFFFEE2E2),
    warning = Color(0xFFF59E0B),
    warningSoft = Color(0xFFFEF3C7),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F8FA),
    card = Color(0xFFFFFFFF),
    fill = Color(0xFFEEF0F3),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF6B7280),
    border = Color(0xFFE5E7EB),
    isDark = false,
)

/**
 * 深色模式。
 *
 * 关键点：语义色整体提亮，否则在 #121212 上看不清。
 * - 主色 #1565C0 → #64B5F6（深底上对比度从 2.3:1 提到 9:1 左右）
 * - 成功 #16A34A → #4ADE80
 * - 错误 #DC2626 → #F87171
 * 同时因为这些亮色底上放白字会糊，onAccent 改成接近黑的 #0B1B2A。
 */
val DarkColors: ShuaatiColors = ShuaatiColors(
    primary = Color(0xFF64B5F6),
    primaryDeep = Color(0xFF90CAF9),
    primarySoft = Color(0xFF16324A),
    onAccent = Color(0xFF0B1B2A),
    success = Color(0xFF4ADE80),
    successSoft = Color(0xFF14351F),
    error = Color(0xFFF87171),
    errorSoft = Color(0xFF3B1A1A),
    warning = Color(0xFFFBBF24),
    warningSoft = Color(0xFF3A2A0E),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    card = Color(0xFF242424),
    fill = Color(0xFF333B45),
    textPrimary = Color(0xFFE6E6E6),
    textSecondary = Color(0xFF9CA3AF),
    border = Color(0xFF374151),
    isDark = true,
)
