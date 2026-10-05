#!/bin/sh
# 自动压缩前若正跑着长迁移就拦下：JSON decision block 同样能拦压缩
cat > /dev/null
printf '%s' '{"decision":"block","reason":"auto-compact skipped: schema migration in progress, compacting now would lose the plan"}'
