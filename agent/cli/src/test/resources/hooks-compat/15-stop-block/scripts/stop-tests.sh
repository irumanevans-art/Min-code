#!/bin/sh
# 测试没过就不许停：decision block 让模型继续干活
cat > /dev/null
printf '%s' '{"decision":"block","reason":"CI is still red (3 failing tests); keep fixing before stopping"}'
