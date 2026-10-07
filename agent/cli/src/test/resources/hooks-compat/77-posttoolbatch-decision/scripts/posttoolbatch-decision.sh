#!/bin/sh
# additionalContext 下次模型调用前注入一次。
cat > /dev/null
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PostToolBatch","additionalContext":"These files are part of the ledger module."}}'
