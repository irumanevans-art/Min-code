#!/bin/sh
# decision:block+reason 阻止停止。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Must be provided when Claude is blocked from stopping"}'
