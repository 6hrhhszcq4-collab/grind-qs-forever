// 界面层：智能刷一组刷完的「小结页」。
//
// 只负责显示，不自己算数字：题量、答对数、答错列表全部由 QuizState 在答题过程中
// 按真实作答累计后传进来（数字必须来自本次作答，不许写死示例）。
// 06A 换皮：颜色/字号/间距/圆角全部用令牌。
package com.shuaati.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.shuaati.app.data.Question
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import kotlin.math.roundToInt

/**
 * 一组智能刷的小结页。
 *
 * @param total 本组题量（正常 20）
 * @param correctCount 本组答对题数
 * @param noAnswerCount 本组「暂无答案」的题数（不计入正确率；当前题库为 0）
 * @param wrongItems 本组答错的题，按作答先后排列
 * @param onAgain 「再来一组」：按最新进度重新抽 20 题
 * @param onHome 「返回主页」
 */
@Composable
fun SmartSummaryScreen(
    total: Int,
    correctCount: Int,
    noAnswerCount: Int,
    wrongItems: List<Question>,
    onAgain: () -> Unit,
    onHome: () -> Unit,
) {
    val c = appColors()
    // 分母是「本组真正判了对错的题数」：暂无答案的题既不是对也不是错，不该被算成错
    val scored = (total - noAnswerCount).coerceAtLeast(0)
    val rate = if (scored > 0) (correctCount * 100.0 / scored).roundToInt() else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        // 顶部标题条
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.lg)) {
            Text(
                text = "本组小结",
                style = AppText.pageTitle,
                color = c.primary,
            )
        }

        // 中间内容：可滚动（错题最多 20 条，小屏也能看全）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl),
        ) {
            // 大字成绩：答对 X / 总题数
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "答对 $correctCount",
                    style = AppText.display,
                    color = if (correctCount == total) c.success else c.error,
                )
                Text(
                    text = " / $total 题",
                    style = AppText.stem,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.height(Spacing.sm))

            // 这句话里的数字全部来自真实作答
            Text(
                text = "本组 $total 题，答对 $correctCount 题，正确率 $rate%",
                style = AppText.body,
                color = c.textPrimary,
            )
            if (noAnswerCount > 0) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "其中 $noAnswerCount 题题库暂无答案，没判对错，也不计入正确率",
                    style = AppText.caption,
                    color = c.textSecondary,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "答错的题，在下一组里更容易再出现。",
                style = AppText.caption,
                color = c.textSecondary,
            )

            Spacer(Modifier.height(Spacing.lg))

            if (wrongItems.isEmpty()) {
                // 全对：给一块绿色提示，不留空白
                Surface(
                    color = c.successSoft,
                    shape = Radius.buttonShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "这一组全部答对，没有错题。",
                        modifier = Modifier.padding(Spacing.lg),
                        style = AppText.bodyStrong,
                        color = c.success,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "本组答错 ${wrongItems.size} 题",
                        style = AppText.bodyStrong,
                        color = c.error,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = "（列表可上下滑动）",
                        style = AppText.caption,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                wrongItems.forEachIndexed { index, question ->
                    WrongItemCard(order = index + 1, question = question)
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }

        // 底部按钮固定，滚到哪都能点「再来一组」
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.md)) {
            AppButton(text = "再来一组", onClick = onAgain)
            Spacer(Modifier.height(Spacing.md))
            AppButton(text = "返回主页", onClick = onHome, style = AppButtonStyle.SECONDARY)
        }
    }
}

/** 一条本组错题：序号 + 题干摘要（最多两行）+ 正确答案 */
@Composable
private fun WrongItemCard(order: Int, question: Question) {
    val c = appColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = Radius.buttonShape,
        color = c.card,
        border = BorderStroke(ComponentSize.stroke, c.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(ComponentSize.optionKeyBadge)
                    .clip(CircleShape)
                    .background(c.errorSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$order",
                    style = AppText.captionStrong,
                    color = c.error,
                )
            }
            Spacer(Modifier.width(Spacing.sm))

            Column(Modifier.weight(1f)) {
                // 题干摘要：先裁到 46 个字，界面上再限制两行，超长省略
                Text(
                    text = stemBrief(question.stem),
                    style = AppText.small,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 题型标签，和错题本列表一致
                    Surface(color = c.primarySoft, shape = Radius.labelShape) {
                        Text(
                            text = question.typeName,
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                            style = AppText.caption,
                            color = c.primary,
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = "正确答案：${question.answerDisplay()}",
                        style = AppText.captionStrong,
                        color = c.success,
                    )
                }
            }
        }
    }
}

/**
 * 题干摘要：换行合成空格，超过 46 个字就截断加省略号。
 * 只截显示用的文字，原题干（题库里的）一个字都不改。
 */
private fun stemBrief(stem: String, maxChars: Int = 46): String {
    val oneLine = stem.replace("\n", " ").replace("\r", " ").trim()
    return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
}
