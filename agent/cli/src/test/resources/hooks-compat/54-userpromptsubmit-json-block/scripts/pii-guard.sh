#!/bin/sh
# 提示词里带客户 PII 就拦下：JSON decision block（现有样本只有纯文本与 exit 2 形态）
cat > /dev/null
printf '%s' '{"decision":"block","reason":"prompts mentioning customer PII must go through the review queue instead"}'
exit 0
