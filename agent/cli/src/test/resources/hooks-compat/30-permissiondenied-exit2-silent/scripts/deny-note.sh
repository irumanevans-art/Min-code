#!/bin/sh
# auto mode 已经拒过这次调用了；这里按老习惯 exit 2 想再拦一次，CC 对该事件整个忽略
echo "already denied, retry not requested" >&2
exit 2
