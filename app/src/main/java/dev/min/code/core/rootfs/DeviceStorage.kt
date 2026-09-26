package dev.min.code.core.rootfs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.min.code.core.settings.SettingsStore
import me.rerere.workspace.WorkspaceBindMount
import java.io.File

/**
 * 把整台设备的共享存储挂进 Rootfs。
 *
 * ## 为什么这样就够了
 *
 * proot 里的进程是 App 自己的子进程，**继承同一个 UID**。所以 App 拿得到的文件权限
 * CLI 天然就有，不需要任何桥接——只要把 `/storage/emulated/0` 用 `-b` 挂进去，
 * `claude` 里一个普通的 `cat /sdcard/Download/x.pdf` 就能读到。
 *
 * ## 拿不到的部分
 *
 * - `/sdcard/Android/data` 与 `/Android/obb`：Android 11+ 对**所有** App 封死，
 *   有「所有文件访问权限」也一样。界面上要先说清楚，否则用户只会以为坏了。
 * - 别的 App 在 `/data/data` 下的私有目录：要 root。
 *
 * ## 为什么防线不在挂载这一侧
 *
 * proot 的 `-b` 没有只读选项，挂进去就是可写的。所以这里做不出「只读地看看相册」
 * 这一档——开关一开就是把整个共享存储交出去，风险要在审批层兜（`/workspace`
 * 之外的写入单独确认）。开关文案必须把这件事直说。
 */

/** 共享存储在 Rootfs 里的挂载点。Linux 侧的老习惯，CLI 和人都认得 */
const val ROOTFS_SDCARD_DIR = "/sdcard"

/**
 * 系统是否已经放行整机文件访问。
 *
 * API 30 起这是「所有文件访问权限」这一档特殊权限，只能由用户在系统设置里给，
 * 请求不了也弹不出对话框；30 以下退回旧的运行时读写权限。
 */
fun hasDeviceStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

/**
 * 跳到系统里那一页去授权。
 *
 * 优先带包名直达本应用那一屏；某些 ROM 上这个 action 不认，退回总列表页让用户自己找，
 * 两个都不认时返回 null——调用方据此提示「去设置 → 应用 → 特殊权限」，
 * 而不是抛一个 ActivityNotFoundException。
 */
fun deviceStorageSettingsIntent(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val direct = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    )
    if (direct.resolveActivity(context.packageManager) != null) return direct
    val list = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    return list.takeIf { it.resolveActivity(context.packageManager) != null }
}

/**
 * 这一次起 proot 要不要挂共享存储。
 *
 * 纯函数，三个条件缺一不可：
 * 1. [enabled]  —— 用户在设置里开了这一档
 * 2. [granted]  —— 系统层面真的给了权限（用户可能开了开关却没去授权，或事后在系统里收回）
 * 3. [storageRoot] 存在 —— 外置存储没挂载时（极少见，但模拟器上有）挂一个不存在的路径，
 *    `ProotShellRunner` 会跳过，这里提前返回 null 让状态一致
 *
 * 第 2 条每次都要现查而不是记在 DataStore 里：用户随时可能去系统设置里收回，
 * 那之后我们不该再把 `/sdcard` 摆在 rootfs 里当作它还能用。
 */
/**
 * 这一次起 proot 实际要挂进去的全部 bind mount。
 *
 * 四条直构 [WorkspaceShellContext] 的路径（会话本体、托管 Bash、终端页签、Codex app-server）
 * 都该用它，而不是自己留空——留空就是开关开了、`!` 命令看得到 `/sdcard`、模型用 Bash
 * 工具却看不到。每次现算，不把结果快照进启动时就算死的值：开关和系统权限都可能在两次
 * 启动之间被改掉（见 [deviceStorageBindMount] 的三条条件）。
 *
 * 现在只有共享存储这一项；以后再加挂载，加在这里，四条路径一起跟上。
 */
fun currentBindMounts(context: Context, settings: SettingsStore): List<WorkspaceBindMount> =
    listOfNotNull(
        deviceStorageBindMount(
            enabled = settings.snapshot?.shareDeviceStorage == true,
            granted = hasDeviceStorageAccess(context),
        )
    )

fun deviceStorageBindMount(
    enabled: Boolean,
    granted: Boolean,
    storageRoot: File = Environment.getExternalStorageDirectory(),
): WorkspaceBindMount? {
    if (!enabled || !granted) return null
    if (!storageRoot.isDirectory) return null
    return WorkspaceBindMount(source = storageRoot, target = ROOTFS_SDCARD_DIR)
}
