#!/bin/sh
# JSON 原文说丢弃；exit 1 在原文里同样让移除失败。
cat > /dev/null
printf '%s' '{"continue":false,"systemMessage":"rm note"}'
exit 1
