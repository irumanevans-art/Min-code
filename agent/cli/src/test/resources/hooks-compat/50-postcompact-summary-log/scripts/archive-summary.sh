#!/bin/sh
# 压缩完成：把一行记录落进工作区文件（后续事务）；纯文本 stdout 只进调试日志
cat > /dev/null
printf '%s\n' 'manual compact completed' >> "$CLAUDE_PROJECT_DIR/.compact-log"
printf '%s' 'post-compact: summary archived'
exit 0
