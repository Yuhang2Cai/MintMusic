package com.example.timedmusicplayer

import android.app.Application
import com.example.timedmusicplayer.analytics.CrashReporter
import com.example.timedmusicplayer.analytics.EventLogger

/**
 * 应用入口：安装崩溃捕获并记录应用启动事件。
 */
class MintMusicApplication : Application() {

    // 函数： onCreate
    // 说明：生命周期初始化入口，完成依赖注入、组件初始化与初始状态设置。
    override fun onCreate() {
        super.onCreate()

    // 属性： logger
    // 说明：日志记录器，写入事件、错误与崩溃信息。
        val logger = EventLogger.getInstance(this)
        CrashReporter(logger).install()

        logger.logEvent(
            name = "app_start",
            params = mapOf(
                "version_name" to BuildConfig.VERSION_NAME,
                "version_code" to BuildConfig.VERSION_CODE.toString()
            )
        )
    }
}