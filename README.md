# 刷题（grind-qs-forever）

离线刷题 App：题库放在 WebDAV 题库源上，手机本地缓存，断网也能刷；支持错题本、模拟考试、智能刷、题库增量更新。

## 功能

- **本地导入**：手机里选一个题库 JSON 直接导入（系统文件选择器，不需要存储权限）
- **题库源（WebDAV）**：索引 `papers.json` + 每套一个题库存 JSON + 图片目录；可建多个源自如切换
- **刷题**：单选 / 判断 / 多选，答对自动跳题，答错立刻显示正确答案与解析
- **错题本**：答错自动收录，连续答对 3 次自动移出
- **模拟考试**：按比例抽题、限时、交卷出分、10 列答题卡、断点续考
- **智能刷**：按 FSRS 记忆曲线抽题，越不会的越常出现
- **题库更新**：`version.json` 优先、`papers.json` 回退；更新前自动迁移做题进度
- **上传到源**：本地试卷传到自建源，版本号自增；同名试卷原地更新
- **调试端口**：调试模式下监听 `127.0.0.1:8765`，用命令驱动界面、改检查更新的源，见 [docs/调试协议.md](docs/调试协议.md)

## 构建

需要 JDK 21 + Android SDK（compileSdk 36、build-tools 36.1.0）。

```bash
./gradlew assembleCommunityRelease
```

`local.properties`（不进仓库，至少要有 `sdk.dir` 和正式签名；内置源与更新仓库都是可选）：

```properties
sdk.dir=...
shuaati.storePassword=...
shuaati.keyAlias=...
shuaati.keyPassword=...
# 可选：应用自身更新检查的仓库（owner/repo）
shuaati.githubRepo=owner/repo
```

## 文档

- [题库 JSON 格式](docs/题库格式.md)
- [调试协议](docs/调试协议.md)

## 许可

[Apache License 2.0](LICENSE)
