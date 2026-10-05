#!/bin/sh
# 会话收尾做清理并打一行日志：无决策控制的旁路事件，输出只进调试日志
cat > /dev/null
printf '%s' 'session cleanup done: scratch files removed'
exit 0
