package dev.min.code.privileged

/**
 * scrcpy 同款 VirtualDisplay flag 组合（framework 里是 @hide，数值按 AOSP 抄）。
 *
 * 抽成纯函数是为了单测：真机连不上时至少能钉住「API 33 必须带 TRUSTED」这条。
 */
internal object VirtualDisplayFlags {
    const val PUBLIC = 1 shl 0
    const val PRESENTATION = 1 shl 1
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val SUPPORTS_TOUCH = 1 shl 6
    const val ROTATES_WITH_CONTENT = 1 shl 7
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14
    const val DEVICE_DISPLAY_GROUP = 1 shl 15

    fun forSdk(sdk: Int): Int {
        var flags = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or
            ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL or SHOULD_SHOW_SYSTEM_DECORATIONS
        if (sdk >= 33) {
            flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        }
        if (sdk >= 34) {
            flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP
        }
        return flags
    }
}
