package dev.min.code.core.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内的一个收件格：`minc://` / `ccswitch://` 从 Activity 进来，供应商页开出来时取走。
 *
 * 为什么要这个中转：链接到达的是 Activity，而处理它的是供应商页的 ViewModel，
 * 两者之间隔着一次导航——Activity 直接去拿那个 VM 会拿到一个还没被任何界面持有的实例，
 * 它处理完就被回收，用户什么都看不到。所以链接先放这儿，页面开出来自己来取。
 *
 * 只留最后一条：连着点两条链接时，第二条覆盖第一条——用户看得见的也只有最后打开的那一个确认框。
 */
object ProviderLinkInbox {
    private val _link = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = _link.asStateFlow()

    fun offer(link: String) {
        _link.value = link
    }

    /** 取走之后就清掉：重进一次供应商页不该再弹一次同样的确认框 */
    fun clear() {
        _link.value = null
    }
}
