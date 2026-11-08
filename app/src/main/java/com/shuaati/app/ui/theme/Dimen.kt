// 设计令牌：间距、圆角、组件尺寸
//
// 规矩：页面里只准用这里的常量，不许出现 padding(13.dp)、RoundedCornerShape(10.dp) 这类随手值。
// 现有页面改造时遇到旧值（12/14/16/20…）一律就近归到下面这几档。
package com.shuaati.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 间距：只有 4 / 8 / 12 / 16 / 24 / 32 六档 */
object Spacing {
    /** 4dp：图标与文字之间、微小间隔 */
    val xs = 4.dp

    /** 8dp：同一组元素内部 */
    val sm = 8.dp

    /** 12dp：列表项之间 */
    val md = 12.dp

    /** 16dp：卡片内边距、页面左右边距 */
    val lg = 16.dp

    /** 24dp：大块之间 */
    val xl = 24.dp

    /** 32dp：页面顶部/底部的呼吸空间 */
    val xxl = 32.dp
}

/** 圆角：按钮 12、卡片 16、标签 8 */
object Radius {
    /** 8dp：标签、小色块 */
    val label = 8.dp

    /** 12dp：按钮、可点击行 */
    val button = 12.dp

    /** 16dp：卡片、分组容器、弹窗 */
    val card = 16.dp

    val labelShape = RoundedCornerShape(label)
    val buttonShape = RoundedCornerShape(button)
    val cardShape = RoundedCornerShape(card)
}

/** 组件固定尺寸：按钮最小高度、选项最小高度、环形参数等 */
object ComponentSize {
    /** 按钮最小高度（可点区域下限） */
    val buttonMinHeight = 48.dp

    /** 选项卡片最小高度 */
    val optionMinHeight = 56.dp

    /** 环形进度条宽度 */
    val ringStroke = 12.dp

    /** 分题型小环的环宽（小环用细一点才不显笨重） */
    val ringStrokeSmall = 8.dp

    /** 首页大环直径 */
    val ringLarge = 210.dp

    /** 分题型小环直径 */
    val ringSmall = 96.dp

    /**
     * 首次引导页的圆形图标区直径（07 新增）。
     * 比分题型小环大、比首页大环小，刚好放得下一个几何图形或一个大字。
     */
    val onboardingBadge = 128.dp

    /** 选项字母圆圈直径 */
    val optionKeyBadge = 28.dp

    /** 图标默认尺寸 */
    val icon = 24.dp

    /** 图标小尺寸（对错标记） */
    val iconSmall = 20.dp

    /** 答题卡格子高度 */
    val answerCell = 36.dp

    /** 图例小色块边长 */
    val legendSwatch = 14.dp

    /** 考试规则卡里的标题列宽（「题量」「时长」…固定列宽，说明文字才能对齐） */
    val ruleLabelWidth = 52.dp

    /**
     * 弹窗里可滚动列表的最大高度（06B：试卷列表、网络试卷列表用）。
     * 放不进令牌的只有这一处：列表项数量由用户导入的试卷数决定，必须封顶才不会把弹窗撑出屏幕。
     */
    val dialogListMaxHeight = 320.dp

    /**
     * 表单弹窗（11：新建 / 编辑题库源的 5 个输入框）的滚动区最大高度。
     * 「测试连接 / 保存」两个按钮放在滚动区外面（固定在弹窗底部），
     * 这里只限制字段区高度：小屏或大字体时字段区自己滚动，按钮永远看得见。
     */
    val dialogFormMaxHeight = 440.dp

    /** 描边宽度：普通 / 强调 */
    val stroke = 1.dp
    val strokeStrong = 2.dp
}
