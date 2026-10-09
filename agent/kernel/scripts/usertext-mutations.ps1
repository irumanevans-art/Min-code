param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
# 「用户文字不进工具结果」的变异验证：每个变异改一处源码，对应测试必须变红（先编译通过、再断言失败），无论结果如何都还原源码。
# 锚点都是单行文本（工作区文件可能是 CRLF，多行锚点会对不上）。一个变异可以跨模块跑几组测试（Runs），每组的测试都要红。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$ask = 'agent/tools/src/main/kotlin/dev/min/agent/tools/AskUserQuestionTool.kt'
$exec = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/ToolExecutor.kt'
$view = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel/ModelView.kt'
$proj = 'agent/llm/src/main/kotlin/dev/min/agent/llm/Projection.kt'
$K = 'dev.min.agent.kernel'
$cases = @(
    # 1 问答的答案放回 tool_result 文本
    @{ Name='answer-back-in-tool-result'
       Edits=@(@{ File=$ask; Before='okResult(ANSWERED_MESSAGE).copy(userText = formatAnswers(input.questions, outcome.answers))'; After='okResult(formatAnswers(input.questions, outcome.answers))' })
       Runs=@(
           @{ Module='tools'; Class='dev.min.agent.tools.AskUserQuestionToolTest'; Tests=@('answers come back one line per question', 'a picked option alone and a free-text answer alone both stay out of the result text') },
           @{ Module='kernel'; Class="$K.QuestionFlowTest"; Tests=@('the model hears the answer the user gave', 'an answer that does not fit the question is refused without writing anything') }) },
    # 2 拒绝说明放回 tool_result 文本
    @{ Name='denial-note-back-in-tool-result'
       Edits=@(
           @{ File=$exec; Before='"用户拒绝了这次调用。" + if (note == null) "不要原样重试，换个做法或先问清楚。" else "用户留了话，见下面用户的话；不要原样重试。",'; After='"用户拒绝了这次调用。" + (note ?: "不要原样重试，换个做法或先问清楚。"),' },
           @{ File=$exec; Before='userText = note?.let { "用户拒绝了你刚才的 $tool 调用，并留言：$it" },'; After='userText = null,' })
       Runs=@(@{ Module='kernel'; Class="$K.SessionRuntimeTest"; Tests=@('a denied write never runs and the model hears the note') }) },
    # 3 拒绝说明丢弃
    @{ Name='denial-note-dropped'
       Edits=@(@{ File=$exec; Before='userText = note?.let { "用户拒绝了你刚才的 $tool 调用，并留言：$it" },'; After='userText = null,' })
       Runs=@(
           @{ Module='kernel'; Class="$K.SessionRuntimeTest"; Tests=@('a denied write never runs and the model hears the note') },
           @{ Module='kernel'; Class="$K.RecoveryTest"; Tests=@('recovered results tell the model exactly what happened') }) },
    # 4 模型视图不再送出用户的话
    @{ Name='projection-drops-user-text'
       Edits=@(@{ File=$view; Before='} + results.mapNotNull { it.userPart() } + reminders(entry.messageId)'; After='} + reminders(entry.messageId)' })
       Runs=@(@{ Module='kernel'; Class="$K.ModelViewUserTextTest"; Tests=@('user text follows every tool_result as its own block and stays out of the result text', 'masking a result never hides what the user said') }) },
    # 5 去掉 OpenAI 的文本块分隔
    @{ Name='openai-no-separator'
       Edits=@(@{ File=$proj; Before='internal const val TEXT_BLOCK_SEPARATOR = "\n\n"'; After='internal const val TEXT_BLOCK_SEPARATOR = ""' })
       Runs=@(
           @{ Module='llm'; Class='dev.min.agent.llm.openai.OpenAIChatRequestTest'; Tests=@('text blocks after tool results stay apart with a blank line between them') },
           @{ Module='llm'; Class='dev.min.agent.llm.openai.OpenAIResponsesTest'; Tests=@('user words and reminders after tool results are separate from the output and from each other') }) },
    # 6 用户的话插到 hook 提醒之后（顺序错）
    @{ Name='user-text-after-reminders'
       Edits=@(@{ File=$view; Before='} + results.mapNotNull { it.userPart() } + reminders(entry.messageId) + memoryReminders(entry.messageId),'; After='} + reminders(entry.messageId) + memoryReminders(entry.messageId) + results.mapNotNull { it.userPart() },' })
       Runs=@(@{ Module='kernel'; Class="$K.ModelViewUserTextTest"; Tests=@('hook context and memory reminders come after the user text, each in its own block') }) }
)
$summary = @()
Push-Location $root
try {
    foreach ($case in $cases) {
        $originals = @{}
        foreach ($edit in $case.Edits) {
            $file = Join-Path $root $edit.File
            if (-not $originals.ContainsKey($file)) { $originals[$file] = [System.IO.File]::ReadAllBytes($file) }
        }
        $log = Join-Path $evidence ($case.Name + '.log')
        try {
            foreach ($edit in $case.Edits) {
                $file = Join-Path $root $edit.File
                $text = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($file))
                $count = ([regex]::Matches($text, [regex]::Escape($edit.Before))).Count
                if ($count -ne 1) { throw "Mutation anchor must appear exactly once ($count): $($case.Name): $($edit.Before)" }
                [System.IO.File]::WriteAllText($file, $text.Replace($edit.Before, $edit.After), [System.Text.UTF8Encoding]::new($false))
            }
            $gradleArgs = @()
            # 同一个模块的 test 任务只能出现一次（Gradle 会把重复的任务合并，后面那组 --tests 就丢了），所以按模块归并过滤器
            foreach ($module in ($case.Runs | ForEach-Object { $_.Module } | Select-Object -Unique)) {
                $gradleArgs += (':agent:' + $module + ':test')
                foreach ($run in ($case.Runs | Where-Object { $_.Module -eq $module })) {
                    foreach ($t in $run.Tests) { $gradleArgs += '--tests'; $gradleArgs += ($run.Class + '.' + $t) }
                }
            }
            $gradleArgs += '--continue'; $gradleArgs += '--console=plain'
            # 上一个变异留下的结果文件不能被误当成这一次的红：先删掉，没跑到的测试类就没有文件、算没红
            foreach ($run in $case.Runs) {
                Remove-Item -LiteralPath (Join-Path $root ('agent/' + $run.Module + '/build/test-results/test/TEST-' + $run.Class + '.xml')) -ErrorAction SilentlyContinue
            }
            # Windows PowerShell 5.1：gradle 失败时往 stderr 写的内容在 Stop 模式下会变成终止错误，这里临时放宽，只看退出码
            $savedPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            & .\gradlew.bat @gradleArgs *> $log
            $gradleExit = $LASTEXITCODE
            $ErrorActionPreference = $savedPreference
            $compiled = -not (Select-String -LiteralPath $log -Pattern 'compileKotlin FAILED|compileTestKotlin FAILED|Compilation error|^e: ' -Quiet)
            $red = 0; $total = 0
            foreach ($run in $case.Runs) {
                $xmlPath = Join-Path $root ('agent/' + $run.Module + '/build/test-results/test/TEST-' + $run.Class + '.xml')
                $total += $run.Tests.Count
                if (Test-Path -LiteralPath $xmlPath) {
                    [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8
                    foreach ($t in $run.Tests) {
                        if (@($xml.testsuite.testcase | Where-Object { $_.name -eq $t -and ($_.failure -or $_.error) }).Count -gt 0) { $red++ }
                    }
                    Copy-Item -LiteralPath $xmlPath -Destination (Join-Path $evidence ($case.Name + '.' + $run.Class + '.xml'))
                }
            }
            $killed = $gradleExit -ne 0 -and $compiled -and $red -eq $total
            $summary += "$($case.Name): GRADLE_EXIT=$gradleExit COMPILED=$compiled RED=$red/$total KILLED=$killed"
            Write-Output $summary[-1]
            if (-not $killed) { throw "Mutation survived or failed to compile: $($case.Name)" }
        } finally {
            foreach ($file in $originals.Keys) { [System.IO.File]::WriteAllBytes($file, $originals[$file]) }
        }
    }
} finally {
    Pop-Location
    [System.IO.File]::WriteAllLines((Join-Path $evidence 'summary.txt'), $summary, [System.Text.UTF8Encoding]::new($false))
}
exit 0
