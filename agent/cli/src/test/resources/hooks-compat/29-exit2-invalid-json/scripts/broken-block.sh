#!/bin/sh
# exit 2 + 坏 JSON：仍然阻断，理由取 stderr，解析失败只记调试日志
cat > /dev/null
printf '%s' '{"decision":"block","reason": broken}'
echo "gate: command matched the repo deny list" >&2
exit 2
