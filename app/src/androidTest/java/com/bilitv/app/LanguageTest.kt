package com.bilitv.app

import android.app.LocaleManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.bilitv.app.ui.MainActivity
import com.bilitv.app.util.Fmt
import com.bilitv.app.util.Strings
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LanguageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val systemLoginTitle = context.createConfigurationContext(
        Configuration(Resources.getSystem().configuration).apply {
            if (Build.VERSION.SDK_INT >= 33) {
                setLocales(context.getSystemService(LocaleManager::class.java).systemLocales)
            }
        }
    ).getString(R.string.login_title)

    @After
    fun resetLanguage() {
        instrumentation.runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun chineseRegionsAndFormattedResources() {
        for ((tag, history, unit) in listOf(
            Triple("zh-CN", "历史记录", "万"),
            Triple("zh-SG", "历史记录", "万"),
            Triple("zh-TW", "歷史紀錄", "萬"),
            Triple("zh-HK", "歷史紀錄", "萬"),
            Triple("en-US", "歷史紀錄", "萬"), // preserve the original fallback
        )) {
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val localized = context.createConfigurationContext(config)
            assertEquals(tag, history, localized.getString(R.string.nav_history))
            assertEquals(tag, "1.2$unit", localized.getString(R.string.count_ten_thousand, 1.2))
            assertEquals(tag, if (unit == "万") "下载中 50%" else "下載中 50%",
                localized.getString(R.string.download_progress, 50))
            assertTrue(localized.getString(R.string.playback_error_retry, "TEST").contains("TEST\n"))
        }
        // Android 13+ appends system locales after the app's selection. An explicit
        // Hant resource set must win over a secondary Simplified Chinese system locale.
        if (Build.VERSION.SDK_INT >= 24) {
            val config = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag("zh-Hant"), Locale.forLanguageTag("zh-Hans")))
            }
            assertEquals("歷史紀錄", context.createConfigurationContext(config).getString(R.string.nav_history))
        }
    }

    @Test
    fun remoteLanguagePickerRecreatesAndPersists() {
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-Hans"))
            }
            openMine()
            awaitText("界面语言")
            assertEquals("历史记录", Strings.get(R.string.nav_history))
            assertEquals("1.2万", Fmt.count(12_000))
            assertTrue(Prefs.qualityName(112).contains("高码率"))

            focusText("繁體中文")
            device.pressDPadCenter()
            awaitText("掃碼登入")
            assertEquals("歷史紀錄", Strings.get(R.string.nav_history))
            assertEquals("1.2萬", Fmt.count(12_000))
            assertTrue(Prefs.qualityName(112).contains("高碼率"))

            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            openMine()
            awaitText("掃碼登入")

            focusText("简体中文")
            device.pressDPadCenter()
            // Activity recreation restores scroll position; the section heading can
            // be above the viewport, while the login panel remains visible.
            awaitText("扫码登录")
            focusText("跟随系统")
            device.pressDPadCenter()
            awaitText(systemLoginTitle)
            instrumentation.runOnMainSync {
                assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
            }
        } finally {
            scenario.close()
        }
    }

    private fun openMine() {
        // The TV drawer can hide its labels and scroll off screen. Navigate by focus,
        // rather than tapping a text node whose bounds move during the drawer animation.
        assertNotNull(device.wait(Until.findObject(By.focused(true)), 10_000))
        device.waitForIdle()
        repeat(7) { device.pressDPadDown() }
        device.pressDPadCenter()
    }

    private fun awaitText(text: String) {
        val found = device.wait(Until.hasObject(By.text(text)), 15_000)
        if (!found) {
            val directory = context.getExternalFilesDir(null)!!
            device.dumpWindowHierarchy(File(directory, "language-failure.xml"))
            device.takeScreenshot(File(directory, "language-failure.png"))
            instrumentation.runOnMainSync {
                println("Selected app locales: ${AppCompatDelegate.getApplicationLocales().toLanguageTags()}")
            }
        }
        assertTrue("Missing UI text: $text", found)
    }

    private fun focusText(text: String) {
        awaitText(text)
        val labels = listOf(listOf("跟随系统", "跟隨系統"), listOf("简体中文"), listOf("繁體中文"))
        val target = labels.indexOfFirst { text in it }
        repeat(16) {
            device.waitForIdle()
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            try {
                val focused = device.findObject(By.focused(true))
                if (focused?.findObject(By.text(text)) != null) return
                val current = labels.indexOfFirst { names ->
                    names.any { focused?.findObject(By.text(it)) != null }
                }
                val bounds = focused?.visibleBounds
                when {
                    bounds == null || bounds.right < device.displayWidth / 8 -> device.pressDPadRight()
                    current < 0 -> device.pressDPadUp()
                    current < target -> device.pressDPadRight()
                    else -> device.pressDPadLeft()
                }
            } catch (_: StaleObjectException) {
                // Locale recreation and QR updates replace accessibility nodes; reacquire them.
            }
        }
        fail("Cannot focus language option with D-pad: $text")
    }
}
