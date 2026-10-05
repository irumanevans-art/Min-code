#!/bin/sh
# 空闲约六十秒：转发给团队频道
cat > /dev/null
printf '%s' 'idle for 60s — pinging the team channel'
exit 0
