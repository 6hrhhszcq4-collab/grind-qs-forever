// 通用组件：选项卡片（答题页、考试页共用）
//
// 五态：
//   默认   —— 卡片底 + 细边框，还没点
//   选中   —— 主色边框 + 主色浅底，点了但还没提交（多选题用）
//   正确   —— 绿边 + 浅绿底 + 勾
//   错误   —— 红边 + 浅红底 + 叉
//   禁用   —— 灰字，答过之后其它选项用
//
// 07A 动效（三处，全部在 300ms 红线内，给 QuizScreen「答对后 350ms 自动跳题」留余量）：
//   1) 按压：手指按下缩到 0.97，松手弹回 1.0（弹簧，跟手）；
//   2) 答对：底色/边框/字母圈 250ms 变绿 + 整体放大到 1.03 再回 1.0（两段各 120ms，共 240ms）；
//   3) 答错：水平抖动 0 → -6dp → 6dp → -4dp → 4dp → 0（共 200ms），
//      抖动期间**只把这一项**设为不可点，抖完立刻恢复；其它选项、整屏、下一题按钮都不受影响。
//
// 关于「按下/松开」怎么拿：这里用 Material 的 MutableInteractionSource + collectIsPressedAsState，
// 而不是再挂一个 pointerInput/detectTapGestures。原因：detectTapGestures 会消费按下事件，
// 而 Surface 自带的点击逻辑要求「未被消费的按下」才会触发，两者叠在一起会互相抢事件、导致点击失灵。
// 交互流是 Material 官方拿「按下状态」的通道，点击与涟漪仍然完全走 Surface 默认实现（未被覆盖）。
package com.shuaati.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Motion
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import com.shuaati.app.ui.theme.motionEnabled
import com.shuaati.app.ui.theme.motionMillis
import com.shuaati.app.ui.theme.motionSpring
import com.shuaati.app.ui.theme.motionTween

/** 选项卡片的五种状态 */
enum class OptionCardState {
    /** 默认：没点过 */
    DEFAULT,

    /** 选中：点了但还没提交 */
    SELECTED,

    /** 正确：提交后判定为正确答案 */
    CORRECT,

    /** 错误：提交后判定为选错的答案 */
    WRONG,

    /** 禁用：已经答过，其它选项不再可点 */
    DISABLED,
}

/**
 * 一个选项卡片，整行可点。
 *
 * @param optionKey 选项字母（A/B/C…）；判断题传 T/F，但 showKey=false 时不显示
 * @param text 选项文字
 * @param state 五态之一
 * @param onClick 点击回调（DISABLED 状态不响应）
 * @param showKey 是否显示左边的字母圆圈（判断题不显示）
 * @param centerText 选项文字是否居中（判断题居中显示「对 / 错」）
 */
@Composable
fun QuizOptionCard(
    optionKey: String,
    text: String,
    state: OptionCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showKey: Boolean = true,
    centerText: Boolean = false,
) {
    val c = appColors()

    // 底色：五种状态各自的语义色
    val backgroundColor: Color = when (state) {
        OptionCardState.DEFAULT -> c.card
        OptionCardState.SELECTED -> c.primarySoft
        OptionCardState.CORRECT -> c.successSoft
        OptionCardState.WRONG -> c.errorSoft
        OptionCardState.DISABLED -> c.card
    }
    // 边框：默认/禁用是细分隔线，其余用 2dp 强调色
    val borderColor: Color = when (state) {
        OptionCardState.DEFAULT -> c.border
        OptionCardState.SELECTED -> c.primary
        OptionCardState.CORRECT -> c.success
        OptionCardState.WRONG -> c.error
        OptionCardState.DISABLED -> c.border
    }
    val strong = state == OptionCardState.SELECTED ||
        state == OptionCardState.CORRECT ||
        state == OptionCardState.WRONG
    val borderWidth = if (strong) ComponentSize.strokeStrong else ComponentSize.stroke

    // 文字：禁用态整行变灰，其余保持主文字色（对错靠边框、底色和右侧标记表达，不牺牲可读性）
    val textColor = if (state == OptionCardState.DISABLED) c.textSecondary else c.textPrimary

    // 字母圆圈的底色与字色
    val badgeColor: Color = when (state) {
        OptionCardState.SELECTED -> c.primary
        OptionCardState.CORRECT -> c.success
        OptionCardState.WRONG -> c.error
        OptionCardState.DEFAULT, OptionCardState.DISABLED -> c.fill
    }
    val badgeTextColor: Color = when (state) {
        OptionCardState.SELECTED, OptionCardState.CORRECT, OptionCardState.WRONG -> c.onAccent
        OptionCardState.DEFAULT, OptionCardState.DISABLED -> c.textSecondary
    }

    val textStyle: TextStyle = if (centerText) AppText.stemStrong else AppText.body

    // ===== 动效 1：按压（按下 0.97 / 松开 1.0，弹簧）=====
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // ===== 动效 5 的「只锁这一项」标记：只在抖动的那 200ms 为 true =====
    var shaking by remember { mutableStateOf(false) }
    val cardEnabled = state != OptionCardState.DISABLED && !shaking

    val pressScale by animateFloatAsState(
        targetValue = if (pressed && cardEnabled) Motion.PRESS_SCALE else 1f,
        animationSpec = motionSpring(),
        label = "选项按压缩放",
    )

    // ===== 动效 4：答对弹一下（1.0 → 1.03 → 1.0，两段各 120ms）=====
    val popScale = remember { Animatable(1f) }
    // ===== 动效 5：答错抖动（0 → -6 → 6 → -4 → 4 → 0，共 200ms）=====
    val shakeDp = remember { Animatable(0f) }

    val popStepMillis = motionMillis(Motion.CORRECT_POP_STEP).coerceAtLeast(1)
    val shakeMillis = motionMillis(Motion.SHAKE_DURATION).coerceAtLeast(5)
    val animate = motionEnabled()

    LaunchedEffect(state) {
        if (state == OptionCardState.CORRECT && animate) {
            // 答对：只弹一次，240ms 内结束，不拖累 350ms 后的自动跳题
            shakeDp.snapTo(0f)
            popScale.snapTo(1f)
            popScale.animateTo(
                targetValue = Motion.CORRECT_POP_SCALE,
                animationSpec = tween(durationMillis = popStepMillis, easing = Motion.easing),
            )
            popScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = popStepMillis, easing = Motion.easing),
            )
        } else if (state == OptionCardState.WRONG && animate) {
            // 答错：抖两下。抖动期间只锁这一张卡片，200ms 后自己解锁
            popScale.snapTo(1f)
            shakeDp.snapTo(0f)
            shaking = true
            shakeDp.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = shakeMillis
                    // 关键帧按 200ms 的比例换算，系统动画调慢/调快时节奏不变
                    -Motion.SHAKE_DP at (shakeMillis * 33 / 200)
                    Motion.SHAKE_DP at (shakeMillis * 66 / 200)
                    -Motion.SHAKE_SOFT_DP at (shakeMillis * 100 / 200)
                    Motion.SHAKE_SOFT_DP at (shakeMillis * 133 / 200)
                    0f at shakeMillis
                },
            )
            shaking = false
        } else {
            // 其它状态（含系统关动画）：直接归位，不留残影
            shakeDp.snapTo(0f)
            popScale.snapTo(1f)
            shaking = false
        }
    }

    // ===== 颜色过渡：答对变绿 / 答错变红都是 250ms 渐变，不是瞬间跳色 =====
    val animatedBackground by animateColorAsState(
        targetValue = backgroundColor,
        animationSpec = motionTween(Motion.NORMAL),
        label = "选项底色",
    )
    val animatedBorder by animateColorAsState(
        targetValue = borderColor,
        animationSpec = motionTween(Motion.NORMAL),
        label = "选项边框",
    )
    val animatedBadge by animateColorAsState(
        targetValue = badgeColor,
        animationSpec = motionTween(Motion.NORMAL),
        label = "字母圆圈底色",
    )
    val animatedText by animateColorAsState(
        targetValue = textColor,
        animationSpec = motionTween(Motion.NORMAL),
        label = "选项文字色",
    )
    val animatedBadgeText by animateColorAsState(
        targetValue = badgeTextColor,
        animationSpec = motionTween(Motion.FAST),
        label = "字母颜色",
    )

    Surface(
        onClick = onClick,
        enabled = cardEnabled,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ComponentSize.optionMinHeight)
            // 抖动：只影响这一张卡片自己的横向位置
            .offset { IntOffset(shakeDp.value.dp.roundToPx(), 0) }
            .scale(pressScale * popScale.value),
        shape = Radius.buttonShape,
        color = animatedBackground,
        border = BorderStroke(borderWidth, animatedBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showKey) {
                Box(
                    modifier = Modifier
                        .size(ComponentSize.optionKeyBadge)
                        .clip(CircleShape)
                        .background(animatedBadge),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = optionKey,
                        style = AppText.captionStrong,
                        color = animatedBadgeText,
                    )
                }
                Spacer(Modifier.width(Spacing.md))
            }

            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = textStyle,
                color = animatedText,
                textAlign = if (centerText) TextAlign.Center else TextAlign.Start,
            )

            // 右侧对错标记：勾 / 叉
            when (state) {
                OptionCardState.CORRECT -> {
                    Spacer(Modifier.width(Spacing.sm))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "正确",
                        tint = c.success,
                        modifier = Modifier.size(ComponentSize.iconSmall),
                    )
                }
                OptionCardState.WRONG -> {
                    Spacer(Modifier.width(Spacing.sm))
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "错误",
                        tint = c.error,
                        modifier = Modifier.size(ComponentSize.iconSmall),
                    )
                }
                else -> Unit
            }
        }
    }
}
