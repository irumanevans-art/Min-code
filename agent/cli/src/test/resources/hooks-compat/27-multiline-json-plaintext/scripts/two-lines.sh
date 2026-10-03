#!/bin/sh
# 两行各自是合法 JSON 且都不带输出字段：CC 把整体当纯文本
printf '%s\n%s\n' '{"tool":"grep","hits":3}' '{"tool":"rg","hits":5}'
