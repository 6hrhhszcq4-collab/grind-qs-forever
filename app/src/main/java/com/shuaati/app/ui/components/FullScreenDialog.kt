// 通用组件（v1.5）：全屏对话框。
//
// 背景：Material 指南里「内容型选择页」应该全屏，而不是挤在一个小 Dialog 里
//（源试卷列表、从源下载、切换试卷、答题卡在手机上全屏看更清楚）。
// 这里用 Dialog + usePlatformDefaultWidth=false + fillMaxSize 实现「视觉完全全屏」，
// 顶栏统一带返回，返回键 / 返回按钮都能关，行为与全屏页一致。
package com.shuaati.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 全屏对话框。
 *
 * @param title 顶栏标题
 * @param onDismiss 关闭（顶栏返回 / 系统返回键 / 背景点击）
 * @param scrollable true = 内容区可滚动（列表类）；false = 内容固定（答题卡这种）
 * @param content 内容
 */
@Composable
fun FullScreenDialog(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = appColors()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = c.background) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                AppTopBar(title = title, onBack = onDismiss)
                val body = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                if (scrollable) {
                    Column(modifier = body.verticalScroll(rememberScrollState())) {
                        content()
                    }
                } else {
                    Column(modifier = body) {
                        content()
                    }
                }
            }
        }
    }
}
