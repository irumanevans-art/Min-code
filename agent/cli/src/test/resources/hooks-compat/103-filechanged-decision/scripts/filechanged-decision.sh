#!/bin/sh
# watchPaths 动态加监视；continue 原文说丢弃——故意写上。
cat > /dev/null
printf '%s' '{"watchPaths":["/home/user/repo/data2.csv"],"systemMessage":"data.csv changed","continue":false}'
