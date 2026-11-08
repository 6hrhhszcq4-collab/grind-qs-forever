// 界面层（11 新增）：题库源管理页。
//
// 入口：设置 → 「题库源」行。
// 这一页只做五件事：
//   1. 列出所有源：名称 +（只有自建源才显示地址）+ 该源下试卷数 + 右侧「设为当前」/「⋯」；
//   2. 新建 / 编辑自建源（弹窗里能「测试连接」，保存后自动拉一次 papers.json 并显示「该源有 N 套试卷」）；
//   3. 删除源（二次确认，文案带源名）；删掉当前源自动切到列表第一个，没有就提示；
//   4. 默认源被删掉之后显示「恢复默认源」；
//   5. 点源行可以看该源的试卷列表并直接下载（验收标准里的「新源的试卷列表能拉出来并下载」）。
//
// 硬性要求 1（默认源三不）在这一页的实现方式：
//   - 源行只在 !isBuiltin 时才显示地址（见 SourceRow）；
//   - 默认源的「⋯」菜单里只有「删除」，没有「编辑」（见 SourceMenuDialog）；
//   - 默认源不出现在「上传到源」目标列表里（过滤逻辑在 SourceSnapshot.uploadTargets()）。
//
// 网络请求全部在 Dispatchers.IO 上（WebDavSource 内部也是），本页只负责状态与提示。
package com.shuaati.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.VisualTransformation
import com.shuaati.app.data.HAS_BUILTIN_SOURCE
import com.shuaati.app.data.PaperMeta
import com.shuaati.app.data.SourceConfig
import com.shuaati.app.data.SourceSnapshot
import com.shuaati.app.data.SourceStore
import com.shuaati.app.data.WebDavEntry
import com.shuaati.app.data.WebDavIndex
import com.shuaati.app.data.WebDavSource
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.AppTopBar
import com.shuaati.app.ui.components.FullScreenDialog
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 题库源管理页。
 *
 * @param sourceStore 源存储（读写 filesDir\sources.json）
 * @param papers 本地试卷列表（统计每个源下有几套卷）
 * @param onBack 返回设置页
 * @param onDownloadPaper 点某套云端试卷 → 由外层走现有下载流程
 */
@Composable
fun SourceScreen(
    sourceStore: SourceStore,
    papers: List<PaperMeta>,
    onBack: () -> Unit,
    onDownloadPaper: (SourceConfig, WebDavEntry) -> Unit,
) {
    val c = appColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 页面数据：每次操作后 version++ 重读一次（源列表很小，读盘没有卡顿感）
    var snapshot by remember { mutableStateOf<SourceSnapshot?>(null) }
    var version by remember { mutableStateOf(0) }

    LaunchedEffect(version) {
        snapshot = withContext(Dispatchers.IO) { sourceStore.reload() }
    }

    // 弹窗状态
    var showNewDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SourceConfig?>(null) }
    var menuTarget by remember { mutableStateOf<SourceConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<SourceConfig?>(null) }
    var papersTarget by remember { mutableStateOf<SourceConfig?>(null) }

    /** 在 IO 线程改 store，改完刷新界面 */
    fun mutate(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            version++
        }
    }

    fun delete(source: SourceConfig) {
        val wasCurrent = snapshot?.currentSourceId == source.id
        deleteTarget = null
        scope.launch {
            val after = withContext(Dispatchers.IO) { sourceStore.delete(source.id) }
            version++
            val nowCurrent = after.current()
            val message = when {
                wasCurrent && nowCurrent != null -> "已删除「${source.name}」，当前源切换为「${nowCurrent.name}」"
                wasCurrent -> "已删除「${source.name}」，当前没有可用的源，请新建一个"
                else -> "已删除「${source.name}」"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun restoreBuiltin() {
        scope.launch {
            withContext(Dispatchers.IO) { sourceStore.restoreBuiltin() }
            version++
            Toast.makeText(context, "默认源已恢复，并设为当前源", Toast.LENGTH_SHORT).show()
        }
    }

    val snap = snapshot
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        AppTopBar(title = "题库源", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        ) {
            Spacer(Modifier.height(Spacing.md))

            // ===== 顶部：当前源 =====
            Text(
                text = "当前源：${snap?.current()?.name ?: "（未选择）"}",
                style = AppText.bodyStrong,
                color = c.textPrimary,
            )

            Spacer(Modifier.height(Spacing.lg))

            // ===== 源列表 =====
            SectionCard(
                title = "源列表",
                subtitle = snap?.let { "${it.sources.size} 个" },
            ) {
                val sources = snap?.sources.orEmpty()
                if (sources.isEmpty()) {
                    Text(
                        text = "还没有任何题库源。可以新建一个自建源，或把默认源恢复回来。",
                        style = AppText.small,
                        color = c.textSecondary,
                    )
                } else {
                    sources.forEachIndexed { index, source ->
                        if (index > 0) {
                            Spacer(Modifier.height(Spacing.md))
                            HorizontalDivider(color = c.border)
                            Spacer(Modifier.height(Spacing.md))
                        }
                        SourceRow(
                            source = source,
                            isCurrent = source.id == snap?.currentSourceId,
                            paperCount = papers.count { it.sourceId == source.id },
                            onOpenPapers = { papersTarget = source },
                            onSetCurrent = { mutate { sourceStore.setCurrent(source.id) } },
                            onMenu = { menuTarget = source },
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // ===== 底部按钮 =====
            AppButton(
                text = "＋ 新建源",
                onClick = { showNewDialog = true },
                style = AppButtonStyle.SECONDARY,
            )
            if (HAS_BUILTIN_SOURCE && snap != null && !snap.hasBuiltin()) {
                Spacer(Modifier.height(Spacing.sm))
                AppButton(
                    text = "恢复默认源",
                    onClick = { restoreBuiltin() },
                    style = AppButtonStyle.SECONDARY,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = if (HAS_BUILTIN_SOURCE) {
                    "默认源不显示地址与账号，也不能作为「上传到源」的目标；删掉之后可以用「恢复默认源」找回来。"
                } else {
                    "自建源可以多建几个、随时切换；本地导入的试卷也能「上传到源」分享给别人。"
                },
                style = AppText.caption,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    // ===== 弹窗 =====

    // 新建 / 编辑（两者共用一个弹窗，editTarget == null 表示新建）
    if (showNewDialog || editTarget != null) {
        SourceEditDialog(
            initial = editTarget,
            sourceStore = sourceStore,
            onDismiss = {
                showNewDialog = false
                editTarget = null
                version++
            },
            onChanged = { version++ },
        )
    }

    // 「⋯」菜单
    menuTarget?.let { source ->
        SourceMenuDialog(
            source = source,
            onEdit = {
                menuTarget = null
                editTarget = source
            },
            onDelete = {
                menuTarget = null
                deleteTarget = source
            },
            onDismiss = { menuTarget = null },
        )
    }

    // 删除二次确认
    deleteTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = "删除源", style = AppText.stemStrong) },
            text = {
                Text(
                    text = "确定删除源「${source.name}」吗？\n" +
                        "只删除这个源的配置，已经下载到手机里的试卷不会被删除。",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { delete(source) }) {
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

    // 该源的试卷列表（可下载）
    papersTarget?.let { source ->
        SourcePapersDialog(
            source = source,
            onDownload = { entry ->
                papersTarget = null
                onDownloadPaper(source, entry)
            },
            onDismiss = { papersTarget = null },
        )
    }
}

/**
 * 源列表里的一行。
 * 默认源：只显示名字与试卷数，**不显示地址**（硬性要求 1）。
 */
@Composable
private fun SourceRow(
    source: SourceConfig,
    isCurrent: Boolean,
    paperCount: Int,
    onOpenPapers: () -> Unit,
    onSetCurrent: () -> Unit,
    onMenu: () -> Unit,
) {
    val c = appColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenPapers() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source.name,
                    style = AppText.bodyStrong,
                    color = if (isCurrent) c.primary else c.textPrimary,
                )
                if (isCurrent) {
                    Spacer(Modifier.size(Spacing.xs))
                    Text(text = "当前", style = AppText.caption, color = c.primary)
                }
            }
            // 只有自建源才显示地址；默认源一个字都不显示
            if (!source.isBuiltin && !source.hiddenConfig) {
                Text(
                    text = maskAddress(source.displayAddress()),
                    style = AppText.caption,
                    color = c.textSecondary,
                )
            }
            Text(
                text = "$paperCount 套试卷",
                style = AppText.caption,
                color = c.textSecondary,
            )
        }
        if (!isCurrent) {
            Text(
                text = "设为当前",
                style = AppText.smallStrong,
                color = c.primary,
                modifier = Modifier
                    .clickable { onSetCurrent() }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            )
        }
        Text(
            text = "⋯",
            style = AppText.bodyStrong,
            color = c.textSecondary,
            modifier = Modifier
                .clickable { onMenu() }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        )
    }
}

/**
 * 「⋯」菜单。
 * 默认源只有「删除」——**不显示编辑**（硬性要求 1）。
 */
@Composable
private fun SourceMenuDialog(
    source: SourceConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = appColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = source.name, style = AppText.stemStrong) },
        text = {
            Column {
                if (!source.isBuiltin) {
                    Text(
                        text = "编辑",
                        style = AppText.body,
                        color = c.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit() }
                            .padding(vertical = Spacing.md),
                    )
                }
                Text(
                    text = "删除",
                    style = AppText.body,
                    color = c.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDelete() }
                        .padding(vertical = Spacing.md),
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

/**
 * 新建 / 编辑自建源弹窗（引导页的「配置自己的 WebDAV」也复用它）。
 *
 * @param initial 传 null = 新建；传一个自建源 = 编辑
 * @param onDismiss 关闭
 * @param onChanged 保存成功时回调（外层据此刷新列表）
 */
@Composable
fun SourceEditDialog(
    initial: SourceConfig?,
    sourceStore: SourceStore,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val c = appColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    // 密码只在这个弹窗里出现；列表页、诊断、日志都不显示它
    var pass by remember { mutableStateOf(initial?.pass ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: "") }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    /** 已经保存成功 → 右侧按钮变成「完成」 */
    var savedOk by remember { mutableStateOf(false) }

    /**
     * 用当前输入框里的值拼一份临时配置。
     * 校验失败时直接写 status 并返回 null。
     */
    fun buildConfig(): SourceConfig? {
        val n = name.trim()
        if (n.isEmpty()) {
            status = "请填写源名称"
            statusError = true
            return null
        }
        var base = baseUrl.trim()
        if (base.isEmpty()) {
            status = "请填写服务器地址"
            statusError = true
            return null
        }
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            base = "https://$base"
        }
        return SourceConfig(
            id = initial?.id ?: "tmp",
            name = n,
            type = SourceConfig.TYPE_CUSTOM,
            baseUrl = base,
            user = user.trim(),
            pass = pass,
            path = path.trim(),
            uploadable = true,
            hiddenConfig = false,
        )
    }

    /** 测试连接：GET 索引，把成功 / 401 / 404 / 超时都翻译成中文显示在弹窗里 */
    fun test() {
        val config = buildConfig() ?: return
        busy = true
        status = "正在连接…（连接 10 秒、读取 30 秒超时）"
        statusError = false
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { WebDavSource.testConnection(config) }
            }
            busy = false
            result.onSuccess {
                status = it
                statusError = false
            }.onFailure {
                status = it.message ?: "连接失败"
                statusError = true
            }
        }
    }

    /** 保存：写 sources.json → 自动拉一次该源的 papers.json → 显示「该源有 N 套试卷」 */
    fun save() {
        val config = buildConfig() ?: return
        busy = true
        status = "正在保存…"
        statusError = false
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                val id = initial?.id ?: sourceStore.newCustomId()
                val finalConfig = config.copy(id = id)
                sourceStore.save(finalConfig, makeCurrent = true)
                finalConfig
            }
            onChanged()
            status = "已保存，正在读取该源的 papers.json…"
            val result = runCatching {
                withContext(Dispatchers.IO) { WebDavSource.loadIndex(target) }
            }
            busy = false
            savedOk = true
            result.onSuccess { index ->
                statusError = false
                status = when {
                    !index.fileExists ->
                        "已保存。「${target.name}」根目录还没有 papers.json（0 套试卷），上传一套试卷后就能看到"

                    else -> "已保存。「${target.name}」有 ${index.entries.size} 套试卷"
                }
            }.onFailure {
                statusError = true
                status = "已保存，但读取该源失败：${it.message ?: "未知错误"}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                text = if (initial == null) "新建题库源" else "编辑题库源",
                style = AppText.stemStrong,
            )
        },
        text = {
            // 外层不滚动：状态文字（测试连接的结果 / 保存后的试卷数）固定在字段区下面，
            // 不论字段滚到哪里都看得见。内层才是可滚动的 5 个字段。
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = ComponentSize.dialogFormMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SourceField(label = "名称", value = name, onChange = { name = it }) {
                        status = null
                    }
                    SourceField(label = "服务器地址", value = baseUrl, onChange = { baseUrl = it }) {
                        status = null
                    }
                    SourceField(label = "用户名", value = user, onChange = { user = it }) {
                        status = null
                    }
                    SourceField(
                        label = "密码",
                        value = pass,
                        onChange = { pass = it },
                        isPassword = true,
                    ) {
                        status = null
                    }
                    SourceField(label = "路径（可空）", value = path, onChange = { path = it }) {
                        status = null
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "地址不以 http 开头时自动补 https://；路径可留空，也可以像 shuaati/ 这样只写一层目录。",
                        style = AppText.caption,
                        color = c.textSecondary,
                    )
                }
                status?.let { text ->
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = text,
                        style = AppText.small,
                        color = if (statusError) c.error else c.primary,
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = c.primary,
                            modifier = Modifier.size(ComponentSize.iconSmall),
                        )
                        Spacer(Modifier.size(Spacing.xs))
                        Text(
                            text = "请稍候…",
                            style = AppText.caption,
                            color = c.textSecondary,
                        )
                    }
                }
            }
        },
        // 两个动作按钮放在滚动区**外面**：表单字段多、状态文字出现时会把按钮挤出可视区，
        // 使用者会以为弹窗里没有「保存」。放这里之后无论字段多少、状态多长，按钮都固定在底部。
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    text = "测试连接",
                    onClick = { test() },
                    style = AppButtonStyle.SECONDARY,
                    enabled = !busy,
                    fillWidth = false,
                )
                AppButton(
                    text = if (savedOk) "完成" else "保存",
                    onClick = { if (savedOk) onDismiss() else save() },
                    enabled = !busy,
                    fillWidth = false,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(text = "关闭", style = AppText.smallStrong, color = c.textSecondary)
            }
        },
    )
}

/**
 * 该源的试卷列表弹窗：拉一次 papers.json，点一套就走现有下载流程。
 * 默认源也能看（只显示试卷名与题数，不显示地址）。
 */
@Composable
private fun SourcePapersDialog(
    source: SourceConfig,
    onDownload: (WebDavEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = appColors()
    var state by remember(source.id) { mutableStateOf<RemoteState>(RemoteState.Loading) }
    var retry by remember(source.id) { mutableStateOf(0) }

    LaunchedEffect(source.id, retry) {
        state = RemoteState.Loading
        state = try {
            val index = withContext(Dispatchers.IO) { WebDavSource.loadIndex(source) }
            RemoteState.Ready(index)
        } catch (e: Exception) {
            RemoteState.Fail(e.message ?: "读取该源失败")
        }
    }

    FullScreenDialog(title = "「${source.name}」的试卷", onDismiss = onDismiss) {
                when (val current = state) {
                    RemoteState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = c.primary,
                            modifier = Modifier.size(ComponentSize.icon),
                        )
                        Spacer(Modifier.size(Spacing.xs))
                        Text(
                            text = "正在读取试卷列表…",
                            style = AppText.small,
                            color = c.textSecondary,
                        )
                    }

                    is RemoteState.Fail -> Text(
                        text = current.message,
                        style = AppText.small,
                        color = c.error,
                    )

                    is RemoteState.Ready -> {
                        val index: WebDavIndex = current.index
                        if (!index.fileExists) {
                            Text(
                                text = "这个源的根目录还没有 papers.json（0 套试卷）。\n" +
                                    "把本地试卷「上传到源」之后，这里就会出现。",
                                style = AppText.small,
                                color = c.textSecondary,
                            )
                        } else if (index.entries.isEmpty()) {
                            Text(
                                text = "索引读到了，但里面还没有试卷（0 套）。",
                                style = AppText.small,
                                color = c.textSecondary,
                            )
                        } else {
                            Text(
                                text = "共 ${index.entries.size} 套，点一套开始下载：",
                                style = AppText.small,
                                color = c.textSecondary,
                            )
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
                                        text = "${entry.total} 题 · 版本 ${entry.version}",
                                        style = AppText.caption,
                                        color = c.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
        Spacer(Modifier.height(Spacing.lg))
        if (state is RemoteState.Fail) {
            AppButton(text = "重试", onClick = { retry++ })
        } else {
            AppButton(text = "关闭", onClick = onDismiss, style = AppButtonStyle.TEXT)
        }
    }
}

/** 输入框标签 + 输入框（密码字段用 PasswordVisualTransformation，界面上不显示明文） */
@Composable
private fun SourceField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    isPassword: Boolean = false,
    onEdit: () -> Unit = {},
) {
    val c = appColors()
    Spacer(Modifier.height(Spacing.sm))
    Text(text = label, style = AppText.caption, color = c.textSecondary)
    Spacer(Modifier.height(Spacing.xs))
    OutlinedTextField(
        value = value,
        onValueChange = {
            onChange(it)
            onEdit()
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = AppText.small,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = if (isPassword) {
            KeyboardOptions(keyboardType = KeyboardType.Password)
        } else {
            KeyboardOptions.Default
        },
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
}

/** 拉取该源试卷列表的状态机 */
private sealed interface RemoteState {
    object Loading : RemoteState
    data class Ready(val index: WebDavIndex) : RemoteState
    data class Fail(val message: String) : RemoteState
}

