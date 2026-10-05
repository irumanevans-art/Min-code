#!/bin/sh
# 新会话：注入项目速览
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"startup context: main branch is main; run ./gradlew check before pushing."}}'
