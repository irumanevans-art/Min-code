#!/usr/bin/env node
let s = '';
process.stdin.on('data', d => s += d);
process.stdin.on('end', () => {
  const input = JSON.parse(s);
  process.stdout.write(JSON.stringify({ hookSpecificOutput: { hookEventName: 'PreToolUse',
    updatedInput: Object.assign({}, input.tool_input, { timeout: 300 }) } }));
});
