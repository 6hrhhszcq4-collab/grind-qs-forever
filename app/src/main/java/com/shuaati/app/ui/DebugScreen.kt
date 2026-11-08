// 界面层（v1.5 新增）：调试模式全屏页。
//
// 全屏页（Overlay），返回键退出；显示调试端口状态、adb forward 命令（可复制）、
// 最近命令日志，以及「关闭调试模式」。
//
// 调试端口的命令协议见 data/DebugServer.kt；宿主机上的客户端是 tools/debugctl.py。
package com.shuaati.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.shuaati.app.data.DebugServer
import com.shuaati.app.ui.components.AppButton
import com.shuaati.app.ui.components.AppButtonStyle
import com.shuaati.app.ui.components.AppTopBar
import com.shuaati.app.ui.components.SectionCard
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors
import kotlinx.coroutines.delay

/**
 * 调试面板（全屏页）。
 *
 * @param onBack 返回设置页
 * @param onDisableDebug 关闭调试模式（同时停止端口）
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    onDisableDebug: () -> Unit,
) {
    val c = appColors()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val forwardCommand = "adb forward tcp:${DebugServer.PORT} tcp:${DebugServer.PORT}"

    var logs by remember { mutableStateOf(DebugServer.recentLog()) }
    var running by remember { mutableStateOf(DebugServer.running) }

    // 每秒刷新一次日志与端口状态（本地小开销）
    LaunchedEffect(Unit) {
        while (true) {
            logs = DebugServer.recentLog()
            running = DebugServer.running
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .systemBarsPadding(),
    ) {
        AppTopBar(title = "调试", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
        ) {
            Spacer(Modifier.height(Spacing.lg))

            SectionCard(title = "调试端口") {
                InfoLine("状态", if (running) "监听中" else "未启动")
                HorizontalDivider(color = c.border)
                InfoLine("地址", "127.0.0.1:${DebugServer.PORT}")
                HorizontalDivider(color = c.border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "宿主机转发", style = AppText.caption, color = c.textSecondary)
                        Text(text = forwardCommand, style = AppText.small, color = c.textPrimary)
                    }
                    AppButton(
                        text = "复制",
                        onClick = {
                            clipboard.setText(AnnotatedString(forwardCommand))
                            android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        style = AppButtonStyle.SECONDARY,
                        fillWidth = false,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            SectionCard(title = "命令") {
                Text(
                    text = "转发后：python tools/debugctl.py help\n" +
                        "改检查更新的源：update source http://10.0.2.2:8099/dav/ testuser testpass shuaati/\n" +
                        "检查更新：update",
                    style = AppText.small,
                    color = c.textSecondary,
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            SectionCard(title = "最近命令") {
                if (logs.isEmpty()) {
                    Text(text = "（还没有命令）", style = AppText.small, color = c.textSecondary)
                } else {
                    logs.forEach { line ->
                        Text(text = line, style = AppText.caption, color = c.textSecondary)
                        Spacer(Modifier.height(Spacing.xs))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            AppButton(
                text = "关闭调试模式",
                onClick = onDisableDebug,
                style = AppButtonStyle.SECONDARY,
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/** 「标签 —— 值」一行 */
@Composable
private fun InfoLine(label: String, value: String) {
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
