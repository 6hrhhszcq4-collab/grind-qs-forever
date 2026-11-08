// 设计令牌：字号阶梯与字体样式
//
// 字号只有 7 档：12 / 14 / 16 / 18 / 22 / 28 / 40 sp，
// 行高统一是字号的 1.4 倍（12→16.8、16→22.4、40→56…），由 lineHeightOf() 一处算出来，
// 不许在页面里手写 lineHeight = 28.sp 这种值。
package com.shuaati.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 行高倍数：所有字号的行高都是字号 × 1.4 */
const val LINE_HEIGHT_RATIO = 1.4f

/** 字号阶梯（sp），页面里只准用这几个值 */
object FontSizes {
    const val CAPTION = 12      // 标签、辅助说明
    const val SMALL = 14        // 次要正文、列表元信息
    const val BODY = 16         // 正文默认
    const val STEM = 18         // 题干
    const val PAGE_TITLE = 22   // 页面大标题
    const val HEADING = 28      // 分块大数字、小标题
    const val DISPLAY = 40      // 首页大数字
}

/** 按字号算行高：字号 × 1.4 */
private fun lineHeightOf(size: Int): Float = size * LINE_HEIGHT_RATIO

/** 造一个 TextStyle：只给字号，行高自动按 1.4 倍算 */
private fun style(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeightOf(size).sp,
    fontWeight = weight,
)

/**
 * 全工程共用的文字样式。
 * 页面里写 `AppText.body`、`AppText.stem`，不要写 `fontSize = 16.sp`。
 */
object AppText {
    /** 12sp：标签、辅助说明 */
    val caption: TextStyle = style(FontSizes.CAPTION)

    /** 12sp 加粗：标签强调 */
    val captionStrong: TextStyle = style(FontSizes.CAPTION, FontWeight.Bold)

    /** 14sp：次要正文 */
    val small: TextStyle = style(FontSizes.SMALL)

    /** 14sp 加粗 */
    val smallStrong: TextStyle = style(FontSizes.SMALL, FontWeight.Bold)

    /** 16sp：正文默认 */
    val body: TextStyle = style(FontSizes.BODY)

    /** 16sp 加粗 */
    val bodyStrong: TextStyle = style(FontSizes.BODY, FontWeight.Bold)

    /** 18sp：题干 */
    val stem: TextStyle = style(FontSizes.STEM)

    /** 18sp 加粗：题干强调、卡片标题 */
    val stemStrong: TextStyle = style(FontSizes.STEM, FontWeight.Bold)

    /** 22sp 加粗：页面大标题 */
    val pageTitle: TextStyle = style(FontSizes.PAGE_TITLE, FontWeight.Bold)

    /** 28sp 加粗：统计块大数字 */
    val heading: TextStyle = style(FontSizes.HEADING, FontWeight.Bold)

    /** 40sp 加粗：首页大数字（环形进度中间的数） */
    val display: TextStyle = style(FontSizes.DISPLAY, FontWeight.Bold)
}

/**
 * 给 MaterialTheme 用的排版表。
 * 把 Material3 的槽位映射到同一套字号，这样没显式指定 style 的 Text 也自动落在阶梯上，
 * 不会出现「某个对话框里突然冒出 15sp」。
 */
val ShuaatiTypography: Typography = Typography(
    displayLarge = AppText.display,
    displayMedium = AppText.heading,
    displaySmall = AppText.pageTitle,
    headlineLarge = AppText.heading,
    headlineMedium = AppText.pageTitle,
    headlineSmall = AppText.stemStrong,
    titleLarge = AppText.pageTitle,
    titleMedium = AppText.stemStrong,
    titleSmall = AppText.smallStrong,
    bodyLarge = AppText.body,
    bodyMedium = AppText.small,
    bodySmall = AppText.caption,
    labelLarge = AppText.smallStrong,
    labelMedium = AppText.captionStrong,
    labelSmall = AppText.caption,
)
