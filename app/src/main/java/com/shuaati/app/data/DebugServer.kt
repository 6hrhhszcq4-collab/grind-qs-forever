// 数据层（v1.5 新增）：调试端口。
//
// 目的：调试模式打开后，app 在 127.0.0.1:8765 上开一个本地命令端口。
// 宿主机 `adb forward tcp:8765 tcp:8765` 之后，Python 脚本直接连上来发命令、收 JSON，
// 不再靠「截图 + 坐标点击」驱动界面（太慢，而且填源地址这种长文本根本没法测）。
//
// 协议（一行一条命令，一行一条 JSON 响应）：
//   help                                     列出全部命令
//   ping                                     连通性检查
//   state                                    当前试卷 / 进度 / 源 / 端口状态
//   paper list                               本地试卷列表
//   source list                              题库源列表
//   source add <name> <baseUrl> <user> <pass> [path]   新建自建源并设为当前
//   source set <id>                          切换当前源
//   source delete <id>                       删除源
//   source builtin                           恢复并设为默认源
//   update                                   检查题库更新（当前源），回可更新列表
//   update source <baseUrl> [user] [pass] [path]
//                                            一步改「检查更新用的源」：有同地址的自建源就改它，
//                                            没有就新建一个，然后设为当前源
//   quit / close                             关闭连接
//
// 安全：只监听 127.0.0.1；只有 debugEnabled=true 时才启动；进程退出随进程回收。
package com.shuaati.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

object DebugServer {

    /** 调试端口（和宿主机 `adb forward tcp:8765 tcp:8765` 对齐） */
    const val PORT = 8765

    private const val TAG = "Shuaati"

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 端口是否在监听（调试面板要显示） */
    @Volatile
    var running: Boolean = false
        private set

    /** 最近命令日志（调试面板显示，最多 40 条） */
    private val logLines = Collections.synchronizedList(ArrayList<String>())

    fun recentLog(): List<String> = synchronized(logLines) { logLines.toList() }

    private fun log(message: String) {
        Log.i(TAG, "debug: $message")
        synchronized(logLines) {
            logLines.add(message)
            while (logLines.size > 40) logLines.removeAt(0)
        }
    }

    /** 启动监听（幂等；仅在调试模式打开时调用） */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        val ctx = context.applicationContext
        try {
            val socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            running = true
            acceptThread = Thread {
                while (running) {
                    try {
                        val client = socket.accept()
                        Thread { handle(ctx, client) }.start()
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "debug accept 失败", e)
                    }
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
            log("调试端口已启动：127.0.0.1:$PORT")
        } catch (e: Exception) {
            running = false
            log("调试端口启动失败：${e.message}")
        }
    }

    /** 停止监听（幂等） */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("调试端口已停止")
    }

    // ============================ 连接处理 ============================

    private fun handle(ctx: Context, client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
            while (true) {
                val line = reader.readLine() ?: break
                val command = line.trim()
                if (command.isEmpty()) continue
                log("<- $command")
                val response = try {
                    dispatch(ctx, command)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: "未知错误")
                }
                val text = response.toString()
                writer.println(text)
                log("-> $text")
                if (command == "quit" || command == "close") break
            }
        }
    }

    private fun dispatch(ctx: Context, line: String): JSONObject {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return error("空命令")
        val repo = PaperRepo(ctx)
        val store = SourceStore(ctx)
        return when (tokens[0].lowercase()) {
            "help" -> helpJson()
            "ping" -> JSONObject().put("ok", true).put("pong", true).put("port", PORT)
            "state" -> stateJson(repo, store)
            "paper" -> paperJson(repo)
            "source" -> sourceCommand(store, tokens)
            "update" -> updateCommand(repo, store, tokens)
            else -> error("未知命令：${tokens[0]}（输入 help 看命令列表）")
        }
    }

    private fun sourceCommand(store: SourceStore, tokens: List<String>): JSONObject {
        val action = tokens.getOrNull(1) ?: "list"
        return when (action.lowercase()) {
            "list" -> {
                val snapshot = store.load()
                JSONObject()
                    .put("ok", true)
                    .put("currentSourceId", snapshot.currentSourceId ?: JSONObject.NULL)
                    .put("sources", sourcesJson(snapshot.sources))
            }
            "add" -> {
                if (tokens.size < 6) return error("用法：source add <name> <baseUrl> <user> <pass> [path]")
                val source = SourceConfig(
                    id = store.newCustomId(),
                    name = tokens[2],
                    type = "custom",
                    baseUrl = tokens[3],
                    user = tokens[4],
                    pass = tokens[5],
                    path = tokens.getOrNull(6) ?: "",
                    uploadable = true,
                    hiddenConfig = false,
                )
                store.save(source, makeCurrent = true)
                JSONObject().put("ok", true)
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("dir", source.dirText())
            }
            "set" -> {
                val id = tokens.getOrNull(2) ?: return error("用法：source set <id>")
                val snapshot = store.load()
                if (snapshot.byId(id) == null) return error("没有这个源：$id")
                store.setCurrent(id)
                JSONObject().put("ok", true).put("currentSourceId", id)
            }
            "delete" -> {
                val id = tokens.getOrNull(2) ?: return error("用法：source delete <id>")
                val snapshot = store.load()
                if (snapshot.byId(id) == null) return error("没有这个源：$id")
                if (snapshot.byId(id)?.isBuiltin == true) {
                    store.delete(id)
                    JSONObject().put("ok", true).put("deleted", id).put("note", "默认源已删除，可用 source builtin 恢复")
                } else {
                    store.delete(id)
                    JSONObject().put("ok", true).put("deleted", id)
                }
            }
            "builtin" -> {
                store.restoreBuiltin()
                JSONObject().put("ok", true).put("currentSourceId", SourceConfig.BUILTIN_ID)
            }
            else -> error("未知 source 子命令：$action")
        }
    }

    /**
     * update 命令：
     *   update                                   检查更新（当前源）
     *   update source <baseUrl> [user] [pass] [path]
     *                                            改「检查更新用的源」：同地址的自建源就改它，否则新建并设为当前
     */
    private fun updateCommand(repo: PaperRepo, store: SourceStore, tokens: List<String>): JSONObject {
        val action = tokens.getOrNull(1)?.lowercase() ?: "check"
        if (action == "source") {
            val baseUrl = tokens.getOrNull(2) ?: return error("用法：update source <baseUrl> [user] [pass] [path]")
            val user = tokens.getOrNull(3) ?: ""
            val pass = tokens.getOrNull(4) ?: ""
            val path = tokens.getOrNull(5) ?: ""
            val snapshot = store.load()
            val existing = snapshot.sources.firstOrNull {
                !it.isBuiltin && it.baseUrl.trimEnd('/') == baseUrl.trimEnd('/')
            }
            val source = if (existing != null) {
                existing.copy(name = existing.name, user = user.ifBlank { existing.user }, pass = pass.ifBlank { existing.pass }, path = path.ifBlank { existing.path })
            } else {
                SourceConfig(
                    id = store.newCustomId(),
                    name = "调试源",
                    type = "custom",
                    baseUrl = baseUrl,
                    user = user,
                    pass = pass,
                    path = path,
                    uploadable = true,
                    hiddenConfig = false,
                )
            }
            store.save(source, makeCurrent = true)
            return JSONObject().put("ok", true)
                .put("updated", existing != null)
                .put("id", source.id)
                .put("name", source.name)
                .put("dir", source.dirText())
        }
        if (action != "check") return error("未知 update 子命令：$action（可用 check / source）")

        val snapshot = store.load()
        val papers = repo.list()
        val outcome = runBlocking { UpdateChecker.manualCheck(repo, snapshot.sources, papers) }
        val updates = JSONArray()
        outcome.updates.forEach { update ->
            updates.put(
                JSONObject()
                    .put("paperId", update.paperId)
                    .put("name", update.localMeta.name)
                    .put("localVersion", update.localMeta.version ?: JSONObject.NULL)
                    .put("remoteVersion", update.remoteVersion)
                    .put("source", update.source.name),
            )
        }
        val unsupported = JSONArray()
        outcome.unsupportedSources.forEach { unsupported.put(it) }
        val failed = JSONArray()
        outcome.failedSources.forEach { failed.put(it) }
        return JSONObject()
            .put("ok", true)
            .put("count", outcome.updates.size)
            .put("updates", updates)
            .put("unsupported", unsupported)
            .put("failed", failed)
    }

    private fun stateJson(repo: PaperRepo, store: SourceStore): JSONObject {
        val snapshot = store.load()
        return JSONObject()
            .put("ok", true)
            .put("port", PORT)
            .put("currentPaperId", repo.currentId() ?: JSONObject.NULL)
            .put("papers", papersJson(repo))
            .put("currentSourceId", snapshot.currentSourceId ?: JSONObject.NULL)
            .put("sources", sourcesJson(snapshot.sources))
    }

    private fun papersJson(repo: PaperRepo): JSONArray {
        val arr = JSONArray()
        repo.list().forEach { meta ->
            arr.put(
                JSONObject()
                    .put("id", meta.id)
                    .put("name", meta.name)
                    .put("total", meta.total)
                    .put("origin", meta.origin)
                    .put("sourceId", meta.sourceId ?: JSONObject.NULL)
                    .put("version", meta.version ?: JSONObject.NULL)
                    .put("remoteId", meta.remoteId ?: JSONObject.NULL),
            )
        }
        return arr
    }

    private fun sourcesJson(sources: List<SourceConfig>): JSONArray {
        val arr = JSONArray()
        sources.forEach { source ->
            arr.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("type", source.type)
                    .put("baseUrl", source.baseUrl)
                    // 内置默认源的账号不在调试输出里出现（与「导出诊断信息」打码规则一致）
                    .put("user", if (source.isBuiltin) "******" else source.user)
                    .put("path", source.path)
                    .put("uploadable", source.uploadable)
                    .put("builtin", source.isBuiltin),
            )
        }
        return arr
    }

    private fun paperJson(repo: PaperRepo): JSONObject =
        JSONObject().put("ok", true).put("papers", papersJson(repo))

    private fun helpJson(): JSONObject {
        val commands = JSONArray()
        listOf(
            "help — 列出全部命令",
            "ping — 连通性检查",
            "state — 当前试卷 / 源 / 端口状态",
            "paper list — 本地试卷列表",
            "source list — 题库源列表",
            "source add <name> <baseUrl> <user> <pass> [path] — 新建自建源并设为当前",
            "source set <id> — 切换当前源",
            "source delete <id> — 删除源",
            "source builtin — 恢复并设为默认源",
            "update — 用当前源检查题库更新，回可更新列表",
            "update source <baseUrl> [user] [pass] [path] — 改「检查更新用的源」并设为当前",
            "quit / close — 关闭连接",
        ).forEach { commands.put(it) }
        return JSONObject().put("ok", true).put("commands", commands)
    }

    private fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    /** 简易分词：支持用双引号包住带空格的参数 */
    private fun tokenize(line: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }
}
