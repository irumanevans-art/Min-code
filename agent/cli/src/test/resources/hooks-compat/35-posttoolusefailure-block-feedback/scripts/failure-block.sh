#!/bin/sh
# 顶层 decision block 把失败的处理建议反馈给模型（工具已失败，反馈不撤销已发生的失败）
cat > /dev/null
printf '%s' '{"decision":"block","reason":"The test run failed because port 3000 is already taken; stop the stale dev server and rerun"}'
