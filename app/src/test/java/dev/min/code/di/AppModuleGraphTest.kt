package dev.min.code.di

import androidx.lifecycle.ViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import java.io.File
import kotlin.reflect.KClass

/**
 * Koin 模块图的静态检查。DI 以前只能靠冷启动看 logcat（CLAUDE.md「动了 DI 必须冷启动」），
 * 漏一行 `viewModelOf(...)` 要到页面第一次组合才崩（`NoDefinitionFoundException`），单测完全看不见。
 *
 * 只查「每个 ViewModel 都有绑定」：页面用 `koinViewModel()` 取，没有任何别的定义依赖它，
 * 所以得从类路径反过来找。
 *
 * 没有上 Koin 的 `verify()`：模块里几乎都是 lambda 定义（`single { X(get(), get<具体类>()) }`），
 * verify 只按构造器反射查参数类型，查不到 lambda 里的 `get<具体类>()`，对「参数是接口、
 * 定义只登记了具体类」这种被注释反复提醒的坑反而全是误报（`CodexProcessLauncher` 之类的函数式参数逐个要加白名单），
 * 白名单一长就没人看，不如不要。
 */
class AppModuleGraphTest {
    @OptIn(KoinInternalApi::class)
    @Test
    fun everyViewModelIsBound() {
        val bound: Set<KClass<*>> = appModule.mappings.values.map { it.beanDefinition.primaryType }.toSet()
        val missing = viewModelClasses().filter { it.kotlin !in bound }
        assertTrue(
            "这些 ViewModel 没有在 appModule 里绑定（页面一取就会 NoDefinitionFoundException）：" +
                missing.joinToString { it.name },
            missing.isEmpty(),
        )
    }

    /** 扫编译产物里 `dev.min.code.ui` 下的类，挑出 ViewModel 的具体子类（不扫测试类、不扫抽象类） */
    private fun viewModelClasses(): List<Class<*>> {
        val location = File(Class.forName("dev.min.code.di.AppModuleKt").protectionDomain.codeSource.location.toURI())
        // Gradle 把 app 的类打成一个 classes.jar 给单测用；用 IDE 跑时则是目录。两种都认
        val names: List<String> = if (location.isFile) {
            java.util.jar.JarFile(location).use { jar ->
                jar.entries().asSequence().map { it.name }.toList()
            }
        } else {
            location.walkTopDown().filter { it.isFile }.map { it.relativeTo(location).path.replace(File.separatorChar, '/') }.toList()
        }
        return names
            .filter { it.startsWith("dev/min/code/ui/") && it.endsWith(".class") && '$' !in it }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .mapNotNull { name -> runCatching { Class.forName(name, false, javaClass.classLoader) }.getOrNull() }
            .filter { ViewModel::class.java.isAssignableFrom(it) && !java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            .also { check(it.size >= 8) { "只扫到 ${it.size} 个 ViewModel，扫描逻辑多半坏了：$it" } }
    }
}
