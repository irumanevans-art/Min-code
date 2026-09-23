package dev.min.code.privileged

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * App 进程里接住 shell 进程交过来的 [IPrivilegedService] Binder。
 *
 * ## 为什么是 ContentProvider
 *
 * shell 进程和 App 进程之间最省事的跨 uid 通道：shell 对 `content://authority` 做
 * [call]，把 Binder 塞进 extras。Socket / 文件描述符都要额外握手；Provider 是现成的。
 *
 * exported=true（见 [call] 内注释与 AndroidManifest）：壳进程 uid 2000 和 App 不是同一个
 * uid，不 exported 就收不到交接。任何 App 都能对这个 Provider 发 [call]，鉴权靠
 * [android.os.Binder.getCallingUid] 只收 shell 与自身。
 */
class PrivilegedBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        instance = this
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_HANDOVER) return null
        // 壳进程是 uid 2000，和 App 不是同一个 uid——Provider 必须 exported，
        // 用调用方 uid 鉴权：只收 shell，拒掉任何普通 App 冒充交接
        val callingUid = android.os.Binder.getCallingUid()
        if (callingUid != 2000 && callingUid != android.os.Process.myUid()) {
            Log.w(TAG, "reject handover from uid=$callingUid")
            return null
        }
        val binder = extras?.getBinder(EXTRA_BINDER)
            ?: run {
                Log.w(TAG, "handover without binder")
                return null
            }
        Log.i(TAG, "received privileged binder from uid=$callingUid")
        binderRef.set(binder)
        listeners.forEach { runCatching { it(binder) } }
        return Bundle()
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "MinPrivBridge"
        const val METHOD_HANDOVER = "handover"
        const val EXTRA_BINDER = "binder"

        @Volatile
        private var instance: PrivilegedBridgeProvider? = null

        private val binderRef = AtomicReference<IBinder?>(null)
        private val listeners = CopyOnWriteArrayList<(IBinder) -> Unit>()

        fun currentBinder(): IBinder? = binderRef.get()

        fun addListener(listener: (IBinder) -> Unit) {
            listeners += listener
            binderRef.get()?.let { runCatching { listener(it) } }
        }

        fun removeListener(listener: (IBinder) -> Unit) {
            listeners -= listener
        }

        fun clear() {
            binderRef.set(null)
        }

        fun authority(context: android.content.Context): String =
            context.packageName + ".privileged"
    }
}
