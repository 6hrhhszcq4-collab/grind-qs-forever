// 数据层（v1.5 新增）：应用自身的版本检查（GitHub Releases）。
//
// 和「题库更新检查」（UpdateChecker，查 WebDAV 上的试卷版本）是两件事：
// 这里查的是 app 自己有没有新版本，数据来自 GitHub 公开仓库的 latest release。
//
// 约定：release 的 tag 写成 v1.5 / v1.5.1 这种；仓库名从 local.properties
// 的 shuaati.githubRepo 经 BuildConfig 注入（默认 SDMW/shuaati）。
// 失败一律静默返回 null（网络问题不该打扰用户），日志里只记原因。
package com.shuaati.app.data

import android.util.Log
import com.shuaati.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 一次「应用有新版本」的结果 */
data class AppUpdate(
    /** 远端版本名（tag 去掉 v 前缀，如 1.6） */
    val versionName: String,
    /** 下载地址：优先 release 里的 .apk 资产，否则退回 release 页面 */
    val downloadUrl: String,
    /** release 页面地址 */
    val releaseUrl: String,
    /** release 说明（截断到 300 字） */
    val notes: String,
)

object AppUpdateChecker {

    private const val TAG = "Shuaati"

    /**
     * 检查 GitHub 上有没有比本机更新的版本。
     * @return 有更新时返回 [AppUpdate]；已是最新 / 网络失败 / 仓库没配 时返回 null
     */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO.trim()
        if (repo.isBlank()) {
            Log.i(TAG, "应用更新检查：没有配置 GITHUB_REPO，跳过")
            return@withContext null
        }
        val url = "https://api.github.com/repos/$repo/releases/latest"
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "shuaati-app")
            }
        } catch (e: Exception) {
            Log.w(TAG, "应用更新检查：创建连接失败（${e.message}）")
            return@withContext null
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.i(TAG, "应用更新检查：HTTP $code（仓库没 release 或地址不对）")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val tag = root.optString("tag_name", "").trim().removePrefix("v").removePrefix("V")
            if (tag.isEmpty()) return@withContext null
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) {
                Log.i(TAG, "应用更新检查：已是最新（远端 $tag / 本机 ${BuildConfig.VERSION_NAME}）")
                return@withContext null
            }
            val releaseUrl = root.optString("html_url", "")
            var downloadUrl = releaseUrl
            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", releaseUrl)
                        break
                    }
                }
            }
            Log.i(TAG, "应用更新检查：发现新版本 $tag（本机 ${BuildConfig.VERSION_NAME}）")
            AppUpdate(
                versionName = tag,
                downloadUrl = downloadUrl,
                releaseUrl = releaseUrl,
                notes = root.optString("body", "").trim().take(300),
            )
        } catch (e: Exception) {
            Log.w(TAG, "应用更新检查失败：${e.message}")
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 版本号比较：按 . 和 - 分段，逐段比数字（v1.5 < v1.5.1 < v1.6） */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        if (r.isEmpty()) return false
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
