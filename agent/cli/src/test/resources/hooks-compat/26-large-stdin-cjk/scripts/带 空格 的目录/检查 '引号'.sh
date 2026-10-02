#!/bin/sh
# 脚本路径本身含中文、空格、单引号；stdin 是几百 KB 的 tool_input
node -e 'let s="";process.stdin.on("data",d=>s+=d);process.stdin.on("end",()=>{const n=Buffer.byteLength(s);if(n<200000){process.stderr.write("stdin too small: "+n);process.exit(1)}process.stdout.write(JSON.stringify({hookSpecificOutput:{hookEventName:"PreToolUse",additionalContext:"大 stdin 送达成功（超过 200KB），含中文与引号的脚本路径也正常"}}))})'
