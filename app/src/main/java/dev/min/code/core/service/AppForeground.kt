package dev.min.code.core.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App 在前台还是后台，以及「离开了多久」。进程级（[ProcessLifecycleOwner]：任何一个界面可见就算前台），
 * 给两件事用：回到前台时按离开的时长决定要不要自动生成会话回顾；进后台时把没人用的 MCP 连接直接关掉。
 *
 * 不复用 [SessionSupervisor] 那份 `isForeground`：它是通知用的私有状态，这里要的是「离开了多久」，
 * 而且得是纯逻辑能测的（[AwayTracker]）。
 */
class AppForeground(appScope: CoroutineScope) {
    private val tracker = AwayTracker(System::currentTimeMillis)
    private val _foreground = MutableStateFlow(true)

    /** 前台为 true。初值 true：进程起来时多半是用户点开的；ON_STOP 才会翻成 false */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _returns = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** 每次从后台回到前台发一个值：离开了多少毫秒（冷启动那一次没有「离开」，不发） */
    val returns: SharedFlow<Long> = _returns.asSharedFlow()

    init {
        // addObserver 要在主线程，appScope 是 Main
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            _foreground.value = true
                            tracker.onForeground()?.let { _returns.tryEmit(it) }
                        }
                        Lifecycle.Event.ON_STOP -> {
                            tracker.onBackground()
                            _foreground.value = false
                        }
                        else -> Unit
                    }
                },
            )
        }
    }
}

/** 记下进后台的时刻，回前台时算离开了多久。纯逻辑：时钟由调用方给 */
class AwayTracker(private val clock: () -> Long) {
    private var leftAt: Long? = null

    fun onBackground() {
        leftAt = clock()
    }

    /** 回到前台：返回离开了多久；没有「离开」的记录（冷启动、重复回调）返回 null，并清掉记录 */
    fun onForeground(): Long? = leftAt?.let { clock() - it }.also { leftAt = null }
}

/**
 * 离开多久算「该给回顾了」。CC 的 away summary 在人离开几分钟后回来时出现；手机上切走一下看消息很常见，
 * 太短就弹回顾是打扰，这里取 5 分钟：够你去回一条消息再回来仍然不弹，真正离开了一阵才弹。
 */
const val AWAY_RECAP_AFTER_MS = 5 * 60_000L
