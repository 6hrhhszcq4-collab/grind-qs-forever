// 界面层（06B 新增）：题目图片的解码入口。
//
// 06B 起题库有多套，图片也不再只存在于 assets：
//   1. 先在「当前试卷」的 images 目录找（papers\<paperId>\images\S015.png）；
//   2. 找不到再退回 assets（内置卷的老路径仍然可用）；
//   3. 都失败返回 null，由调用方显示「图片加载失败」。
//
// 之所以单独一个文件：刷题页和考试页各有一个图片 Composable，
// 两处必须用同一套查找顺序，否则会出现「刷题有图、考试没图」的怪现象。
package com.shuaati.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.shuaati.app.data.CurrentPaperImages

/**
 * 解码一道题的图片。
 *
 * @param path 题库里的 image 字段（例如 images/S015.png）
 * @return 解码好的位图；路径为空、文件不存在、解码失败都返回 null
 */
fun decodeQuestionImage(context: Context, path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val file = CurrentPaperImages.resolve(path)
        if (file != null) {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        } else {
            context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }
    }.getOrNull()
}
