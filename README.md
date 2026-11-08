# 刷题（grind-qs-forever）

离线刷题 App：题库放在 WebDAV 题库源上，手机本地缓存，断网也能刷；支持错题本、模拟考试、智能刷、题库增量更新。

## 特性

- **多题库源（WebDAV）**：题库索引 `papers.json` + 每套一个 JSON + 图片目录；支持内置源（特供版）与自建源
- **本地导入**：手机里选一个题库 JSON 直接导入（SAF，不需要存储权限）
- **模拟考试**：限时、交卷出分、10 列答题卡、断点续考
- **智能刷**：按 FSRS 记忆曲线抽题
- **题库更新**：`version.json` 优先、`papers.json` 回退；更新前迁移做题进度
- **上传到源**：把本地试卷传到自建源，版本号自增；同名试卷原地更新
- **调试端口**：调试模式下监听 `127.0.0.1:8765`，用命令驱动界面/改源/检查更新，见 [docs/调试协议.md](docs/调试协议.md)

## 两个版本（同包名同签名，可互相覆盖安装、数据互通）

| flavor | 说明 |
|---|---|
| `community` | 社区版：不内置任何题库源，首次启动自行新建源或本地导入 |
| `internal` | 特供版：内置默认源；凭据只从本机 `local.properties` 注入，不进仓库 |

## 构建

需要 JDK 21 + Android SDK（compileSdk 36、build-tools 36.1.0）。

```bash
./gradlew assembleCommunityRelease    # 社区版
./gradlew assembleInternalRelease     # 特供版（先配好 local.properties）
```

`local.properties`（不进仓库）：

```properties
sdk.dir=...
# 正式签名（release 包必需）
shuaati.storePassword=...
shuaati.keyAlias=...
shuaati.keyPassword=...
# 只有特供版需要内置源
shuaati.defaultSource.baseUrl=...
shuaati.defaultSource.user=...
shuaati.defaultSource.pass=...
shuaati.defaultSource.path=...
# 应用自身更新检查的仓库（owner/repo）
shuaati.githubRepo=owner/repo
```

## 文档

- [题库 JSON 格式](docs/题库格式.md)
- [调试协议](docs/调试协议.md)

## 许可

[Apache License 2.0](LICENSE)
