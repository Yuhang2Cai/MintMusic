package com.example.timedmusicplayer.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartup() = rule.measureRepeated(
        packageName = "com.example.timedmusicplayer",
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() }
    ) { startActivityAndWait() }
}
