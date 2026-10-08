param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
# M5 团队与定时调度的变异验证：每个变异改一处源码，对应测试必须变红（先编译通过、再断言失败），无论结果如何都还原源码。
# 锚点都是单行文本（工作区文件可能是 CRLF，多行锚点会对不上）。
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[System.IO.Directory]::CreateDirectory($evidence) | Out-Null
$K = 'agent/kernel/src/main/kotlin/dev/min/agent/kernel'
$cases = @(
    @{ Name='no-concurrency-cap'
       Edits=@(@{ File="$K/TeamCoordinator.kt"; Before='private fun hasRoom(): Boolean = active.size < maxConcurrent'; After='private fun hasRoom(): Boolean = true' })
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
           @{ File="$K/Teams.kt"; Before='task.owner != e.by ->'; After='false ->' },
           @{ File="$K/Teams.kt"; Before='task.owner != null && e.by != TEAM_LEAD ->'; After='false ->' })
       Class='TeamSessionTest'
       Tests=@('members change only their own tasks and the leader changes any') },
    @{ Name='member-edits-others-tasks-fold'
       Edits=@(
           @{ File="$K/Teams.kt"; Before='task.owner != e.by ->'; After='false ->' },
           @{ File="$K/Teams.kt"; Before='task.owner != null && e.by != TEAM_LEAD ->'; After='false ->' })
       Class='TeamFoldTest'
       Tests=@('a member cannot finish a task someone else claimed', 'a member cannot steal or assign tasks') },
    @{ Name='cron-cap-ignored'
       Edits=@(@{ File="$K/Crons.kt"; Before='spent >= limitUsd ->'; After='false ->' })
       Class='CronSessionTest'
       Tests=@('a run that spends up to the cap is stopped, the task is cancelled with the reason and the user is told') },
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
       Edits=@(@{ File="$K/CronEngine.kt"; Before='t.nextFireAt(deps.zone, entry.floorMs)'; After='t.nextFireAt(deps.zone, 0)' })
       Class='CronSessionTest'
       Tests=@('reopening restores unexpired tasks and does not catch up on missed fires') },
    @{ Name='cron-never-expires'
       Edits=@(@{ File="$K/CronEngine.kt"; Before='if (task.isPastExpiry(now)) expire('; After='if (false) expire(' })
       Class='CronSessionTest'
       Tests=@('a recurring task expires after seven days with a notice') }
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
            $filters = @()
            foreach ($t in $case.Tests) { $filters += '--tests'; $filters += ('dev.min.agent.kernel.' + $case.Class + '.' + $t) }
            & .\gradlew.bat ':agent:kernel:test' @filters '--console=plain' *> $log
            $gradleExit = $LASTEXITCODE
            $xmlPath = Join-Path $root ('agent/kernel/build/test-results/test/TEST-dev.min.agent.kernel.' + $case.Class + '.xml')
            $compiled = -not (Select-String -LiteralPath $log -Pattern 'compileKotlin FAILED|compileTestKotlin FAILED|Compilation error|^e: ' -Quiet)
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
