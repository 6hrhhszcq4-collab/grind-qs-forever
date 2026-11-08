// 界面状态层：负责抽题、当场判分、切题，并把每次变化立刻写进 ProgressStore
package com.shuaati.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shuaati.app.data.GradeResult
import com.shuaati.app.data.Grading
import com.shuaati.app.data.ProgressStore
import com.shuaati.app.data.Question
import com.shuaati.app.data.RoundKeys
import kotlin.random.Random

/**
 * 答题页唯一的可变状态。
 * 三种玩法共用这一个类，靠构造参数区分：
 * - 统一刷：roundKey = _round，会记住「停在哪题」，下次点统一刷接着上次那道；
 * - 题型专练：roundKey = _round_single / _round_judge / _round_multi，只在自己这个子集里轮，
 *   不会和统一刷的本轮进度互相污染；
 * - 错题本：roundKey = null、ordered = true，严格按传进来的错题列表顺序往下刷，不随机、不记轮次。
 *
 * 所有会影响「进度」的动作（抽题、切题、提交）都在这里调用 ProgressStore；
 * 界面只读这里的状态，不自己算对错、不自己算错次。
 */
class QuizState(
    private val questions: List<Question>,
    private val store: ProgressStore,
    /** 答题页顶部显示的标题，例如「刷题」「单选专练」「错题本」「智能刷」 */
    val title: String = "刷题",
    /** 本轮已出题的键名；null 表示这个模式不参与任何轮次（错题本、智能刷） */
    private val roundKey: String? = RoundKeys.UNIFIED,
    /** 是否沿用「统一刷停在哪题」的记录（只有统一刷为 true） */
    private val resumeLast: Boolean = false,
    /** 是否按列表顺序出题（错题本、智能刷为 true，其余随机） */
    private val ordered: Boolean = false,
    /**
     * 按顺序刷到最后一题是否「刷完就结束」。
     * 错题本是刷到末尾回到第一条（无穷刷）；智能刷是一组固定 20 题，刷完要出小结，所以为 true。
     */
    private val stopAtEnd: Boolean = false,
) {
    private val byId = questions.associateBy { it.id }

    /** 当前显示的题；为空表示还没开始刷 */
    var current by mutableStateOf<Question?>(null)
        private set

    /** 当前题已选中的选项 key（多选题可多个） */
    var selectedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 是否已提交（提交后选项不能再点） */
    var submitted by mutableStateOf(false)
        private set

    /** 判分结果：true 对 / false 错 / null 未提交或暂无答案 */
    var correct by mutableStateOf<Boolean?>(null)
        private set

    /** 本题是否「暂无答案」（answer 为空），此时不判对错 */
    var noAnswer by mutableStateOf(false)
        private set

    /** 本题已错次数，直接取自落盘进度 */
    var wrongCount by mutableStateOf(0)
        private set

    /** 本轮已出过的题数（错题本模式下表示当前是第几条） */
    var roundCount by mutableStateOf(if (roundKey != null) store.roundCount(roundKey) else 0)
        private set

    /** 本组是否已经刷完（只有 stopAtEnd 的模式会置 true，例如智能刷刷完 20 题） */
    var finished by mutableStateOf(false)
        private set

    /** 本组已判分的题数（暂无答案的题不算，因为它既不是对也不是错） */
    var groupAnswered by mutableStateOf(0)
        private set

    /** 本组答对题数 */
    var groupCorrect by mutableStateOf(0)
        private set

    /** 本组答错的题，按作答先后排列；小结页的「本组答错」列表就是它 */
    var groupWrong by mutableStateOf<List<Question>>(emptyList())
        private set

    /** 本组「暂无答案」的题数：既不算对也不算错，也不进正确率的分母 */
    var groupNoAnswer by mutableStateOf(0)
        private set

    /** 当前题在本模式题池里的位置，从 0 开始；错题本的顺序就靠它 */
    private var orderIndex by mutableStateOf(0)

    /** 本模式的题池大小：统一刷是 300，专练是该题型题量，错题本是错题条数 */
    val total: Int get() = questions.size

    /** 顶部进度文字：统一刷/专练显示「本轮 x/N」，错题本显示「第 x/N 题」 */
    val progressText: String
        get() = if (ordered) {
            "第 ${(orderIndex + 1).coerceAtMost(total.coerceAtLeast(1))}/$total 题"
        } else {
            "本轮 $roundCount/$total"
        }

    /**
     * 开始刷题。startId 是要从哪一题开始（错题本点的那一条；其余模式传 null）。
     * - 指定了题：直接打开那一题（错题本用它实现「从这道题开始」）；
     * - 统一刷且上次有记录：回到上次那道题（退出再进接着刷）；
     * - 其余情况：按本模式规则抽下一题。
     */
    fun start(startId: String? = null) {
        val direct = startId?.let { byId[it] }
        if (direct != null) {
            val idx = questions.indexOfFirst { it.id == direct.id }
            if (idx >= 0) orderIndex = idx
            open(direct)
            return
        }

        if (resumeLast) {
            val saved = store.currentQuestionId()?.let { byId[it] }
            if (saved != null) {
                val key = roundKey
                if (key != null && !store.containsRound(saved.id, key)) store.addToRound(saved.id, key)
                open(saved)
                return
            }
        }

        next()
    }

    /**
     * 下一题。
     * - 错题本：按列表顺序 +1，走到末尾回到第一条；
     * - 统一刷/专练：从本模式「本轮没出过」的题里随机抽，本模式出满后只清空本模式那一轮。
     */
    fun next() {
        if (questions.isEmpty()) return

        if (ordered) {
            val idx = if (current == null) orderIndex else orderIndex + 1
            if (idx >= questions.size) {
                // 智能刷：一组 20 题刷完就停在这里，由界面切到小结页（current 保持最后一题不动）
                if (stopAtEnd) {
                    finished = true
                    return
                }
                // 错题本：刷到末尾回到第一条，没有「刷完」这个概念
                orderIndex = 0
                open(questions[0])
                return
            }
            orderIndex = idx
            open(questions[idx])
            return
        }

        val key = roundKey
        if (key == null) {
            // 兜底：既不按顺序也没有轮次的模式，纯随机
            open(questions[Random.nextInt(questions.size)])
            return
        }

        if (store.roundCount(key) >= questions.size) store.resetRound(key)

        val candidates = questions.filter { !store.containsRound(it.id, key) }
        val picked = if (candidates.isEmpty()) {
            // 理论上不会走到这里，兜底也保证能出题
            questions[Random.nextInt(questions.size)]
        } else {
            candidates[Random.nextInt(candidates.size)]
        }

        store.addToRound(picked.id, key)
        open(picked)
    }

    /** 选中/取消一个选项；单选和判断题点一下立即提交，多选题等「确认」 */
    fun toggleOption(key: String) {
        if (submitted) return
        val question = current ?: return
        selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
        if (!question.isMulti) submit()
    }

    /** 提交当前选择并判分；判分规则统一走 Grading，与模拟考试共用同一份 */
    fun submit() {
        val question = current ?: return
        if (submitted) return
        if (selectedKeys.isEmpty()) return

        submitted = true
        when (Grading.grade(question, selectedKeys)) {
            GradeResult.NO_ANSWER -> {
                // 暂无答案：不判对错，只记「出现过一次」；
                // 也不算进本组正确率（既不是对也不是错，不该被扣分）
                noAnswer = true
                correct = null
                store.markSeen(question.id)
                groupNoAnswer += 1
            }
            GradeResult.CORRECT -> {
                noAnswer = false
                correct = true
                store.markAnswered(question.id, true)
                groupAnswered += 1
                groupCorrect += 1
            }
            GradeResult.WRONG -> {
                noAnswer = false
                correct = false
                store.markAnswered(question.id, false)
                groupAnswered += 1
                // 新建一个列表而不是原地 add：Compose 靠引用变化发现「本组错题变了」
                groupWrong = groupWrong + question
            }
        }
        refreshCounts()
    }

    /** 显示一道题，重置作答状态；统一刷才写「当前题」，专练与错题本不碰 _current */
    private fun open(question: Question) {
        current = question
        selectedKeys = emptySet()
        submitted = false
        correct = null
        noAnswer = false
        if (resumeLast) store.setCurrent(question.id)
        wrongCount = store.wrongCount(question.id)
        roundCount = if (roundKey != null) store.roundCount(roundKey) else (orderIndex + 1)
    }

    /** 错次与本轮进度重新从 store 读，保证界面数字来自落盘数据 */
    private fun refreshCounts() {
        val id = current?.id ?: return
        wrongCount = store.wrongCount(id)
        roundCount = if (roundKey != null) store.roundCount(roundKey) else (orderIndex + 1)
    }
}
