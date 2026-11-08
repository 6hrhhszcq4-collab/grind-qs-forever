// 数据层：把一份题库 JSON 变成 Question 列表，代码里不写死任何题目。
//
// v1.3 起不再从 assets 读题库（安装包里的内置题库已删除）：
// 唯一来源是 filesDir\papers\<paperId>\questions.json（从源下载 / 本地导入 / 更新后的正式位置）。
// 解析逻辑只有这一份，所有路径共用。
package com.shuaati.app.data

import android.util.Log
import org.json.JSONObject
import java.io.File

/** 题库读取器：只负责把 JSON 变成 Question 列表，不做缓存与分页 */
object QuestionRepo {

    private const val TAG = "Shuaati"

    /**
     * 读取一套试卷的题库文件（papers\<paperId>\questions.json）。
     * 找不到或解析失败时抛异常，由界面决定怎么提示，不在这里吞掉。
     */
    fun loadFromFile(file: File): List<Question> {
        if (!file.isFile) throw IllegalStateException("找不到题库文件：${file.absolutePath}")
        return parse(file.readText(Charsets.UTF_8))
    }

    /**
     * 解析题库 JSON 文本。
     * 顶层必须有 questions 数组；单题解析是宽容的（缺字段给空值），
     * 严格校验在 PaperImporter 里做，因为「导入」需要逐条说明哪一题不合法。
     */
    fun parse(text: String): List<Question> {
        val root = JSONObject(text)
        val array = root.optJSONArray("questions")
            ?: throw IllegalStateException("题库缺少 questions 数组")

        val list = ArrayList<Question>(array.length())
        for (i in 0 until array.length()) {
            list.add(parseQuestion(array.getJSONObject(i)))
        }

        Log.i(TAG, "loaded ${list.size} questions")
        // 把图片题的路径也打出来，方便核对试卷目录里确实有这张图
        list.filter { it.image != null }.forEach {
            Log.i(TAG, "图片题 ${it.id} no=${it.no} image=${it.image}")
        }
        return list
    }

    /**
     * 逐字段解析一道题；字段名按任务书，不许改名。
     * 06B 起 PaperImporter 也复用它做校验，所以是 public。
     *
     * v1.3 新增 paper：题库 JSON 里的「第几套」编号。进度迁移靠它组成
     * (paper, type, no) 三元组；老题库没有这个字段时按 1 处理。
     */
    fun parseQuestion(obj: JSONObject): Question {
        val optionsArray = obj.optJSONArray("options")
        val options = ArrayList<Option>(optionsArray?.length() ?: 0)
        if (optionsArray != null) {
            for (j in 0 until optionsArray.length()) {
                val item = optionsArray.getJSONObject(j)
                options.add(
                    Option(
                        key = item.optString("key", ""),
                        text = item.optString("text", ""),
                    )
                )
            }
        }

        return Question(
            id = obj.optString("id", ""),
            type = obj.optString("type", ""),
            no = obj.optInt("no", 0),
            stem = obj.optString("stem", ""),
            // image 可能是 JSON null，要单独判断，不能直接 optString
            image = if (obj.isNull("image")) null else obj.optString("image", "").takeIf { it.isNotBlank() },
            options = options,
            // answer 可能是空字符串：保留原样，交给界面按「本题暂无答案」处理
            answer = obj.optString("answer", ""),
            explain = if (obj.isNull("explain")) null else obj.optString("explain", "").takeIf { it.isNotBlank() },
            paper = obj.optInt("paper", 1),
        )
    }
}
