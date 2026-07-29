# MintMusic（薄荷音乐）

MintMusic 是一款面向本地曲库与在线音源的 Android 音乐播放器，支持统一曲库浏览、后台播放、播放队列和断点续播。

## 当前功能

- 通过系统文件访问框架（SAF）选择并扫描本地音乐目录。
- 添加、编辑和删除在线音源，并与本地歌曲统一展示。
- 支持顺序播放、单曲循环、列表循环和随机播放。
- 保存播放队列、当前曲目和播放进度，支持继续上次播放。
- 使用前台服务保持后台播放，并提供通知栏媒体控制。
- 通过 MediaSession 支持系统媒体控制，在线播放期间使用 Wi-Fi Lock 保持连接。
- 提供音乐库筛选、独立播放器页面和迷你播放器。

## 技术栈

- Kotlin
- AndroidX / Material Components
- Media3 ExoPlayer
- ViewModel、StateFlow 与 Kotlin Coroutines
- RecyclerView、ListAdapter 与 ViewBinding
- Storage Access Framework / DocumentFile

## 环境要求

- Android Studio
- Android SDK 34
- JDK 8 兼容工具链
- Android 8.0（API 26）及以上设备

## 构建与运行

1. 使用 Android Studio 打开项目。
2. 等待 Gradle 同步完成。
3. 连接 Android 设备或启动模拟器。
4. 运行 `app` 配置。

首次使用本地曲库时，需要通过系统目录选择器授予音乐目录访问权限。在线音源播放需要网络连接。

## 项目结构

```text
app/src/main/java/.../
├── analytics/   # 事件日志与异常记录
├── data/        # 本地曲库、在线音源及播放记录
├── model/       # 领域模型
├── playback/    # 播放服务、队列与播放状态
├── ui/          # 页面状态与 ViewModel
└── adapter/     # 曲库及在线音源列表
```

## 验证

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

## 后续方向

- 提升弱网流媒体播放和错误恢复能力。
- 迁移到完整的 Media3 MediaSessionService 架构。
- 建设基于 Room、Paging 3 和增量扫描的大型本地曲库。
- 使用 Macrobenchmark 与 Perfetto 建立可复现的性能基线。
