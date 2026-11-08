package fr.spotifeur.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import fr.spotifeur.data.preferences.AppSettings
import fr.spotifeur.data.preferences.SettingsStore
import java.util.Locale

/** The app language: handled by the system on Android 13+, by a wrapped context before that. */
object AppLocales {
    fun apply(context: Context, language: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == AppSettings.LANGUAGE_SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language)
        } else (context as? Activity)?.recreate()
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = SettingsStore.get(base).settings.value.language
        if (language == AppSettings.LANGUAGE_SYSTEM) return base
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return base.createConfigurationContext(configuration)
    }
}
