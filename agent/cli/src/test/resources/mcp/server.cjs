const { createInterface } = require('node:readline');
const { spawn } = require('node:child_process');
const child = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: 'ignore' });
const send = m => process.stdout.write(JSON.stringify({jsonrpc:'2.0', ...m})+'\n');
const running = new Map();
const rl = createInterface({input:process.stdin});
rl.on('line', line => {
  const m = JSON.parse(line);
  if (m.method === 'initialize') send({id:m.id,result:{protocolVersion:'2025-11-25',serverInfo:{name:'real-node',version:'1'},capabilities:{tools:{}}}});
  else if (m.method === 'tools/list') send({id:m.id,result:{tools:[{name:'pid',inputSchema:{type:'object'}},{name:'slow',inputSchema:{type:'object'}}]}});
  else if (m.method === 'tools/call') {
    if (m.params.name === 'pid') send({id:m.id,result:{content:[{type:'text',text:JSON.stringify({pid:process.pid,child:child.pid})}]}});
    else {
      const timer = setInterval(()=>send({method:'notifications/progress',params:{progressToken:m.params._meta?.progressToken,progress:1}}),50);
      running.set(m.id,timer);
    }
  } else if (m.method === 'notifications/cancelled') {
    clearInterval(running.get(m.params.requestId)); running.delete(m.params.requestId);
    console.error('cancelled '+m.params.requestId);
  }
});
// 故意不处理 stdin EOF；端口必须能用 TERM/KILL 连同仍活着的后代一起收尾。
setInterval(()=>{},1000);
