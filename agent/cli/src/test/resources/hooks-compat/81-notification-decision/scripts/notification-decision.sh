#!/bin/sh
# systemMessage/continue 原文说丢弃；terminalSequence 仍代发——故意都写上。
cat > /dev/null
printf '%s' '{"systemMessage":"Claude needs your permission","continue":false,"stopReason":"no","decision":"block","terminalSequence":"\u001b]777;notify;Claude Code;Needs attention\u0007"}'
