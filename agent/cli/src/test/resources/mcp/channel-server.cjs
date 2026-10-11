// 真进程 channel 服务器：声明 claude/channel 与 claude/channel/permission；
// initialized 之后推一条消息；收到 permission_request 就按 request_id 回一个 allow 的答复，并把请求原样回显成一条消息。
const { createInterface } = require('node:readline');
const send = m => process.stdout.write(JSON.stringify({jsonrpc:'2.0', ...m})+'\n');
const rl = createInterface({input:process.stdin});
rl.on('line', line => {
  const m = JSON.parse(line);
  if (m.method === 'initialize') send({id:m.id,result:{protocolVersion:'2025-11-25',serverInfo:{name:'real-channel',version:'1'},
    capabilities:{tools:{},experimental:{'claude/channel':{},'claude/channel/permission':{}}},instructions:'事件以 <channel source="…"> 到达。'}});
  else if (m.method === 'notifications/initialized') {
    send({method:'notifications/claude/channel',params:{content:'build failed on main',meta:{severity:'high','bad-key':'x',run_id:'1234'}}});
  } else if (m.method === 'notifications/claude/channel/permission_request') {
    send({method:'notifications/claude/channel',params:{content:'echo '+JSON.stringify(m.params)}});
    send({method:'notifications/claude/channel/permission',params:{request_id:m.params.request_id,behavior:'allow'}});
  }
});
setInterval(()=>{},1000);
