// 通用组件：环形进度
//
// 全部用 Compose 自带 Canvas 的 drawArc 手绘，**不引任何图表库**。
// 圆环从正上方（-90°）开始顺时针画，sweepAngle = 360 × 进度，useCenter = false 才是环而不是扇形。
//
// 07A 动效：环的 sweepAngle 和中间数字**共用同一个动画值** animatedProgress，
// 所以数字是跟着环一起涨的，不会出现「数字先跳、环后追」的割裂感。
package com.shuaati.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Motion
import com.shuaati.app.ui.theme.appColors
import com.shuaati.app.ui.theme.motionEnabled
import com.shuaati.app.ui.theme.motionTween

/**
 * 环形进度。
 *
 * @param progress 进度 0f~1f，超出范围会被夹住
 * @param centerText 环中间的主文字（例如「142/300」）；不用动画时显示它
 * @param subtitle 环下方副文字（例如「本轮进度」），不传就不占位
 * @param diameter 直径，默认 210dp（首页大环）；分题型小环传 96dp
 * @param strokeWidth 环宽，默认 12dp
 * @param ringColor 进度条颜色，默认主色；传 null 用主色
 * @param trackColor 轨道底色，传 null 用中性填充色
 * @param centerStyle 中间主文字的样式，默认 28sp 加粗；首页大环传 40sp
 * @param growOnEnter 进入界面时是否从 0 涨到目标进度（首页大环、分题型小环传 true）
 * @param centerTextOfProgress 用动画值生成中间文字，例如 `{ p -> "${(p * total).roundToInt()}/$total" }`；
 *        传 null 时显示静态的 [centerText]
 */
@Composable
fun StatRing(
    progress: Float,
    centerText: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    diameter: Dp = ComponentSize.ringLarge,
    strokeWidth: Dp = ComponentSize.ringStroke,
    ringColor: Color? = null,
    trackColor: Color? = null,
    centerStyle: TextStyle = AppText.heading,
    subtitleStyle: TextStyle = AppText.small,
    growOnEnter: Boolean = false,
    centerTextOfProgress: ((Float) -> String)? = null,
) {
    val c = appColors()
    val safeProgress = progress.coerceIn(0f, 1f)
    val arcColor = ringColor ?: c.primary
    val track = trackColor ?: c.fill

    // 起始目标：要「从 0 涨上来」就先钉在 0，等组合完再放开；
    // 系统关掉动画时直接给真值，否则会先闪一帧 0 再跳过去。
    val startAtZero = growOnEnter && motionEnabled()
    var target by remember { mutableStateOf(if (startAtZero) 0f else safeProgress) }

    val animatedProgress by animateFloatAsState(
        targetValue = target,
        animationSpec = motionTween(Motion.SLOW),
        label = "环形进度增长",
    )

    // 组合完的下一帧把目标放开到真实进度，tween(600) 就会把它一路推上去
    LaunchedEffect(safeProgress) { target = safeProgress }

    // 中间文字跟着同一个动画值算，不另做一套数字动画
    val shownText = centerTextOfProgress?.invoke(animatedProgress) ?: centerText

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            // 画弧时把半径缩进半个线宽，弧线才不会超出边界被裁掉
            val inset = strokePx / 2f
            val arcSize = Size(
                width = (size.width - strokePx).coerceAtLeast(1f),
                height = (size.height - strokePx).coerceAtLeast(1f),
            )
            val topLeft = Offset(inset, inset)

            // 轨道：整圈，用中性浅色
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            // 进度：从正上方顺时针扫过 360 × animatedProgress
            if (animatedProgress > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = shownText, style = centerStyle, color = c.textPrimary, maxLines = 1)
            if (subtitle != null) {
                Text(text = subtitle, style = subtitleStyle, color = c.textSecondary)
            }
        }
    }
}
