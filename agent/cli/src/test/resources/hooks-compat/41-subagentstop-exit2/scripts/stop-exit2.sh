#!/bin/sh
# 用 exit 2 拦下子 agent 收工；stderr 即它继续跑的理由（与 reason 同路）
cat > /dev/null
printf '%s' 'subagent must rerun the failing benchmark first' >&2
exit 2
