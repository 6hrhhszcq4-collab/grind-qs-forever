// 数据层（11 新增，v1.4 增强：版本自增 + 版本文件同步）：把一套「本地」试卷上传到用户自建的源。
//
// 流程（任务书第 4 条）：
//   0. **先读云端 papers.json / version.json，算这次要写的新版本号**（v1.4）：
//      新版本 = max(云端同 id 的 version, 云端 version.json 里本卷的 version, 本地 meta.version) + 1；
//      云端和本地都还没有版本号时（第一次上传）就是 1。
//      读云端索引失败就直接中止，**绝不在拿不到旧索引的情况下写 papers.json**（源题库不能丢）。
//   1. 上传 questions.json 到  <baseUrl><path><paperId>.json
//   2. 上传 images/ 下的图片到 <baseUrl><path>images/<文件名>
//   3. 读回该源根目录的 papers.json → 追加/替换一条（id/name/file/total/version=新版本）→ 写回
//   4. 同步 version.json（v1.4）：源上没有版本文件、或版本文件就是本卷时才写；
//      版本文件属于别的卷时**不动它**，避免把别人那套卷的更新提示弄丢。
//   5. 全部成功之后，才把本地 meta 改成 origin=cloud、sourceId=该源、version=新版本
//
// 硬性要求 2：**上传失败不许标记成功**。
// 第 5 步放在最后，失败分支里还会显式把原 meta 写回去（回滚），本地永远保持 local。
//
// 硬性要求 1：默认源不能作为上传目标 —— 入口（源管理页）已经按 uploadable 过滤，
// 这里再挡一次，防止以后有人绕过界面直接调。
package com.shuaati.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 上传结果：ok=false 时 message 是可以直接显示的中文原因 */
data class UploadResult(val ok: Boolean, val message: String)

/** 本地试卷上传器 */
object PaperUploader {

    private const val TAG = "Shuaati"

    /** 第一次上传（云端和本地都没有版本号）写进 papers.json 的版本号 */
    const val FIRST_VERSION = 1

    /** 云端版本文件 version.json 的内容（与服务端脚本 tools/upload_version.py 一致） */
    private data class RemoteVersionFile(val paperId: String, val version: Int)

    /**
     * 上传一套本地试卷。
     *
     * 调用方必须在 IO 线程（函数内部自己也切了 Dispatchers.IO，双保险）。
     *
     * @param repo 试卷库
     * @param meta 要上传的试卷（必须是 origin=local）
     * @param source 目标源（必须 uploadable=true 且不是默认源）
     */
    suspend fun upload(repo: PaperRepo, meta: PaperMeta, source: SourceConfig): UploadResult =
        withContext(Dispatchers.IO) {
            // ---- 前置检查（硬性要求 1）----
            if (source.isBuiltin || !source.uploadable || source.hiddenConfig) {
                Log.w(TAG, "拒绝上传：目标源不是可上传的自建源（id=${source.id}）")
                return@withContext UploadResult(false, "默认源不能作为上传目标，请先新建一个自建源")
            }
            val dir = repo.dirOf(meta.id)
            val questionsFile = repo.questionsFile(meta.id)
            if (!dir.isDirectory || !questionsFile.isFile) {
                return@withContext UploadResult(false, "找不到这套试卷的文件，可能已被删除")
            }

            val original = meta
            try {
                // ---- 第 0 步（v1.4）：先读云端索引，算新版本号 ----
                // 读失败就抛出去中止，绝不在拿不到旧 papers.json 的情况下「当空索引写回」，
                // 否则会把源上其他试卷的条目一起冲掉。
                val indexUrl = WebDavSource.indexUrl(source)
                val oldText = WebDavSource.getTextOrNull(indexUrl, source, "读取索引")
                val old = if (oldText.isNullOrBlank()) {
                    JSONObject()
                } else {
                    try {
                        JSONObject(oldText)
                    } catch (e: Exception) {
                        // 云端索引坏了：不覆盖它，让用户自己处理（否则会把别人的试卷列表抹掉）
                        throw WebDavException("该源的 papers.json 不是合法 JSON，已停止上传，避免覆盖它")
                    }
                }
                val papers = old.optJSONArray("papers") ?: JSONArray()

                // 云端已有条目：先按 id 找，再按名称找。
                // 同名视为同一套卷——老师拿改好的题库重新导入再上传时，源上原地更新那一套，
                // 不会多出一套重复的卷，学生端按 remoteId 就能收到版本更新。
                // (id, name, version)
                val entries = ArrayList<Triple<String, String, Int>>()
                for (i in 0 until papers.length()) {
                    val obj = papers.optJSONObject(i) ?: continue
                    entries.add(
                        Triple(
                            obj.optString("id", "").trim(),
                            obj.optString("name", "").trim(),
                            obj.optInt("version", 0),
                        )
                    )
                }
                val sameId = entries.firstOrNull { it.first == meta.id }
                val sameName = if (sameId == null) {
                    entries.filter { normalizedName(it.second) == normalizedName(meta.name) }
                        .maxByOrNull { it.third }
                } else {
                    null
                }
                val target = sameId ?: sameName
                val targetId = target?.first ?: meta.id
                val cloudVersion = target?.third ?: 0

                // 云端 version.json（可能是别的卷的；只有 paperId 对得上才算这套）
                val versionFile = readVersionFile(source)
                val versionFileVersion =
                    if (versionFile != null &&
                        (versionFile.paperId == targetId || versionFile.paperId == meta.id)
                    ) {
                        versionFile.version
                    } else {
                        0
                    }

                // 本地 meta.version（云端更新的卷会比 0 大；本地导入的卷是 null）
                val localVersion = meta.version ?: 0

                val baseVersion = maxOf(cloudVersion, versionFileVersion, localVersion)
                // v1.4：**版本号自增**。服务端的 papers.json / version.json 本来就按版本号递增，
                // 之前这里写死 FIRST_VERSION 导致重复上传永远是 1，学生端永远检测不到更新。
                val version = if (baseVersion <= 0) FIRST_VERSION else baseVersion + 1

                // ---- 第 1 步：上传题库 JSON ----
                val questionsText = questionsFile.readText(Charsets.UTF_8)
                val fileName = "$targetId.json"
                WebDavSource.ensureDirectories(source)
                WebDavSource.putText(
                    source = source,
                    url = WebDavSource.paperUrl(source, fileName),
                    text = questionsText,
                    what = "上传「${meta.name}」题库",
                )

                // ---- 第 2 步：上传图片（失败不阻塞整套上传，但要在结果里说清楚）----
                val imagesDir = repo.imagesDir(meta.id)
                var imageOk = 0
                var imageFailed = 0
                val failedNames = ArrayList<String>()
                imagesDir.listFiles { f -> f.isFile }?.sortedBy { it.name }?.forEach { image ->
                    try {
                        WebDavSource.putBytes(
                            source = source,
                            url = WebDavSource.imageUrl(source, image.name),
                            bytes = image.readBytes(),
                            contentType = "application/octet-stream",
                            what = "上传图片 ${image.name}",
                        )
                        imageOk++
                    } catch (e: Exception) {
                        imageFailed++
                        failedNames.add(image.name)
                        Log.w(TAG, "图片上传失败：${image.name}（${e.message}）")
                    }
                }

                // ---- 第 3 步：更新 papers.json（用自增后的版本号）----
                val merged = JSONArray()
                var replaced = false
                for (i in 0 until papers.length()) {
                    val obj = papers.optJSONObject(i) ?: continue
                    if (obj.optString("id", "").trim() == targetId) {
                        merged.put(entryJson(meta, targetId, fileName, version))
                        replaced = true
                    } else {
                        merged.put(obj)
                    }
                }
                if (!replaced) merged.put(entryJson(meta, targetId, fileName, version))

                val out = JSONObject()
                // 保留 papers.json 里可能存在的其它顶层字段（只替换 papers 数组）
                val keys = old.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "papers") out.put(key, old.get(key))
                }
                out.put("papers", merged)
                WebDavSource.putText(
                    source = source,
                    url = indexUrl,
                    text = out.toString(2),
                    what = "更新 papers.json",
                )

                // ---- 第 4 步（v1.4）：同步 version.json ----
                // 只在「源上没有版本文件」或「版本文件就是这套卷」时写；
                // 版本文件属于别的卷时保持原样（那个源是按单卷版本文件工作的，硬写会弄丢别人的更新提示）。
                var versionFileUpdated = false
                if (versionFile == null ||
                    versionFile.paperId == targetId ||
                    versionFile.paperId == meta.id
                ) {
                    val ver = JSONObject().apply {
                        put("paperId", targetId)
                        put("version", version)
                        put("updatedAt", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                        put("note", meta.name)
                    }
                    WebDavSource.putText(
                        source = source,
                        url = WebDavSource.versionUrl(source),
                        text = ver.toString(2),
                        what = "更新 version.json",
                    )
                    versionFileUpdated = true
                } else {
                    Log.w(
                        TAG,
                        "跳过 version.json：源上的版本文件属于另一套卷（paperId=${versionFile.paperId}），" +
                            "保持不动，避免别的卷收不到更新提示",
                    )
                }

                // ---- 第 5 步：全部成功，才改本地 meta ----
                // remoteId 记源上的条目 id（同名更新时和本地试卷 id 不同），更新检测靠它对齐。
                val updated = original.copy(
                    origin = PaperMeta.ORIGIN_CLOUD,
                    sourceId = source.id,
                    version = version,
                    remoteId = targetId,
                    source = "上传到「${source.name}」",
                )
                repo.writeMeta(updated)
                Log.i(
                    TAG,
                    "上传成功：${meta.id} -> 源 ${source.id}，条目 $targetId，版本 v$version（原云端 v$cloudVersion），" +
                        "图片 $imageOk 张，失败 $imageFailed 张，" +
                        "papers.json ${if (replaced) "已替换条目" else "已追加"}，" +
                        "version.json ${if (versionFileUpdated) "已同步" else "未动（属于别的卷）"}",
                )
                val imageText = when {
                    imageFailed == 0 && imageOk > 0 -> "，$imageOk 张图片已一起上传"
                    imageFailed > 0 -> "，$imageOk 张图片成功、$imageFailed 张失败（${failedNames.joinToString("、")}）"
                    else -> ""
                }
                val versionText = if (versionFileUpdated) "，版本 v$version" else "，版本 v$version（版本文件是别的卷，未改）"
                val actionText = when {
                    sameId != null -> "已更新「${meta.name}」（v$cloudVersion → v$version）到「${source.name}」"
                    sameName != null -> "已更新源上的同名试卷「${meta.name}」（v$cloudVersion → v$version）到「${source.name}」"
                    else -> "已上传「${meta.name}」到「${source.name}」$versionText"
                }
                UploadResult(true, actionText + imageText)
            } catch (e: Exception) {
                // 硬性要求 2：失败时把本地 meta 恢复成上传前的样子（保持 local）
                val reason = (e as? WebDavException)?.message
                    ?: "上传失败：${e.message ?: "未知错误"}"
                runCatching { repo.writeMeta(original) }
                    .onFailure { Log.e(TAG, "回滚本地 meta 失败：${original.id}", it) }
                Log.w(TAG, "上传失败：${meta.id} -> 源 ${source.id}，本地仍保持 local（${reason}）")
                UploadResult(false, reason)
            }
        }

    /**
     * 读云端 version.json。
     *
     * - 404 → null（源上没有版本文件，正常）
     * - 格式不对 → 抛异常中止上传：宁可失败，也不猜版本号把版本写小
     */
    private suspend fun readVersionFile(source: SourceConfig): RemoteVersionFile? {
        val text = WebDavSource.fetchVersionText(source)
        if (text == null) return null
        return try {
            val root = JSONObject(text)
            val paperId = root.optString("paperId", "").trim()
            if (paperId.isEmpty()) {
                throw WebDavException("该源的 version.json 缺少 paperId，已停止上传，避免把版本号写错")
            }
            RemoteVersionFile(paperId = paperId, version = root.optInt("version", 0))
        } catch (e: WebDavException) {
            throw e
        } catch (e: Exception) {
            throw WebDavException("该源的 version.json 不是合法 JSON，已停止上传，避免覆盖它")
        }
    }

    /** 写进 papers.json 的一条：字段与任务书一致（id / name / file / total / version） */
    private fun entryJson(meta: PaperMeta, id: String, fileName: String, version: Int): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", meta.name)
            put("file", fileName)
            put("total", meta.total)
            put("version", version)
            put("updatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        }

    /**
     * 名称规范化：去掉空白与常见分隔符，用来判断「是不是同一套卷」。
     * 规则与 UpdateChecker.normalizedName 一致，保证上传匹配和更新检测对得上。
     */
    private fun normalizedName(name: String): String =
        name.replace(Regex("[\\s·•\\-—_.、，,（）()【】\\[\\]]"), "").trim()
}
