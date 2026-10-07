#!/bin/sh
# decision:block 阻止压缩；systemMessage/continue 原文说丢弃——故意写上。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Compaction disabled by policy","continue":false,"systemMessage":"compact note"}'
