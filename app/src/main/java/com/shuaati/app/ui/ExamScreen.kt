// 界面层：模拟考试的三个页面——开始页、答题页（含答题卡）、结果页。
// 判分全部交给 data/Grading.kt，答题状态全部放在 ui/ExamState.kt，这里只负责显示和点击。
// 06A 换皮：颜色/字号/间距/圆角全部来自 ui/theme 令牌，选项复用通用组件 QuizOptionCard。
package com.shuaati.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shuaati.app.data.CurrentPaperImages
import com.shuaati.app.data.ExamBuilder
import com.shuaati.app.data.ExamItem
import com.shuaati.app.data.ExamPaper
import com.shuaati.app.data.ExamResult
import com.shuaati.app.data.GradeResult
import com.shuaati.app.data.Grading
import com.shuaati.app.data.Option
import com.shuaati.app.data.Question
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.AppTopBar
import com.shuaati.app.ui.components.FullScreenDialog
import com.shuaati.app.ui.components.OptionCardState
import com.shuaati.app.ui.components.QuizOptionCard
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Motion
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import com.shuaati.app.ui.theme.motionEnabled
import com.shuaati.app.ui.theme.motionTween
import kotlinx.coroutines.delay
import java.util.Locale

/** 秒数转 mm:ss；超过 99 分钟也只显示分钟数（本卷 90 分钟，正常不会超） */
private fun formatTime(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d", safe / 60, safe % 60)
}

// ============================ 一、考试开始页 ============================

/**
 * 考试开始页：把规则写清楚，点「开始考试」才真正组卷进考场。
 * @param paper 已经组好的试卷（开始页只读它的题量与满分，不下发题目内容）
 * @param onStart 点「开始考试」
 * @param onBack 返回
 */
@Composable
fun ExamStartScreen(
    paper: ExamPaper,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val c = appColors()
    val noAnswerCount = paper.questions.count { !it.hasAnswer }
    val enabled = paper.questions.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        AppTopBar(title = "模拟考试", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        ) {
            Text(
                text = "考前摸底",
                style = AppText.pageTitle,
                color = c.primary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "按真实考试的比例抽题、限时作答，交卷后立刻出总分和逐题对错。",
                style = AppText.small,
                color = c.textSecondary,
            )

            Spacer(Modifier.height(Spacing.lg))

            // 规则卡片：一行完整规则 + 拆成四条要点，便于一眼看清
            SectionCard(title = "考试规则", outlined = true) {
                Text(
                    text = "共 ${paper.total} 题 · ${ExamBuilder.DURATION_MINUTES} 分钟 · " +
                        "单选 ${paper.singleCount} / 判断 ${paper.judgeCount} / 多选 ${paper.multiCount} · " +
                        "满分 ${paper.fullScore} 分 · ${paper.passLine} 分及格",
                    style = AppText.small,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.sm))
                RuleLine("题量", "${paper.total} 题（单选 ${paper.singleCount} / 判断 ${paper.judgeCount} / 多选 ${paper.multiCount}）")
                RuleLine("时长", "${ExamBuilder.DURATION_MINUTES} 分钟，时间到自动交卷")
                RuleLine("计分", "每题 1 分；多选全选对才得分，少选、多选都不得分")
                RuleLine("及格", "满分 ${paper.fullScore} 分，${paper.passLine} 分及格")
            }

            Spacer(Modifier.height(Spacing.md))

            // 题库不足 100 题时的专门提示（现在题库 300 题，正常不出现）
            paper.shortageText()?.let { text ->
                WarningBlock(text)
                Spacer(Modifier.height(Spacing.md))
            }

            // 本题库有题没答案时，提前把「满分不是 100」说清楚，交卷页还会再提示一次
            if (noAnswerCount > 0) {
                WarningBlock(
                    "本题库有 $noAnswerCount 题暂无答案，已从总分中剔除，满分按 ${paper.fullScore} 分计",
                )
                Spacer(Modifier.height(Spacing.md))
            }

            Text(
                text = "考试中退出 App 不会清空试卷，下次进来可以继续；放弃才清空。",
                style = AppText.caption,
                color = c.textSecondary,
            )

            Spacer(Modifier.height(Spacing.xl))

            AppButton(text = "开始考试", onClick = onStart, enabled = enabled)
            if (!enabled) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = "题库为空，无法开始考试，请检查 assets/questions.json",
                    style = AppText.caption,
                    color = c.error,
                )
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/** 规则卡片里的一行：左边小标题，右边说明 */
@Composable
private fun RuleLine(title: String, content: String) {
    val c = appColors()
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(
            text = title,
            modifier = Modifier.width(ComponentSize.ruleLabelWidth),
            style = AppText.captionStrong,
            color = c.primary,
        )
        Text(
            text = content,
            modifier = Modifier.weight(1f),
            style = AppText.caption,
            color = c.textSecondary,
        )
    }
}

/** 提醒块：橙黄底 + 橙黄字，用于「题库不足」「有题暂无答案」这类提示 */
@Composable
private fun WarningBlock(text: String) {
    val c = appColors()
    Surface(
        color = c.warningSoft,
        shape = Radius.buttonShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            style = AppText.small,
            color = c.warning,
        )
    }
}

// ============================ 二、答题页 ============================

/**
 * 考场：顶部固定倒计时和题号，中间题目可滚动，底部固定三个按钮。
 * 交卷后这个函数内部直接切到结果页（同一个 ExamState 里带着算分结果）。
 */
@Composable
fun ExamScreen(state: ExamState, onExit: () -> Unit) {
    val c = appColors()

    // 倒计时驱动：每 250 毫秒看一次表。用的是「结束时间戳 − 现在」，
    // 所以切后台、锁屏再回来，剩余时间仍然准确，到点就会自动交卷。
    LaunchedEffect(state) {
        while (true) {
            state.tick()
            if (state.result != null) break
            delay(250)
        }
    }

    val result = state.result
    if (result != null) {
        BackHandler { onExit() }
        ExamResultScreen(result = result, onBackHome = onExit)
        return
    }

    // v1.5：答题期间禁止截屏 / 录屏 / 最近任务预览；交卷出分后（上面 result != null 分支）
    // 这个 DisposableEffect 会随重组被释放，自动解除。
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // 答题页自己的三种弹窗：答题卡、交卷确认、退出确认
    var showSheet by remember { mutableStateOf(false) }
    var showSubmitConfirm by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    BackHandler { showExitConfirm = true }

    val question = state.current
    if (question == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.background)
                .systemBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Text("试卷没有题目", style = AppText.small, color = c.textSecondary)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        ExamHeader(
            state = state,
            onExitClick = { showExitConfirm = true },
            onSubmitClick = { showSubmitConfirm = true },
        )

        // 题干与选项区域：可以上下滚动，长题目也能看全
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            Text(
                text = question.stem,
                style = AppText.stem,
                color = c.textPrimary,
            )

            ExamQuestionImage(question.image)
            Spacer(Modifier.height(Spacing.lg))

            // 选项：选中只是主色边框 + 浅底，不显示对错（考试中不判分）
            question.options.forEach { option ->
                ExamOptionRow(
                    question = question,
                    option = option,
                    selected = option.key in state.answers[question.id].orEmpty(),
                    onClick = { state.select(option.key) },
                )
                Spacer(Modifier.height(Spacing.md))
            }

            // 题型提示：把「点选即记录 / 多选要点下一题保存」说清楚
            Text(
                text = if (question.isMulti) {
                    "多选题：可以选多项，再点一次取消；答案已自动记录，点「下一题」保存并继续。"
                } else {
                    "点选项即记录答案，不判对错；选错了直接点另一个选项就能改。"
                },
                style = AppText.caption,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.xl))
        }

        // 底部固定的三个按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppButton(
                text = "上一题",
                onClick = { state.prev() },
                style = AppButtonStyle.SECONDARY,
                enabled = !state.isFirst,
                modifier = Modifier.weight(1f),
                fillWidth = false,
            )
            AppButton(
                text = "下一题",
                onClick = { state.next() },
                enabled = !state.isLast,
                modifier = Modifier.weight(1f),
                fillWidth = false,
            )
            AppButton(
                text = "答题卡",
                onClick = { showSheet = true },
                style = AppButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
                fillWidth = false,
            )
        }
    }

    // 答题卡：100 个格子，点哪个跳哪个
    if (showSheet) {
        AnswerSheetDialog(
            total = state.total,
            currentPosition = state.index,
            isAnswered = { state.isAnswered(it) },
            onJump = { position ->
                state.jumpTo(position)
                showSheet = false
            },
            onDismiss = { showSheet = false },
        )
    }

    // 手动交卷确认：把还剩多少题没答说清楚
    if (showSubmitConfirm) {
        val left = state.unansweredCount
        AlertDialog(
            onDismissRequest = { showSubmitConfirm = false },
            title = { Text("交卷", style = AppText.stemStrong) },
            text = {
                Text(
                    text = if (left > 0) "还有 $left 题未作答，确定交卷？" else "所有题都已作答，确定交卷？",
                    style = AppText.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSubmitConfirm = false
                    state.finish(auto = false)
                }) {
                    Text("确定交卷", style = AppText.smallStrong, color = c.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubmitConfirm = false }) {
                    Text("继续答题", style = AppText.smallStrong, color = c.textSecondary)
                }
            },
        )
    }

    // 退出确认：考试进度会保留，这里要明确告诉人「不是放弃」
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出考试？", style = AppText.stemStrong) },
            text = {
                Text(
                    text = "已作答的内容会保存，下次进入 App 可以继续上次考试；倒计时不会因为你退出而暂停。",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    onExit()
                }) {
                    Text("退出", style = AppText.smallStrong, color = c.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("继续答题", style = AppText.smallStrong, color = c.textSecondary)
                }
            },
        )
    }
}

/** 顶部固定区：退出、标题、交卷；下面一行是倒计时和「第 x/N 题」 */
@Composable
private fun ExamHeader(
    state: ExamState,
    onExitClick: () -> Unit,
    onSubmitClick: () -> Unit,
) {
    val c = appColors()
    Column(Modifier.fillMaxWidth()) {
        AppTopBar(
            title = "模拟考试",
            backText = "退出",
            onBack = onExitClick,
            action = {
                AppButton(
                    text = "交卷",
                    onClick = onSubmitClick,
                    style = AppButtonStyle.TEXT,
                    fillWidth = false,
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 倒计时：剩 5 分钟及以内变红
            Text(
                text = formatTime(state.remainingSec),
                style = AppText.heading,
                color = if (state.isTimeWarning) c.error else c.textPrimary,
            )
            if (state.isTimeWarning) {
                Spacer(Modifier.width(Spacing.xs))
                Text("即将结束", style = AppText.caption, color = c.error)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "第 ${state.index + 1}/${state.total} 题",
                style = AppText.small,
                color = c.textSecondary,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        HorizontalDivider(color = c.border, thickness = ComponentSize.stroke)
    }
}

/** 题目图片：先从当前试卷的 images 目录找，找不到退回 assets；按宽度撑满 */
@Composable
private fun ExamQuestionImage(path: String?) {
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

/**
 * 一个考试选项：整行卡片。
 * 选中显示主色边框 + 浅蓝底，没选中是卡片底 + 细边框；**不显示对错颜色**，因为考试中不判分。
 * 直接复用通用组件 QuizOptionCard 的「默认 / 选中」两态，保证和刷题页手感一致。
 */
@Composable
private fun ExamOptionRow(
    question: Question,
    option: Option,
    selected: Boolean,
    onClick: () -> Unit,
) {
    QuizOptionCard(
        optionKey = option.key,
        text = option.text,
        state = if (selected) OptionCardState.SELECTED else OptionCardState.DEFAULT,
        onClick = onClick,
        showKey = question.type != "judge",
        centerText = question.type == "judge",
    )
}

/**
 * 答题卡弹窗：10 列 × N 行的格子。
 * 已答 = 主色底 + 反色字，未答 = 中性填充，当前题 = 深色描边。
 */
@Composable
private fun AnswerSheetDialog(
    total: Int,
    currentPosition: Int,
    isAnswered: (Int) -> Boolean,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = appColors()
    val rows = ((total + 9) / 10).coerceAtLeast(1)
    // 格子高 × 行数 + 行间距 × (行数 - 1)，全部用令牌拼，不写裸 dp
    val gridHeight = ComponentSize.answerCell * rows + Spacing.xs * (rows - 1)

    FullScreenDialog(title = "答题卡", onDismiss = onDismiss, scrollable = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "已答 ${(0 until total).count { isAnswered(it) }}/$total",
                        style = AppText.caption,
                        color = c.textSecondary,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))

                // 图例：三种状态一眼对上
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SheetLegendBlock(c.primary, "已答")
                    Spacer(Modifier.width(Spacing.md))
                    SheetLegendBlock(c.fill, "未答")
                    Spacer(Modifier.width(Spacing.md))
                    Box(
                        modifier = Modifier
                            .size(ComponentSize.legendSwatch)
                            .background(c.card, Radius.labelShape)
                            .border(ComponentSize.strokeStrong, c.primaryDeep, Radius.labelShape)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("当前题", style = AppText.caption, color = c.textSecondary)
                }
                Spacer(Modifier.height(Spacing.sm))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(10),
                    modifier = Modifier.fillMaxWidth().height(gridHeight),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(count = total) { position ->
                        val answered = isAnswered(position)
                        val isCurrent = position == currentPosition
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ComponentSize.answerCell)
                                .clip(Radius.labelShape)
                                .background(if (answered) c.primary else c.fill)
                                .then(
                                    if (isCurrent) {
                                        Modifier.border(ComponentSize.strokeStrong, c.primaryDeep, Radius.labelShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onJump(position) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${position + 1}",
                                style = if (isCurrent) AppText.captionStrong else AppText.caption,
                                color = if (answered) c.onAccent else c.textSecondary,
                            )
                        }
                    }
                }

        Spacer(Modifier.height(Spacing.md))
        AppButton(text = "继续答题", onClick = onDismiss)
    }
}

/** 答题卡图例里的一个小色块 + 文字 */
@Composable
private fun SheetLegendBlock(color: Color, label: String) {
    Box(
        modifier = Modifier
            .size(ComponentSize.legendSwatch)
            .background(color, Radius.labelShape)
    )
    Spacer(Modifier.width(Spacing.xs))
    Text(label, style = AppText.caption, color = appColors().textSecondary)
}

// ============================ 三、结果页 ============================

/**
 * 交卷结果：大字总分、及格判定、统计行、无答案提示、逐题对错列表、返回主页。
 * 这个页面只是显示 ExamState.finish() 算好的 ExamResult，不重新判分。
 */
@Composable
private fun ExamResultScreen(result: ExamResult, onBackHome: () -> Unit) {
    val c = appColors()

    // ===== 动效 7：分数从 0 滚到实际分（600ms）=====
    // 初始目标钉在 0，组合完再放开到 result.score，animateIntAsState 就会一路滚上去。
    // 系统关掉动画时直接给实际分：不会先闪一帧 0，也不会滚。
    val motionOn = motionEnabled()
    var scoreTarget by remember(result.score) {
        mutableIntStateOf(if (motionOn) 0 else result.score)
    }
    val animatedScore by animateIntAsState(
        targetValue = scoreTarget,
        animationSpec = motionTween(Motion.SLOW),
        label = "交卷分数滚动",
    )
    LaunchedEffect(result.score) {
        if (motionOn) scoreTarget = result.score
    }

    // 及格/不及格的判定不依赖动画：颜色和文字立刻按 result.passed 显示，不等分数滚完
    val passColor = if (result.passed) c.success else c.error

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "考试结果",
                style = AppText.pageTitle,
                color = c.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            if (result.autoSubmitted) {
                Text("时间到，已自动交卷", style = AppText.caption, color = c.error)
            }
        }
        HorizontalDivider(color = c.border, thickness = ComponentSize.stroke)

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.lg,
                bottom = Spacing.lg,
            ),
        ) {
            // 大字总分 + 及格/不及格
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        // 滚动中的分数；动画结束时正好等于 result.score
                        text = "$animatedScore",
                        style = AppText.display,
                        color = passColor,
                    )
                    Text(
                        text = " 分",
                        style = AppText.stem,
                        color = c.textSecondary,
                        modifier = Modifier.padding(bottom = Spacing.xs),
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = passColor,
                        shape = Radius.labelShape,
                    ) {
                        Text(
                            text = if (result.passed) "及格" else "不及格",
                            modifier = Modifier.padding(
                                horizontal = Spacing.md,
                                vertical = Spacing.sm,
                            ),
                            style = AppText.stemStrong,
                            color = c.onAccent,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "满分 ${result.fullScore} 分 · ${result.passLine} 分及格",
                    style = AppText.caption,
                    color = c.textSecondary,
                )
            }

            // 统计行：对 / 错 / 未答（另有「无答案」时单独一行）
            item {
                Spacer(Modifier.height(Spacing.lg))
                SectionCard(outlined = true) {
                    Text(
                        text = "对 ${result.correctCount} 题 / 错 ${result.wrongCount} 题 / 未答 ${result.unansweredCount} 题",
                        style = AppText.body,
                        color = c.textPrimary,
                    )
                    if (result.noAnswerCount > 0) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = "另有 ${result.noAnswerCount} 题暂无答案，不计入总分",
                            style = AppText.caption,
                            color = c.warning,
                        )
                    }
                }
            }

            // 无答案题不计分，交卷页必须提示，并把满分说清楚
            if (result.noAnswerCount > 0) {
                item {
                    Spacer(Modifier.height(Spacing.sm))
                    WarningBlock(
                        "本题库有 ${result.noAnswerCount} 题暂无答案，已从总分中剔除，满分按 ${result.fullScore} 分计",
                    )
                }
            }

            item {
                Spacer(Modifier.height(Spacing.xl))
                Text(
                    text = "逐题对错（${result.items.size} 题）",
                    style = AppText.bodyStrong,
                    color = c.textPrimary,
                )
                Spacer(Modifier.height(Spacing.sm))
            }

            // 逐题对错：题号、题型、你的答案、正确答案、✅/❌
            items(result.items, key = { it.order }) { item ->
                ExamResultRow(item)
                Spacer(Modifier.height(Spacing.sm))
            }
        }

        // 底部固定的「返回主页」
        AppButton(
            text = "返回主页",
            onClick = onBackHome,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        )
    }
}

/** 结果页的一行：第几题 + 题型 + 对错标记，下面一行「你的答案 / 正确答案」 */
@Composable
private fun ExamResultRow(item: ExamItem) {
    val c = appColors()
    val question = item.question
    val mark = when (item.grade) {
        GradeResult.CORRECT -> "✅"
        GradeResult.WRONG -> "❌"
        GradeResult.NO_ANSWER -> "⚠️"
    }
    // 行边框用「软色」：浅色下是浅绿/浅红/浅橙，深色下是对应的深色，不会扎眼
    val borderColor = when (item.grade) {
        GradeResult.CORRECT -> c.successSoft
        GradeResult.WRONG -> c.errorSoft
        GradeResult.NO_ANSWER -> c.warningSoft
    }

    Surface(
        color = c.card,
        shape = Radius.buttonShape,
        border = BorderStroke(ComponentSize.strokeStrong, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${item.order} 题",
                    style = AppText.captionStrong,
                    color = c.textPrimary,
                )
                Spacer(Modifier.width(Spacing.sm))
                Surface(color = c.primarySoft, shape = Radius.labelShape) {
                    Text(
                        text = question.typeName,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        style = AppText.caption,
                        color = c.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(text = mark, style = AppText.body)
            }
            Spacer(Modifier.height(Spacing.xs))
            if (item.grade == GradeResult.NO_ANSWER) {
                // 无答案题：不判对错，明确写「不计分」
                Text(
                    text = "本题暂无答案，不计入总分，也不算错题。",
                    style = AppText.caption,
                    color = c.warning,
                )
            } else {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "你的答案：${Grading.selectedText(question, item.selected)}",
                            style = AppText.caption,
                            color = if (item.grade == GradeResult.CORRECT) c.success else c.error,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "正确答案：${Grading.answerText(question)}",
                            style = AppText.caption,
                            color = c.success,
                        )
                    }
                }
            }
        }
    }
}
