#!/bin/sh
# 想借 exit 2 把重试线索推给 Claude；原文说该事件 exit 2 不阻断、stderr 给 Claude
cat > /dev/null
echo "retry hint: the fixture file is locked by another process" >&2
exit 2
