// 数据层：把每道题的作答进度、各模式本轮已出过的题、当前停在的题持久化到
// filesDir\papers\<paperId>\progress.json（06B 起一套试卷一份，互不干扰）。
package com.shuaati.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// 单题进度 ProgressRecord 已拆到 ProgressRecord.kt：
// 那个文件不依赖 Android，能在电脑上用 kotlinc 直接编译，方便验证 SmartPicker 的权重公式。
// FSRS 的公式在 Fsrs.kt，同样不依赖 Android。

/**
 * 轮次键名。
 * 「统一刷」沿用任务 03 的 `_round`（旧进度文件不用迁移就能继续用）；
 * 三种题型专练各用各的键，互不污染，所以「专练刷了 5 题」不会让统一刷的本轮数字变成 5。
 */
object RoundKeys {
    const val UNIFIED = "_round"
    const val SINGLE = "_round_single"
    const val JUDGE = "_round_judge"
    const val MULTI = "_round_multi"

    /** 保存与读取时统一遍历这个清单；以后加新模式只在这里加一行 */
    val ALL = listOf(UNIFIED, SINGLE, JUDGE, MULTI)
}

/**
 * 进度存储器。**一个实例对应一套试卷的一个文件**，切试卷时由 PaperSession 重新构造。
 *
 * 文件结构固定为：
 * { "S001": {seen,correct,wrong,streak,last,recent,stability,difficulty},
 *   "_round": [...], "_round_single": [...], "_round_judge": [...], "_round_multi": [...],
 *   "_current": "S001" }。
 * 读坏文件时按空进度处理，绝不让 App 崩；每次改动都立刻写盘，进程被杀也不会丢。
 *
 * @param file 进度文件（App 里永远指向「当前试卷」目录下的 progress.json）
 */
class ProgressStore(private val file: File) {

    /** 便利构造：不关心试卷、只想用默认位置时用（旧调用点与测试都还能用） */
    constructor(context: Context) : this(File(context.filesDir, "progress.json"))

    private val tag = "Shuaati"

    /** 各题进度，key 是题目 id；LinkedHashMap 只为让写盘顺序稳定，便于人工查看 */
    private val records = LinkedHashMap<String, ProgressRecord>()

    /**
     * 各模式的本轮已出题 id，key 是轮次键名（_round / _round_single / …）。
     * 用 Map 而不是三个散装字段，是为了「新增一种专练只加一行键名」，不用再改读写逻辑。
     */
    private val rounds = LinkedHashMap<String, LinkedHashSet<String>>()

    /** 统一刷当前停在的题 id；退出再进靠它回到同一题。专练和错题本不写这个字段。 */
    private var currentId: String? = null

    /** 已成功从磁盘读进来的题目记录条数（迁移对账用） */
    var loadedRecordCount: Int = 0
        private set

    init {
        load()
    }

    /** 进度文件的绝对路径，日志与验收时打印用 */
    val filePath: String get() = file.absolutePath

    /** 取（或新建）某个轮次的已出题集合 */
    private fun roundSet(key: String): LinkedHashSet<String> =
        rounds.getOrPut(key) { LinkedHashSet() }

    /** 某个轮次本轮已出过多少题 */
    fun roundCount(key: String = RoundKeys.UNIFIED): Int = roundSet(key).size

    /** 某个轮次本轮出过的题 id，按出题顺序 */
    fun roundIdList(key: String = RoundKeys.UNIFIED): List<String> = roundSet(key).toList()

    fun currentQuestionId(): String? = currentId

    fun recordOf(id: String): ProgressRecord = records[id] ?: ProgressRecord()

    fun wrongCount(id: String): Int = recordOf(id).wrong

    /** 这道题在指定轮次里出过没有（默认查统一刷那一轮） */
    fun containsRound(id: String, key: String = RoundKeys.UNIFIED): Boolean = roundSet(key).contains(id)

    /** 把一道题记进某个轮次，已存在就不重复添加 */
    fun addToRound(id: String, key: String = RoundKeys.UNIFIED) {
        if (roundSet(key).add(id)) saveQuietly()
    }

    /** 某个轮次出满之后清空它，自动开始新一轮（其他轮次不受影响） */
    fun resetRound(key: String = RoundKeys.UNIFIED) {
        val set = roundSet(key)
        if (set.isNotEmpty()) {
            set.clear()
            saveQuietly()
        }
    }

    /** 记录统一刷停在哪一题；每次切题立刻写盘 */
    fun setCurrent(id: String?) {
        currentId = id
        saveQuietly()
    }

    /**
     * 记录一次作答：对就 correct+1、streak+1，错就 wrong+1、streak 归零，seen 都加 1。
     * 同时把这次对错追加进 recent（智能刷的权重靠它），
     * 并按 FSRS 更新这道题的记忆稳定度 S 与难度 D。
     * 写盘成功才会保留这次改动，失败则回滚，保证界面上的数字与文件里的数字一致。
     */
    fun markAnswered(id: String, correct: Boolean): Boolean = mutate(id) { base ->
        // 不管对错都由 appendRecent 统一处理「满 10 个丢最旧的」，
        // 这样两个分支只有计数不同，recent 的规则不会在两边各写一遍
        val nextRecent = ProgressRecord.appendRecent(base.recent, correct)
        // FSRS：注意传进去的是**作答前**的 base（它的 last 才是上次作答日期）
        val (nextStability, nextDifficulty) = Fsrs.next(base, correct, LocalDate.now())
        if (correct) {
            base.copy(
                seen = base.seen + 1,
                correct = base.correct + 1,
                streak = base.streak + 1,
                last = today(),
                recent = nextRecent,
                stability = nextStability,
                difficulty = nextDifficulty,
            )
        } else {
            base.copy(
                seen = base.seen + 1,
                wrong = base.wrong + 1,
                streak = 0,
                last = today(),
                recent = nextRecent,
                stability = nextStability,
                difficulty = nextDifficulty,
            )
        }
    }

    /** 只记「出现过/作答过」一次，不判对错；给 answer 为空的题用（不改 S/D） */
    fun markSeen(id: String): Boolean = mutate(id) { base ->
        base.copy(seen = base.seen + 1, last = today())
    }

    private fun today(): String = LocalDate.now().toString()

    /** 改动一条记录并落盘；失败回滚，返回是否写盘成功 */
    private fun mutate(id: String, change: (ProgressRecord) -> ProgressRecord): Boolean {
        val old = records[id]
        records[id] = change(old ?: ProgressRecord())
        return try {
            save()
            true
        } catch (e: Exception) {
            if (old == null) records.remove(id) else records[id] = old
            Log.e(tag, "作答进度写盘失败：$id", e)
            false
        }
    }

    private fun saveQuietly() {
        try {
            save()
        } catch (e: Exception) {
            Log.e(tag, "进度写盘失败", e)
        }
    }

    /** 写整个进度文件：先写临时文件再改名，避免写到一半被杀导致文件损坏 */
    private fun save() {
        val root = JSONObject()
        records.forEach { (id, r) ->
            root.put(
                id,
                JSONObject().apply {
                    put("seen", r.seen)
                    put("correct", r.correct)
                    put("wrong", r.wrong)
                    put("streak", r.streak)
                    put("last", r.last)
                    // recent 存成 [1,0,1,…]，最新的在最后；没有作答记录时存空数组，
                    // 不省略这个键——这样文件一眼就能看出是「新格式」
                    put(
                        "recent",
                        JSONArray().apply {
                            r.recent.forEach { put(it) }
                        },
                    )
                    // 06B 新增：FSRS 的两个字段。从没学过时 stability 写 0，difficulty 写 5.0，
                    // 也不省略——这样「没有 S/D 字段」就只可能是老文件，迁移判定不会错。
                    put("stability", r.stability)
                    put("difficulty", r.difficulty)
                }
            )
        }
        // 每个轮次都写成独立数组：统一刷 _round、单选 _round_single、判断 _round_judge、多选 _round_multi
        RoundKeys.ALL.forEach { key ->
            val ids = rounds[key]?.toList() ?: emptyList()
            root.put(key, JSONArray(ids))
        }
        root.put("_current", currentId ?: JSONObject.NULL)

        val text = root.toString(2)
        val tmp = File(file.parentFile, "progress.json.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 个别文件系统不支持覆盖式改名，退回直接写
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /** 读取进度；文件损坏、字段类型不对等任何异常都按空进度处理 */
    private fun load() {
        if (!file.exists()) return
        // 本次读到的「没有 recent 字段」的记录条数；>0 说明是老格式文件，读完要回写升级
        var legacyRecords = 0
        // 本次读到的「没有 stability / difficulty 字段」的记录条数（06B 的 FSRS 迁移）
        var fsrsRecords = 0
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when {
                    // 所有以 _round 开头的键都是轮次（_round、_round_single、_round_judge、_round_multi）
                    key.startsWith("_round") -> {
                        val arr = root.optJSONArray(key)
                        if (arr != null) {
                            val set = roundSet(key)
                            for (i in 0 until arr.length()) {
                                val id = arr.optString(i, "")
                                if (id.isNotBlank()) set.add(id)
                            }
                        }
                    }
                    key == "_current" -> {
                        currentId = if (root.isNull("_current")) {
                            null
                        } else {
                            root.optString("_current", "").takeIf { it.isNotBlank() }
                        }
                    }
                    else -> {
                        val obj = root.optJSONObject(key) ?: continue
                        // 老版本进度文件没有 recent 字段：这里按空数组处理并记一笔，
                        // 读完统一回写一次，把磁盘上的文件也真的升级成新格式
                        if (obj.optJSONArray("recent") == null) legacyRecords++
                        val recent = parseRecent(obj)

                        // 06B：老记录没有 S/D，不能一律给 0（那会让所有老题同时变成「从没学过」），
                        // 按任务书规则初始化：S = max(0.5, recent 里 1 的个数 × 2.0)，D = 5.0
                        val rawStability = readDouble(obj, "stability")
                        val rawDifficulty = readDouble(obj, "difficulty")
                        val missingFsrs = rawStability == null || rawDifficulty == null
                        if (missingFsrs) fsrsRecords++

                        records[key] = ProgressRecord(
                            seen = obj.optInt("seen", 0),
                            correct = obj.optInt("correct", 0),
                            wrong = obj.optInt("wrong", 0),
                            streak = obj.optInt("streak", 0),
                            last = obj.optString("last", ""),
                            recent = recent,
                            stability = if (missingFsrs) {
                                Fsrs.migratedStability(recent)
                            } else {
                                // 已经迁移过的记录：保留原值，只把明显越界的夹回来（0 是合法的「没学过」）
                                // 这里用 !! 是安全的：missingFsrs 为 false 就说明两个字段都读到了数字
                                rawStability!!.coerceIn(0.0, Fsrs.MAX_STABILITY)
                            },
                            difficulty = if (missingFsrs) {
                                Fsrs.DEFAULT_DIFFICULTY
                            } else {
                                Fsrs.clampDifficulty(rawDifficulty!!)
                            },
                        )
                    }
                }
            }
            loadedRecordCount = records.size
            Log.i(
                tag,
                "progress loaded: records=${records.size}, " +
                    RoundKeys.ALL.joinToString(", ") { "$it=${roundSet(it).size}" } +
                    ", current=$currentId",
            )
            // 老数据迁移：只要发现有记录没有 recent 字段，就立刻按新格式回写一次。
            // 这样迁移不只发生在内存里，磁盘上的文件也真的升级了；
            // 回写失败只在 saveQuietly 里记日志，绝不影响启动。
            if (legacyRecords > 0) {
                Log.i(tag, "迁移：$legacyRecords 条老进度缺少 recent 字段，已按空数组补全并回写")
            }
            // 06B 的 FSRS 迁移：迁移后必须回写，否则每次启动都会重算一遍
            if (fsrsRecords > 0) {
                Log.i(tag, "fsrs-migrated: n=$fsrsRecords")
            }
            if (legacyRecords > 0 || fsrsRecords > 0) saveQuietly()
        } catch (e: Exception) {
            records.clear()
            rounds.clear()
            currentId = null
            loadedRecordCount = 0
            Log.w(tag, "进度文件读取失败，已按空进度处理", e)
        }
    }

    /**
     * 读一个 double 字段。
     * 字段缺失、是 null、或者不是数字（老版本文本、手工改坏）都返回 null，
     * 由调用方决定按「老数据」初始化，而不是悄悄变成 0.0。
     */
    private fun readDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value
    }

    /**
     * 解析 recent 字段 —— 老数据迁移的关键一行。
     * - 字段不存在、不是数组、单个元素不是 0/1：一律丢弃，按空数组处理；
     * - 长度超过 [ProgressRecord.RECENT_MAX] 时只保留最后 10 个（文件被手工改长也不会把正确率算歪）。
     * 整个过程不抛异常，所以带不带 recent 的 progress.json 读进来都不会崩。
     */
    private fun parseRecent(obj: JSONObject): List<Int> {
        val arr = obj.optJSONArray("recent") ?: return emptyList()
        val list = ArrayList<Int>(minOf(arr.length(), ProgressRecord.RECENT_MAX))
        for (i in 0 until arr.length()) {
            val value = arr.optInt(i, -1)
            if (value == 0 || value == 1) list.add(value)
        }
        return if (list.size > ProgressRecord.RECENT_MAX) {
            list.takeLast(ProgressRecord.RECENT_MAX)
        } else {
            list
        }
    }
}
