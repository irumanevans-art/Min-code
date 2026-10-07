#!/bin/sh
# http 型经 hookSpecificOutput.worktreePath 返回路径。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"WorktreeCreate","worktreePath":"/home/user/repo/.worktrees/feature-auth"}}'
