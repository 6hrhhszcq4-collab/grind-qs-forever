// 界面层：首页——统计面板。
//
// 数字全部来自 progress.json 的实时计算（见 HomeStats.kt），本文件只负责摆版式：
//   试卷名 + 题量 → 本轮进度大环 → 四宫格统计 → 分题型三个小环 → 连续刷题天数 → 导入按钮。
// 五块内容各自受设置里的开关控制，关掉就整块不渲染（不留空位）。
package com.shuaati.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.shuaati.app.data.AppSettings
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.components.StatRing
import com.shuaati.app.ui.components.StatTile
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import kotlin.math.roundToInt

/**
 * 首页。
 *
 * @param stats 已经算好的统计数字（由调用方从当前试卷的 progress.json 现算）
 * @param settings 设置；用来决定哪几块显示
 * @param paperName 当前试卷名（06B 起从 meta.json 读，不再写死）
 * @param onImport 点「＋ 导入新试卷」时回调，由外层弹「本地 / 网络」二选一
 * @param animateOnEnter 进入首页时是否播「环形从 0 涨上去」（07A）；
 *        外层用 rememberSaveable 记住「播过了」，从设置页返回时传 false，不重播
 * @param onEnterAnimationPlayed 动画已经开始播的回调；外层收到后把「播过了」写进 rememberSaveable
 */
@Composable
fun HomeScreen(
    stats: HomeStats,
    settings: AppSettings,
    paperName: String,
    onImport: () -> Unit,
    animateOnEnter: Boolean = true,
    onEnterAnimationPlayed: () -> Unit = {},
) {
    val c = appColors()

    // 只在进入首页时播一次：一进来就告诉外层「已播」，之后从设置页回来不再重播
    LaunchedEffect(animateOnEnter) {
        if (animateOnEnter) onEnterAnimationPlayed()
    }

    // 首页自己的滚动：内容多时靠它，不依赖外层
    // 本轮进度：分母是题库总数，分子是统一刷这一轮已经出过的题数
    val roundProgress = if (stats.totalQuestions > 0) {
        stats.roundCount.toFloat() / stats.totalQuestions
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.xl))

        // ===== 顶部：当前试卷名 + 题量（都来自当前试卷的 meta.json）=====
        Text(text = paperName, style = AppText.pageTitle, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.xs))
        Text(text = "共 ${stats.totalQuestions} 题", style = AppText.small, color = c.textSecondary)

        // ===== 本轮进度大环 =====
        if (settings.showRing) {
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                StatRing(
                    progress = roundProgress,
                    centerText = "${stats.roundCount}/${stats.totalQuestions}",
                    // 中间数字用同一个动画值算，环涨到哪数字就跟到哪
                    centerTextOfProgress = { p ->
                        "${(p * stats.totalQuestions).roundToInt()}/${stats.totalQuestions}"
                    },
                    growOnEnter = animateOnEnter,
                    subtitle = "本轮进度",
                    diameter = ComponentSize.ringLarge,
                    strokeWidth = ComponentSize.ringStroke,
                    ringColor = c.primary,
                    centerStyle = AppText.display,
                )
            }
        }

        // ===== 四宫格统计 =====
        if (settings.showTiles) {
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatTile(
                    value = "${stats.totalSeen}",
                    label = "累计做题",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = stats.accuracyPercent?.let { "$it%" } ?: "—",
                    label = "正确率",
                    modifier = Modifier.weight(1f),
                    valueColor = c.success,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatTile(
                    value = "${stats.wrongCount}",
                    label = "错题数",
                    modifier = Modifier.weight(1f),
                    valueColor = c.error,
                )
                StatTile(
                    value = "${stats.masteredCount}",
                    label = "已掌握",
                    modifier = Modifier.weight(1f),
                    valueColor = c.primary,
                )
            }
        }

        // ===== 分题型三个小环 =====
        if (settings.showTypeRings) {
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TypeRingColumn(stats.single, animateOnEnter, Modifier.weight(1f))
                TypeRingColumn(stats.judge, animateOnEnter, Modifier.weight(1f))
                TypeRingColumn(stats.multi, animateOnEnter, Modifier.weight(1f))
            }
        }

        // ===== 连续刷题天数 =====
        if (settings.showStreak) {
            Spacer(Modifier.height(Spacing.xl))
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "连续刷题",
                        style = AppText.body,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${stats.streakDays} 天",
                        style = AppText.heading,
                        color = if (stats.streakDays > 0) c.primary else c.textSecondary,
                    )
                }
            }
        }

        // ===== 导入新试卷（06B 起真的能导入了：本地文件 / 网络获取）=====
        if (settings.showImport) {
            Spacer(Modifier.height(Spacing.xl))
            AppButton(
                text = "＋ 导入新试卷",
                onClick = onImport,
                style = AppButtonStyle.SECONDARY,
            )
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** 分题型的一个小环 + 下方题型名；三个环各自独立地从小涨到自己的进度 */
@Composable
private fun TypeRingColumn(
    progress: TypeProgress,
    animateOnEnter: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = appColors()
    val ratio = if (progress.total > 0) {
        progress.done.toFloat() / progress.total
    } else {
        0f
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatRing(
            progress = ratio,
            centerText = "${progress.done}/${progress.total}",
            centerTextOfProgress = { p ->
                "${(p * progress.total).roundToInt()}/${progress.total}"
            },
            growOnEnter = animateOnEnter,
            diameter = ComponentSize.ringSmall,
            strokeWidth = ComponentSize.ringStrokeSmall,
            ringColor = c.primary,
            trackColor = c.fill,
            centerStyle = AppText.smallStrong,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(text = progress.typeName, style = AppText.caption, color = c.textSecondary)
    }
}
