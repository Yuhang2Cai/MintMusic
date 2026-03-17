package com.example.timedmusicplayer.analytics

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 轻量本地埋点记录器：记录事件、错误与崩溃。
 */
class EventLogger private constructor(context: Context) {

    // 属性： appContext
    // 说明：运行期状态变量，承载 appContext 相关上下文信息。
    private val appContext = context.applicationContext
    // 属性： executor
    // 说明：运行期状态变量，承载 executor 相关上下文信息。
    private val executor = Executors.newSingleThreadExecutor()
    // 属性： lock
    // 说明：运行期状态变量，承载 lock 相关上下文信息。
    private val lock = Any()
    // 属性： timeFormat
    // 说明：运行期状态变量，承载 timeFormat 相关上下文信息。
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 属性： eventsFile
    // 说明：运行期状态变量，承载 eventsFile 相关上下文信息。
    private val eventsFile: File by lazy {
        resolveLogFile("events.log")
    }

    // 属性： crashFile
    // 说明：运行期状态变量，承载 crashFile 相关上下文信息。
    private val crashFile: File by lazy {
        resolveLogFile("crash.log")
    }

    // 函数： logEvent
    // 说明：封装 logEvent 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun logEvent(name: String, params: Map<String, String> = emptyMap()) {
    // 属性： line
    // 说明：运行期状态变量，承载 line 相关上下文信息。
        val line = buildLine(level = "EVENT", name = name, params = params)
        executor.execute {
            writeLine(eventsFile, line)
        }
    }

    // 函数： logError
    // 说明：封装 logError 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun logError(name: String, throwable: Throwable, params: Map<String, String> = emptyMap()) {
    // 属性： merged
    // 说明：运行期状态变量，承载 merged 相关上下文信息。
        val merged = params + mapOf(
            "exception" to throwable.javaClass.simpleName,
            "message" to (throwable.message ?: "")
        )
    // 属性： line
    // 说明：运行期状态变量，承载 line 相关上下文信息。
        val line = buildLine(level = "ERROR", name = name, params = merged)
        executor.execute {
            writeLine(eventsFile, line)
        }
    }

    // 函数： logCrashSync
    // 说明：封装 logCrashSync 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun logCrashSync(threadName: String, throwable: Throwable) {
    // 属性： stackTrace
    // 说明：运行期状态变量，承载 stackTrace 相关上下文信息。
        val stackTrace = StringWriter().also { writer ->
            throwable.printStackTrace(PrintWriter(writer))
        }.toString().replace("\r", "")

    // 属性： crashParams
    // 说明：运行期状态变量，承载 crashParams 相关上下文信息。
        val crashParams = mapOf(
            "thread" to threadName,
            "exception" to throwable.javaClass.name,
            "message" to (throwable.message ?: "")
        )
    // 属性： line
    // 说明：运行期状态变量，承载 line 相关上下文信息。
        val line = buildLine(level = "CRASH", name = "uncaught_exception", params = crashParams)

        synchronized(lock) {
            writeLineInternal(crashFile, line)
            writeLineInternal(crashFile, stackTrace)
            writeLineInternal(crashFile, "----------------------------------------")
        }
    }

    // 函数： buildLine
    // 说明：封装 buildLine 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun buildLine(level: String, name: String, params: Map<String, String>): String {
    // 属性： ts
    // 说明：运行期状态变量，承载 ts 相关上下文信息。
        val ts = timeFormat.format(Date())
    // 属性： attrs
    // 说明：运行期状态变量，承载 attrs 相关上下文信息。
        val attrs = if (params.isEmpty()) {
            ""
        } else {
            params.entries.joinToString(separator = ";") { (k, v) ->
                "$k=${v.replace("\n", " ").replace(";", ",").trim()}"
            }
        }
        return if (attrs.isBlank()) {
            "$ts|$level|$name"
        } else {
            "$ts|$level|$name|$attrs"
        }
    }

    // 函数： writeLine
    // 说明：封装 writeLine 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun writeLine(file: File, line: String) {
        synchronized(lock) {
            writeLineInternal(file, line)
        }
    }

    // 函数： writeLineInternal
    // 说明：封装 writeLineInternal 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun writeLineInternal(file: File, line: String) {
        trimIfNeeded(file)
        file.appendText(line)
        file.appendText("\n")
    }

// 函数： trimIfNeeded
// 说明：裁剪数据体积，控制文件或内存占用。
private fun trimIfNeeded(file: File) {
        if (!file.exists()) {
            return
        }

        if (file.length() <= MAX_FILE_SIZE_BYTES) {
            return
        }

    // 属性： bytes
    // 说明：运行期状态变量，承载 bytes 相关上下文信息。
        val bytes = file.readBytes()
    // 属性： keepSize
    // 说明：运行期状态变量，承载 keepSize 相关上下文信息。
        val keepSize = (MAX_FILE_SIZE_BYTES / 2).toInt()
    // 属性： kept
    // 说明：运行期状态变量，承载 kept 相关上下文信息。
        val kept = if (bytes.size > keepSize) {
            bytes.copyOfRange(bytes.size - keepSize, bytes.size)
        } else {
            bytes
        }
        file.writeBytes(kept)
    }

    // 函数： resolveLogFile
    // 说明：基于输入条件推导最终可用结果。
    private fun resolveLogFile(name: String): File {
    // 属性： dir
    // 说明：运行期状态变量，承载 dir 相关上下文信息。
        val dir = File(appContext.filesDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
    // 属性： file
    // 说明：运行期状态变量，承载 file 相关上下文信息。
        val file = File(dir, name)
        if (!file.exists()) {
            file.createNewFile()
        }
        return file
    }

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 600 * 1024L

        @Volatile
        private var instance: EventLogger? = null

        // 函数： getInstance
        // 说明：读取并返回当前数据或状态快照。
        fun getInstance(context: Context): EventLogger {
            return instance ?: synchronized(this) {
                instance ?: EventLogger(context).also { instance = it }
            }
        }
    }
}