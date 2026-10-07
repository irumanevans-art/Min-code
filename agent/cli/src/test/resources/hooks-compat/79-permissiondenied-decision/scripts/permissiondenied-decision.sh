#!/bin/sh
# retry:true 告诉模型可以重试。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PermissionDenied","retry":true}}'
