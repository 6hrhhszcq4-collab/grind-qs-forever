// 数据层（11 新增）：调试模式的「导出诊断信息」。
//
// 只做一件事：把「App 版本 + 数据目录 + sources.json（密码打码）+ 试卷列表 + 进度概要」
// 拼成一个 txt，写到 filesDir\diag\diag-<时间戳>.txt。
//
// 安全红线（硬性要求 3）：导出前把 sources.json 里的 "pass" 值全部替换成 ******，
// 其余字段原样保留（诊断时最需要看的就是地址、路径、uploadable 有没有写对）。
package com.shuaati.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 诊断信息导出器 */
object Diagnostics {

    private const val TAG = "Shuaati"

    /** 密码打码的替换文本（六颗星，与任务书一致） */
    const val MASK = "******"

    /**
     * 导出诊断信息。
     * 调用方必须在 IO 线程（内部也切了一次）。
     *
     * @return 写好的 txt 文件
     */
    fun export(context: Context, repo: PaperRepo, sourceStore: SourceStore): File {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val dir = File(context.filesDir, "diag")
        dir.mkdirs()
        val out = File(dir, "diag-$stamp.txt")

        val builder = StringBuilder()
        builder.appendLine("刷题 诊断信息")
        builder.appendLine("==================================================")
        builder.appendLine("生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
        builder.appendLine("应用版本：${versionText(context)}")
        builder.appendLine("filesDir：${context.filesDir.absolutePath}")
        builder.appendLine("sources.json：${sourceStore.filePath}")
        builder.appendLine("papers 目录：${repo.root.absolutePath}")

        // ---- sources.json（密码打码）----
        builder.appendLine()
        builder.appendLine("=== sources.json（密码已打码）===")
        builder.appendLine(maskedSourcesText(sourceStore))
        builder.appendLine()

        // ---- 源概况 ----
        builder.appendLine("=== 题库源 ===")
        val snapshot = sourceStore.load()
        if (snapshot.sources.isEmpty()) {
            builder.appendLine("（没有任何源：默认源被删且没有新建）")
        } else {
            builder.appendLine("当前源 id：${snapshot.currentSourceId ?: "（未选择）"}")
            snapshot.sources.forEach { source ->
                val address = if (source.isBuiltin || source.hiddenConfig) {
                    "（默认源：地址/账号不输出）"
                } else {
                    maskAddress(source.dirText())
                }
                builder.appendLine(
                    "- ${source.id}「${source.name}」类型=${source.type} " +
                        "可上传=${source.uploadable} 试卷=${snapshot.paperCount(repo.list(), source.id)} 地址=$address"
                )
            }
        }
        builder.appendLine()

        // ---- 试卷列表 ----
        builder.appendLine("=== 试卷库（papers 目录）===")
        val papers = repo.list()
        if (papers.isEmpty()) {
            builder.appendLine("（没有任何试卷）")
        } else {
            papers.forEach { meta ->
                val paperDir = repo.dirOf(meta.id)
                builder.appendLine(
                    "- ${meta.id}「${meta.name}」origin=${meta.origin} " +
                        "sourceId=${meta.sourceId ?: "null"} version=${meta.version ?: "null"} " +
                        "total=${meta.total} importedAt=${meta.importedAt}"
                )
                builder.appendLine(
                    "  目录大小=${repo.dirSizeBytes(paperDir)} 字节，" +
                        "图片=${repo.fileCount(repo.imagesDir(meta.id))} 张，" +
                        "progress=${if (repo.progressFile(meta.id).isFile) "有" else "无"}，" +
                        "exam=${if (repo.examFile(meta.id).isFile) "有" else "无"}"
                )
            }
        }
        builder.appendLine()

        // ---- 进度概要 ----
        builder.appendLine("=== progress 概要 ===")
        if (papers.isEmpty()) {
            builder.appendLine("（没有试卷）")
        } else {
            papers.forEach { meta ->
                val progressFile = repo.progressFile(meta.id)
                builder.appendLine("- ${meta.id}: ${repo.progressSummaryText(progressFile)}")
                builder.appendLine("  exam.json 题数=${repo.examQuestionCount(repo.examFile(meta.id))}")
            }
        }

        out.writeText(builder.toString(), Charsets.UTF_8)
        Log.i(TAG, "诊断信息已导出：${out.absolutePath}（${out.length()} 字节）")
        return out
    }

    /** 应用版本号（versionName + versionCode），读不到就写「未知」 */
    fun versionText(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "未知"}（versionCode $code）"
        } catch (e: Exception) {
            "未知"
        }
    }

    /**
     * v1.3：只给人看的版本号（例如「1.3」），不带 versionCode。
     * 设置页「关于」组用这个——任务书要求设置页不出现任何技术术语。
     */
    fun versionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        } catch (e: Exception) {
            "未知"
        }
    }

    /** 读 sources.json 并把 "pass" 的值打码 */
    private fun maskedSourcesText(sourceStore: SourceStore): String {
        val file = File(sourceStore.filePath)
        if (!file.isFile) return "（sources.json 不存在）"
        return try {
            val raw = file.readText(Charsets.UTF_8)
            // JSON 里 pass 一定是字符串值；这里用正则替换值，保留键名与缩进
            raw.replace(Regex("\"pass\"\\s*:\\s*\"[^\"]*\""), "\"pass\": \"$MASK\"")
        } catch (e: Exception) {
            "（sources.json 读取失败：${e.message ?: "未知错误"}）"
        }
    }

    /** 把地址里的 user:pass@ 段打码（地址理论上不含凭据，这里是兜底） */
    private fun maskAddress(raw: String): String =
        raw.trim().replace(Regex("//[^/@]+@"), "//$MASK@")
}
