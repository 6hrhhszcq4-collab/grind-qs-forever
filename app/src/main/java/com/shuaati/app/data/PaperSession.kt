// 数据层（06B 新增）：把「一套试卷」的全部运行时对象打成一个包。
//
// 切试卷 = 丢掉旧的 PaperSession、按新目录重新构造一套。
// 因为 ProgressStore / ExamStore 各自绑定一个文件，切卷后
// _round、_current、错题本、智能刷用的 recent 全部天然隔离，不需要任何「清空状态」的代码——
// 这也是硬性要求 2（每套试卷进度必须隔离）的实现方式。
package com.shuaati.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 一套正在使用的试卷。
 *
 * @param meta 试卷元信息（名称、题数、来源…）
 * @param questions 题目列表（从这套试卷目录里的 questions.json 解析）
 * @param store 这套试卷的进度文件读写器
 * @param examStore 这套试卷的考试断点读写器
 * @param imagesDir 这套试卷的图片目录
 */
data class PaperSession(
    val meta: PaperMeta,
    val questions: List<Question>,
    val store: ProgressStore,
    val examStore: ExamStore,
    val imagesDir: File,
) {
    val paperId: String get() = meta.id
}

/** 打开试卷的入口：所有「读哪套卷」的路径判定都收在这里 */
object PaperSessionLoader {

    private const val TAG = "Shuaati"

    /**
     * 打开一套试卷。
     *
     * @param paperId 指定要打开的试卷；null 表示「当前试卷」（SharedPreferences 里那一套）
     * @throws IllegalStateException 试卷库为空、meta.json 缺失、questions.json 读不出来时抛出，
     *         由界面显示中文提示，不在这里吞掉
     */
    fun open(context: Context, repo: PaperRepo, paperId: String? = null): PaperSession {
        val id = paperId ?: repo.currentId()
            ?: throw IllegalStateException("试卷库是空的，请先导入一套试卷")
        val meta = repo.metaOf(id)
            ?: throw IllegalStateException("找不到试卷「$id」的 meta.json，目录可能已损坏")
        val questions = QuestionRepo.loadFromFile(repo.questionsFile(id))
        if (questions.isEmpty()) {
            throw IllegalStateException("试卷「${meta.name}」里一道题也没有（questions.json 的 questions 是空数组）")
        }

        val store = ProgressStore(repo.progressFile(id))
        val examStore = ExamStore(repo.examFile(id))
        val images = repo.imagesDir(id)
        // 让图片解码知道该去哪个目录找图；切卷时这里会被重新指向
        CurrentPaperImages.set(images)

        // 明确记录「当前就是这套」，下次启动直接回到这里
        repo.setCurrent(id)

        Log.i(
            TAG,
            "当前试卷：${meta.name}（$id），共 ${questions.size} 题，" +
                "进度=${store.filePath}，已读记录=${store.loadedRecordCount} 条",
        )
        return PaperSession(
            meta = meta,
            questions = questions,
            store = store,
            examStore = examStore,
            imagesDir = images,
        )
    }

    /**
     * 启动流程：先做一次「试卷库准备」→ 补 meta 的来源字段 → 打开当前试卷。
     *
     * v1.3 起安装包里不再内置题库：如果本地一套试卷都没有（全新安装、或从 v1.1 直接升级），
     * 这里**返回 null**，由界面显示「还没有题库」空状态，而不是抛异常/白屏。
     *
     * 11 新增的 [PaperRepo.migrateSourceMeta] 只改 meta.json 的几个字段，
     * 改之前会把 progress.json / exam.json 备份成 .bak-<时间戳>，进度条数一条都不会动。
     *
     * @return 打开好的试卷；本地没有任何试卷时返回 null
     */
    fun start(context: Context, repo: PaperRepo): PaperSession? {
        val report = repo.ensureMigrated()
        if (report != null) {
            Log.i(
                TAG,
                "首次启动迁移完成：试卷=${report.paperId}，旧进度 ${report.progressCount} 条、" +
                    "本轮 ${report.roundLength} 条、图片 ${report.imageCount} 张、" +
                    "备份=${report.backupPath ?: "（无旧文件）"}",
            )
        }
        val sourceReport = repo.migrateSourceMeta()
        if (!sourceReport.alreadyMigrated) {
            Log.i(
                TAG,
                "11 来源迁移：升级 ${sourceReport.upgraded}/${sourceReport.paperCount} 套，" +
                    "备份 ${sourceReport.backups.size} 个文件",
            )
        }
        // v1.3：一套试卷都没有 → 回 null，界面走空状态
        if (repo.currentId() == null) {
            Log.i(TAG, "试卷库是空的：等待用户从源下载或本地导入")
            return null
        }
        return open(context, repo)
    }
}
