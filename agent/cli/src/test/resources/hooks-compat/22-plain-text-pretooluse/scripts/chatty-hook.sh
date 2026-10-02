#!/bin/sh
# 普通文字输出、exit 0：PreToolUse 上只进调试日志，不进上下文也不是错误
cat > /dev/null
printf '%s' 'hook ran fine, nothing to decide'
exit 0
