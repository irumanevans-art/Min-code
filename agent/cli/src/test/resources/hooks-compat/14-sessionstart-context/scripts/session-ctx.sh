#!/bin/sh
# SessionStart 输出 additionalContext（随会话开头注入）
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"项目约定：提交信息用中文；改完跑 ./gradlew check；文档放 docs/ 下，注释用中文。"}}'
