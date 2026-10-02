---
name: budget-audit
description: Audits bundle size against the performance budget
tools:
  - Read
  - Grep
  - Bash(npm run build)
model: haiku
isolation: worktree
maxTurns: 8
---
Build in a worktree and report chunk sizes against budget.json.
