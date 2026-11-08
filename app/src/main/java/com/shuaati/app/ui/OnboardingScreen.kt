// 界面层：引导页（11 起改成「增量引导」）。
//
// 为什么要改：老师是**覆盖升级**装新版本的，不是卸载重装，`onboarded` 早就是 true。
// 如果引导只认「首次启动」，后加的屏老师永远看不到。所以现在只播**没看过的屏**：
//   1. 每屏一个稳定 id（见 data/AppSettings.kt 的 OnboardingIds）；
//   2. SharedPreferences 存 seenOnboardingIds（逗号拼接）；
//   3. 启动时算「全部屏 - 已看过」，为空就直接进主界面；不为空就只播这几屏；
//   4. 播完 / 点跳过：把这次播过的 id 全部写进 seenOnboardingIds，同时 onboarded=true；
//   5. 老用户迁移：onboarded=true 且没有 seenOnboardingIds → 把 v1.1 的 7 个 id 全标已看，
//      只留 `source` 未看（所以老师升级后只会看到「选择题库来源」这一屏）。
//
// 单屏模式：只剩 1 屏未看时不显示进度点，只显示内容 + 一个「知道了」按钮，
// 避免老用户升级看到「1/1」这种奇怪的进度。
//
// 样式规矩（06A）不变：颜色只用 appColors()，字号只用 AppText.*，间距只用 Spacing.*，
// 按钮用 AppButton，分组用 SectionCard，开关行用 ToggleRow。
package com.shuaati.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import com.shuaati.app.BuildConfig
import com.shuaati.app.data.AppSettings
import com.shuaati.app.data.OnboardingIds
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.RadioRow
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.components.ToggleRow
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Motion
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import com.shuaati.app.ui.theme.motionSpring
import com.shuaati.app.ui.theme.motionTween

/** 一屏内容的种类（与 id 分开：id 记录「看过没有」，种类只决定长什么样） */
private enum class PageKind {
    WELCOME,
    SOURCE,
    FEATURE,
    STATS,
    START_PAGE,
}

/**
 * 一屏引导。
 * @param id 稳定 id（见 OnboardingIds），**不许改**
 * @param kind 内容种类
 * @param badge 图标区画什么
 * @param title / @param line1 / @param line2 仅 FEATURE 用
 */
private data class OnboardingPage(
    val id: String,
    val kind: PageKind,
    val badge: BadgeKind = BadgeKind.UNIFIED,
    val title: String = "",
    val line1: String = "",
    val line2: String = "",
)

/**
 * 全部屏，顺序就是播放顺序（任务书第 6 条第 1 点给的 id 顺序）。
 * 新增屏往后排，id 加进 OnboardingIds.ALL；这里只负责「长什么样」。
 */
private val ALL_PAGES: List<OnboardingPage> = listOf(
    OnboardingPage(id = OnboardingIds.WELCOME, kind = PageKind.WELCOME, badge = BadgeKind.WELCOME),
    OnboardingPage(id = OnboardingIds.SOURCE, kind = PageKind.SOURCE, badge = BadgeKind.SOURCE),
    OnboardingPage(
        id = OnboardingIds.FEATURE_QUIZ,
        kind = PageKind.FEATURE,
        badge = BadgeKind.UNIFIED,
        title = "统一刷",
        line1 = "全部题目随机出，一轮不重复。",
        line2 = "点了就判对错，答错马上给正确答案。",
    ),
    OnboardingPage(
        id = OnboardingIds.FEATURE_WRONG,
        kind = PageKind.FEATURE,
        badge = BadgeKind.WRONG_BOOK,
        title = "错题本",
        line1 = "答错的题自动收进来，专门重练。",
        line2 = "连续答对 3 次自动移出，错题越刷越少。",
    ),
    OnboardingPage(
        id = OnboardingIds.FEATURE_EXAM,
        kind = PageKind.FEATURE,
        badge = BadgeKind.EXAM,
        title = "模拟考试",
        line1 = "100 题、90 分钟，按真考节奏走。",
        line2 = "交卷立刻出分，考前先摸底。",
    ),
    OnboardingPage(
        id = OnboardingIds.FEATURE_SMART,
        kind = PageKind.FEATURE,
        badge = BadgeKind.SMART,
        title = "智能刷",
        line1 = "越不会的题越常出现，专挑薄弱点。",
        line2 = "20 题一组，刷完给本组小结。",
    ),
    OnboardingPage(id = OnboardingIds.STATS, kind = PageKind.STATS, badge = BadgeKind.HOME),
    OnboardingPage(id = OnboardingIds.START_PAGE, kind = PageKind.START_PAGE, badge = BadgeKind.START),
)

/** 每一屏图标区画什么 */
private enum class BadgeKind {
    /** 欢迎屏：大圆底 + 放大的「刷」字 */
    WELCOME,

    /** 选择题库来源：云 + 索引横线 */
    SOURCE,

    /** 统一刷：题卡列表（三行） */
    UNIFIED,

    /** 错题本：打开的书 */
    WRONG_BOOK,

    /** 模拟考试：时钟 */
    EXAM,

    /** 智能刷：往上走的折线 + 箭头 */
    SMART,

    /** 默认启动页：向右的箭头（「从这一页开始」） */
    START,

    /** 首页显示：四宫格 */
    HOME,
}

/**
 * 增量引导页。
 *
 * @param unseenIds 这次要播的屏 id（调用方传「全部 - 已看过」；顺序由本文件决定）
 * @param paperName 当前试卷名（从这套试卷的 meta.json 读，不写死）
 * @param questionCount 当前试卷题数
 * @param settings 设置读写器；统计开关与启动页只有在播到对应屏时才会被写回
 * @param currentSourceName 当前源名字（选择题库来源那屏显示一行「当前源：……」）
 * @param onFinished 引导结束（走完或跳过）后回调；此时 seenOnboardingIds 已写盘
 * @param onUseDefaultSource 点「使用默认源」→ 由外层拉一次索引并提示「已获取 N 套试卷」
 * @param onConfigureSource 点「配置自己的 WebDAV」→ 由外层打开新建源弹窗
 * @param onSkipSource 点「稍后再说」→ 由外层只做提示，随后照样进主界面
 */
@Composable
fun OnboardingScreen(
    unseenIds: Set<String>,
    paperName: String,
    questionCount: Int,
    settings: AppSettings,
    currentSourceName: String,
    onFinished: () -> Unit,
    onUseDefaultSource: () -> Unit,
    onConfigureSource: () -> Unit,
    onSkipSource: () -> Unit,
) {
    val c = appColors()

    // 只播没看过的屏，按 ALL_PAGES 的原顺序
    val pages = remember(unseenIds) { ALL_PAGES.filter { it.id in unseenIds } }
    if (pages.isEmpty()) {
        // 理论上不会走到这里（外层会直接进主界面），兜底：立刻结束，不留白屏
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    val single = pages.size == 1

    // 当前播到第几屏（相对 pages 的下标）
    var index by remember(pages) { mutableStateOf(0) }

    // 统计开关与启动页的临时状态：先取设置里的当前值，只有播到那屏并点「开始使用」才写回
    var startTab by remember { mutableStateOf(settings.defaultTab) }
    var showRing by remember { mutableStateOf(settings.showRing) }
    var showTiles by remember { mutableStateOf(settings.showTiles) }
    var showTypeRings by remember { mutableStateOf(settings.showTypeRings) }
    var showStreak by remember { mutableStateOf(settings.showStreak) }
    var showImport by remember { mutableStateOf(settings.showImport) }

    /**
     * 结束引导：写 seen + 兼容 onboarded。
     * @param applySettings true = 点的是「开始使用 / 知道了」，把这次播到的屏里的选择写回设置；
     *                      false = 点的是「跳过」，设置保持原样（v1.1 的老行为）。
     */
    fun finish(applySettings: Boolean) {
        if (applySettings) {
            if (pages.any { it.id == OnboardingIds.START_PAGE }) {
                settings.updateDefaultTab(startTab)
            }
            if (pages.any { it.id == OnboardingIds.STATS }) {
                settings.updateShowRing(showRing)
                settings.updateShowTiles(showTiles)
                settings.updateShowTypeRings(showTypeRings)
                settings.updateShowStreak(showStreak)
                settings.updateShowImport(showImport)
            }
        }
        settings.finishOnboarding(pages.map { it.id })
        onFinished()
    }

    /** 下一个：已经是最后一屏就结束 */
    fun goNext() {
        if (index >= pages.lastIndex) finish(true) else index++
    }

    // 返回键：不在第一屏时退到上一屏，第一屏交给系统（退出 App）
    BackHandler(enabled = index > 0) { index-- }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        // ===== 内容区：一屏放得下时垂直居中；小屏 / 大字体时改为滚动 =====
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(Spacing.xxl))

                // ===== 切屏滑动 + 淡入淡出（250ms）=====
                val onboardSlide = motionTween<IntOffset>(Motion.NORMAL)
                val onboardFade = motionTween<Float>(Motion.NORMAL)
                AnimatedContent(
                    targetState = index,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val shift = Motion.ONBOARD_SLIDE_DIVISOR
                        if (forward) {
                            (slideInHorizontally(onboardSlide) { it / shift } + fadeIn(onboardFade)) togetherWith
                                (slideOutHorizontally(onboardSlide) { -it / shift } + fadeOut(onboardFade))
                        } else {
                            (slideInHorizontally(onboardSlide) { -it / shift } + fadeIn(onboardFade)) togetherWith
                                (slideOutHorizontally(onboardSlide) { it / shift } + fadeOut(onboardFade))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                    label = "引导页切屏",
                ) { shownIndex ->
                    val page = pages.getOrNull(shownIndex) ?: pages.first()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (page.kind) {
                            PageKind.WELCOME -> WelcomePage(
                                paperName = paperName,
                                questionCount = questionCount,
                            )

                            PageKind.SOURCE -> SourcePage(
                                currentSourceName = currentSourceName,
                                onUseDefault = {
                                    onUseDefaultSource()
                                    goNext()
                                },
                                onConfigure = { onConfigureSource() },
                                onLater = {
                                    onSkipSource()
                                    finish(false)
                                },
                            )

                            PageKind.FEATURE -> FeaturePage(
                                kind = page.badge,
                                title = page.title,
                                line1 = page.line1,
                                line2 = page.line2,
                            )

                            PageKind.START_PAGE -> StartTabPage(
                                selected = startTab,
                                onSelect = { startTab = it },
                            )

                            PageKind.STATS -> HomeDisplayPage(
                                showRing = showRing,
                                showTiles = showTiles,
                                showTypeRings = showTypeRings,
                                showStreak = showStreak,
                                showImport = showImport,
                                onRingChange = { showRing = it },
                                onTilesChange = { showTiles = it },
                                onTypeRingsChange = { showTypeRings = it },
                                onStreakChange = { showStreak = it },
                                onImportChange = { showImport = it },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.xxl))
            }
        }

        // ===== 底部：进度点（单屏模式不显示）+ 跳过 + 主按钮 =====
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl)) {
            if (!single) {
                PageDots(current = index, total = pages.size)
                Spacer(Modifier.height(Spacing.lg))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!single) {
                    AppButton(
                        text = "跳过",
                        onClick = { finish(false) },
                        style = AppButtonStyle.TEXT,
                        modifier = Modifier.weight(1f),
                    )
                }
                AppButton(
                    text = when {
                        single -> "知道了"
                        index == pages.lastIndex -> "开始使用"
                        else -> "下一步"
                    },
                    onClick = { goNext() },
                    modifier = Modifier.weight(if (single) 1f else 2f),
                )
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

/** 欢迎屏：试卷名与题数都来自当前试卷的 meta.json */
@Composable
private fun WelcomePage(paperName: String, questionCount: Int) {
    val c = appColors()

    Badge(kind = BadgeKind.WELCOME)
    Spacer(Modifier.height(Spacing.xl))
    Text(text = "刷题", style = AppText.display, color = c.textPrimary)
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = "$paperName · 共 $questionCount 题",
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.lg))
    Text(
        text = "先花一分钟看看它能帮你做什么。",
        style = AppText.small,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 选择题库来源（11 新增）。
 * 三个出口（任务书第 6 条第 7 点）：
 *   A 使用默认源 —— 不显示任何地址；点了由外层去拉索引，成功提示「已获取 N 套试卷」；
 *   B 配置自己的 WebDAV —— 打开新建源弹窗（复用源管理页那一个）；
 *   C 稍后再说 —— 进主界面（首页仍可用本地导入的试卷）。
 */
@Composable
private fun SourcePage(
    currentSourceName: String,
    onUseDefault: () -> Unit,
    onConfigure: () -> Unit,
    onLater: () -> Unit,
) {
    val c = appColors()

    Badge(kind = BadgeKind.SOURCE)
    Spacer(Modifier.height(Spacing.xl))
    Text(text = "选择题库来源", style = AppText.pageTitle, color = c.textPrimary)
    Spacer(Modifier.height(Spacing.md))
    Text(
        text = "题目都放在 WebDAV 上，选一个源就能取题。",
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.xs))
    // 没有内置源时不显示「默认源」相关文案与按钮
    if (BuildConfig.HAS_BUILTIN_SOURCE) {
        Text(
            text = "默认源已经配好，点一下就能用。",
            style = AppText.body,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            text = "先在下面填一个自己有的 WebDAV 源，也可以稍后到「设置 → 题库源」里配。",
            style = AppText.body,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(Spacing.lg))

    if (BuildConfig.HAS_BUILTIN_SOURCE) {
        AppButton(
            text = "使用默认源",
            onClick = onUseDefault,
        )
        Spacer(Modifier.height(Spacing.sm))
    }
    AppButton(
        text = "配置自己的 WebDAV",
        onClick = onConfigure,
        style = AppButtonStyle.SECONDARY,
    )
    Spacer(Modifier.height(Spacing.sm))
    AppButton(
        text = "稍后再说",
        onClick = onLater,
        style = AppButtonStyle.TEXT,
    )

    Spacer(Modifier.height(Spacing.md))
    if (currentSourceName.isNotBlank()) {
        Text(
            text = "当前源：$currentSourceName",
            style = AppText.caption,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.xs))
    }
    Text(
        text = "选「稍后再说」会先进入主界面，之后随时可以在『设置 → 题库源』里配置。",
        style = AppText.caption,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 功能屏：一个图标区 + 标题 + 两行说明 */
@Composable
private fun FeaturePage(
    kind: BadgeKind,
    title: String,
    line1: String,
    line2: String,
) {
    val c = appColors()

    Badge(kind = kind)
    Spacer(Modifier.height(Spacing.xl))
    Text(text = title, style = AppText.pageTitle, color = c.textPrimary)
    Spacer(Modifier.height(Spacing.md))
    Text(
        text = line1,
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = line2,
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 默认启动页（07A 新增）。
 * 只问一件事：打开 App 先看哪一页。两个选项与设置页「启动页」完全一致，同一套存储键。
 */
@Composable
private fun StartTabPage(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val c = appColors()

    Badge(kind = BadgeKind.START)
    Spacer(Modifier.height(Spacing.xl))
    Text(text = "从哪一页开始", style = AppText.pageTitle, color = c.textPrimary)
    Spacer(Modifier.height(Spacing.md))
    Text(
        text = "打开 App 后先看到哪一页。",
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = "选完随时能改，不用重装。",
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.lg))

    SectionCard {
        RadioRow(
            title = "首页（先看进度统计）",
            selected = selected == AppSettings.TAB_HOME,
            onClick = { onSelect(AppSettings.TAB_HOME) },
        )
        RadioRow(
            title = "刷题（直接开始做题）",
            selected = selected == AppSettings.TAB_QUIZ,
            onClick = { onSelect(AppSettings.TAB_QUIZ) },
        )
    }

    Spacer(Modifier.height(Spacing.md))
    Text(
        text = "以后可以在『设置 → 启动页』里改",
        style = AppText.caption,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 首页显示哪些统计块。
 * 5 个开关与设置页「首页显示」一一对应（同一个存储键），以后在设置里也能改。
 */
@Composable
private fun HomeDisplayPage(
    showRing: Boolean,
    showTiles: Boolean,
    showTypeRings: Boolean,
    showStreak: Boolean,
    showImport: Boolean,
    onRingChange: (Boolean) -> Unit,
    onTilesChange: (Boolean) -> Unit,
    onTypeRingsChange: (Boolean) -> Unit,
    onStreakChange: (Boolean) -> Unit,
    onImportChange: (Boolean) -> Unit,
) {
    val c = appColors()

    Badge(kind = BadgeKind.HOME)
    Spacer(Modifier.height(Spacing.xl))
    Text(text = "首页显示", style = AppText.pageTitle, color = c.textPrimary)
    Spacer(Modifier.height(Spacing.md))
    Text(
        text = "首页会显示这些统计块。",
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = "不需要的可以先关掉。",
        style = AppText.body,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.lg))

    SectionCard {
        ToggleRow(
            title = "大环形进度",
            hint = "本轮刷到哪了",
            checked = showRing,
            onCheckedChange = onRingChange,
        )
        ToggleRow(
            title = "四宫格统计",
            hint = "累计做题、正确率、错题、已掌握",
            checked = showTiles,
            onCheckedChange = onTilesChange,
        )
        ToggleRow(
            title = "分题型小环",
            hint = "单选、判断、多选各自进度",
            checked = showTypeRings,
            onCheckedChange = onTypeRingsChange,
        )
        ToggleRow(
            title = "连续打卡",
            hint = "连续几天在刷题",
            checked = showStreak,
            onCheckedChange = onStreakChange,
        )
        ToggleRow(
            title = "导入新试卷按钮",
            hint = "首页底部的导入入口",
            checked = showImport,
            onCheckedChange = onImportChange,
        )
    }

    Spacer(Modifier.height(Spacing.md))
    Text(
        text = "以后可以在『设置 → 首页显示』里改",
        style = AppText.caption,
        color = c.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 大大的圆形图标区：蓝底 + 白色图形（欢迎屏是放大的「刷」字） */
@Composable
private fun Badge(kind: BadgeKind) {
    val c = appColors()

    Box(
        modifier = Modifier
            .size(ComponentSize.onboardingBadge)
            .clip(CircleShape)
            .background(c.primary),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == BadgeKind.WELCOME) {
            Text(
                text = "刷",
                // 用现有最大字号再整体放大，避免在页面里手写 fontSize
                style = AppText.display,
                color = c.onAccent,
                modifier = Modifier.scale(1.7f),
            )
        } else {
            BadgeGlyph(kind = kind)
        }
    }
}

/**
 * 图标区里的白色几何图形。
 * 全部按画布尺寸的比例画，不写死 dp，换屏幕尺寸也不会变形。
 */
@Composable
private fun BadgeGlyph(kind: BadgeKind) {
    val c = appColors()
    val ink = c.onAccent

    Canvas(modifier = Modifier.fillMaxSize(0.54f)) {
        val w = size.width
        val h = size.height
        val line = w * 0.085f

        when (kind) {
            // 选择题库来源：一朵云 + 下面两条横线（「题库在云端」）
            BadgeKind.SOURCE -> {
                val radius = w * 0.16f
                drawCircle(color = ink, radius = radius, center = Offset(w * 0.30f, h * 0.42f))
                drawCircle(color = ink, radius = radius * 1.25f, center = Offset(w * 0.50f, h * 0.34f))
                drawCircle(color = ink, radius = radius, center = Offset(w * 0.70f, h * 0.42f))
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(w * 0.30f, h * 0.42f),
                    size = Size(w * 0.40f, h * 0.18f),
                    cornerRadius = CornerRadius(radius),
                )
                drawLine(
                    color = ink,
                    start = Offset(w * 0.26f, h * 0.74f),
                    end = Offset(w * 0.74f, h * 0.74f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ink,
                    start = Offset(w * 0.36f, h * 0.88f),
                    end = Offset(w * 0.64f, h * 0.88f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
            }

            // 统一刷：题卡列表（左侧圆点 + 三行长短不一的横线）
            BadgeKind.UNIFIED -> {
                val dot = w * 0.06f
                val rows = listOf(
                    0.26f to 0.90f,
                    0.50f to 0.76f,
                    0.74f to 0.84f,
                )
                rows.forEach { (fy, xEnd) ->
                    drawCircle(color = ink, radius = dot, center = Offset(w * 0.12f, h * fy))
                    drawLine(
                        color = ink,
                        start = Offset(w * 0.30f, h * fy),
                        end = Offset(w * xEnd, h * fy),
                        strokeWidth = line,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // 错题本：打开的书（左右两页各是一个四边形，中间留缝）
            BadgeKind.WRONG_BOOK -> {
                val leftPage = Path().apply {
                    moveTo(w * 0.10f, h * 0.22f)
                    lineTo(w * 0.47f, h * 0.32f)
                    lineTo(w * 0.47f, h * 0.84f)
                    lineTo(w * 0.10f, h * 0.72f)
                    close()
                }
                val rightPage = Path().apply {
                    moveTo(w * 0.90f, h * 0.22f)
                    lineTo(w * 0.53f, h * 0.32f)
                    lineTo(w * 0.53f, h * 0.84f)
                    lineTo(w * 0.90f, h * 0.72f)
                    close()
                }
                drawPath(
                    path = leftPage,
                    color = ink,
                    style = Stroke(width = line, join = StrokeJoin.Round),
                )
                drawPath(
                    path = rightPage,
                    color = ink,
                    style = Stroke(width = line, join = StrokeJoin.Round),
                )
            }

            // 模拟考试：时钟（圆环 + 时针分针 + 表轴）
            BadgeKind.EXAM -> {
                val radius = w * 0.40f
                drawCircle(color = ink, radius = radius, style = Stroke(width = line))
                drawLine(
                    color = ink,
                    start = center,
                    end = Offset(center.x, center.y - radius * 0.55f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ink,
                    start = center,
                    end = Offset(center.x + radius * 0.40f, center.y + radius * 0.18f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
                drawCircle(color = ink, radius = line * 0.9f, center = center)
            }

            // 智能刷：往上走的折线 + 箭头
            BadgeKind.SMART -> {
                val points = listOf(
                    Offset(w * 0.10f, h * 0.80f),
                    Offset(w * 0.38f, h * 0.58f),
                    Offset(w * 0.56f, h * 0.68f),
                    Offset(w * 0.86f, h * 0.24f),
                )
                for (index in 0 until points.size - 1) {
                    drawLine(
                        color = ink,
                        start = points[index],
                        end = points[index + 1],
                        strokeWidth = line,
                        cap = StrokeCap.Round,
                    )
                }
                drawLine(
                    color = ink,
                    start = points.last(),
                    end = Offset(w * 0.64f, h * 0.20f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ink,
                    start = points.last(),
                    end = Offset(w * 0.84f, h * 0.50f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
            }

            // 默认启动页：一个向右的箭头（「从这一页开始」）
            BadgeKind.START -> {
                drawLine(
                    color = ink,
                    start = Offset(w * 0.16f, h * 0.5f),
                    end = Offset(w * 0.84f, h * 0.5f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ink,
                    start = Offset(w * 0.56f, h * 0.24f),
                    end = Offset(w * 0.84f, h * 0.5f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ink,
                    start = Offset(w * 0.56f, h * 0.76f),
                    end = Offset(w * 0.84f, h * 0.5f),
                    strokeWidth = line,
                    cap = StrokeCap.Round,
                )
            }

            // 首页显示：四宫格
            BadgeKind.HOME -> {
                val gap = w * 0.12f
                val cell = (w - gap) / 2f
                val origins = listOf(
                    0f to 0f,
                    (cell + gap) to 0f,
                    0f to (cell + gap),
                    (cell + gap) to (cell + gap),
                )
                origins.forEach { (dx, dy) ->
                    drawRoundRect(
                        color = ink,
                        topLeft = Offset(dx, dy),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(line * 0.8f),
                        style = Stroke(width = line * 0.9f),
                    )
                }
            }

            BadgeKind.WELCOME -> Unit
        }
    }
}

/**
 * 底部进度点：当前屏用主色并放大 1.4 倍（缩放不占位，旁边的点不会被挤动）。
 * @param total 这次要播的屏数（不是 8 —— 增量引导可能只有 1~2 屏）
 */
@Composable
private fun PageDots(current: Int, total: Int) {
    val c = appColors()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            // 缩放和颜色都跟当前屏同步过渡：点不会「啪」一下跳过去
            val dotScale by animateFloatAsState(
                targetValue = if (active) Motion.DOT_SCALE else 1f,
                animationSpec = motionSpring(),
                label = "引导进度点缩放",
            )
            val dotColor by animateColorAsState(
                targetValue = if (active) c.primary else c.fill,
                animationSpec = motionTween(Motion.FAST),
                label = "引导进度点颜色",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = Spacing.xs)
                    // 基准始终是 8dp，放大只发生在绘制阶段（scale），不会改变布局
                    .size(Spacing.sm)
                    .scale(dotScale)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}
