package me.rerere.rikkahub.ui.hooks

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.LocaleListCompat
import me.rerere.rikkahub.R
import java.util.Locale

/**
 * 应用内可选语言。[tag] 为空表示跟随系统。
 *
 * 语言值保存在 `airko.preferences`；Activity/Application 在 attachBaseContext 时套用，
 * API 33+ 同时同步给系统的 per-app language 设置。
 */
enum class AppLanguage(val tag: String, val labelRes: Int) {
    SYSTEM("", R.string.app_language_system),
    ENGLISH("en", R.string.app_language_english),
    SIMPLIFIED_CHINESE("zh-CN", R.string.app_language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-TW", R.string.app_language_traditional_chinese),
    JAPANESE("ja", R.string.app_language_japanese),
    KOREAN("ko", R.string.app_language_korean),
    RUSSIAN("ru", R.string.app_language_russian),
    ARABIC("ar", R.string.app_language_arabic),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

const val APP_LANGUAGE_KEY = "appLanguage"

/** 读取保存的语言标签；空串表示跟随系统。 */
fun Context.readAppLanguageTag(): String = readStringPreference(APP_LANGUAGE_KEY, "").orEmpty()

/** 在 attachBaseContext 里给 Context 套用应用语言。 */
fun Context.withAppLanguage(): Context {
    val tag = readAppLanguageTag()
    if (tag.isBlank()) return this
    val locale = Locale.forLanguageTag(tag)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

/** 启动时同步给 AppCompat（API 33+ 会写入系统 per-app language）。 */
fun applySavedAppLanguage(context: Context) {
    val tag = context.readAppLanguageTag()
    val locales = if (tag.isBlank()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(tag)
    }
    runCatching { AppCompatDelegate.setApplicationLocales(locales) }
}

@Composable
fun rememberAppLanguage(): MutableState<AppLanguage> {
    val context = LocalContext.current
    val preference = rememberSharedPreferenceString(APP_LANGUAGE_KEY, "")
    return remember(preference, context) {
        object : MutableState<AppLanguage> {
            override var value: AppLanguage
                get() = AppLanguage.fromTag(preference.value)
                set(value) {
                    preference.value = value.tag
                    applySavedAppLanguage(context)
                    // API < 33 时 AppCompat 不会重建我们的 ComponentActivity，手动刷新一次
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        (context as? Activity)?.recreate()
                    }
                }

            override fun component1(): AppLanguage = value

            override fun component2(): (AppLanguage) -> Unit = { value = it }
        }
    }
}
