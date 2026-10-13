package dev.min.code.core.rootfs

/**
 * proot 里的默认工作目录：工作区的 `files/` 被 bind-mount 到这里。
 * Claude 和 Codex 两个会话、`CwdPath` / `SessionJsonl` 这些纯函数都认这一个，
 * 不要再在引擎各自的类里各声明一份（两份字面量改一边就悄悄对不上）。
 */
const val DEFAULT_CWD = "/workspace"
