// 应用入口：加载多套试卷库、进度与设置，承载「底部三 Tab + 全屏子页面」的切换。
//
// v1.3 启动顺序：
//   1. PaperRepo.ensureMigrated()：安装包已不再内置题库，这里只检查 papers 目录；
//      有试卷（v1.2 装过的用户）就照常用，没有就进「还没有题库」空状态；
//   2. PaperSessionLoader.start()：按 currentPaperId 打开当前试卷（题目、进度、考试断点、图片目录）；
//      没有试卷时返回 null（界面走空状态，不白屏、不崩）；
//   3. 启动 3 秒后（开关打开时）后台检查一次题库更新：公网 version.json 优先，
//      404 退 papers.json，都拿不到就静默跳过；发现新版本弹「下载 / 以后再说」对话框；
//   4. 之后所有读写都走这套试卷的目录；切试卷 = 换一个 PaperSession，
//      旧会话的状态跟着旧会话一起丢掉，所以天然隔离。
//
// 业务逻辑（抽题、判分、错题、组卷）仍然在 data/ 下，本文件只做状态编排与页面切换。
package com.shuaati.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import com.shuaati.app.data.AppSettings
import com.shuaati.app.data.DebugServer
import com.shuaati.app.data.Diagnostics
import com.shuaati.app.data.ExamBuilder
import com.shuaati.app.data.ExamPaper
import com.shuaati.app.data.ExamStore
import com.shuaati.app.data.ImageDownloadResult
import com.shuaati.app.data.PaperImporter
import com.shuaati.app.data.PaperMeta
import com.shuaati.app.data.PaperRepo
import com.shuaati.app.data.PaperSession
import com.shuaati.app.data.PaperSessionLoader
import com.shuaati.app.data.PaperUpdate
import com.shuaati.app.data.PaperUploader
import com.shuaati.app.data.ProgressStore
import com.shuaati.app.data.Question
import com.shuaati.app.data.QuestionRepo
import com.shuaati.app.data.RoundKeys
import com.shuaati.app.data.SavedExam
import com.shuaati.app.data.SmartPicker
import com.shuaati.app.data.SourceConfig
import com.shuaati.app.data.SourceStore
import com.shuaati.app.data.UpdateCheckOutcome
import com.shuaati.app.data.UpdateChecker
import com.shuaati.app.data.WebDavEntry
import com.shuaati.app.data.WebDavException
import com.shuaati.app.data.WebDavIndex
import com.shuaati.app.data.WebDavSource
import com.shuaati.app.data.WrongBook
import com.shuaati.app.ui.AboutScreen
import com.shuaati.app.ui.DebugScreen
import com.shuaati.app.ui.ExamScreen
import com.shuaati.app.ui.ExamStartScreen
import com.shuaati.app.ui.ExamState
import com.shuaati.app.ui.HomeScreen
import com.shuaati.app.ui.OnboardingScreen
import com.shuaati.app.ui.PracticeScreen
import com.shuaati.app.ui.QuizScreen
import com.shuaati.app.ui.QuizState
import com.shuaati.app.ui.SettingsScreen
import com.shuaati.app.ui.SmartSummaryScreen
import com.shuaati.app.ui.SourceEditDialog
import com.shuaati.app.ui.SourceScreen
import com.shuaati.app.ui.WrongBookScreen
import com.shuaati.app.ui.maskAddress
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.FullScreenDialog
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.computeHomeStats
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Motion
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.ShuaatiTheme
import com.shuaati.app.ui.theme.appColors
import com.shuaati.app.ui.theme.motionSpring
import com.shuaati.app.ui.theme.motionTween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 底部导航的三个 Tab；key 与设置里存的启动页名字一致 */
private enum class Tab(val key: String, val label: String, val icon: ImageVector) {
    HOME(AppSettings.TAB_HOME, "首页", Icons.Default.Home),
    QUIZ(AppSettings.TAB_QUIZ, "刷题", Icons.Default.PlayArrow),
    SETTINGS("settings", "设置", Icons.Default.Settings),
    ;

    companion object {
        /** 由设置里的字符串还原 Tab；认不出来（例如设置被手改坏）一律回「首页」 */
        fun of(key: String): Tab = entries.firstOrNull { it.key == key } ?: HOME
    }
}

/**
 * 全屏子页面。这些页面盖住底部导航，返回后回到原来的 Tab。
 * 顺序无关，只是增删子页面时少改一点代码。
 * 11 新增：SOURCES（源管理页）、ABOUT（关于页）。
 */
private enum class Overlay { WRONG_BOOK, QUIZ, EXAM_START, EXAM, SOURCES, ABOUT, DEBUG }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 设置：启动页 + 首页要显示哪几块 + 引导记录 + 调试开关（SharedPreferences）
        val settings = AppSettings(this)

        // 11 老用户增量引导迁移：必须在「要不要播引导」的判定之前跑。
        // 判定条件是 onboarded == true 但这个版本还没有 seenOnboardingIds 记录。
        val onboardingMigrated = settings.ensureOnboardingMigrated()
        val unseenOnboarding = settings.unseenOnboardingIds()
        Log.i(
            "Shuaati",
            "设置：启动页=${settings.defaultTab}，首页大环=${settings.showRing}，" +
                "四宫格=${settings.showTiles}，分题型=${settings.showTypeRings}，" +
                "打卡=${settings.showStreak}，导入按钮=${settings.showImport}，" +
                "已看过引导=${settings.onboarded}，debug=${settings.debugEnabled}，" +
                "启动检查更新=${settings.startupCheckUpdates}，" +
                "未看引导屏=${unseenOnboarding.size} 个$unseenOnboarding",
        )
        if (onboardingMigrated) {
            Log.i(
                "Shuaati",
                "增量引导迁移：老用户（onboarded=true）没有看过记录，" +
                    "已把 v1.1 的 7 屏标为已看，只留 source 未看",
            )
        }

        // v1.5：平板（最小边 ≥ 600dp）暂不支持——直接给提示页，不做拉宽的自适应布局。
        // 用物理屏幕算而不是窗口配置：Android 16 在大屏上可能把应用塞进小窗口，按窗口算会误判成手机。
        val dm = resources.displayMetrics
        val minDp = minOf(
            (dm.widthPixels / dm.density).toInt(),
            (dm.heightPixels / dm.density).toInt(),
        )
        if (minDp >= 600) {
            Log.i("Shuaati", "检测到平板（最小边 ${minDp}dp），显示暂不支持提示")
            setContent {
                ShuaatiTheme {
                    TabletUnsupportedScreen(onExit = { finish() })
                }
            }
            return
        }

        setContent {
            // 深浅色跟随系统，不给手动开关
            ShuaatiTheme {
                AppRoot(settings)
            }
        }
    }
}

/**
 * v1.5：平板提示页。
 * 在最小边 ≥ 600dp 的设备上启动时直接显示，避免把手机版单列布局拉成超宽一整条。
 */
@Composable
private fun TabletUnsupportedScreen(onExit: () -> Unit) {
    val c = appColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "暂不支持平板", style = AppText.pageTitle, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "刷题目前只适配手机竖屏。\n题库文件可以发到手机上，用「本地导入」加进来。",
                style = AppText.small,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            AppButton(text = "退出", onClick = onExit, style = AppButtonStyle.PRIMARY)
        }
    }
}

/**
 * 第一层：负责试卷库的加载与切换、底部 Tab、v1.3 的更新检查与空状态。
 * 真正的页面逻辑在 [PaperApp]：每一套试卷进来都会重建它，所以切卷 = 状态全清 + 换数据源。
 */
@Composable
private fun AppRoot(settings: AppSettings) {
    val appContext = LocalContext.current.applicationContext
    val repo = remember { PaperRepo(appContext) }
    // 11：题库源存储。所有网络请求都从它拿「当前源」或「某个源」的配置。
    val sourceStore = remember { SourceStore(appContext) }
    val scope = rememberCoroutineScope()
    // 设置页「关于」组显示的版本号（只显示「1.3」，不带 versionCode 这种技术词）
    val appVersion = remember { Diagnostics.versionName(appContext) }

    var session by remember { mutableStateOf<PaperSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var startupToken by remember { mutableStateOf(0) }
    // Tab 放在这一层：切试卷时保持当前 Tab，不把用户弹回启动页
    var tab by remember { mutableStateOf(Tab.of(settings.defaultTab)) }
    // 导入结果对话框也放在这一层：导入成功会自动切到新试卷，若放在 PaperApp 里会被切卷重置掉
    var importMessage by remember { mutableStateOf<ImportMessage?>(null) }

    // ===== 11 增量引导 =====
    var showOnboarding by remember { mutableStateOf(settings.needsOnboarding()) }
    val onboardingIds = remember { settings.unseenOnboardingIds() }
    // 当前源名字：引导页「选择题库来源」那屏要显示，源管理页改完这里也会重读
    var currentSourceName by remember { mutableStateOf("") }
    // 引导页点「配置自己的 WebDAV」时弹出的新建源弹窗
    var showOnboardingSourceDialog by remember { mutableStateOf(false) }

    // ===== v1.3：更新检查 =====
    // 要弹给用户的那一条更新（同一时间只处理一条，处理完就清空）
    var pendingUpdate by remember { mutableStateOf<PaperUpdate?>(null) }
    // 设置页「检查更新」按钮是否正在跑
    var checkingUpdate by remember { mutableStateOf(false) }
    // 正在下载并替换题库
    var updating by remember { mutableStateOf(false) }
    // 本次启动里点过「以后再说」的版本：paperId -> version。
    // 进程重启就清空，所以下次启动还会问，除非版本又变了（任务书第 2 条）。
    var skippedVersions by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // 启动检查只做一次（手动检查不受它限制）
    var startupCheckDone by remember { mutableStateOf(false) }

    // ===== 空状态（本地没有任何试卷）时的网络下载 / 本地导入 =====
    var emptyNetVisible by remember { mutableStateOf(false) }
    var emptyNetState by remember { mutableStateOf<NetState>(NetState.Loading) }
    var showSourceManagerFromEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        currentSourceName = withContext(Dispatchers.IO) {
            sourceStore.load().current()?.name.orEmpty()
        }
    }

    // v1.5：调试模式打开时启动本地调试端口（127.0.0.1:8765），关闭时停止
    LaunchedEffect(settings.debugEnabled) {
        if (settings.debugEnabled) {
            DebugServer.start(appContext)
        } else {
            DebugServer.stop()
        }
    }

    // 启动检查：开关打开、并且已经有试卷时，**延迟 3 秒**在后台跑一次，绝不挡首屏。
    // 任务书要求：关掉开关就完全不请求；网络失败静默；本次启动跳过的不再追问。
    LaunchedEffect(session, settings.startupCheckUpdates) {
        val active = session ?: return@LaunchedEffect
        if (!settings.startupCheckUpdates || startupCheckDone) return@LaunchedEffect
        delay(STARTUP_CHECK_DELAY_MS)
        // 3 秒内用户可能把开关又关了：这时直接放弃，不请求
        if (!settings.startupCheckUpdates) return@LaunchedEffect
        startupCheckDone = true
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                UpdateChecker.checkAll(
                    repo = repo,
                    sources = sourceStore.load().sources,
                    localPapers = repo.list(),
                    skipped = skippedVersions,
                )
            }.onFailure {
                // 任务书：失败也要有日志，不许静默吞异常
                Log.w("Shuaati", "update-check: failed 启动检查出现异常：${it.message}")
            }.getOrElse { UpdateCheckOutcome(emptyList(), emptyList(), emptyList()) }
        }
        if (outcome.updates.isNotEmpty()) {
            pendingUpdate = outcome.updates.first()
            Log.i("Shuaati", "启动更新检查：${outcome.updates.size} 套可更新，已弹出提示")
        }
        // active 只为让编译器知道这里有当前试卷；具体检查不依赖它
        Log.i("Shuaati", "启动更新检查结束：当前试卷=${active.paperId}")
    }

    // 启动：准备试卷库并打开当前试卷。v1.3 起「没有试卷」不再算错误：session 保持 null，走空状态。
    LaunchedEffect(startupToken) {
        loading = true
        loadError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { PaperSessionLoader.start(appContext, repo) }
        }
        result.onSuccess { session = it }
        result.onFailure {
            Log.e("Shuaati", "试卷库准备失败", it)
            loadError = it.message ?: "试卷库准备失败"
        }
        loading = false
    }

    /** 切到某套试卷：重新构造会话对象；失败只弹中文提示，不把界面留成白屏 */
    fun openPaper(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PaperSessionLoader.open(appContext, repo, id) }
            }
            result.onSuccess { session = it }
            result.onFailure {
                Log.e("Shuaati", "打开试卷失败：$id", it)
                Toast.makeText(appContext, it.message ?: "打开这套试卷失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 引导页「使用默认源」：
     * 恢复默认源（如果被删过）→ 设为当前源 → 拉一次索引 → Toast「已获取 N 套试卷」。
     */
    fun useDefaultSource() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = sourceStore.ensureBuiltinCurrent()
                    source to WebDavSource.loadIndex(source).entries.size
                }
            }
            result.onSuccess { pair ->
                val source = pair.first
                val count = pair.second
                currentSourceName = source.name
                Toast.makeText(appContext, "已获取 $count 套试卷", Toast.LENGTH_SHORT).show()
                Log.i("Shuaati", "引导页选择默认源：索引可读，$count 套试卷")
            }.onFailure {
                Log.w("Shuaati", "引导页选择默认源失败：${it.message}")
                Toast.makeText(
                    appContext,
                    "读取默认源失败：${it.message ?: "未知错误"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ===== 空状态：从源下载 =====

    /** 空状态点「从源下载」：读当前源索引，弹可选试卷列表 */
    fun loadEmptyIndex() {
        emptyNetVisible = true
        emptyNetState = NetState.Loading
        scope.launch {
            emptyNetState = try {
                val pair = withContext(Dispatchers.IO) {
                    val source = sourceStore.load().current()
                        ?: throw WebDavException(
                            "还没有选择题库源，请到「题库源」里新建一个，或用「恢复默认源」找回默认源",
                        )
                    source to WebDavSource.loadIndex(source)
                }
                NetState.Ready(index = pair.second, source = pair.first)
            } catch (e: WebDavException) {
                NetState.Error(e.message ?: "读取题库源失败")
            } catch (e: Exception) {
                Log.e("Shuaati", "读取题库源失败", e)
                NetState.Error("读取题库源失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 空状态：下载选中的那一套；成功后直接打开它 */
    fun downloadEmpty(source: SourceConfig, entry: WebDavEntry) {
        emptyNetState = NetState.Downloading(entry.name)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                downloadAndImport(appContext, repo, source, entry)
            }
            val newId = outcome.newPaperId
            if (newId != null) {
                emptyNetVisible = false
                importMessage = ImportMessage(outcome.title, outcome.body)
                openPaper(newId)
            } else {
                emptyNetState = NetState.Error(outcome.body)
            }
        }
    }

    /** 空状态：本地导入 */
    fun importLocalFromEmpty(uri: Uri) {
        importMessage = ImportMessage("正在导入", "正在读取并校验文件，请稍候…")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { importLocal(appContext, repo, uri) }
            importMessage = ImportMessage(outcome.title, outcome.body)
            outcome.newPaperId?.let { openPaper(it) }
        }
    }

    // 空状态用的文件选择器（与 PaperApp 里那个互不干扰：只会在空状态分支里被调用）
    val emptyOpenDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importLocalFromEmpty(uri)
    }

    // ===== v1.3：手动检查 / 下载更新 / 以后再说 =====

    /** 设置页「检查更新」：用户主动点的，不受「本次启动已跳过」影响 */
    fun runManualCheck() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.manualCheck(repo, sourceStore.load().sources, repo.list()) }
                    .onFailure { Log.w("Shuaati", "update-check: failed 手动检查异常：${it.message}") }
                    .getOrElse { UpdateCheckOutcome(emptyList(), emptyList(), emptyList()) }
            }
            checkingUpdate = false
            if (outcome.updates.isEmpty()) {
                val message = when {
                    outcome.unsupportedSources.isNotEmpty() ->
                        "当前题库源不支持自动检查更新，可以到「题库源」里手动刷新"

                    outcome.failedSources.isNotEmpty() ->
                        "检查失败：网络连接不上，请检查网络后再试"

                    else -> "已经是最新版本"
                }
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            } else {
                pendingUpdate = outcome.updates.first()
            }
        }
    }

    /** 用户点「下载」：原地更新（进度迁移由 UpdateChecker 负责） */
    fun downloadUpdate(update: PaperUpdate) {
        pendingUpdate = null
        updating = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.applyUpdate(repo, update) }
            }
            updating = false
            result.onSuccess { applied ->
                val migration = applied.migration
                val droppedNote = if (migration.dropped > 0) {
                    "，${migration.dropped} 条对不上新题目的旧记录已单独备份保留"
                } else {
                    ""
                }
                importMessage = ImportMessage(
                    title = "更新成功",
                    body = "已更新到第 ${update.remoteVersion} 版，共 ${applied.total} 题。\n" +
                        "做题进度已迁移：对上 ${migration.matched} 条（按题号 ${migration.byNo}、" +
                        "按题干 ${migration.byStem}）$droppedNote。",
                )
                // 重新打开这套卷：题目列表换成新的，进度文件不动
                openPaper(update.localMeta.id)
            }.onFailure {
                Log.e("Shuaati", "update-check: 更新失败", it)
                importMessage = ImportMessage("更新失败", it.message ?: "未知错误")
            }
        }
    }

    /** 用户点「以后再说」：记下版本，同一次启动不再追问 */
    fun skipUpdate(update: PaperUpdate) {
        skippedVersions = skippedVersions + (update.paperId to update.remoteVersion)
        pendingUpdate = null
        Log.i(
            "Shuaati",
            "update-check: 用户选择「以后再说」paperId=${update.paperId} " +
                "version=${update.remoteVersion}，本次启动不再提示",
        )
    }

    val current = session
    when {
        loading -> StartupScreen(message = "正在准备试卷库…")

        // 真正的错误（meta 损坏、题库读不出来）才显示错误页；「没有试卷」不是错误
        current == null && loadError != null -> StartupScreen(
            message = loadError ?: "试卷库准备失败",
            error = true,
            onRetry = { startupToken++ },
        )

        // 11：只播没看过的屏。没有试卷时也能播（来源屏正是给这种场景准备的）
        showOnboarding -> OnboardingScreen(
            unseenIds = onboardingIds,
            paperName = current?.meta?.name ?: "还没有题库",
            questionCount = current?.meta?.total ?: 0,
            settings = settings,
            currentSourceName = currentSourceName,
            onFinished = {
                tab = Tab.of(settings.defaultTab)
                showOnboarding = false
                Log.i("Shuaati", "增量引导结束（走完或跳过），进入主界面")
            },
            onUseDefaultSource = { useDefaultSource() },
            onConfigureSource = { showOnboardingSourceDialog = true },
            onSkipSource = {
                Toast.makeText(
                    appContext,
                    "已跳过：之后可以在「题库源」里选择或新建源",
                    Toast.LENGTH_LONG,
                ).show()
            },
        )

        // v1.3：一套试卷都没有 → 首页空状态（不白屏、不崩）
        current == null -> EmptyPaperScreen(
            hasLegacyData = remember { repo.hasLegacyData() },
            onDownload = { loadEmptyIndex() },
            onLocalImport = { emptyOpenDocument.launch(arrayOf("application/json")) },
            onOpenSources = { showSourceManagerFromEmpty = true },
        )

        else -> PaperApp(
            settings = settings,
            repo = repo,
            sourceStore = sourceStore,
            session = current,
            tab = tab,
            currentSourceName = currentSourceName,
            appVersion = appVersion,
            checkingUpdate = checkingUpdate,
            onTabChange = { tab = it },
            onOpenPaper = { openPaper(it) },
            onCheckUpdate = { runManualCheck() },
            importMessage = importMessage,
            onImportMessage = { importMessage = it },
        )
    }

    // 引导页的「配置自己的 WebDAV」弹窗：与源管理页复用同一个 SourceEditDialog
    if (showOnboardingSourceDialog) {
        SourceEditDialog(
            initial = null,
            sourceStore = sourceStore,
            onDismiss = { showOnboardingSourceDialog = false },
            onChanged = {
                scope.launch {
                    currentSourceName = withContext(Dispatchers.IO) {
                        sourceStore.load().current()?.name.orEmpty()
                    }
                }
            },
        )
    }

    // 空状态的「题库源」管理页：可以从那里点一套试卷直接下载
    if (showSourceManagerFromEmpty) {
        SourceScreen(
            sourceStore = sourceStore,
            papers = emptyList(),
            onBack = { showSourceManagerFromEmpty = false },
            onDownloadPaper = { source, entry -> downloadEmpty(source, entry) },
        )
    }

    // 空状态的网络获取弹窗
    if (emptyNetVisible) {
        NetworkDialog(
            state = emptyNetState,
            onDownload = { entry ->
                val ready = emptyNetState as? NetState.Ready
                if (ready != null) downloadEmpty(ready.source, entry)
            },
            onRetry = { loadEmptyIndex() },
            onDismiss = { emptyNetVisible = false },
        )
    }

    // 空状态时的导入/下载结果对话框（有试卷后由 PaperApp 自己显示）
    if (current == null) {
        importMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { importMessage = null },
                title = { Text(text = message.title, style = AppText.stemStrong) },
                text = {
                    Text(text = message.body, style = AppText.small, color = appColors().textSecondary)
                },
                confirmButton = {
                    TextButton(onClick = { importMessage = null }) {
                        Text(text = "知道了", style = AppText.smallStrong, color = appColors().primary)
                    }
                },
            )
        }
    }

    // v1.3：发现新版本 → 弹「下载 / 以后再说」
    pendingUpdate?.let { update ->
        UpdatePromptDialog(
            update = update,
            onDownload = { downloadUpdate(update) },
            onLater = { skipUpdate(update) },
        )
    }

    // 正在更新（下载 + 校验 + 迁移进度，几秒钟）
    if (updating) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(text = "正在更新题库", style = AppText.stemStrong) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = appColors().primary,
                        modifier = Modifier.size(ComponentSize.icon),
                    )
                    Spacer(Modifier.padding(horizontal = Spacing.sm))
                    Text(
                        text = "正在下载新题库并迁移做题进度，请稍候…",
                        style = AppText.small,
                        color = appColors().textSecondary,
                    )
                }
            },
            confirmButton = {
                // 更新过程不提供取消：中途停下可能留下半套题库
                TextButton(onClick = { }, enabled = false) {
                    Text(text = "请稍候", style = AppText.smallStrong, color = appColors().textSecondary)
                }
            },
        )
    }
}

/** 启动中的两态页面：转圈等 / 出错带「重试」 */
@Composable
private fun StartupScreen(
    message: String,
    error: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val c = appColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        Spacer(Modifier.height(Spacing.xxl))
        if (!error) {
            CircularProgressIndicator(color = c.primary)
            Spacer(Modifier.height(Spacing.lg))
        }
        Text(
            text = message,
            style = AppText.body,
            color = if (error) c.error else c.textSecondary,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(Spacing.xl))
            AppButton(text = "重试", onClick = onRetry)
        }
    }
}

/**
 * 一套试卷的完整界面。
 * 所有 remember 都带 paperId / session 作为键：切试卷时连同 quiz、overlay、tick 一起重置，
 * 保证「切回来和切走之前完全一样」——因为数据都来自磁盘上那套试卷的 progress.json。
 *
 * v1.3 参数变化：
 * @param currentSourceName 当前源名字（设置页题库组要显示）
 * @param appVersion 应用版本号（设置页关于组要显示）
 * @param checkingUpdate 手动检查更新是否正在跑（设置页按钮文案/禁用）
 * @param onCheckUpdate 设置页「检查更新」按钮
 */
@Composable
private fun PaperApp(
    settings: AppSettings,
    repo: PaperRepo,
    sourceStore: SourceStore,
    session: PaperSession,
    tab: Tab,
    currentSourceName: String,
    appVersion: String,
    checkingUpdate: Boolean,
    onTabChange: (Tab) -> Unit,
    onOpenPaper: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    importMessage: ImportMessage?,
    onImportMessage: (ImportMessage?) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val c = appColors()
    val scope = rememberCoroutineScope()

    val paperId = session.paperId
    val questions: List<Question> = session.questions
    val store: ProgressStore = session.store
    val examStore: ExamStore = session.examStore

    var overlay by remember(paperId) { mutableStateOf<Overlay?>(null) }
    var quiz by remember(paperId) { mutableStateOf<QuizState?>(null) }
    var quizBackToWrongBook by remember(paperId) { mutableStateOf(false) }
    var tick by remember(paperId) { mutableStateOf(0) }

    // 07A：首页环形进度「只在进入首页时播一次」。
    // 放在这一层（而不是 HomeScreen 里）是因为切 Tab 时首页会被 Crossfade 移出组合，
    // HomeScreen 内部的 remember 会跟着丢；rememberSaveable 放在这里才能记住「已经播过」。
    var homeRingPlayed by rememberSaveable(paperId) { mutableStateOf(false) }

    // 模拟考试：组好的卷子、正在考的这场、启动时发现的未完成考试
    var examPaper by remember(paperId) { mutableStateOf<ExamPaper?>(null) }
    var examState by remember(paperId) { mutableStateOf<ExamState?>(null) }
    var resumeExam by remember(paperId) { mutableStateOf<SavedExam?>(null) }

    // 试卷库列表（设置页 / 切换弹窗用）：切卷或导入删除后重新读一次
    var papers by remember(paperId) { mutableStateOf<List<PaperMeta>>(emptyList()) }
    var papersVersion by remember(paperId) { mutableStateOf(0) }

    // 导入 / 网络 / 删除的界面状态
    var showPaperPicker by remember(paperId) { mutableStateOf(false) }
    var showImportChoice by remember(paperId) { mutableStateOf(false) }
    var deleteTarget by remember(paperId) { mutableStateOf<PaperMeta?>(null) }
    var netVisible by remember(paperId) { mutableStateOf(false) }
    var netState by remember(paperId) { mutableStateOf<NetState>(NetState.Loading) }

    // 11：上传到源（本地卷 → 自建源）
    var uploadTarget by remember(paperId) { mutableStateOf<PaperMeta?>(null) }
    var uploadSources by remember(paperId) { mutableStateOf<List<SourceConfig>>(emptyList()) }
    var uploadBusy by remember(paperId) { mutableStateOf(false) }

    // 启动时先看一眼这套试卷有没有没考完、也没超时的卷子；有就弹「继续上次考试 / 放弃」
    LaunchedEffect(paperId) {
        resumeExam = withContext(Dispatchers.IO) { examStore.loadActive() }
    }

    // 试卷列表：本地几个 meta.json，读起来很快，但仍然放 IO 线程
    LaunchedEffect(paperId, papersVersion) {
        papers = withContext(Dispatchers.IO) { repo.list() }
    }

    // 错题列表：完全由进度推导，和首页上的数量用同一个函数，所以 N 一定等于列表条数
    val wrongItems = remember(session, tick) {
        WrongBook.filter(questions) { store.recordOf(it) }
    }
    // 首页统计：全部从当前试卷的 progress.json 现算
    val stats = remember(session, tick) {
        computeHomeStats(questions, store)
    }
    // 三种题型的题量，用于刷题 Tab 入口上的数字
    val singleCount = questions.count { it.type == "single" }
    val judgeCount = questions.count { it.type == "judge" }
    val multiCount = questions.count { it.type == "multi" }

    /** 进入答题页；backToWrongBook 决定按返回键时回错题本还是回 Tab 页 */
    fun openQuiz(state: QuizState, backToWrongBook: Boolean) {
        quiz = state
        quizBackToWrongBook = backToWrongBook
        overlay = Overlay.QUIZ
        tick++
    }

    /** 统一刷：沿用原行为，上次停在哪题就回到哪题 */
    fun startUnified() {
        if (questions.isEmpty()) return
        val state = QuizState(
            questions = questions,
            store = store,
            title = "刷题",
            roundKey = RoundKeys.UNIFIED,
            resumeLast = true,
        )
        state.start()
        openQuiz(state, backToWrongBook = false)
    }

    /** 题型专练：题池只放这一种题型，轮次也用自己的键 */
    fun startPractice(type: String, title: String, roundKey: String) {
        val subset = questions.filter { it.type == type }
        if (subset.isEmpty()) return
        val state = QuizState(
            questions = subset,
            store = store,
            title = title,
            roundKey = roundKey,
        )
        state.start()
        openQuiz(state, backToWrongBook = false)
    }

    /**
     * 智能刷：每次进来按当前进度重新抽一组（默认 20 题）。
     * 权重公式（FSRS 的 R）与抽题算法全部在 data/SmartPicker.kt；界面层只负责把抽好的子集交给答题页。
     */
    fun startSmart() {
        if (questions.isEmpty()) return
        val picked = SmartPicker.pick(
            questions = questions,
            progressOf = { store.recordOf(it) },
            count = SmartPicker.GROUP_SIZE,
        )
        if (picked.isEmpty()) return
        // 验收时可以在 logcat 里对着这一行核对「这一组到底抽了哪 20 题」
        Log.i(
            "Shuaati",
            "智能刷抽题 count=${picked.size} ids=${picked.joinToString(",") { it.id }}",
        )
        val state = QuizState(
            questions = picked,
            store = store,
            title = "智能刷",
            // 一组固定的题，不参与统一刷的「本轮」；组内顺序已由 SmartPicker 打乱
            roundKey = null,
            ordered = true,
            // 刷完最后一道就停，交给小结页
            stopAtEnd = true,
        )
        state.start()
        openQuiz(state, backToWrongBook = false)
    }

    /** 进错题本：先按当前进度重新筛一遍，保证列表与数量都是最新的 */
    fun openWrongBook() {
        overlay = Overlay.WRONG_BOOK
        tick++
    }

    /**
     * 从错题本开始刷。
     * startId 为 null 表示「全部开始」从第一条开始；否则从点的那一条开始，之后按列表顺序往下。
     */
    fun startWrongBook(startId: String?) {
        val snapshot = WrongBook.filter(questions) { store.recordOf(it) }
        if (snapshot.isEmpty()) return
        val state = QuizState(
            questions = snapshot,
            store = store,
            title = "错题本",
            roundKey = null,
            ordered = true,
        )
        state.start(startId)
        openQuiz(state, backToWrongBook = true)
    }

    /** 进入考试开始页；磁盘上有没考完的卷子时，先让人决定继续还是放弃 */
    fun openExamStart() {
        if (questions.isEmpty()) return
        val saved = examStore.loadActive()
        if (saved != null) {
            resumeExam = saved
            return
        }
        examPaper = ExamBuilder.build(questions)
        overlay = Overlay.EXAM_START
        tick++
    }

    /** 开始新考试：开考这一刻定下结束时间戳，之后倒计时只认这个戳，不做每秒累减 */
    fun startExam() {
        val paper = examPaper ?: return
        if (paper.questions.isEmpty()) return
        val now = System.currentTimeMillis()
        examState = ExamState(
            questions = paper.questions,
            progress = store,
            examStore = examStore,
            startAt = now,
            endAt = now + ExamBuilder.DURATION_SECONDS * 1000L,
            durationSec = ExamBuilder.DURATION_SECONDS,
        )
        overlay = Overlay.EXAM
        tick++
    }

    /** 从 exam.json 恢复考试：按存下来的题目 id 顺序还原卷子和每题答案 */
    fun resumeSavedExam(saved: SavedExam) {
        // 弹窗先关掉，再决定恢复还是清掉
        resumeExam = null
        val byId = questions.associateBy { it.id }
        val ordered = saved.ids.mapNotNull { byId[it] }
        if (ordered.isEmpty()) {
            examStore.clear()
            return
        }
        val validIds = ordered.map { it.id }.toSet()
        val answers = saved.answers.filterKeys { it in validIds }
        examPaper = ExamPaper(
            questions = ordered,
            singleCount = ordered.count { it.type == "single" },
            judgeCount = ordered.count { it.type == "judge" },
            multiCount = ordered.count { it.type == "multi" },
        )
        examState = ExamState(
            questions = ordered,
            progress = store,
            examStore = examStore,
            startAt = saved.startAt,
            endAt = saved.endAt,
            durationSec = saved.durationSec,
            initialAnswers = answers,
            initialIndex = saved.index,
        )
        overlay = Overlay.EXAM
        tick++
    }

    /** 放弃上次考试：清掉 exam.json，不写任何 progress */
    fun abandonSavedExam() {
        resumeExam = null
        examStore.clear()
    }

    /** 离开考场：没交卷时 exam.json 保留，下次点「模拟考试」还能接着考 */
    fun exitExam() {
        examState = null
        examPaper = null
        overlay = null
        tick++
    }

    /** 返回上一级：答题页 → 错题本或 Tab 页；错题本 / 考试开始页 / 源管理页 / 关于页 → Tab 页 */
    fun goBack() {
        overlay = when (overlay) {
            Overlay.QUIZ -> if (quizBackToWrongBook) Overlay.WRONG_BOOK else null
            Overlay.WRONG_BOOK, Overlay.EXAM_START, Overlay.EXAM,
            Overlay.SOURCES, Overlay.ABOUT, Overlay.DEBUG,
            null,
            -> null
        }
        tick++
    }

    // ===== 导入：本地文件 =====
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImportMessage(ImportMessage("正在导入", "正在读取并校验文件，请稍候…"))
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { importLocal(appContext, repo, uri) }
            onImportMessage(ImportMessage(outcome.title, outcome.body))
            val newId = outcome.newPaperId
            if (newId != null) {
                papersVersion++
                onOpenPaper(newId)
            }
        }
    }

    fun startLocalImport() {
        showImportChoice = false
        // 只收 JSON：选别的文件时系统会灰掉，不用自己做过滤
        openDocument.launch(arrayOf("application/json"))
    }

    // ===== 导入：网络（11 起用「当前源」；题库永远从源获取）=====
    fun loadIndex() {
        netState = NetState.Loading
        scope.launch {
            netState = try {
                val pair = withContext(Dispatchers.IO) {
                    val source = sourceStore.load().current()
                        ?: throw WebDavException(
                            "还没有选择题库源，请先到「设置 → 题库源」里新建一个，或用「恢复默认源」找回默认源",
                        )
                    source to WebDavSource.loadIndex(source)
                }
                NetState.Ready(index = pair.second, source = pair.first)
            } catch (e: WebDavException) {
                NetState.Error(e.message ?: "读取题库源失败")
            } catch (e: Exception) {
                Log.e("Shuaati", "读取题库源失败", e)
                NetState.Error("读取题库源失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun openNetwork() {
        showImportChoice = false
        netVisible = true
        loadIndex()
    }

    /**
     * 下载一套试卷。
     * @param source 用哪个源下载（可能来自「网络获取」的当前源，也可能来自源管理页里点开某个源）
     */
    fun downloadEntry(source: SourceConfig, entry: WebDavEntry) {
        netVisible = true
        netState = NetState.Downloading(entry.name)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                downloadAndImport(appContext, repo, source, entry)
            }
            val newId = outcome.newPaperId
            if (newId != null) {
                netVisible = false
                onImportMessage(ImportMessage(outcome.title, outcome.body))
                papersVersion++
                onOpenPaper(newId)
            } else {
                netState = NetState.Error(outcome.body)
            }
        }
    }

    /** 「从网络获取」弹窗里点一套：用当前源下载 */
    fun downloadFromCurrent(entry: WebDavEntry) {
        val ready = netState as? NetState.Ready ?: return
        downloadEntry(ready.source, entry)
    }

    /**
     * 11：上传本地试卷到自建源。
     * 目标列表只来自 SourceSnapshot.uploadTargets()（默认源永远不会出现在里面）。
     */
    fun openUpload(meta: PaperMeta) {
        uploadTarget = meta
        uploadSources = emptyList()
        scope.launch {
            uploadSources = withContext(Dispatchers.IO) { sourceStore.load().uploadTargets() }
        }
    }

    fun doUpload(meta: PaperMeta, source: SourceConfig) {
        uploadBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { PaperUploader.upload(repo, meta, source) }
            uploadBusy = false
            papersVersion++
            if (result.ok) {
                uploadTarget = null
                onImportMessage(ImportMessage("上传成功", result.message))
            } else {
                // 失败：本地 meta 已回滚成 local，这里只提示中文原因
                Toast.makeText(appContext, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 删除一套试卷：先二次确认（文案含名称和题数），确认后删目录并提示「已删除」 */
    fun doDelete(meta: PaperMeta) {
        deleteTarget = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { repo.delete(meta.id) }
            if (!ok) {
                Toast.makeText(appContext, "删除失败，请重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(appContext, "已删除「${meta.name}」", Toast.LENGTH_SHORT).show()
            papersVersion++
            if (meta.id == session.paperId) {
                // 删的是当前卷：自动切到列表里剩下的一套（设置页已禁止删最后一套）
                val next = withContext(Dispatchers.IO) { repo.list().firstOrNull() }
                if (next != null) onOpenPaper(next.id)
            }
        }
    }

    // 考场（Overlay.EXAM）自己在 ExamScreen 里注册返回键：考试中弹退出确认，结果页直接退出，
    // 所以这里不拦 EXAM，避免两个 BackHandler 抢同一个返回事件。
    BackHandler(enabled = overlay != null && overlay != Overlay.EXAM) { goBack() }

    val currentOverlay = overlay
    if (currentOverlay == null) {
        // ===== 顶部层级：底部三 Tab =====
        Scaffold(
            containerColor = c.background,
            // v1.3：启动更新不再用顶部提示条，改成「发现新版本」对话框（在 AppRoot 里弹）
            topBar = {},
            bottomBar = {
                NavigationBar(
                    containerColor = c.surface,
                    contentColor = c.textPrimary,
                ) {
                    BottomTabItem(
                        selected = tab == Tab.HOME,
                        label = Tab.HOME.label,
                        icon = Tab.HOME.icon,
                        onClick = { onTabChange(Tab.HOME) },
                    )
                    BottomTabItem(
                        selected = tab == Tab.QUIZ,
                        label = Tab.QUIZ.label,
                        icon = Tab.QUIZ.icon,
                        onClick = { onTabChange(Tab.QUIZ) },
                    )
                    BottomTabItem(
                        selected = tab == Tab.SETTINGS,
                        label = Tab.SETTINGS.label,
                        icon = Tab.SETTINGS.icon,
                        onClick = { onTabChange(Tab.SETTINGS) },
                    )
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                // 07A：三 Tab 内容区整屏左右移动（300ms），按 Tab 顺序决定方向：
                // 往右切（首页→刷题→设置）新页从右边进来、旧页往左走；往左切方向相反。
                // 底部导航栏不参与移动，保持不动（与「蓝底是屏幕范围」的示意一致）。
                val tabSlide = motionTween<IntOffset>(Motion.SLIDE)
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        if (forward) {
                            slideInHorizontally(tabSlide) { it } togetherWith
                                slideOutHorizontally(tabSlide) { -it }
                        } else {
                            slideInHorizontally(tabSlide) { -it } togetherWith
                                slideOutHorizontally(tabSlide) { it }
                        }
                    },
                    label = "底部 Tab 切换",
                ) { currentTab ->
                    when (currentTab) {
                        // 首页：试卷名与题数全部来自当前试卷的 meta.json
                        Tab.HOME -> HomeScreen(
                            stats = stats,
                            settings = settings,
                            paperName = session.meta.name,
                            onImport = { showImportChoice = true },
                            animateOnEnter = !homeRingPlayed,
                            onEnterAnimationPlayed = { homeRingPlayed = true },
                        )

                        Tab.QUIZ -> PracticeScreen(
                            totalQuestions = questions.size,
                            wrongCount = wrongItems.size,
                            singleCount = singleCount,
                            judgeCount = judgeCount,
                            multiCount = multiCount,
                            paperName = session.meta.name,
                            onUnified = { startUnified() },
                            onSmart = { startSmart() },
                            onExam = { openExamStart() },
                            onWrongBook = { openWrongBook() },
                            onSingle = { startPractice("single", "单选专练", RoundKeys.SINGLE) },
                            onJudge = { startPractice("judge", "判断专练", RoundKeys.JUDGE) },
                            onMulti = { startPractice("multi", "多选专练", RoundKeys.MULTI) },
                            onSwitch = { showPaperPicker = true },
                        )

                        Tab.SETTINGS -> SettingsScreen(
                            settings = settings,
                            papers = papers,
                            currentPaperId = session.paperId,
                            currentSourceName = currentSourceName,
                            appVersion = appVersion,
                            checkingUpdate = checkingUpdate,
                            onSwitchPaper = { showPaperPicker = true },
                            onCheckUpdate = onCheckUpdate,
                            onImportPaper = { showImportChoice = true },
                            onOpenSources = {
                                overlay = Overlay.SOURCES
                                tick++
                            },
                            onReplayOnboarding = {
                                settings.resetOnboarding()
                                Toast.makeText(
                                    appContext,
                                    "已重置，下次启动会重新播放全部引导",
                                    Toast.LENGTH_LONG,
                                ).show()
                                Log.i("Shuaati", "重看引导：seenOnboardingIds 已清空，onboarded=false")
                            },
                            onOpenAbout = {
                                overlay = Overlay.ABOUT
                                tick++
                            },
                            onOpenDebug = {
                                overlay = Overlay.DEBUG
                                tick++
                            },
                            sourceStore = sourceStore,
                        )
                    }
                }
            }
        }
    } else {
        // ===== 全屏子页面：不显示底部导航 =====
        when (currentOverlay) {
            Overlay.WRONG_BOOK -> WrongBookScreen(
                items = wrongItems,
                progressOf = { store.recordOf(it) },
                onBack = { goBack() },
                onStartAll = { startWrongBook(null) },
                onStartFrom = { id -> startWrongBook(id) },
                onGoPractice = {
                    overlay = null
                    onTabChange(Tab.QUIZ)
                    tick++
                },
            )

            Overlay.QUIZ -> {
                val active = quiz
                when {
                    active == null -> {
                        // 兜底：没有答题状态就回 Tab 页，避免白屏
                        LaunchedEffect(Unit) {
                            overlay = null
                            tick++
                        }
                    }
                    // 只有「一组固定题量」的模式（智能刷）刷到末尾才会 finished=true，
                    // 这时不显示答题页，改显示本组小结；数字全部来自这次作答
                    active.finished -> {
                        SmartSummaryScreen(
                            total = active.total,
                            correctCount = active.groupCorrect,
                            noAnswerCount = active.groupNoAnswer,
                            wrongItems = active.groupWrong,
                            onAgain = { startSmart() },
                            onHome = {
                                quiz = null
                                overlay = null
                                tick++
                            },
                        )
                    }
                    else -> QuizScreen(state = active, onBack = { goBack() })
                }
            }

            Overlay.EXAM_START -> {
                val paper = examPaper
                if (paper == null) {
                    // 兜底：没有试卷就回 Tab 页，避免白屏
                    LaunchedEffect(Unit) {
                        overlay = null
                        tick++
                    }
                } else {
                    ExamStartScreen(
                        paper = paper,
                        onStart = { startExam() },
                        onBack = { goBack() },
                    )
                }
            }

            Overlay.EXAM -> {
                val active = examState
                if (active == null) {
                    // 兜底：没有考试状态就回 Tab 页，避免白屏
                    LaunchedEffect(Unit) {
                        overlay = null
                        tick++
                    }
                } else {
                    ExamScreen(state = active, onExit = { exitExam() })
                }
            }

            // 11：题库源管理页。点开某个源的试卷可以直接下载（下载流程与「网络获取」同一套）。
            Overlay.SOURCES -> SourceScreen(
                sourceStore = sourceStore,
                papers = papers,
                onBack = { goBack() },
                onDownloadPaper = { source, entry -> downloadEntry(source, entry) },
            )

            // v1.5：调试面板（全屏页，返回键退出）
            Overlay.DEBUG -> DebugScreen(
                onBack = { goBack() },
                onDisableDebug = {
                    settings.updateDebugEnabled(false)
                    goBack()
                },
            )

            // 11：关于页（版本号 + 连点 7 次的调试入口）
            Overlay.ABOUT -> AboutScreen(
                settings = settings,
                papers = papers,
                sources = remember(papers) {
                    // 源列表很小，这里同步读一次内存快照；网络与写盘操作都不在这里
                    sourceStore.load().sources
                },
                currentSourceName = sourceStore.load().current()?.name.orEmpty(),
                onBack = { goBack() },
            )
        }
    }

    // ===== 弹窗区 =====

    // 1) 切换试卷：当前这套打「当前」，点一下切过去；这里也保留「删除 / 上传到源」（v1.3 设置页不再单列）
    if (showPaperPicker) {
        PaperPickerDialog(
            papers = papers,
            currentId = session.paperId,
            canDelete = papers.size > 1,
            onPick = { meta ->
                showPaperPicker = false
                if (meta.id != session.paperId) onOpenPaper(meta.id)
            },
            onDelete = { meta ->
                showPaperPicker = false
                deleteTarget = meta
            },
            onUpload = { meta ->
                showPaperPicker = false
                openUpload(meta)
            },
            onDismiss = { showPaperPicker = false },
        )
    }

    // 2) 导入新试卷：二选一
    if (showImportChoice) {
        ImportChoiceDialog(
            onLocal = { startLocalImport() },
            onNetwork = { openNetwork() },
            onDismiss = { showImportChoice = false },
        )
    }

    // 3) 导入结果（本地导入 / 网络下载共用）
    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { onImportMessage(null) },
            title = { Text(text = message.title, style = AppText.stemStrong) },
            text = {
                Text(text = message.body, style = AppText.small, color = c.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = { onImportMessage(null) }) {
                    Text(text = "知道了", style = AppText.smallStrong, color = c.primary)
                }
            },
        )
    }

    // 4) 删除试卷：二次确认，文案含试卷名和题数
    deleteTarget?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = "删除试卷", style = AppText.stemStrong) },
            text = {
                Text(
                    text = "确定删除「${meta.name}」（共 ${meta.total} 题）吗？\n" +
                        "这套试卷的做题记录会一起删除，且无法恢复。",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { doDelete(meta) }) {
                    Text(text = "删除", style = AppText.smallStrong, color = c.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = "取消", style = AppText.smallStrong, color = c.textSecondary)
                }
            },
        )
    }

    // 5) 网络获取：连接中 / 列表 / 下载中 / 错误
    if (netVisible) {
        NetworkDialog(
            state = netState,
            onDownload = { entry -> downloadFromCurrent(entry) },
            onRetry = { loadIndex() },
            onDismiss = { netVisible = false },
        )
    }

    // 6) 11：上传本地试卷到自建源（目标列表里没有默认源）
    uploadTarget?.let { meta ->
        AlertDialog(
            onDismissRequest = { if (!uploadBusy) uploadTarget = null },
            title = { Text(text = "上传到源", style = AppText.stemStrong) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = ComponentSize.dialogListMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "把「${meta.name}」（共 ${meta.total} 题）上传到哪个源？",
                        style = AppText.small,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    when {
                        uploadBusy -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = c.primary,
                                    modifier = Modifier.size(ComponentSize.icon),
                                )
                                Spacer(Modifier.padding(horizontal = Spacing.sm))
                                Text(
                                    text = "正在上传，请不要退出…",
                                    style = AppText.small,
                                    color = c.textSecondary,
                                )
                            }
                        }

                        uploadSources.isEmpty() -> {
                            Text(
                                text = "还没有可上传的源。默认源不能作为上传目标，" +
                                    "请先在「设置 → 题库源」里新建一个自建源。",
                                style = AppText.small,
                                color = c.textSecondary,
                            )
                        }

                        else -> {
                            // uploadSources 只来自 uploadTargets()（uploadable=true 且不是默认源）
                            uploadSources.forEach { source ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { doUpload(meta, source) }
                                        .padding(vertical = Spacing.sm),
                                ) {
                                    Text(
                                        text = source.name,
                                        style = AppText.bodyStrong,
                                        color = c.textPrimary,
                                    )
                                    Text(
                                        text = maskAddress(source.displayAddress()),
                                        style = AppText.caption,
                                        color = c.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (!uploadBusy) uploadTarget = null }) {
                    Text(
                        text = "取消",
                        style = AppText.smallStrong,
                        color = c.textSecondary,
                    )
                }
            },
        )
    }

    // 「继续上次考试 / 放弃」弹窗：只有这两个出口，点弹窗外面不会关掉，
    // 避免考生误触后既没继续也没放弃，磁盘上还留着一份模糊状态。
    resumeExam?.let { saved ->
        val left = saved.remainingSecNow()
        AlertDialog(
            onDismissRequest = { },
            title = { Text("发现未完成的考试", style = AppText.stemStrong) },
            text = {
                Text(
                    text = "上次考试共 ${saved.ids.size} 题，已作答 ${saved.answers.size} 题，" +
                        "还剩 ${left / 60} 分 ${left % 60} 秒。\n要继续上次考试吗？",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { resumeSavedExam(saved) }) {
                    Text("继续上次考试", style = AppText.smallStrong, color = c.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { abandonSavedExam() }) {
                    Text("放弃", style = AppText.smallStrong, color = c.textSecondary)
                }
            },
        )
    }
}

/**
 * 底部导航的一项：选中用主色，未选中用文字次要色，图标只用 Icons.Default.* 里现成的。
 *
 * 07A 动效：选中的图标从 1.0 缩放到 1.12（弹簧），未选中回 1.0；
 * 图标和文字的颜色跟缩放同步过渡（150ms），切 Tab 时不会出现「图标先变、文字后变」。
 */
@Composable
private fun RowScope.BottomTabItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val c = appColors()

    // 选中时放大 1.12，未选中 1.0；弹簧让缩放有回弹但不夸张
    val iconScale by animateFloatAsState(
        targetValue = if (selected) Motion.TAB_ICON_SCALE else 1f,
        animationSpec = motionSpring(),
        label = "Tab 图标缩放",
    )
    // 图标与文字共用一个动画色，保证同步
    val tabColor by animateColorAsState(
        targetValue = if (selected) c.primary else c.textSecondary,
        animationSpec = motionTween(Motion.FAST),
        label = "Tab 图标与文字颜色",
    )

    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tabColor,
                modifier = Modifier.size(ComponentSize.icon).scale(iconScale),
            )
        },
        label = { Text(text = label, style = AppText.caption, color = tabColor) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = c.primary,
            selectedTextColor = c.primary,
            unselectedIconColor = c.textSecondary,
            unselectedTextColor = c.textSecondary,
            indicatorColor = c.primarySoft,
        ),
    )
}

/**
 * 切换试卷弹窗：列表 + 当前标记。
 * v1.3：设置页不再单列「删除 / 上传到源」，这两个操作保留在这里，
 * 功能不丢（本地卷可以上传到自建源，任意卷可以删除，最后一套不能删）。
 */
@Composable
private fun PaperPickerDialog(
    papers: List<PaperMeta>,
    currentId: String?,
    canDelete: Boolean,
    onPick: (PaperMeta) -> Unit,
    onDelete: (PaperMeta) -> Unit,
    onUpload: (PaperMeta) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = appColors()
    FullScreenDialog(title = "切换试卷", onDismiss = onDismiss) {
            if (papers.isEmpty()) {
                Text(text = "还没有任何试卷", style = AppText.small, color = c.textSecondary)
            } else {
                Column {
                    papers.forEach { meta ->
                        val isCurrent = meta.id == currentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onPick(meta) }
                                    .padding(vertical = Spacing.xs),
                            ) {
                                Text(
                                    text = meta.name + if (isCurrent) "（当前）" else "",
                                    style = AppText.bodyStrong,
                                    color = if (isCurrent) c.primary else c.textPrimary,
                                )
                                Text(
                                    text = meta.summary(),
                                    style = AppText.caption,
                                    color = c.textSecondary,
                                )
                            }
                            if (meta.isLocal) {
                                Text(
                                    text = "上传",
                                    style = AppText.smallStrong,
                                    color = c.primary,
                                    modifier = Modifier
                                        .clickable { onUpload(meta) }
                                        .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                                )
                            }
                            Text(
                                text = "删除",
                                style = AppText.smallStrong,
                                color = if (canDelete) c.error else c.border,
                                modifier = Modifier
                                    .clickable(enabled = canDelete) { onDelete(meta) }
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                            )
                        }
                    }
                }
            }
        Spacer(Modifier.height(Spacing.lg))
        AppButton(text = "关闭", onClick = onDismiss, style = AppButtonStyle.TEXT)
    }
}

/** 导入二选一弹窗 */
@Composable
private fun ImportChoiceDialog(
    onLocal: () -> Unit,
    onNetwork: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = appColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "导入新试卷", style = AppText.stemStrong) },
        text = {
            Column {
                Text(
                    text = "选择这套试卷从哪里来：",
                    style = AppText.small,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Spacing.md))
                AppButton(
                    text = "从本地文件导入",
                    onClick = onLocal,
                    style = AppButtonStyle.SECONDARY,
                )
                Spacer(Modifier.height(Spacing.sm))
                AppButton(
                    text = "从网络获取",
                    onClick = onNetwork,
                    style = AppButtonStyle.SECONDARY,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = AppText.smallStrong, color = c.textSecondary)
            }
        },
    )
}

/** v1.3：启动更新检查的延迟（毫秒）。任务书要求启动后 3 秒，不挡首屏 */
private const val STARTUP_CHECK_DELAY_MS = 3_000L

/**
 * v1.3：本地一套试卷都没有时的首页空状态。
 * 不白屏、不崩，只给两个出口（从源下载 / 本地导入），再给一个题库源入口。
 */
@Composable
private fun EmptyPaperScreen(
    hasLegacyData: Boolean,
    onDownload: () -> Unit,
    onLocalImport: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val c = appColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        Text(text = "还没有题库", style = AppText.pageTitle, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "从题库源下载一套，或从手机里选择一个题库文件导入。拿到题库后就能开始刷题。",
            style = AppText.small,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Spacing.xl))
        SectionCard {
            AppButton(text = "从源下载", onClick = onDownload)
            Spacer(Modifier.height(Spacing.sm))
            AppButton(text = "本地导入", onClick = onLocalImport, style = AppButtonStyle.SECONDARY)
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "点这里管理题库源（新建 / 删除 / 恢复默认源）",
                style = AppText.caption,
                color = c.textSecondary,
                modifier = Modifier
                    .clickable { onOpenSources() }
                    .padding(vertical = Spacing.xs),
            )
        }
        if (hasLegacyData) {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "检测到旧版本留下的做题记录。先下载或导入题库，完成后会自动把旧记录搬过来。",
                style = AppText.caption,
                color = c.textSecondary,
            )
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}

/**
 * v1.3：发现新版本题库 → 对话框（下载 / 以后再说）。
 * 「以后再说」只记下这次跳过的版本，同一次启动不再追问；下次启动还会问。
 */
@Composable
private fun UpdatePromptDialog(
    update: PaperUpdate,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    val c = appColors()
    val dateText = update.updatedAt?.let { "（$it）" } ?: ""
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Text(text = "发现新版本题库 v${update.remoteVersion}", style = AppText.stemStrong)
        },
        text = {
            Text(
                text = "题库「${update.localMeta.name}」有新版本$dateText。\n" +
                    "现在下载吗？下载后错题、连对、记忆进度都会自动保留。",
                style = AppText.small,
                color = c.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(text = "下载", style = AppText.smallStrong, color = c.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(text = "以后再说", style = AppText.smallStrong, color = c.textSecondary)
            }
        },
    )
}

/** 网络获取弹窗：连接中 / 可下载列表 / 下载中 / 中文报错 */
@Composable
private fun NetworkDialog(
    state: NetState,
    onDownload: (WebDavEntry) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = appColors()
    val busy = state is NetState.Loading || state is NetState.Downloading
    FullScreenDialog(title = "从网络获取", onDismiss = { if (!busy) onDismiss() }) {
                when (state) {
                    NetState.Loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = c.primary,
                                modifier = Modifier.size(ComponentSize.icon),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                text = "正在连接题库源…",
                                style = AppText.small,
                                color = c.textSecondary,
                            )
                        }
                    }

                    is NetState.Downloading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = c.primary,
                                modifier = Modifier.size(ComponentSize.icon),
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                text = "正在下载「${state.name}」…",
                                style = AppText.small,
                                color = c.textSecondary,
                            )
                        }
                    }

                    is NetState.Error -> {
                        Text(
                            text = state.message,
                            style = AppText.small,
                            color = c.error,
                        )
                    }

                    is NetState.Ready -> {
                        val index: WebDavIndex = state.index
                        when {
                            !index.fileExists -> {
                                Text(
                                    text = "「${state.source.name}」的根目录还没有 papers.json。" +
                                        "到「设置 → 题库源」里点这个源，可以看到更详细的说明。",
                                    style = AppText.small,
                                    color = c.textSecondary,
                                )
                            }

                            index.entries.isEmpty() -> {
                                Text(
                                    text = "「${state.source.name}」的索引读到了，但里面还没有试卷（0 套）。",
                                    style = AppText.small,
                                    color = c.textSecondary,
                                )
                            }

                            else -> {
                                Text(
                                    text = "「${state.source.name}」共 ${index.entries.size} 套试卷，点一套开始下载：",
                                    style = AppText.small,
                                    color = c.textSecondary,
                                )
                                if (index.skipped > 0) {
                                    Text(
                                        text = "（索引里有 ${index.skipped} 条不完整，已忽略）",
                                        style = AppText.caption,
                                        color = c.textSecondary,
                                    )
                                }
                                Spacer(Modifier.height(Spacing.sm))
                                index.entries.forEach { entry ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onDownload(entry) }
                                            .padding(vertical = Spacing.sm),
                                    ) {
                                        Text(
                                            text = entry.name,
                                            style = AppText.bodyStrong,
                                            color = c.textPrimary,
                                        )
                                        Text(
                                            text = "${entry.total} 题 · ${entry.file}",
                                            style = AppText.caption,
                                            color = c.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        Spacer(Modifier.height(Spacing.lg))
        if (state is NetState.Error) {
            AppButton(text = "重试", onClick = onRetry)
        } else {
            AppButton(text = "关闭", onClick = onDismiss, style = AppButtonStyle.TEXT, enabled = !busy)
        }
    }
}

// ===== 下面是不依赖 Compose 的辅助函数与界面状态 =====

/** 导入结果对话框的内容 */
private data class ImportMessage(val title: String, val body: String)

/** 本地导入的结果（成功时带新试卷 id，供外层自动切过去） */
private data class ImportOutcome(
    val title: String,
    val body: String,
    val newPaperId: String?,
)

/** 网络获取弹窗的状态机 */
private sealed interface NetState {
    /** 正在读索引 */
    object Loading : NetState

    /** 正在下载某一套 */
    data class Downloading(val name: String) : NetState

    /** 出错（message 已经是中文原因） */
    data class Error(val message: String) : NetState

    /**
     * 索引读到了，可以选一套。
     * 11：把 source 一起带着，下载时不用再猜「刚才读的是哪个源」。
     */
    data class Ready(val index: WebDavIndex, val source: SourceConfig) : NetState
}

/**
 * 本地文件导入：读 URI → 校验 → 落盘。
 * 全程在 IO 线程（调用方 withContext(Dispatchers.IO)），失败也返回中文原因。
 */
private fun importLocal(context: Context, repo: PaperRepo, uri: Uri): ImportOutcome {
    return try {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: return ImportOutcome(
            title = "导入失败",
            body = "读不到这个文件（可能已被移动，或没有读取权限）",
            newPaperId = null,
        )

        val nameHint = queryDisplayName(context, uri)?.substringBeforeLast(".")
        // 11：本地导入的卷 origin=local、sourceId=null、version=null；
        // 列表里会显示「本地」标签，也能「上传到源」。
        val result = PaperImporter.importTo(
            repo = repo,
            text = text,
            source = "本地导入",
            nameHint = nameHint,
            origin = PaperMeta.ORIGIN_LOCAL,
            sourceId = null,
            version = null,
        )
        val meta = result.meta
        if (meta == null) {
            ImportOutcome(
                title = "导入失败",
                body = result.reason ?: "未知原因",
                newPaperId = null,
            )
        } else {
            // v1.3：如果这是从 v1.1 直接升上来的用户，把根目录的旧做题记录搬过来
            val legacy = migrateLegacyIfNeeded(repo, meta.id, context)
            ImportOutcome(
                title = "导入成功",
                body = buildSuccessText(meta, result.warnings) + legacy,
                newPaperId = meta.id,
            )
        }
    } catch (e: Exception) {
        Log.e("Shuaati", "本地导入失败", e)
        ImportOutcome(
            title = "导入失败",
            body = "读取文件出错：${e.message ?: "未知错误"}",
            newPaperId = null,
        )
    }
}

/**
 * 网络下载一套试卷：下载 JSON → 同一套校验 → 落盘 → 下载图片。
 * 图片失败不阻塞导入，只把「N 张未成功」带回给界面提示。
 *
 * v1.3：落盘目录直接用源上的 paperId（例如 hanjie-gaoji-202610），
 * 这样以后判断「本地这套卷对应源上哪一套」只认 paperId，不再靠名字。
 * 如果本地已经有同 paperId 的卷（之前下载过），就走原地更新流程（带进度迁移），
 * 而不是再新建一套、把做题记录留在旧目录里。
 *
 * 调用方必须已经在 IO 线程（withContext(Dispatchers.IO)）。
 */
private suspend fun downloadAndImport(
    context: Context,
    repo: PaperRepo,
    source: SourceConfig,
    entry: WebDavEntry,
): ImportOutcome {
    return try {
        // ---- 本地已经有同一套（同 paperId）：按「更新」处理，保留并迁移进度 ----
        val existing = repo.metaOf(entry.id)
        if (existing != null) {
            val update = PaperUpdate(
                source = source,
                paperId = entry.id,
                remoteVersion = entry.version,
                updatedAt = null,
                localMeta = existing,
            )
            val applied = UpdateChecker.applyUpdate(repo, update)
            val migration = applied.migration
            return ImportOutcome(
                title = "下载成功",
                body = "已更新「${existing.name}」到第 ${entry.version} 版，共 ${applied.total} 题。\n" +
                    "做题进度已迁移：对上 ${migration.matched} 条" +
                    (if (migration.dropped > 0) "，${migration.dropped} 条旧记录已单独备份" else "") + "。",
                newPaperId = existing.id,
            )
        }

        val text = WebDavSource.fetchQuestions(source, entry)

        // 先校验：坏文件绝不落盘
        val validation = PaperImporter.parseAndValidate(text)
        if (validation is PaperImporter.Validation.Fail) {
            return ImportOutcome(
                title = "下载失败",
                body = "下载的题库校验不通过：${validation.reason}",
                newPaperId = null,
            )
        }

        val imported = PaperImporter.importTo(
            repo = repo,
            text = text,
            source = "网络下载（${source.name}）",
            nameHint = entry.name,
            origin = PaperMeta.ORIGIN_CLOUD,
            sourceId = source.id,
            version = entry.version,
            remoteId = entry.id,
            targetId = entry.id,
        )
        val meta = imported.meta
            ?: return ImportOutcome(
                title = "下载失败",
                body = imported.reason ?: "保存试卷失败",
                newPaperId = null,
            )

        // 图片：失败不影响题目可用，但要在结果里说清楚
        val imageInfo: ImageDownloadResult = try {
            WebDavSource.downloadImages(source, text, repo.imagesDir(meta.id))
        } catch (e: Exception) {
            Log.w("Shuaati", "图片下载整体失败", e)
            ImageDownloadResult(total = 0, failed = 0, failedNames = emptyList())
        }
        val warnings = ArrayList(imported.warnings)
        if (imageInfo.failed > 0) {
            warnings.add(
                "${imageInfo.failed} 张图片未下载成功（${imageInfo.failedNames.joinToString("、")}），" +
                    "题目本身可以正常刷",
            )
        }
        if (imageInfo.total > 0 && imageInfo.failed == 0) {
            warnings.add("${imageInfo.total} 张图片已一起下载")
        }

        // v1.3：从 v1.1 直接升上来的用户，旧做题记录在 filesDir 根目录，这里搬过来
        val legacy = migrateLegacyIfNeeded(repo, meta.id, context)

        ImportOutcome(
            title = "下载成功",
            body = buildSuccessText(meta, warnings) + legacy,
            newPaperId = meta.id,
        )
    } catch (e: WebDavException) {
        ImportOutcome(
            title = "下载失败",
            body = e.message ?: "网络请求失败",
            newPaperId = null,
        )
    } catch (e: Exception) {
        Log.e("Shuaati", "网络下载失败", e)
        ImportOutcome(
            title = "下载失败",
            body = "下载出错：${e.message ?: "未知错误"}",
            newPaperId = null,
        )
    }
}

/**
 * v1.3：把 v1.1 留在 filesDir 根目录的旧做题记录搬进新试卷。
 * 没有旧数据时什么都不做，返回空字符串；有旧数据时返回一句给用户看的说明。
 */
private fun migrateLegacyIfNeeded(repo: PaperRepo, paperId: String, context: Context): String {
    if (!repo.hasLegacyData()) return ""
    return try {
        val questions = QuestionRepo.loadFromFile(repo.questionsFile(paperId))
        val report = repo.migrateLegacyData(paperId, questions)
        Log.i("Shuaati", "v1.1 旧数据迁移：moved=${report.moved} dropped=${report.dropped}")
        "\n已把旧版本的做题记录搬过来：${report.moved} 条" +
            if (report.dropped > 0) "，${report.dropped} 条对不上新题目已单独备份" else ""
    } catch (e: Exception) {
        Log.w("Shuaati", "v1.1 旧数据迁移失败（旧文件保留，不影响使用）", e)
        ""
    }
}

/** 成功对话框的正文：题量 + 之前的提示 */
private fun buildSuccessText(meta: PaperMeta, warnings: List<String>): String {
    val builder = StringBuilder()
    builder.append("已导入「${meta.name}」，共 ${meta.total} 题。")
    builder.append("\n已自动切换到这套试卷。")
    warnings.forEach { warning ->
        builder.append("\n· ").append(warning)
    }
    return builder.toString()
}

/** 用系统文件选择器给的 display name 当试卷名（去掉扩展名） */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
    } catch (e: Exception) {
        null
    }
}
