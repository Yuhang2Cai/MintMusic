# MintMusic（薄荷音乐）

MintMusic 是面向本地曲库和在线音源的 Android 音乐播放器。项目使用 XML/ViewBinding，支持后台播放、系统媒体控制、断点续播、增量曲库扫描和弱网恢复。

## 功能与架构

- Media3 `MediaSessionService + MediaController`：播放进程、通知栏和系统媒体控制使用同一状态源。
- Room：保存本地曲目索引、在线音源、播放队列及播放历史；首次升级会自动导入旧 SharedPreferences/JSON 数据。
- DataStore：保存目录、播放模式等轻量设置；旧数据保留一个版本用于回退。
- 增量 SAF 扫描：用 URI、文件大小和修改时间识别变化，每 100 条批量写入，仅在完整扫描成功后删除失效记录。
- 封面管线：本地音乐按需读取内嵌封面，在线音源可配置封面 URL；使用 Coil 的内存/磁盘缓存，不在扫描阶段解码图片。
- 弱网恢复：区分可重试网络错误与永久错误，离线时等待网络，在线后采用 1/2/4/8 秒指数退避并加入抖动，最多重试 4 次。
- 播放进度：500ms 仅更新可见 UI 的内存状态；Room 每 5 秒且位移至少 5 秒保存一次，并在暂停、切歌、拖动和错误时立即保存。

## 环境

- Android Studio / JDK 17
- Android SDK 36（`targetSdk 34`、`minSdk 26`）
- AGP 8.10.1 / Gradle 8.11.1 / Kotlin 2.1.20

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

性能基准模块为 `macrobenchmark`，测试协议见 `docs/performance/BASELINE.md`。弱网实验使用 Toxiproxy，操作说明见 `tools/toxiproxy/README.md`。
