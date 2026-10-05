#!/bin/sh
# 误以为通知钩子能停会话：continue:false、stopReason、systemMessage 都该被丢弃
cat > /dev/null
printf '%s' '{"continue":false,"stopReason":"stopping from the notification hook","systemMessage":"webhook queued"}'
exit 0
