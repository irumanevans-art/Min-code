#!/bin/sh
# 子 agent 想收工但还有没做完的事：decision block 让它继续，reason 是它的下一条指令
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Two TODOs remain in src/api.ts; finish them before stopping"}'
