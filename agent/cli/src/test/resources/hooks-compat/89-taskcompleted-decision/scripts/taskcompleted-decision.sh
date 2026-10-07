#!/bin/sh
# exit 2 阻止完成并把 stderr 反馈给模型。
cat > /dev/null
printf '%s' 'Tests not passing. Fix failing tests before completing' >&2
exit 2
