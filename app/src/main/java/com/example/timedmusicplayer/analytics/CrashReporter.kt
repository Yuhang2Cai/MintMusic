package com.example.timedmusicplayer.analytics

/**
 * 全局崩溃钩子：在交给系统前先记录未捕获异常。
 */
class CrashReporter(
    private val logger: EventLogger
) {

    // 属性： installed
    // 说明：运行期状态变量，承载 installed 相关上下文信息。
    private var installed = false

    // 函数： install
    // 说明：封装 install 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun install() {
        if (installed) {
            return
        }
        installed = true

    // 属性： previous
    // 说明：运行期状态变量，承载 previous 相关上下文信息。
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logger.logCrashSync(threadName = thread.name, throwable = throwable)
            } catch (_: Throwable) {
                // 不阻塞系统默认崩溃处理流程。
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}