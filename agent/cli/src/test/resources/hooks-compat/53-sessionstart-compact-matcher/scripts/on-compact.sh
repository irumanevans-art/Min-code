#!/bin/sh
# 压缩后：指回被压缩掉的计划
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"compact context: earlier turns were compacted; the migration plan is in docs/migration.md."}}'
