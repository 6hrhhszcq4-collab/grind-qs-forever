// 界面层：答题页，一次只显示一题，负责选项交互、判分着色和反馈展示。
// 06A 换皮后：颜色/字号/间距全部来自 ui/theme 令牌，选项改用通用组件 QuizOptionCard（五态）。
// 判分、跳题、进度落盘仍然完全交给 QuizState + Grading + ProgressStore，本文件不做任何业务判断。
package com.shuaati.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import com.shuaati.app.data.CurrentPaperImages
import com.shuaati.app.data.Option
import com.shuaati.app.data.Question
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppTopBar
import com.shuaati.app.ui.components.OptionCardState
import com.shuaati.app.ui.components.QuizOptionCard
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Motion
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import com.shuaati.app.ui.theme.motionTween
import kotlinx.coroutines.delay

/**
 * 答题页：顶部固定显示本题错次，下面依次是题干、图片、选项、判分反馈和按钮。
 * 答对后由 LaunchedEffect 等约 350 毫秒自动切下一题；答错停住，等人点「下一题」。
 */
@Composable
fun QuizScreen(state: QuizState, onBack: () -> Unit) {
    val c = appColors()
    val question = state.current
    if (question == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(c.background),
            contentAlignment = Alignment.Center,
        ) {
            Text("还没有题目", style = AppText.body, color = c.textSecondary)
        }
        return
    }

    // 答对约 350 毫秒后自动进入下一题；答错不自动跳（行为与改造前一致）
    LaunchedEffect(state.submitted) {
        if (state.submitted && state.correct == true) {
            delay(350)
            state.next()
        }
    }

    // 切题的左右移动用这个 spec（300ms，位移整整一屏宽）。
    // 在 transitionSpec 外面先算好：transitionSpec 不是 @Composable，里面读不到动效令牌。
    val quizSlide = motionTween<IntOffset>(Motion.SLIDE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        QuizHeader(state, question, onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            // ===== 动效 6（07A 修订版）：题干 + 图片 + 选项整体左右移动 =====
            // 旧题向左滑出一屏、新题从右侧一屏处滑入，300ms；不再用原地淡入淡出。
            // 顶部栏、题目信息条、「第 N 题」这些不随内容移动，保持稳定（与用户给的示意图一致）。
            AnimatedContent(
                targetState = question,
                transitionSpec = {
                    slideInHorizontally(quizSlide) { it } togetherWith
                        slideOutHorizontally(quizSlide) { -it }
                },
                contentKey = { it.id },
                label = "切换题目",
            ) { shown ->
                Column(Modifier.fillMaxWidth()) {
                    // 题干：18sp，行高 1.4 倍
                    Text(
                        text = shown.stem,
                        style = AppText.stem,
                        color = c.textPrimary,
                    )

                    // 图片：image 不为空时按宽度撑满显示
                    QuestionImage(shown.image)
                    Spacer(Modifier.height(Spacing.lg))

                    // 选项：整行可点的卡片，五态由通用组件负责
                    shown.options.forEach { option ->
                        QuizOptionCard(
                            optionKey = option.key,
                            text = option.text,
                            state = optionState(shown, option, state),
                            onClick = { state.toggleOption(option.key) },
                            showKey = shown.type != "judge",
                            centerText = shown.type == "judge",
                        )
                        Spacer(Modifier.height(Spacing.md))
                    }
                }
            }

            FeedbackArea(question, state)
            Spacer(Modifier.height(Spacing.md))
            BottomActions(question, state)
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/**
 * 把一个选项映射成卡片五态：
 * - 还没提交：选中 → SELECTED，其余 DEFAULT
 * - 已提交：正确答案 → CORRECT；选错的那一项 → WRONG；其余 → DISABLED
 * - 暂无答案的题：一律 DISABLED（不判对错，也不许再点）
 */
private fun optionState(question: Question, option: Option, state: QuizState): OptionCardState {
    if (!state.submitted) {
        return if (option.key in state.selectedKeys) {
            OptionCardState.SELECTED
        } else {
            OptionCardState.DEFAULT
        }
    }
    if (state.noAnswer) return OptionCardState.DISABLED
    val isRight = option.key in question.correctKeys
    val chosen = option.key in state.selectedKeys
    return when {
        isRight -> OptionCardState.CORRECT
        chosen -> OptionCardState.WRONG
        else -> OptionCardState.DISABLED
    }
}

/** 顶部固定区：返回、当前模式标题、进度，下面一行题号与题型，最后是「本题已错 N 次」 */
@Composable
private fun QuizHeader(state: QuizState, question: Question, onBack: () -> Unit) {
    val c = appColors()
    Column(Modifier.fillMaxWidth()) {
        AppTopBar(
            title = state.title,
            onBack = onBack,
            action = { Text(text = state.progressText, style = AppText.caption, color = c.textSecondary) },
        )

        Text(
            text = "第 ${question.no} 题 · ${question.typeName}",
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
            style = AppText.caption,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )

        // 这一行固定显示，N 直接来自落盘进度；为 0 时显示「本题还没错过」
        val hasWrong = state.wrongCount > 0
        Surface(
            color = if (hasWrong) c.errorSoft else c.fill,
            shape = Radius.labelShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        ) {
            Text(
                text = if (hasWrong) "本题已错 ${state.wrongCount} 次" else "本题还没错过",
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                style = AppText.small,
                color = if (hasWrong) c.error else c.textSecondary,
            )
        }
    }
}

/** 题目图片：先从当前试卷的 images 目录找，找不到退回 assets；按可用宽度撑满 */
@Composable
private fun QuestionImage(path: String?) {
    if (path == null) return
    val c = appColors()
    val context = LocalContext.current
    // 目录也作为 key：同一个文件名在不同试卷里是不同的图，切卷后必须重新解码
    val dir = CurrentPaperImages.currentDir()
    val bitmap: ImageBitmap? = remember(path, dir) {
        decodeQuestionImage(context, path)
    }

    Spacer(Modifier.height(Spacing.md))
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "题目图片",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(Radius.labelShape),
        )
    } else {
        Text(text = "图片加载失败：$path", style = AppText.small, color = c.error)
    }
}

/** 判分反馈：答对/答错、正确答案、本题已错次数、解析；暂无答案时单独提示 */
@Composable
private fun FeedbackArea(question: Question, state: QuizState) {
    val c = appColors()
    if (!state.submitted) return

    Spacer(Modifier.height(Spacing.xs))
    if (state.noAnswer) {
        Surface(
            color = c.warningSoft,
            shape = Radius.labelShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "本题暂无答案",
                modifier = Modifier.padding(Spacing.md),
                style = AppText.bodyStrong,
                color = c.warning,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "答案册没有覆盖这道题，本题不计对错，也不计入错题次数。",
            style = AppText.small,
            color = c.textSecondary,
        )
        return
    }

    val ok = state.correct == true
    Text(
        text = if (ok) "回答正确" else "回答错误",
        style = AppText.stemStrong,
        color = if (ok) c.success else c.error,
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = "正确答案：${question.answerDisplay()}",
        style = AppText.body,
        color = if (ok) c.success else c.error,
    )
    if (!ok) {
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "本题已错 ${state.wrongCount} 次",
            style = AppText.body,
            color = c.error,
        )
    }
    if (!question.explain.isNullOrBlank()) {
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "解析：${question.explain}",
            style = AppText.small,
            color = c.textSecondary,
        )
    }
}

/** 底部动作：多选题显示「确认」，答错或暂无答案显示「下一题」 */
@Composable
private fun BottomActions(question: Question, state: QuizState) {
    val c = appColors()
    when {
        !state.submitted && question.isMulti -> {
            AppButton(
                text = "确认",
                onClick = { state.submit() },
                enabled = state.selectedKeys.isNotEmpty(),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "多选题：可以选多项，选好后点「确认」提交",
                style = AppText.caption,
                color = c.textSecondary,
            )
        }
        state.submitted && state.correct == true -> {
            Text(
                text = "回答正确，正在进入下一题…",
                style = AppText.small,
                color = c.textSecondary,
            )
        }
        state.submitted -> {
            AppButton(text = "下一题", onClick = { state.next() })
        }
    }
}
