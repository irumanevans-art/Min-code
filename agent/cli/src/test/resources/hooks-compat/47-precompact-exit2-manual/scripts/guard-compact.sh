#!/bin/sh
# 手动 /compact 前检查未提交改动：有就拦下压缩（exit 2，stderr 显示给用户）
cat > /dev/null
printf '%s' 'manual compact blocked: 2 uncommitted files, commit or stash first' >&2
exit 2
