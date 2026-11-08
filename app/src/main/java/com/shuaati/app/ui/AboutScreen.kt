// 界面层（11 新增，v1.5 按 Material 3 关于页结构重做）：
//
// 这一页只放「关于」该有的东西（Material 设置/关于模式）：
//   1. 应用身份：图标 + 名称 + 版本号（版本号连点 7 次 = 隐藏调试入口）
//   2. 应用状态：题库源数量、当前源、试卷数量（只读，不放操作）
//   3. 数据：数据目录路径（点击复制，方便反馈问题时提供）
//   4. 开源：GitHub 仓库、开源许可、问题反馈
// 不再把「当前源 / 试卷数」这类业务状态塞进设置页底部，设置页只留「关于本应用」入口。
//
// 密码红线（硬性要求 3）：DEBUG_PASSWORD 只以常量形式写在本文件里，界面上不出现任何提示，
// 也不写进日志；报告里只写「在 ui/AboutScreen.kt」不写明文。
package com.shuaati.app.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shuaati.app.R
import com.shuaati.app.data.AppSettings
import com.shuaati.app.data.Diagnostics
import com.shuaati.app.data.PaperMeta
import com.shuaati.app.data.SourceConfig
import com.shuaati.app.ui.components.AppTopBar
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 调试模式密码（硬性要求 3：只以常量形式存在这里）。
 * 界面上不提示、日志里不打、报告里不写明文。
 */
internal const val DEBUG_PASSWORD = "AyqLt4fj5JoGEb"

/** 连点间隔上限（毫秒）：两次点击超过这个时间就重新计数 */
private const val TAP_INTERVAL_MS = 800L

/** 连点多少次打开密码框 */
private const val TAP_COUNT_NEEDED = 7

/** 最多允许输错几次 */
private const val MAX_PASSWORD_ATTEMPTS = 3

/** 开源仓库地址（与 local.properties 的 shuaati.githubRepo 保持一致） */
private const val REPO_URL = "https://github.com/6hrhhszcq4-collab/grind-qs-forever"

/**
 * 关于页。
 *
 * @param settings 设置（写 debugEnabled）
 * @param papers 本地试卷（算来源概况）
 * @param sources 题库源（算来源概况）
 * @param currentSourceName 当前源名字（可能是空字符串 = 没选）
 * @param onBack 返回设置页
 */
@Composable
fun AboutScreen(
    settings: AppSettings,
    papers: List<PaperMeta>,
    sources: List<SourceConfig>,
    currentSourceName: String,
    onBack: () -> Unit,
) {
    val c = appColors()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val versionText = remember { Diagnostics.versionText(context) }
    val versionName = remember { Diagnostics.versionName(context) }
    val filesDir = remember { context.filesDir.absolutePath }

    // 连点计数：taps 是「这一轮点了几下」，lastTapAt 用单调时钟，改系统时间不影响
    var taps by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }

    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var attempts by remember { mutableStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showWrongDialog by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }

    fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        taps = if (now - lastTapAt < TAP_INTERVAL_MS) taps + 1 else 1
        lastTapAt = now
        if (taps >= TAP_COUNT_NEEDED) {
            taps = 0
            password = ""
            attempts = 0
            errorText = null
            showPassword = true
        }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "打不开链接：${it.message ?: "没有可用的浏览器"}", Toast.LENGTH_LONG).show()
        }
    }

    val cloudCount = papers.count { it.origin == PaperMeta.ORIGIN_CLOUD }
    val localCount = papers.count { it.isLocal }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        AppTopBar(title = "关于", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        ) {
            Spacer(Modifier.height(Spacing.xxl))

            // ===== 应用身份：图标 + 名称 + 版本号（连点 7 次 = 调试入口）=====
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.height(Spacing.md))
                Text(text = "刷题", style = AppText.pageTitle, color = c.textPrimary)
                Spacer(Modifier.height(Spacing.xs))
                // 版本号整块可点：连点 7 次进调试入口（界面上不出现任何提示）
                Text(
                    text = "版本 $versionName",
                    style = AppText.small,
                    color = c.textSecondary,
                    modifier = Modifier
                        .clickable { onVersionTap() }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }

            Spacer(Modifier.height(Spacing.xxl))

            // ===== 应用状态（只读）=====
            SectionCard(title = "应用") {
                AboutRow(label = "版本号", value = versionText)
                HorizontalDivider(color = c.border)
                AboutRow(label = "题库源", value = "${sources.size} 个")
                HorizontalDivider(color = c.border)
                AboutRow(label = "当前源", value = currentSourceName.ifBlank { "（未选择）" })
                HorizontalDivider(color = c.border)
                AboutRow(
                    label = "试卷",
                    value = "${papers.size} 套（云端 $cloudCount · 本地 $localCount）",
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            // ===== 数据 =====
            SectionCard(title = "数据") {
                ActionRow(
                    title = "数据目录",
                    subtitle = filesDir,
                    onClick = {
                        clipboard.setText(AnnotatedString(filesDir))
                        Toast.makeText(context, "路径已复制", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            // ===== 开源 =====
            SectionCard(title = "开源") {
                ActionRow(
                    title = "GitHub 仓库",
                    subtitle = "查看源码、下载新版本",
                    onClick = { openUrl(REPO_URL) },
                )
                HorizontalDivider(color = c.border)
                ActionRow(
                    title = "开源许可",
                    subtitle = "Apache License 2.0",
                    onClick = { showLicense = true },
                )
                HorizontalDivider(color = c.border)
                ActionRow(
                    title = "问题反馈",
                    subtitle = "提交 issue",
                    onClick = { openUrl("$REPO_URL/issues") },
                )
            }

            Spacer(Modifier.height(Spacing.xxl))
            Text(
                text = "© 2026 刷题 · 离线刷题工具",
                style = AppText.caption,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    // ===== 密码框 =====
    if (showPassword) {
        AlertDialog(
            onDismissRequest = {
                if (!settings.debugEnabled) {
                    showPassword = false
                    password = ""
                    attempts = 0
                    errorText = null
                }
            },
            title = { Text(text = "输入调试密码", style = AppText.stemStrong) },
            text = {
                Column {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorText = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = AppText.small,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
                    errorText?.let { message ->
                        Spacer(Modifier.height(Spacing.sm))
                        Text(text = message, style = AppText.small, color = c.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (password.trim() == DEBUG_PASSWORD) {
                        settings.updateDebugEnabled(true)
                        showPassword = false
                        password = ""
                        attempts = 0
                        errorText = null
                        Toast.makeText(context, "调试模式已开启", Toast.LENGTH_SHORT).show()
                    } else {
                        attempts++
                        if (attempts >= MAX_PASSWORD_ATTEMPTS) {
                            // 输错 3 次：关掉密码框，弹一句「密码错误」，不锁定
                            showPassword = false
                            password = ""
                            attempts = 0
                            errorText = null
                            showWrongDialog = true
                        } else {
                            errorText = "密码不对，还可以再试 ${MAX_PASSWORD_ATTEMPTS - attempts} 次"
                        }
                    }
                }) {
                    Text(text = "确定", style = AppText.smallStrong, color = c.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPassword = false
                    password = ""
                    attempts = 0
                    errorText = null
                }) {
                    Text(text = "取消", style = AppText.smallStrong, color = c.textSecondary)
                }
            },
        )
    }

    // ===== 输错 3 次的提示 =====
    if (showWrongDialog) {
        AlertDialog(
            onDismissRequest = { showWrongDialog = false },
            title = { Text(text = "密码错误", style = AppText.stemStrong) },
            text = {
                Text(
                    text = "连续输错 $MAX_PASSWORD_ATTEMPTS 次，已关闭密码输入框。",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { showWrongDialog = false }) {
                    Text(text = "知道了", style = AppText.smallStrong, color = c.primary)
                }
            },
        )
    }

    // ===== 开源许可 =====
    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(text = "开源许可", style = AppText.stemStrong) },
            text = {
                Text(
                    text = "本应用以 Apache License 2.0 开源发布。\n\n" +
                        "你可以自由使用、修改和再分发，需保留版权声明与许可文本；" +
                        "不提供任何担保。",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) {
                    Text(text = "知道了", style = AppText.smallStrong, color = c.primary)
                }
            },
        )
    }
}

/** 「标签 —— 值」一行（只读信息行） */
@Composable
private fun AboutRow(label: String, value: String) {
    val c = appColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppText.body,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = AppText.bodyStrong, color = c.textPrimary)
    }
}

/** 可点的一行：标题 + 副标题 + 右侧箭头 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val c = appColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = AppText.body, color = c.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = subtitle,
                    style = AppText.caption,
                    color = c.textSecondary,
                    maxLines = 2,
                )
            }
        }
        Text(text = "›", style = AppText.bodyStrong, color = c.textSecondary)
    }
}
