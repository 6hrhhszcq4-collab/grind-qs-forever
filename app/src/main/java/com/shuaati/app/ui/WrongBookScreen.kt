// 界面层：错题本页面，按「错题判定」的结果列出所有还没毕业的题。
// 列表只负责显示与跳转；「哪些题算错题」全部来自 data/WrongBook.kt 的判定，这里不自己判断。
// 06A 换皮：颜色/字号/间距用令牌，空状态改用通用组件 EmptyState。
package com.shuaati.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.shuaati.app.data.ProgressRecord
import com.shuaati.app.data.Question
import com.shuaati.app.data.WrongBook
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.AppTopBar
import com.shuaati.app.ui.components.EmptyState
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 错题本页面。
 * @param items 已经从题库里筛好的错题，顺序就是列表顺序（也是「全部开始」时的刷题顺序）
 * @param progressOf 取某题进度（App 里传 store.recordOf），用来显示「已错 N 次 / 连对 X 次」
 * @param onStartFrom 点某一条：从这道题开始依次刷
 * @param onStartAll 右上角「全部开始」：从第一条开始刷
 * @param onGoPractice 空状态里的「去刷题」
 */
@Composable
fun WrongBookScreen(
    items: List<Question>,
    progressOf: (String) -> ProgressRecord,
    onBack: () -> Unit,
    onStartAll: () -> Unit,
    onStartFrom: (String) -> Unit,
    onGoPractice: () -> Unit,
) {
    val c = appColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        // 顶部：返回 + 标题（数量与列表实际条数同源）+ 右上「全部开始」
        AppTopBar(
            title = "错题本 ${items.size} 题",
            onBack = onBack,
            action = {
                AppButton(
                    text = "全部开始",
                    onClick = onStartAll,
                    style = AppButtonStyle.TEXT,
                    enabled = items.isNotEmpty(),
                    fillWidth = false,
                )
            },
        )

        if (items.isEmpty()) {
            // 空状态：居中文案 + 「去刷题」按钮，不白屏
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    text = "还没有错题，去刷几道题吧",
                    hint = "答错的题会自动进这里；连续答对 ${WrongBook.GRADUATE_STREAK} 次后自动移出。",
                    actionText = "去刷题",
                    onAction = onGoPractice,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = Spacing.sm),
            ) {
                itemsIndexed(items, key = { _, question -> question.id }) { index, question ->
                    WrongBookItem(
                        order = index + 1,
                        question = question,
                        record = progressOf(question.id),
                        onClick = onStartFrom,
                    )
                }
            }
            // 底部说明：把「什么情况下会移出」写清楚，省得猜
            Text(
                text = "连续答对 ${WrongBook.GRADUATE_STREAK} 次自动移出；中间答错一次，连对次数清零重来。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                style = AppText.caption,
                color = c.textSecondary,
            )
        }
    }
}

/** 一条错题：序号、题干（最多两行）、题型标签，右侧「已错 N 次 / 连对 X 次」 */
@Composable
private fun WrongBookItem(
    order: Int,
    question: Question,
    record: ProgressRecord,
    onClick: (String) -> Unit,
) {
    val c = appColors()
    Surface(
        onClick = { onClick(question.id) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        shape = Radius.buttonShape,
        color = c.card,
        border = BorderStroke(ComponentSize.stroke, c.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            // 列表序号（第几条），与顶部「错题本 N 题」的计数一一对应
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
            Spacer(Modifier.width(Spacing.md))

            Column(Modifier.weight(1f)) {
                // 题干最多两行，超出用省略号
                Text(
                    text = question.stem,
                    style = AppText.small,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.sm))
                // 题型标签
                Surface(
                    color = c.primarySoft,
                    shape = Radius.labelShape,
                ) {
                    Text(
                        text = question.typeName,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        style = AppText.caption,
                        color = c.primary,
                    )
                }
            }

            Spacer(Modifier.width(Spacing.sm))
            // 右侧：已错次数来自 wrong 字段，连对次数来自 streak 字段，都直接读落盘进度
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "已错 ${record.wrong} 次",
                    style = AppText.caption,
                    color = c.error,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "连对 ${record.streak} 次",
                    style = AppText.caption,
                    color = if (record.streak > 0) c.success else c.textSecondary,
                )
            }
        }
    }
}
