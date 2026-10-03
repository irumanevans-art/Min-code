#!/usr/bin/env node
// 给没有超时的命令统一加 timeout=300（整体替换 tool_input）
let s = '';
process.stdin.on('data', d => s += d);
process.stdin.on('end', () => {
  const input = JSON.parse(s);
  const updated = Object.assign({}, input.tool_input, { timeout: 300 });
  process.stdout.write(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse', updatedInput: updated } }));
});
