#!/bin/sh
# decision:block 让子 agent 继续跑。
cat > /dev/null
printf '%s' '{"decision":"block","reason":"Run the integration check before stopping"}'
