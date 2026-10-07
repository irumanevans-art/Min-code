#!/bin/sh
# watchPaths 动态改监视列表；continue 原文说丢弃——故意写上。
cat > /dev/null
printf '%s' '{"watchPaths":["/home/user/repo/.envrc"],"systemMessage":"now in src","continue":false}'
