package dev.min.agent.cli

/** 外部服务器的文字不能通过终端控制序列覆盖域名或确认提示。 */
internal fun terminalText(text: String): String = text
    .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
    .replace(Regex("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)"), "")
    .filterNot { it.isISOControl() }
