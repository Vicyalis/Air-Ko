package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

private const val PREFERENCES_NAME = "airko.preferences"
private const val LEGACY_PREFERENCES_NAME = "rikkahub.preferences"

@Volatile
private var legacyPreferencesMigrated = false

/**
 * Air-Ko 专用 SharedPreferences。
 *
 * 旧版本(RikkaHub 血统)使用 `rikkahub.preferences` 存储少量 UI 状态,
 * 首次访问时把旧文件里的键值迁移到新的 `airko.preferences`, 然后清空旧文件,
 * 这样既清掉旧品牌的存储位置, 又不丢用户状态。
 */
private fun Context.airkoPreferences(): SharedPreferences {
    val prefs = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    migrateLegacyPreferences(prefs)
    return prefs
}

private fun Context.migrateLegacyPreferences(target: SharedPreferences) {
    if (legacyPreferencesMigrated) return
    legacyPreferencesMigrated = true
    if (target.all.isNotEmpty()) return
    runCatching {
        val legacy = getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val entries = legacy.all
        if (entries.isEmpty()) return@runCatching
        target.edit {
            entries.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        legacy.edit { clear() }
    }
}

@Composable
fun rememberSharedPreferenceString(
    keyForString: String,
    defaultValue: String? = null
): MutableState<String?> {
    val context = LocalContext.current
    val prefs = remember {
        context.airkoPreferences()
    }
    val stateFlow =
        remember(keyForString, defaultValue) { prefs.getStringFlowForKey(keyForString, defaultValue) }
    val state by stateFlow.collectAsStateWithLifecycle(prefs.getString(keyForString, defaultValue))
    val currentState = rememberUpdatedState(state)
    return remember {
        object : MutableState<String?> {
            override var value: String?
                get() = currentState.value
                set(value) {
                    prefs.edit { putString(keyForString, value) }
                }

            override fun component1(): String? = value
            override fun component2(): (String?) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberSharedPreferenceBoolean(
    keyForBoolean: String,
    defaultValue: Boolean = false
): MutableState<Boolean> {
    val context = LocalContext.current
    val prefs = remember {
        context.airkoPreferences()
    }
    val stateFlow =
        remember(keyForBoolean, defaultValue) { prefs.getBooleanFlowForKey(keyForBoolean, defaultValue) }
    val state by stateFlow.collectAsStateWithLifecycle(prefs.getBoolean(keyForBoolean, defaultValue))
    val currentState = rememberUpdatedState(state)
    return remember {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = currentState.value
                set(value) {
                    prefs.edit { putBoolean(keyForBoolean, value) }
                }

            override fun component1(): Boolean = value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }
}

fun Context.writeStringPreference(key: String, value: String?) {
    airkoPreferences().edit {
        putString(key, value)
    }
}

fun Context.readStringPreference(key: String, defaultValue: String? = null): String? {
    return airkoPreferences().getString(key, defaultValue)
}

fun Context.writeBooleanPreference(key: String, value: Boolean) {
    airkoPreferences().edit {
        putBoolean(key, value)
    }
}

fun Context.readBooleanPreference(key: String, defaultValue: Boolean = false): Boolean {
    return airkoPreferences().getBoolean(key, defaultValue)
}

fun SharedPreferences.getStringFlowForKey(keyForString: String, defaultValue: String? = null) =
    callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (keyForString == key) {
                trySend(getString(key, defaultValue))
            }
        }
        registerOnSharedPreferenceChangeListener(listener)
        if (contains(keyForString)) {
            send(
                getString(
                    keyForString,
                    defaultValue
                )
            ) // if you want to emit an initial pre-existing value
        }
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.UNLIMITED) // so trySend never fails

fun SharedPreferences.getBooleanFlowForKey(keyForBoolean: String, defaultValue: Boolean = false) =
    callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (keyForBoolean == key) {
                trySend(getBoolean(key, defaultValue))
            }
        }
        registerOnSharedPreferenceChangeListener(listener)
        if (contains(keyForBoolean)) {
            send(
                getBoolean(
                    keyForBoolean,
                    defaultValue
                )
            ) // if you want to emit an initial pre-existing value
        }
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.UNLIMITED) // so trySend never fails
