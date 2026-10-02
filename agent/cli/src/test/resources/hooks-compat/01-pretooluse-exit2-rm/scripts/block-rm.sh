#!/bin/sh
# 拦截危险 rm -rf：读 tool_input.command，命中就 exit 2 并把理由写到 stderr
input=$(cat)
command=$(printf '%s' "$input" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s);process.stdout.write((j.tool_input&&j.tool_input.command)||"")})')
case "$command" in
  "rm -rf"*)
    echo "Blocked: rm -rf is not allowed in this repo" >&2
    exit 2
    ;;
esac
exit 0
