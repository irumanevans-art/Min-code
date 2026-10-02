#!/bin/sh
# 顶层 decision block + reason：把问题反馈给模型（工具已执行，不撤销副作用）
cat > /dev/null
printf '%s' '{"decision":"block","reason":"ESLint still failing: src/api.ts:12 no-unused-vars (run npm run lint --fix)"}'
