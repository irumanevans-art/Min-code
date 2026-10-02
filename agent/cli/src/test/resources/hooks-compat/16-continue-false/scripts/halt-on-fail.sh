#!/bin/sh
# continue:false + stopReason：优先于事件级 decision，完全停止
cat > /dev/null
printf '%s' '{"continue":false,"stopReason":"Build failed, fix errors before continuing"}'
