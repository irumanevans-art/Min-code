package dev.min.code.ui.session

/**
 * 纯函数单测用的字头包：把 values-zh 的现值钉在这里，断言才能对中文文案逐字较真。
 * 资源变了这里不会自动同步 —— 动 values-zh 里这些模板时，来这里对一遍。
 */
internal fun testTranscriptLabels(): TranscriptLabels = TranscriptLabels(
    user = "你",
    thinking = "思考",
    toolRunning = "运行中",
    toolDone = "完成",
    toolError = "出错",
    toolAwaiting = "等你批准",
    readRange = "%1\$d–%2\$d 行",
    readFrom = "从 %1\$d 行",
    readFirst = "前 %1\$d 行",
    replaceAll = "全部替换",
    nLines = "%1\$d 行",
    nEdits = "%1\$d 处",
    todoDone = "%1\$d/%2\$d 已完成",
    askUser = "向用户提问",
    planMode = "计划模式",
    nSteps = "%1\$d 步",
    diffElidedAbove = "@@ 上方 %1\$d 行未改动 @@",
    diffElidedBelow = "@@ 下方 %1\$d 行未改动 @@",
    diffTruncated = "…（已截断，共 %1\$d 字符）",
    deviceTap = "点击 %1\$s",
    deviceInput = "在 %1\$s 里输入",
    deviceSwipe = "滑动 %1\$s",
    deviceOpenApp = "打开 %1\$s",
    deviceBack = "按返回键",
    deviceUiTree = "读取屏幕",
)
