package dev.min.code.core.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 把「设置里选的语言」真正落到进程上。
 *
 * **不走 `AppCompatDelegate.setApplicationLocales`**。那个方法要从 AppCompat 自己的静态
 * app context 里取 `LocaleManager`，而那个 context 只在 `AppCompatActivity` /
 * `AppCompatDelegate` 创建时才被赋值。Min 是纯 Compose + [android.app.Activity]，全项目
 * 一个 AppCompat 的 Activity 都没有 —— 于是那行调用**静默什么也不做，也不报错**：设置里
 * 点了 English，格子变蓝、值也存下来了，界面纹丝不动。
 * 实测佐证：`adb shell cmd locale get-app-locales dev.min.code` 一直返回空；手工写一个
 * `--locales en` 进去，界面立刻全变英文 —— 资源和翻译都是好的，断的就是这一道传话。
 *
 * 所以分两条路：
 * - **API 33+**：直接调系统 [LocaleManager]。持久化、Activity 重建、以及「系统设置 → 应用 →
 *   Min → 语言」那个入口都归系统管（那个入口要 manifest 里的 `android:localeConfig` 才会出现，
 *   见 `res/xml/locales_config.xml`）。
 * - **API 26..32**：系统根本没有「按应用设置语言」这回事，只能自己来 ——
 *   [MainActivity][dev.min.code.MainActivity] 在 `attachBaseContext` 里按 [LocalePrefs]
 *   存的值 [wrap] 一层 Configuration，语言改了就 `recreate()`。
 *
 * 两条路都先写一份 [LocalePrefs] 镜像：`attachBaseContext` 跑在 DataStore 能异步读出来之前，
 * 那一刻只有同步的 SharedPreferences 拿得到值。
 */
object AppLocale {

    /** 设置变更时调用。33+ 交给系统；更低版本只落镜像，真正生效靠 Activity 重建后的 [wrap] */
    fun apply(context: Context, language: AppLanguage) {
        LocalePrefs.write(context, language)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = localeOf(language)
            ?.let { LocaleList(it) }
            ?: LocaleList.getEmptyLocaleList()
    }

    /**
     * API 26..32 专用：给 Activity 的 base context 套一层改过 locale 的 Configuration。
     * 33+ 原样返回 —— 那边系统已经把 Configuration 改好了，再包一层只会和系统打架。
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = localeOf(LocalePrefs.read(base)) ?: return base
        val locales = LocaleList(locale)
        // 静态默认值也要改：格式化日期 / 数字的地方拿的是 Locale.getDefault()，
        // 只改 Configuration 的话它们还说着旧语言
        Locale.setDefault(locale)
        LocaleList.setDefault(locales)
        val config = Configuration(base.resources.configuration).apply { setLocales(locales) }
        return base.createConfigurationContext(config)
    }

    /**
     * 系统侧当前记着的是哪一种。33+ 才有；用来和 App 自己存的值对齐 ——
     * 用户可能是从「系统设置 → 应用 → Min → 语言」改的，那边才是更权威的一次操作。
     * 返回 null = 这个版本没有系统级 per-app locale，或者系统给了个我们不认识的语言。
     */
    fun readSystem(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = context.getSystemService(LocaleManager::class.java) ?: return null
        val locales = manager.applicationLocales
        if (locales.isEmpty) return AppLanguage.SYSTEM
        return fromTag(locales[0].toLanguageTag())
    }

    /** [AppLanguage] → BCP 47 标签。[AppLanguage.SYSTEM] 没有标签（= 跟随系统，清空设置） */
    fun tagOf(language: AppLanguage): String? = when (language) {
        AppLanguage.SYSTEM -> null
        AppLanguage.ZH -> "zh-CN"
        AppLanguage.EN -> "en"
    }

    /**
     * BCP 47 标签 → [AppLanguage]。只看主语言子标签：系统可能回 `zh-Hans-CN`、`en-US`，
     * 都该落到我们那两档上；认不出来的返回 null，交给调用方决定怎么办（而不是悄悄当成跟随系统）。
     */
    fun fromTag(tag: String): AppLanguage? = when (tag.substringBefore('-').lowercase()) {
        "zh" -> AppLanguage.ZH
        "en" -> AppLanguage.EN
        else -> null
    }

    private fun localeOf(language: AppLanguage): Locale? =
        tagOf(language)?.let { Locale.forLanguageTag(it) }
}

/**
 * 语言选择的同步镜像。
 *
 * 真身在 DataStore（[SettingsStore]），但 `attachBaseContext` 比任何协程都早，
 * 在那里只能同步读 —— 所以每次 [AppLocale.apply] 都往 SharedPreferences 抄一份。
 * 只有 API 26..32 会读它；33+ 留着是为了降级安装后仍有值可用。
 */
internal object LocalePrefs {
    private const val FILE = "locale"
    private const val KEY = "app_language"

    fun read(context: Context): AppLanguage {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return AppLanguage.SYSTEM
        return runCatching { AppLanguage.valueOf(raw) }.getOrDefault(AppLanguage.SYSTEM)
    }

    fun write(context: Context, language: AppLanguage) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.name)
            .apply()
    }
}
