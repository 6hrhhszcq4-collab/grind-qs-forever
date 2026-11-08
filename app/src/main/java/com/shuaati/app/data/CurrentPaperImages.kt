// 数据层（06B 新增）：当前试卷的图片目录。
//
// 题目里的 image 字段是「images/S015.png」这种相对路径；换试卷后同一个文件名可能对应不同的图，
// 所以解码前必须先问「当前这套试卷的 images 目录在哪」。
//
// 为什么做成全局单例而不是层层传参：答题页、考试页各自有独立的图片 Composable，
// 一路把目录传到最里面要改六七个函数签名，且它们本来就知道「当前在刷哪套卷」。
// 这里只存一个不可变目录（切卷时由 PaperSession 重新设置），不存任何题目数据。
package com.shuaati.app.data

import java.io.File

object CurrentPaperImages {

    @Volatile
    private var dir: File? = null

    /** 切到某套试卷时由 PaperSessionLoader 调用 */
    fun set(imagesDir: File?) {
        dir = imagesDir
    }

    /** 当前试卷的 images 目录（可能还没有，例如纯文字卷） */
    fun currentDir(): File? = dir

    /**
     * 把题目里的 image 路径解析成磁盘文件。
     * - 只取最后一段文件名：「images/S015.png」→ images\S015.png；
     * - 文件不存在返回 null，调用方会退回 assets，再不行就显示「图片加载失败」。
     */
    fun resolve(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val base = path.substringAfterLast('/').trim()
        if (base.isBlank()) return null
        val parent = dir ?: return null
        val file = File(parent, base)
        return if (file.isFile) file else null
    }
}
