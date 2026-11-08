// 数据层（非算法）：设置项的存取。
//
// 只做一件事：把「启动页」「首页显示哪几块」「引导看过哪几屏」「题库源以外的开关」
// 存进 SharedPreferences，并在值变化时通知 Compose 重组。
// 不碰 progress.json，不碰判分/错题/组卷/智能刷的任何算法。
//
// 字段用 mutableStateOf 包着：设置页改了开关，首页立刻跟着消失/出现，不用重启 App。
package com.shuaati.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 引导页每一屏的稳定 id（11 新增，任务书第 6 条第 1 点）。
 *
 * 规矩：**id 一旦发布就不许改**——它记录在用户的 SharedPreferences 里，
 * 改了等于让老用户又看一遍那屏。以后加新屏只能往后排，并把新 id 加进 [ALL]。
 */
object OnboardingIds {
    /** 欢迎 */
    const val WELCOME = "welcome"

    /** 选择题库来源（11 新增，插在欢迎之后） */
    const val SOURCE = "source"

    /** 统一刷 */
    const val FEATURE_QUIZ = "feature_quiz"

    /** 错题本 */
    const val FEATURE_WRONG = "feature_wrong"

    /** 模拟考试 */
    const val FEATURE_EXAM = "feature_exam"

    /** 智能刷 */
    const val FEATURE_SMART = "feature_smart"

    /** 首页显示（统计开关） */
    const val STATS = "stats"

    /** 从哪一页开始（启动页） */
    const val START_PAGE = "start_page"

    /** v1.2 全部屏：顺序就是播放顺序 */
    val ALL: List<String> = listOf(
        WELCOME,
        SOURCE,
        FEATURE_QUIZ,
        FEATURE_WRONG,
        FEATURE_EXAM,
        FEATURE_SMART,
        STATS,
        START_PAGE,
    )

    /**
     * v1.1（10 及更早）已有的 7 屏。
     * 老用户覆盖升级时把这 7 个 id 全部标成已看，只留 [SOURCE] 未看 ——
     * 效果就是老师升级到 v1.2 后只弹「选择题库来源」这一屏。
     */
    val V11: Set<String> = setOf(
        WELCOME,
        FEATURE_QUIZ,
        FEATURE_WRONG,
        FEATURE_EXAM,
        FEATURE_SMART,
        STATS,
        START_PAGE,
    )
}

/**
 * 设置读写器。
 *
 * 存储位置：SharedPreferences 文件 `shuaati_settings`，键名见各常量。
 * 默认值都写在这里，第一次安装不写盘也能正常显示。
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 启动页：首页 / 刷题。
     * 07A 修订：默认改成「首页」——打开先看到进度统计，比直接进答题页更像「首页」；
     * 用户也可以在引导页或设置页改成「刷题」。
     */
    var defaultTab: String by mutableStateOf(
        prefs.getString(KEY_DEFAULT_TAB, TAB_HOME) ?: TAB_HOME
    )
        private set

    /** 首页是否显示「本轮进度」大环 */
    var showRing: Boolean by mutableStateOf(prefs.getBoolean(KEY_SHOW_RING, true))
        private set

    /** 首页是否显示四宫格统计 */
    var showTiles: Boolean by mutableStateOf(prefs.getBoolean(KEY_SHOW_TILES, true))
        private set

    /** 首页是否显示分题型三个小环 */
    var showTypeRings: Boolean by mutableStateOf(prefs.getBoolean(KEY_SHOW_TYPE_RINGS, true))
        private set

    /** 首页是否显示「连续刷题 N 天」 */
    var showStreak: Boolean by mutableStateOf(prefs.getBoolean(KEY_SHOW_STREAK, true))
        private set

    /** 首页是否显示「＋ 导入新试卷」按钮 */
    var showImport: Boolean by mutableStateOf(prefs.getBoolean(KEY_SHOW_IMPORT, true))
        private set

    /**
     * 题库源的「自定义地址」（06B 留下的旧字段）。
     * 11 起题库源改成多源列表（data/SourceStore.kt），这里不再读写它，
     * 保留属性只是为了老配置文件读进来时不报错、也不做任何自动迁移（默认源本来就是内置的）。
     */
    var webDavCustomUrl: String by mutableStateOf(
        prefs.getString(KEY_WEBDAV_URL, "") ?: ""
    )
        private set

    /**
     * 是否已经完成（或跳过）首次引导（07 新增）。
     * 11 起它只是兼容旧逻辑的布尔值，真正的「哪些屏看过」由 [seenOnboardingIds] 决定。
     */
    var onboarded: Boolean by mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false))
        private set

    /** 调试模式（11 新增）：关于页连点版本号 7 次并输入正确密码后打开 */
    var debugEnabled: Boolean by mutableStateOf(prefs.getBoolean(KEY_DEBUG_ENABLED, false))
        private set

    /** 启动时检查试卷更新（11 新增）：默认关闭 */
    var startupCheckUpdates: Boolean by mutableStateOf(prefs.getBoolean(KEY_STARTUP_CHECK, false))
        private set

    /** 改启动页，立刻落盘（方法名用 update 前缀，避免与属性 setter 的 JVM 签名冲突） */
    fun updateDefaultTab(tab: String) {
        val safe = if (tab == TAB_HOME) TAB_HOME else TAB_QUIZ
        defaultTab = safe
        prefs.edit().putString(KEY_DEFAULT_TAB, safe).apply()
    }

    /** 改「本轮进度大环」开关 */
    fun updateShowRing(value: Boolean) {
        showRing = value
        prefs.edit().putBoolean(KEY_SHOW_RING, value).apply()
    }

    /** 改「四宫格统计」开关 */
    fun updateShowTiles(value: Boolean) {
        showTiles = value
        prefs.edit().putBoolean(KEY_SHOW_TILES, value).apply()
    }

    /** 改「分题型小环」开关 */
    fun updateShowTypeRings(value: Boolean) {
        showTypeRings = value
        prefs.edit().putBoolean(KEY_SHOW_TYPE_RINGS, value).apply()
    }

    /** 改「连续刷题天数」开关 */
    fun updateShowStreak(value: Boolean) {
        showStreak = value
        prefs.edit().putBoolean(KEY_SHOW_STREAK, value).apply()
    }

    /** 改「导入新试卷按钮」开关 */
    fun updateShowImport(value: Boolean) {
        showImport = value
        prefs.edit().putBoolean(KEY_SHOW_IMPORT, value).apply()
    }

    /** 改题库源的自定义地址（11 起界面不再提供入口，保留兼容） */
    fun updateWebDavCustomUrl(value: String) {
        webDavCustomUrl = value.trim()
        prefs.edit().putString(KEY_WEBDAV_URL, webDavCustomUrl).apply()
    }

    /** 改「启动时检查试卷更新」；用 commit，下一刻就要按它决定要不要联网 */
    fun updateStartupCheckUpdates(value: Boolean) {
        startupCheckUpdates = value
        prefs.edit().putBoolean(KEY_STARTUP_CHECK, value).commit()
    }

    /**
     * 打开 / 关闭调试模式。
     * 用 commit()：开关状态决定设置页要不要出现调试块，必须立刻落盘。
     */
    fun updateDebugEnabled(value: Boolean) {
        debugEnabled = value
        prefs.edit().putBoolean(KEY_DEBUG_ENABLED, value).commit()
    }

    /**
     * 标记「引导已完成」。
     * 用 commit() 而不是 apply()：这里写完马上就要切界面，
     * 万一用户紧接着杀进程，apply() 的异步落盘可能还没完成，下次又会看到引导页。
     */
    fun markOnboarded() {
        onboarded = true
        prefs.edit().putBoolean(KEY_ONBOARDED, true).commit()
    }

    // ===== 11 新增：增量引导 =====

    /** 已经看过的屏 id（SharedPreferences 里是逗号拼接的字符串，按任务书允许的写法） */
    fun seenOnboardingIds(): Set<String> {
        val raw = prefs.getString(KEY_SEEN_ONBOARDING, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** 是否已经有「看过哪些屏」的记录（老用户升级时没有，这是迁移的判定条件） */
    fun hasOnboardingSeenRecord(): Boolean = prefs.contains(KEY_SEEN_ONBOARDING)

    /** 还没看过的屏（按 OnboardingIds.ALL 的顺序无关；播放端会自己按原顺序过滤） */
    fun unseenOnboardingIds(): Set<String> = OnboardingIds.ALL.toSet() - seenOnboardingIds()

    /** 是否还需要播引导：还有没看过的屏就要播 */
    fun needsOnboarding(): Boolean = unseenOnboardingIds().isNotEmpty()

    /**
     * 把这次播过的 id 写进 seenOnboardingIds（并集，不会把以前看过的抹掉）。
     */
    fun markOnboardingSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val merged = seenOnboardingIds() + ids
        prefs.edit().putString(KEY_SEEN_ONBOARDING, merged.joinToString(",")).commit()
    }

    /**
     * 一次引导结束（走完或跳过）：写 seen + 兼容旧的 onboarded=true。
     * @param ids 这次播过的屏 id
     */
    fun finishOnboarding(ids: Collection<String>) {
        markOnboardingSeen(ids)
        markOnboarded()
    }

    /**
     * 设置页「重看引导」：清空记录并把 onboarded 置 false。
     * 只写盘，不改本轮已经显示的主界面——下次启动才会重播全部（任务书第 6 条第 8 点）。
     */
    fun resetOnboarding() {
        onboarded = false
        prefs.edit()
            .remove(KEY_SEEN_ONBOARDING)
            .putBoolean(KEY_ONBOARDED, false)
            .commit()
    }

    /**
     * 老用户迁移（任务书第 6 条第 5 点）。
     *
     * 判定：onboarded == true 但 seenOnboardingIds 这个键不存在 →
     * 把 v1.1 已有的 7 个 id 全部标记为已看，只留 `source` 未看。
     *
     * @return true 表示这次真的做了迁移（日志里要打一行）
     */
    fun ensureOnboardingMigrated(): Boolean {
        val alreadyOnboarded = prefs.getBoolean(KEY_ONBOARDED, false)
        if (!alreadyOnboarded) return false
        if (prefs.contains(KEY_SEEN_ONBOARDING)) return false
        prefs.edit()
            .putString(KEY_SEEN_ONBOARDING, OnboardingIds.V11.joinToString(","))
            .commit()
        onboarded = true
        return true
    }

    companion object {
        /** SharedPreferences 文件名 */
        const val PREFS_NAME = "shuaati_settings"

        /** 启动页 = 首页 */
        const val TAB_HOME = "home"

        /** 启动页 = 刷题 */
        const val TAB_QUIZ = "quiz"

        const val KEY_DEFAULT_TAB = "defaultTab"
        const val KEY_SHOW_RING = "homeShowRing"
        const val KEY_SHOW_TILES = "homeShowTiles"
        const val KEY_SHOW_TYPE_RINGS = "homeShowTypeRings"
        const val KEY_SHOW_STREAK = "homeShowStreak"
        const val KEY_SHOW_IMPORT = "homeShowImport"

        /** 06B：题库源自定义地址（11 起不再使用，保留键名做兼容） */
        const val KEY_WEBDAV_URL = "webDavCustomUrl"

        /** 07：首次引导是否已经走完（或跳过） */
        const val KEY_ONBOARDED = "onboarded"

        /** 11：看过哪些引导屏（逗号拼接的稳定 id） */
        const val KEY_SEEN_ONBOARDING = "seenOnboardingIds"

        /** 11：调试模式开关 */
        const val KEY_DEBUG_ENABLED = "debugEnabled"

        /** 11：启动时检查试卷更新，默认关闭 */
        const val KEY_STARTUP_CHECK = "startupCheckUpdates"
    }
}
