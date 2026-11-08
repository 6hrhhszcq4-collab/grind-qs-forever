// 界面层：设置页（v1.3 重做）。
//
// 页面按任务书第 6 条分成三组，从上到下：
//   1.「题库」：当前试卷（名称、题数、来源）、切换试卷、检查更新、
//      启动时检查试卷更新开关、题库源入口、导入试卷；
//   2.「显示」：首页 5 个显示开关、默认打开页、重看引导；
//   3.「关于」：版本号一行（连点 7 次打开调试入口）、调试块（仅调试模式打开后出现）、
//      关于本应用入口。
//
// 每组一个标题 + 一张分组卡片；组与组之间用一条分割线隔开。
//
// 分工：
//   - 网络请求一律不在这里发：检查更新、切换试卷、导入试卷都只回调，由外层（MainActivity）处理；
//   - 导出诊断是本地写文件，沿用 Diagnostics.export()，仍然放后台线程；
//   - 调试密码常量在 AboutScreen.kt（同包可见），本文件只引用，界面上不出现任何提示。
package com.shuaati.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.shuaati.app.data.AppSettings
import com.shuaati.app.data.AppUpdate
import com.shuaati.app.data.AppUpdateChecker
import com.shuaati.app.data.Diagnostics
import com.shuaati.app.data.PaperMeta
import com.shuaati.app.data.PaperRepo
import com.shuaati.app.data.SourceConfig
import com.shuaati.app.data.SourceStore
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.RadioRow
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.components.Tag
import com.shuaati.app.ui.components.TagTone
import com.shuaati.app.ui.components.ToggleRow
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 连点间隔上限（毫秒）：两次点击超过这个时间就重新计数 */
private const val VERSION_TAP_INTERVAL_MS = 800L

/** 连续点几下版本号打开密码框 */
private const val VERSION_TAP_COUNT_NEEDED = 7

/** 密码最多允许输错几次 */
private const val DEBUG_PASSWORD_MAX_ATTEMPTS = 3

/**
 * 设置页。
 *
 * @param settings 设置读写器；改了就落盘，首页立刻跟着变
 * @param papers 试卷库列表（外层用 PaperRepo.list() 读出来）
 * @param currentPaperId 当前正在用的试卷；找不到时按「还没选试卷」显示
 * @param currentSourceName 当前题库源的名字（空字符串 = 还没选）
 * @param appVersion 当前应用版本号，显示在「关于」组
 * @param checkingUpdate 外层是否正在检查更新（true 时「检查更新」显示「正在检查…」并禁用）
 * @param onSwitchPaper 点「切换试卷」→ 由外层弹出试卷选择
 * @param onCheckUpdate 点「检查更新」→ 由外层做网络检查
 * @param onImportPaper 点「导入试卷」→ 由外层弹「本地 / 网络」二选一
 * @param onOpenSources 点「题库源」行 → 进入源管理页
 * @param onReplayOnboarding 点「重看引导」→ 清空记录（下次启动重播）
 * @param onOpenAbout 点「关于本应用」行 → 进入关于页
 * @param sourceStore 题库源存储（把试卷的来源翻译成源名、调试块里读当前源）
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    papers: List<PaperMeta>,
    currentPaperId: String?,
    currentSourceName: String,
    appVersion: String,
    checkingUpdate: Boolean,
    onSwitchPaper: () -> Unit,
    onCheckUpdate: () -> Unit,
    onImportPaper: () -> Unit,
    onOpenSources: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDebug: () -> Unit,
    sourceStore: SourceStore,
) {
    val c = appColors()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    // ===== 题库源列表：把试卷的来源翻译成源名（源管理页改完回来，按当前源名重读一次）=====
    var sources by remember { mutableStateOf<List<SourceConfig>>(emptyList()) }
    LaunchedEffect(sourceStore, currentSourceName) {
        sources = withContext(Dispatchers.IO) { sourceStore.load().sources }
    }

    // 当前试卷：只按外层传进来的编号找；找不到就按「还没选试卷」显示，不在这里自行兜底切卷
    val currentPaper = papers.firstOrNull { it.id == currentPaperId }

    // ===== 调试块：只在调试模式打开时读一次路径（后台线程）=====
    var debugInfo by remember { mutableStateOf<DebugSnapshot?>(null) }
    var exporting by remember { mutableStateOf(false) }

    // ===== v1.5：应用更新检查（GitHub Releases）=====
    var checkingAppUpdate by remember { mutableStateOf(false) }
    var appUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(settings.debugEnabled, currentSourceName, sourceStore) {
        debugInfo = if (settings.debugEnabled) {
            withContext(Dispatchers.IO) { buildDebugSnapshot(appContext, sourceStore) }
        } else {
            null
        }
    }

    // ===== 版本号连点：7 次、每次间隔 < 800ms，超时重新计数 =====
    var taps by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var attempts by remember { mutableStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showWrongDialog by remember { mutableStateOf(false) }

    fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        taps = if (now - lastTapAt < VERSION_TAP_INTERVAL_MS) taps + 1 else 1
        lastTapAt = now
        if (taps >= VERSION_TAP_COUNT_NEEDED) {
            taps = 0
            password = ""
            attempts = 0
            errorText = null
            showPassword = true
        }
    }

    /** 导出诊断信息：本地写文件，仍放后台线程 */
    fun exportDiagnostics() {
        exporting = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    Diagnostics.export(appContext, PaperRepo(appContext), sourceStore)
                }
            }
            exporting = false
            result.onSuccess { file ->
                Toast.makeText(
                    appContext,
                    "诊断信息已导出：${file.absolutePath}",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                Toast.makeText(
                    appContext,
                    "导出失败：${it.message ?: "未知错误"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** v1.5：检查应用更新（GitHub Releases）；有更新弹下载框，没有就 Toast「已是最新」 */
    fun checkAppUpdate() {
        if (checkingAppUpdate) return
        checkingAppUpdate = true
        scope.launch {
            val result = runCatching { AppUpdateChecker.check() }
            checkingAppUpdate = false
            result.onSuccess { update ->
                if (update == null) {
                    Toast.makeText(appContext, "已是最新版", Toast.LENGTH_SHORT).show()
                } else {
                    appUpdate = update
                }
            }.onFailure {
                Toast.makeText(appContext, "检查失败：${it.message ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.xl))
        Text(text = "设置", style = AppText.pageTitle, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.xl))

        // ===== 第一组：题库 =====
        GroupTitle(text = "题库")
        Spacer(Modifier.height(Spacing.md))
        SectionCard {
            // ---- 当前试卷：名字 + 「共 N 题」+ 来源 ----
            Text(text = "当前试卷", style = AppText.caption, color = c.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            if (currentPaper == null) {
                Text(
                    text = "还没有可用的试卷。点下面的「导入试卷」，或者到「题库源」里下载一套。",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            } else {
                Text(
                    text = currentPaper.name,
                    style = AppText.bodyStrong,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "共 ${currentPaper.total} 题",
                        style = AppText.caption,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.size(Spacing.sm))
                    Tag(
                        text = sourceLabelOf(currentPaper, sources),
                        tone = if (currentPaper.isLocal) TagTone.WARNING else TagTone.PRIMARY,
                    )
                }
            }

            CardDivider()

            // ---- 切换试卷：整行可点 ----
            ActionRow(
                title = "切换试卷",
                subtitle = "从已有的试卷里挑一套",
                onClick = onSwitchPaper,
            )

            CardDivider()

            // ---- 检查更新：检查中显示「正在检查…」并禁用 ----
            ActionRow(
                title = "检查更新",
                subtitle = if (checkingUpdate) "正在检查…" else "看看题库源上有没有新题库",
                enabled = !checkingUpdate,
                onClick = onCheckUpdate,
            )

            CardDivider()

            // ---- 启动时检查试卷更新（默认关闭）----
            ToggleRow(
                title = "启动时检查试卷更新",
                checked = settings.startupCheckUpdates,
                onCheckedChange = { settings.updateStartupCheckUpdates(it) },
                hint = "打开后每次启动会自动看看有没有新题库，只提示，不自动下载。",
            )

            CardDivider()

            // ---- 题库源：整行可点，进源管理页 ----
            ActionRow(
                title = "题库源",
                subtitle = "当前：${currentSourceName.ifBlank { "（未选择）" }}",
                onClick = onOpenSources,
            )

            Spacer(Modifier.height(Spacing.md))
            AppButton(
                text = "导入试卷",
                onClick = onImportPaper,
                style = AppButtonStyle.SECONDARY,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "可以从手机里的文件导入，也可以从题库源下载一套。",
                style = AppText.caption,
                color = c.textSecondary,
            )
        }

        Spacer(Modifier.height(Spacing.lg))
        HorizontalDivider(color = c.border)
        Spacer(Modifier.height(Spacing.lg))

        // ===== 第二组：显示 =====
        GroupTitle(text = "显示")
        Spacer(Modifier.height(Spacing.md))
        SectionCard {
            ToggleRow(
                title = "本轮进度大环",
                checked = settings.showRing,
                onCheckedChange = { settings.updateShowRing(it) },
            )
            ToggleRow(
                title = "四宫格统计",
                checked = settings.showTiles,
                onCheckedChange = { settings.updateShowTiles(it) },
            )
            ToggleRow(
                title = "分题型小环",
                checked = settings.showTypeRings,
                onCheckedChange = { settings.updateShowTypeRings(it) },
            )
            ToggleRow(
                title = "连续刷题天数",
                checked = settings.showStreak,
                onCheckedChange = { settings.updateShowStreak(it) },
            )
            ToggleRow(
                title = "导入新试卷按钮",
                checked = settings.showImport,
                onCheckedChange = { settings.updateShowImport(it) },
                hint = "关掉的区块在首页整块消失，不留空位。",
            )

            CardDivider()

            // ---- 默认打开页 ----
            Text(text = "默认打开页", style = AppText.caption, color = c.textSecondary)
            Spacer(Modifier.height(Spacing.xs))
            RadioRow(
                title = "首页",
                selected = settings.defaultTab == AppSettings.TAB_HOME,
                onClick = { settings.updateDefaultTab(AppSettings.TAB_HOME) },
            )
            RadioRow(
                title = "刷题",
                selected = settings.defaultTab == AppSettings.TAB_QUIZ,
                onClick = { settings.updateDefaultTab(AppSettings.TAB_QUIZ) },
            )
            Text(
                text = "打开应用默认进哪一页，改完下次启动生效。",
                style = AppText.caption,
                color = c.textSecondary,
            )

            CardDivider()

            // ---- 重看引导 ----
            AppButton(
                text = "重看引导",
                onClick = onReplayOnboarding,
                style = AppButtonStyle.SECONDARY,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "清空已经看过的引导，下次启动重新播放一遍。",
                style = AppText.caption,
                color = c.textSecondary,
            )
        }

        Spacer(Modifier.height(Spacing.lg))
        HorizontalDivider(color = c.border)
        Spacer(Modifier.height(Spacing.lg))

        // ===== 第三组：关于 =====
        GroupTitle(text = "关于")
        Spacer(Modifier.height(Spacing.md))
        SectionCard {
            // ---- 调试块：只有调试模式打开后才出现（v1.5：调试入口移到关于页的版本号）----
            if (settings.debugEnabled) {
                Text(text = "调试信息", style = AppText.smallStrong, color = c.textPrimary)
                Spacer(Modifier.height(Spacing.xs))
                val info = debugInfo
                if (info == null) {
                    Text(
                        text = "正在读取调试信息…",
                        style = AppText.caption,
                        color = c.textSecondary,
                    )
                } else {
                    DebugInfoLine(label = "数据存放位置", value = info.filesDir)
                    DebugInfoLine(label = "当前题库源", value = info.currentSourceName)
                    DebugInfoLine(label = "试卷目录", value = info.papersDir)
                }
                Spacer(Modifier.height(Spacing.md))
                AppButton(
                    text = if (exporting) "正在导出…" else "导出诊断信息",
                    onClick = { exportDiagnostics() },
                    style = AppButtonStyle.SECONDARY,
                    enabled = !exporting,
                )
                Spacer(Modifier.height(Spacing.sm))
                AppButton(
                    text = "打开调试面板",
                    onClick = onOpenDebug,
                    style = AppButtonStyle.SECONDARY,
                )
                Spacer(Modifier.height(Spacing.sm))
                AppButton(
                    text = "关闭调试模式",
                    onClick = {
                        settings.updateDebugEnabled(false)
                        Toast.makeText(appContext, "调试模式已关闭", Toast.LENGTH_SHORT).show()
                    },
                    style = AppButtonStyle.TEXT,
                )
                CardDivider()
            }

            // ---- v1.5：检查应用更新（GitHub Releases）----
            ActionRow(
                title = "检查应用更新",
                subtitle = if (checkingAppUpdate) "正在检查…" else "看看 GitHub 上有没有新版本",
                enabled = !checkingAppUpdate,
                onClick = { checkAppUpdate() },
            )

            // ---- 关于本应用入口 ----
            ActionRow(
                title = "关于本应用",
                subtitle = "应用介绍、题库来源概况",
                onClick = onOpenAbout,
            )
        }

        Spacer(Modifier.height(Spacing.xxl))
    }

    // ===== 密码框：密码正确才打开调试模式 =====
    if (showPassword) {
        DebugPasswordDialog(
            password = password,
            errorText = errorText,
            onPasswordChange = {
                password = it
                errorText = null
            },
            onConfirm = {
                if (password.trim() == DEBUG_PASSWORD) {
                    settings.updateDebugEnabled(true)
                    showPassword = false
                    password = ""
                    attempts = 0
                    errorText = null
                    Toast.makeText(appContext, "调试模式已开启", Toast.LENGTH_SHORT).show()
                } else {
                    attempts++
                    if (attempts >= DEBUG_PASSWORD_MAX_ATTEMPTS) {
                        // 输错 3 次：关掉密码框，弹一句「密码错误」，不做锁定
                        showPassword = false
                        password = ""
                        attempts = 0
                        errorText = null
                        showWrongDialog = true
                    } else {
                        errorText =
                            "密码不对，还可以再试 ${DEBUG_PASSWORD_MAX_ATTEMPTS - attempts} 次"
                    }
                }
            },
            onDismiss = {
                showPassword = false
                password = ""
                attempts = 0
                errorText = null
            },
        )
    }

    // ===== 输错 3 次的提示 =====
    if (showWrongDialog) {
        WrongPasswordDialog(onDismiss = { showWrongDialog = false })
    }

    // ===== v1.5：发现新版本应用 =====
    appUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { appUpdate = null },
            title = { Text(text = "发现新版本 ${update.versionName}", style = AppText.stemStrong) },
            text = {
                Text(
                    text = update.notes.ifBlank { "GitHub 上发布了新版本，可以下载更新。" },
                    style = AppText.small,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        appContext.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        Toast.makeText(
                            appContext,
                            "打不开下载页：${it.message ?: "没有可用的浏览器"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    appUpdate = null
                }) {
                    Text(text = "去下载", style = AppText.smallStrong, color = c.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { appUpdate = null }) {
                    Text(text = "以后再说", style = AppText.smallStrong, color = c.textSecondary)
                }
            },
        )
    }
}

/** 一组的小标题（「题库」「显示」「关于」） */
@Composable
private fun GroupTitle(text: String) {
    val c = appColors()
    Text(text = text, style = AppText.stemStrong, color = c.textPrimary)
}

/** 卡片内部分隔线（上下各留 8dp，跟行自身的间距对齐） */
@Composable
private fun CardDivider() {
    val c = appColors()
    Spacer(Modifier.height(Spacing.sm))
    HorizontalDivider(color = c.border)
    Spacer(Modifier.height(Spacing.sm))
}

/**
 * 一行可点的动作行（右侧一个「›」）。
 *
 * @param enabled false 时整行不可点、文字与箭头一起变灰（「检查更新」正在跑时用）
 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = appColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppText.body,
                color = if (enabled) c.textPrimary else c.textSecondary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(text = subtitle, style = AppText.caption, color = c.textSecondary)
            }
        }
        Text(
            text = "›",
            style = AppText.bodyStrong,
            color = if (enabled) c.textSecondary else c.border,
        )
    }
}

/** 「版本号 —— 当前版本」一行：整行可点，连点 7 次打开调试入口 */
@Composable
private fun VersionRow(appVersion: String, onClick: () -> Unit) {
    val c = appColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "版本号",
            style = AppText.body,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(text = appVersion, style = AppText.bodyStrong, color = c.textPrimary)
    }
}

/** 调试块里的一行：标签在上、真实路径在下（路径可能很长，允许换行） */
@Composable
private fun DebugInfoLine(label: String, value: String) {
    val c = appColors()
    Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
        Text(text = label, style = AppText.caption, color = c.textSecondary)
        Text(text = value, style = AppText.small, color = c.textPrimary)
    }
}

/** 调试密码输入框 */
@Composable
private fun DebugPasswordDialog(
    password: String,
    errorText: String?,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = appColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "输入调试密码", style = AppText.stemStrong) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = AppText.small,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.primary,
                        unfocusedBorderColor = c.border,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                        cursorColor = c.primary,
                        focusedContainerColor = c.surface,
                        unfocusedContainerColor = c.surface,
                    ),
                )
                errorText?.let { message ->
                    Spacer(Modifier.height(Spacing.sm))
                    Text(text = message, style = AppText.small, color = c.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "确定", style = AppText.smallStrong, color = c.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = AppText.smallStrong, color = c.textSecondary)
            }
        },
    )
}

/** 连续输错 3 次后的提示：不做锁定，别把人锁在外面 */
@Composable
private fun WrongPasswordDialog(onDismiss: () -> Unit) {
    val c = appColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "密码错误", style = AppText.stemStrong) },
        text = {
            Text(
                text = "连续输错 $DEBUG_PASSWORD_MAX_ATTEMPTS 次，已关闭密码输入框。",
                style = AppText.small,
                color = c.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "知道了", style = AppText.smallStrong, color = c.primary)
            }
        },
    )
}

/**
 * 试卷来源标签：本地 → 「本地」；来自源 → 源名；源已经被删掉 → 「已删除的源」。
 */
private fun sourceLabelOf(paper: PaperMeta, sources: List<SourceConfig>): String {
    if (paper.isLocal) return "本地"
    return sources.firstOrNull { it.id == paper.sourceId }?.name ?: "已删除的源"
}

/** 调试块里显示的信息（只在后台线程算） */
private data class DebugSnapshot(
    val filesDir: String,
    val currentSourceName: String,
    val papersDir: String,
)

/** 读调试块要用的路径与当前源名；不联网、只读本地 */
private fun buildDebugSnapshot(context: Context, sourceStore: SourceStore): DebugSnapshot {
    val snapshot = sourceStore.load()
    return DebugSnapshot(
        filesDir = context.filesDir.absolutePath,
        currentSourceName = snapshot.current()?.name ?: "（未选择）",
        papersDir = PaperRepo(context).root.absolutePath,
    )
}

/**
 * 把地址里的 `user:pass@` 段打码。
 * 源管理页要显示「自建源的地址」，但绝不能把密码显示在屏幕上（硬性要求 1、3）。
 * 本函数是 internal，供 ui/SourceScreen.kt 复用。
 */
internal fun maskAddress(raw: String): String =
    raw.trim().replace(Regex("//[^/@]+@"), "//******@")
