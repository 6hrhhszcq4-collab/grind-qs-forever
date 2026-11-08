// 界面层：刷题 Tab。
//
// 原来首页上那一列七个入口原样保留（统一刷 / 智能刷 / 模拟考试 / 错题本 / 单选 / 判断 / 多选），
// 只是把按钮换成统一的「入口行」并分成两组卡片；功能、顺序、可用条件都不变。
package com.shuaati.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shuaati.app.data.WrongBook
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 刷题 Tab。
 *
 * @param totalQuestions 题库总数，用于「统一刷」的说明文字与可用判断
 * @param wrongCount 当前错题数，显示在「错题本（N）」
 * @param singleCount / @param judgeCount / @param multiCount 三种题型的题量
 * @param paperName 当前试卷名（06B 起从 meta.json 读，不再写死）
 * @param onSwitch 点「切换」时回调，由外层弹试卷列表
 */
@Composable
fun PracticeScreen(
    totalQuestions: Int,
    wrongCount: Int,
    singleCount: Int,
    judgeCount: Int,
    multiCount: Int,
    paperName: String,
    onUnified: () -> Unit,
    onSmart: () -> Unit,
    onExam: () -> Unit,
    onWrongBook: () -> Unit,
    onSingle: () -> Unit,
    onJudge: () -> Unit,
    onMulti: () -> Unit,
    onSwitch: () -> Unit,
) {
    val c = appColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.xl))
        Text(text = "刷题", style = AppText.pageTitle, color = c.textPrimary)

        // 当前试卷 + 「切换」：06B 起真的能切，点开是试卷列表
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前：$paperName",
                modifier = Modifier.weight(1f),
                style = AppText.small,
                color = c.textSecondary,
            )
            AppButton(
                text = "切换",
                onClick = onSwitch,
                style = AppButtonStyle.TEXT,
                fillWidth = false,
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        // ===== 第一组：刷题与考试 =====
        SectionCard(title = "开始刷题") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                EntryRow(
                    title = "统一刷",
                    hint = if (totalQuestions > 0) {
                        "全部 $totalQuestions 题随机刷，本轮不重复"
                    } else {
                        "这套试卷读不出题目，请到设置页换一套或重新导入"
                    },
                    enabled = totalQuestions > 0,
                    onClick = onUnified,
                )
                EntryRow(
                    title = "智能刷",
                    hint = "20 题一组 · 越不会的越常出现",
                    enabled = totalQuestions > 0,
                    onClick = onSmart,
                )
                EntryRow(
                    title = "模拟考试",
                    hint = "100 题 · 90 分钟 · 交卷出分，考前摸底",
                    enabled = totalQuestions > 0,
                    onClick = onExam,
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // ===== 第二组：错题本与题型专练 =====
        SectionCard(title = "错题与专练") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                EntryRow(
                    title = "错题本（$wrongCount）",
                    hint = "连续答对 ${WrongBook.GRADUATE_STREAK} 次自动移出",
                    titleColor = c.error,
                    onClick = onWrongBook,
                )
                EntryRow(
                    title = "单选专练（$singleCount）",
                    hint = "只出单选题",
                    enabled = singleCount > 0,
                    onClick = onSingle,
                )
                EntryRow(
                    title = "判断专练（$judgeCount）",
                    hint = "只出判断题",
                    enabled = judgeCount > 0,
                    onClick = onJudge,
                )
                EntryRow(
                    title = "多选专练（$multiCount）",
                    hint = "只出多选题",
                    enabled = multiCount > 0,
                    onClick = onMulti,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/**
 * 一个入口行：左侧标题 + 说明，右侧箭头，整行可点，最小高度 56dp。
 *
 * @param enabled 不可用时整行变灰且不响应点击（和原来按钮置灰一致）
 * @param titleColor 标题颜色，错题本用错误色强调
 */
@Composable
private fun EntryRow(
    title: String,
    hint: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    titleColor: Color? = null,
) {
    val c = appColors()
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = ComponentSize.optionMinHeight),
        shape = Radius.buttonShape,
        color = c.card,
        border = BorderStroke(ComponentSize.stroke, c.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppText.bodyStrong,
                    color = when {
                        !enabled -> c.textSecondary
                        titleColor != null -> titleColor
                        else -> c.textPrimary
                    },
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = hint,
                    style = AppText.caption,
                    color = c.textSecondary,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (enabled) c.textSecondary else c.border,
                modifier = Modifier.size(ComponentSize.icon),
            )
        }
    }
}
