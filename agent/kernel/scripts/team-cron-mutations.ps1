param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
# M5 团队与定时调度的变异验证（含复核修复）：每个变异改一处源码，对应测试必须变红（先编译通过、再断言失败），无论结果如何都还原源码。
# 锚点都是单行文本（工作区文件可能是 CRLF，多行锚点会对不上）。
# 每个用例可以指定 Module（gradle 路径，默认 :agent:kernel）、Pkg（测试类包名）、Task（默认 test；app 是 testDebugUnitTest）。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$K = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel'
$T = 'agent/tools/src/main/kotlin/dev/min/agent/tools'
$C = 'agent/cli/src/main/kotlin/dev/min/agent/cli'
$A = 'app/src/main/java/dev/min/code/core/engine/min'
$cliPkg = 'dev.min.agent.cli'
$cases = @(
    # ---- 团队 ----
    @{ Name='no-concurrency-cap'
       Edits=@(@{ File="$K/TeamCoordinator.kt"; Before='private fun hasRoom(): Boolean = active.size < maxConcurrent()'; After='private fun hasRoom(): Boolean = true' })
       Class='TeamSessionTest'
       Tests=@('at most teamMaxConcurrent members run at once and the fourth waits for a slot') },
    @{ Name='memory-mailbox'
       Edits=@(
           @{ File="$K/TeamCoordinator.kt"; Before='runtime.receive('; After='MEMORY_MAIL.getOrPut(sessionId) { java.util.concurrent.CopyOnWriteArrayList() }.add(text); if (false) runtime.receive(' },
           @{ File="$K/TeamCoordinator.kt"; Before='internal class TeamCoordinator('; After="private val MEMORY_MAIL = java.util.concurrent.ConcurrentHashMap<SessionId, java.util.concurrent.CopyOnWriteArrayList<String>>()`n`ninternal class TeamCoordinator(" })
       Class='TeamSessionTest'
       Tests=@('a team message lands in the recipient log with the inbox entry in the same commit', 'mail survives a restart and is read exactly once') },
    @{ Name='member-edits-others-tasks'
       Edits=@(
           @{ File="$K/Teams.kt"; Before='if (ownerAfter != by) {'; After='if (false) {' },
           @{ File="$K/Teams.kt"; Before='if (task.owner != null && task.owner != by) return'; After='if (false) return' })
       Class='TeamSessionTest'
       Tests=@('members change only their own tasks and the leader changes any') },
    @{ Name='member-edits-others-tasks-fold'
       Edits=@(
           @{ File="$K/Teams.kt"; Before='if (ownerAfter != by) {'; After='if (false) {' },
           @{ File="$K/Teams.kt"; Before='if (task.owner != null && task.owner != by) return'; After='if (false) return' })
       Class='TeamFoldTest'
       Tests=@('a member cannot change or finish a task someone else claimed or nobody claimed', 'a member cannot steal assign or release a claim somebody else holds') },
    @{ Name='task-tools-not-routed-to-team'
       Module=':agent:tools'; Pkg='dev.min.agent.tools'
       Edits=@(@{ File="$T/TaskTools.kt"; Before='if (ctx.tasks.team != null) {'; After='if (false) {' })
       Class='TeamTaskRoutingTest'
       Tests=@('in a team TaskCreate and TaskUpdate go to the team port instead of declaring personal effects') },
    @{ Name='peer-turn-wakes-leader'
       Edits=@(@{ File="$K/SessionHub.kt"; Before='&& !peerTriggered'; After='' })
       Class='TeamSessionTest'
       Tests=@('a turn started by a peer message reports to the leader inbox without waking it, a leader message still does') },
    @{ Name='team-cap-above-eight-allowed'
       Edits=@(@{ File="$K/TeamCoordinator.kt"; Before='requested > MAX_CONCURRENT -> Setting.Accepted'; After='false -> Setting.Accepted' })
       Class='TeamSessionTest'
       Tests=@('eight is a hard cap - a bigger setting is clamped and there is no confirmation path') },
    @{ Name='former-member-bypasses-slots'
       Edits=@(@{ File="$K/SessionHub.kt"; Before='if (formerMember) null else TurnTrigger.User'; After='TurnTrigger.User' })
       Class='TeamSessionTest'
       Tests=@('after a disband, messaging old members still goes through the concurrency slots') },
    @{ Name='stalled-shown-as-queued'
       Edits=@(@{ File="$K/TeamCoordinator.kt"; Before='if (m.session in waiting) TeamMemberStatus.Phase.Queued else TeamMemberStatus.Phase.Stalled'; After='TeamMemberStatus.Phase.Queued' })
       Class='TeamSessionTest'
       Tests=@('a queued member does not start by itself after a restart') },
    @{ Name='teammate-idle-never-fires'
       Module=':agent:cli'; Pkg=$cliPkg
       Edits=@(@{ File="$K/TurnRunner.kt"; Before='&& !teammateIdleContinues(turnId)'; After='' })
       Class='HooksDecisionKernelTest'
       Tests=@('TeammateIdle fires when a member is about to idle with nothing to claim, and exit 2 keeps it working (sample 94 and 95)') },
    # ---- 分支 ----
    @{ Name='branch-keeps-team-events'
       Edits=@(@{ File="$K/SessionHub.kt"; Before='.filterNot { it is dev.min.agent.spi.TeamEvent }'; After='.filterNot { false }' })
       Class='BranchSessionTest'
       Tests=@('a session that built a team can be branched and the branch opens - team events stay with the original') },
    @{ Name='create-writes-before-folding'
       Edits=@(@{ File="$K/SessionRuntime.kt"; Before='fold(header, events.mapIndexed { i, e -> EventRecord(i + 1L, deps.clock.nowMs(), e) })'; After='Unit' })
       Class='BranchSessionTest'
       Tests=@('creating a session whose initial events do not fold leaves no orphan log behind') },
    # ---- 定时：基本盘 ----
    @{ Name='cron-fires-while-busy'
       Edits=@(
           @{ File="$K/CronEngine.kt"; Before='if (!rt.isQuiescent()) return'; After='if (false) return' },
           @{ File="$K/CronEngine.kt"; Before='precondition = { s -> rt.isQuiescent() && s.cronTask'; After='precondition = { s -> s.cronTask' })
       Class='CronSessionTest'
       Tests=@('a task that comes due while a turn is running waits and fires as soon as the turn ends', 'a pending input in the inbox also keeps the session busy') },
    @{ Name='cron-marked-as-user'
       Edits=@(@{ File="$K/CronEngine.kt"; Before='InputOrigin.Cron(task.id),'; After='InputOrigin.User,' })
       Class='CronSessionTest'
       Tests=@('a due task fires once the session is idle and the fired input is marked as cron, not as the user') },
    @{ Name='cron-catches-up-after-reopen'
       Edits=@(@{ File="$K/CronEngine.kt"; Before='maxOf(entry.floorMs, entry.skippedAt[t.id] ?: 0L)'; After='0L' })
       Class='CronSessionTest'
       Tests=@('reopening restores unexpired tasks and does not catch up on missed fires') },
    @{ Name='cron-never-expires'
       Edits=@(@{ File="$K/CronEngine.kt"; Before='if (task.isPastExpiry(now)) expire('; After='if (false) expire(' })
       Class='CronSessionTest'
       Tests=@('a recurring task expires after seven days with a notice') },
    @{ Name='cronlist-ignores-reopen-floor'
       Edits=@(@{ File="$T/CronTools.kt"; Before='nextFireFloors[t.id] ?: 0'; After='0' })
       Class='CronSessionTest'
       Tests=@('CronList after a reopen shows the next fire counted from the reopen, not a time in the past') },
    # ---- 定时：花费口径 ----
    @{ Name='cron-default-cap-comes-back'
       Edits=@(@{ File="$K/CronEngine.kt"; Before='val limit = listOfNotNull(task.maxBudgetUsd, ceilingUsd()).minOrNull()'; After='val limit = task.maxBudgetUsd ?: 0.05' })
       Class='CronSessionTest'
       Tests=@('a task without a cap is never stopped for spending - same as Claude Code') },
    @{ Name='cron-cap-ignored'
       Edits=@(@{ File="$K/Crons.kt"; Before='spent >= limitUsd ->'; After='false ->' })
       Class='CronSessionTest'
       Tests=@('an overrun stops only that run - interrupted by budget, the task stays active and the user is told') },
    @{ Name='cron-counts-root-spend-only'
       Edits=@(@{ File="$K/ModelBilling.kt"; Before='run?.charge(charged?.amount, charged?.currency)'; After='if (rt.state.value.origin == null) run?.charge(charged?.amount, charged?.currency)' })
       Class='CronSessionTest'
       Tests=@('spend of a sub agent counts toward the run and stops the whole tree') },
    @{ Name='cron-first-overrun-ends-the-task'
       Edits=@(@{ File="$K/CronEngine.kt"; Before='internal const val CRON_PAUSE_AFTER_OVERRUNS = 2'; After='internal const val CRON_PAUSE_AFTER_OVERRUNS = 1' })
       Class='CronSessionTest'
       Tests=@('an overrun stops only that run - interrupted by budget, the task stays active and the user is told', 'two overruns in a row end the task, an overrun after a clean run starts the count over') },
    @{ Name='cron-unpriced-model-cancels-the-task'
       Edits=@(@{ File="$K/CronEngine.kt"; Before='skip(rt, entry, task, now, limit)'; After='expire(rt, task, expired(CronExpiry.NoPricing, null))' })
       Class='CronSessionTest'
       Tests=@('a capped task on a model without a USD price skips that run once with a note and stays active') },
    @{ Name='wakeup-chain-lives-forever'
       Edits=@(@{ File="$K/SessionEffects.kt"; Before='working.firingWakeup()?.expiresAtMs ?: (nowMs'; After='null ?: (nowMs' })
       Class='CronSessionTest'
       Tests=@('a wakeup chain shares one seven day lifetime from its first wakeup and then ends') },
    @{ Name='wakeup-delay-not-clamped'
       Module=':agent:tools'; Pkg='dev.min.agent.tools'
       Edits=@(@{ File="$T/CronTools.kt"; Before='val delay = asked.coerceIn(MIN_DELAY_SECONDS.toLong(), MAX_DELAY_SECONDS.toLong())'; After='val delay = asked' })
       Class='ScheduleWakeupToolTest'
       Tests=@('an out of range delay is clamped to a minute and an hour and the result says so - same as Claude Code') },
    # ---- 宿主 ----
    @{ Name='print-run-gets-team-and-cron-tools'
       Module=':agent:cli'; Pkg=$cliPkg
       Edits=@(@{ File="$C/DesktopApp.kt"; Before='(if (!interactive) emptyList()'; After='(if (false) emptyList()' })
       Class='DesktopWebTest'
       Tests=@('a one shot -p run gets no team or schedule tools because nothing outlives the process') },
    @{ Name='host-denial-note-sent-as-user-words'
       Module=':agent:cli'; Pkg=$cliPkg
       Edits=@(@{ File="$C/ConsoleUi.kt"; Before='fromHost = true)'; After='fromHost = false)' })
       Class='HeadlessCompatTest'
       Tests=@('the denial note a print run generates itself stays in the tool result and is not sent as a user message') },
    @{ Name='interactive-loop-skips-prompt-expansion'
       Module=':agent:cli'; Pkg=$cliPkg
       Edits=@(@{ File="$C/Main.kt"; Before='promptExpansionBlock(runtime, input, action)?.let'; After='(null as String?)?.let' })
       Class='HooksExpansionHeadlessTest'
       Tests=@('in the interactive loop a blocking UserPromptExpansion hook stops the slash command and shows the reason (sample 65)') },
    @{ Name='setmodel-reports-success-when-blocked'
       Module=':app'; Task='testDebugUnitTest'; Pkg='dev.min.code.core.engine.min'; ResultsDir='app/build/test-results/testDebugUnitTest'
       Edits=@(@{ File="$A/MinCommands.kt"; Before='if (refusal == null) CommandResult.Accepted else CommandResult.Rejected(refusal)'; After='CommandResult.Accepted' })
       Class='ModelSwitchResultTest'
       Tests=@('no refusal is accepted and a refusal is rejected with the hook reason') }
)
$summary = @()
Push-Location $root
try {
    foreach ($case in $cases) {
        $module = if ($case.Module) { $case.Module } else { ':agent:kernel' }
        $task = if ($case.Task) { $case.Task } else { 'test' }
        $pkg = if ($case.Pkg) { $case.Pkg } else { 'dev.min.agent.kernel' }
        $resultsDir = if ($case.ResultsDir) { $case.ResultsDir } else { ('agent/' + $module.Substring(7) + '/build/test-results/test') }
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
            $filters = @()
            foreach ($t in $case.Tests) { $filters += '--tests'; $filters += ($pkg + '.' + $case.Class + '.' + $t) }
            # Windows PowerShell 5.1：gradle 失败时往 stderr 写的内容在 Stop 模式下会变成终止错误，这里临时放宽，只看退出码
            $savedPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $xmlPath = Join-Path $root ($resultsDir + '/TEST-' + $pkg + '.' + $case.Class + '.xml')
            if (Test-Path -LiteralPath $xmlPath) { Remove-Item -LiteralPath $xmlPath -Force } # 跑前清掉旧结果，免得读到上一轮的
            & .\gradlew.bat ($module + ':' + $task) @filters '--console=plain' *> $log
            $gradleExit = $LASTEXITCODE
            $ErrorActionPreference = $savedPreference
            $compiled = -not (Select-String -LiteralPath $log -Pattern 'compileKotlin FAILED|compileTestKotlin FAILED|compileDebugKotlin FAILED|Compilation error|^e: ' -Quiet)
            $failedNames = @()
            if (Test-Path -LiteralPath $xmlPath) {
                [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8
                foreach ($t in $case.Tests) {
                    if (@($xml.testsuite.testcase | Where-Object { $_.name -eq $t -and ($_.failure -or $_.error) }).Count -gt 0) { $failedNames += $t }
                }
                Copy-Item -LiteralPath $xmlPath -Destination (Join-Path $evidence ($case.Name + '.xml'))
            }
            $killed = $gradleExit -ne 0 -and $compiled -and $failedNames.Count -eq $case.Tests.Count
            $summary += "$($case.Name): GRADLE_EXIT=$gradleExit COMPILED=$compiled RED=$($failedNames.Count)/$($case.Tests.Count) KILLED=$killed"
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
