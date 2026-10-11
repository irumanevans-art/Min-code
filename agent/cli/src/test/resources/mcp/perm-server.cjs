// 测试用的 MCP 权限提示工具服务器（--permission-prompt-tool mcp__perm__approve）：
// approve 按收到的 input 里的文件名决定怎么答——allowme 批准、rewrite 批准但改写入参、garbage 回非 JSON、
// nobehavior 回缺 behavior 的对象、erroring 以 isError 回一个看着像批准的 JSON，别的拒绝。
// consent 是标了 requiresUserInteraction 的普通工具（真被调用才写标记文件）。
const { createInterface } = require('node:readline');
const fs = require('node:fs');
const send = m => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...m }) + '\n');
const rl = createInterface({ input: process.stdin });
rl.on('line', line => {
  const m = JSON.parse(line);
  if (m.method === 'initialize') {
    send({ id: m.id, result: { protocolVersion: '2025-11-25', serverInfo: { name: 'perm', version: '1' }, capabilities: { tools: {} } } });
  } else if (m.method === 'tools/list') {
    send({ id: m.id, result: { tools: [
      { name: 'approve', description: 'permission prompt', inputSchema: { type: 'object' } },
      { name: 'consent', description: 'needs a human', inputSchema: { type: 'object' }, _meta: { 'anthropic/requiresUserInteraction': true } },
    ] } });
  } else if (m.method === 'tools/call') {
    const a = m.params.arguments || {};
    const text = t => send({ id: m.id, result: { content: [{ type: 'text', text: t }] } });
    if (m.params.name === 'consent') {
      if (process.env.CONSENT_MARK) fs.writeFileSync(process.env.CONSENT_MARK, 'ran');
      return text('consented');
    }
    if (process.env.CALLS_LOG) fs.appendFileSync(process.env.CALLS_LOG, JSON.stringify(a) + '\n');
    const subject = String((a.input && (a.input.file_path || a.input.command)) || '');
    if (String(a.tool_name).includes('consent')) text(JSON.stringify({ behavior: 'allow' }));
    else if (subject.includes('allowme')) text(JSON.stringify({ behavior: 'allow', updatedInput: a.input }));
    else if (subject.includes('rewrite')) text(JSON.stringify({ behavior: 'allow', updatedInput: { ...a.input, content: 'changed' } }));
    else if (subject.includes('garbage')) text('definitely not json');
    else if (subject.includes('nobehavior')) text('{"updatedInput":{}}');
    else if (subject.includes('erroring')) send({ id: m.id, result: { isError: true, content: [{ type: 'text', text: '{"behavior":"allow"}' }] } });
    else text(JSON.stringify({ behavior: 'deny', message: 'nope from mcp' }));
  }
});
setInterval(() => {}, 1000);
