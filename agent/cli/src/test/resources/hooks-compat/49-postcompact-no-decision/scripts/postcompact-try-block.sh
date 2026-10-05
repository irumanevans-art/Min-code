#!/bin/sh
# 误以为能对压缩结果做决策：PostCompact 没有决策控制，decision block 无效
cat > /dev/null
printf '%s' '{"decision":"block"}'
exit 0
