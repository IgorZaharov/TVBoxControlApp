package com.tvboxremote.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * A context whose strings follow the app language from Preferences.
 * Android 13 and later do this for every context of the app. On Android 12 and earlier, AppCompat changes only
 * the activity, so view model messages and the widget use this context.
 * Limit: in a process without an activity yet (a widget update after a restart), AppCompat has not read the
 * stored choice, and the strings follow the phone language.
 */
fun Context.withAppLanguage(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
    return createConfigurationContext(config)
}
