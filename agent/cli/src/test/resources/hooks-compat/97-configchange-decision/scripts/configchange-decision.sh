#!/bin/sh
# decision:block 阻止变更生效；reason/systemMessage/continue 原文说不展示或丢弃。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Configuration changes require admin approval","continue":false,"systemMessage":"config note"}'
