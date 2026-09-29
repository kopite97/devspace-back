// Isolated bootTestRun server and a dedicated Chrome debugging profile. No product frontend changes.
import {mkdir,writeFile} from 'node:fs/promises';
const output='docs/reviews/plan0016';await mkdir(output,{recursive:true});
const base=process.env.DOCUMENT_VALIDATION_BASE||'http://127.0.0.1:18083';
const mode=process.argv[2]||'browser';
if(mode==='request-limit') {
  const login=await fetch(base+'/browser-test/login',{redirect:'manual'});
  const Cookie=login.headers.getSetCookie().map(c=>c.split(';')[0]).join('; ');
  const csrf=(await fetch(base+'/api/v1/auth/csrf',{headers:{Cookie}}).then(r=>r.json())).csrfToken;
  const headers={Cookie,Origin:base,'X-CSRF-Token':csrf,'Content-Type':'application/json'};
  async function create(path,body,key){const r=await fetch(base+path,{method:'POST',headers:{...headers,...(key?{'Idempotency-Key':crypto.randomUUID()}:{})},body:JSON.stringify(body)});if(r.status!==201)throw Error(await r.text());return r.json();}
  const p=await create('/api/v2/projects',{name:'Limits',stack:'Java'},true);
  const j=await create('/api/v2/journals',{title:'Limits',body:'Body',projectId:p.id,entryDate:'2026-09-23'},true);
  const d=await create('/api/v2/journals/'+j.id+'/documents',{title:'',content:[]});
  const boundary='registry-boundary';const prefix=`--${boundary}\r\nContent-Disposition: form-data; name="documentId"\r\n\r\n${d.id}\r\n--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.txt"\r\nContent-Type: text/plain\r\n\r\n`;
  const suffix=`\r\n--${boundary}--\r\n`;const results=[];
  for(const size of [1024,1025]){const body=prefix+'a'.repeat(size-Buffer.byteLength(prefix+suffix))+suffix;const r=await fetch(base+'/api/v2/attachments',{method:'POST',headers:{...headers,'Content-Type':'multipart/form-data; boundary='+boundary},body});const result=await r.json();if(r.status!==(size===1024?201:413)||size===1025&&result.code!=='PAYLOAD_TOO_LARGE')throw Error(JSON.stringify(result));results.push({requestBytes:Buffer.byteLength(body),status:r.status,code:result.code});}
  await writeFile(output+'/multipart-boundary.json',JSON.stringify({result:'PASS',isolatedRequestLimit:1024,fileLimit:10485760,results},null,2));console.log('Multipart exact boundary PASS');
  process.exit(0);
}
const page=await fetch('http://127.0.0.1:19224/json/new?'+base+'/browser-test/login',{method:'PUT'}).then(r=>r.json());
const ws=new WebSocket(page.webSocketDebuggerUrl);await new Promise(r=>ws.addEventListener('open',r,{once:true}));
let sequence=0;const pending=new Map();ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(!m.id)return;const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);});
const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
async function evaluate(expression){const r=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function ready(){for(let i=0;i<120;i++){if(await evaluate(`document.querySelectorAll('.opblock').length>=9`))return;await sleep(250);}throw Error('Swagger did not render');}
try {
  await ready();
  const created=await evaluate(`(async()=>{
    const csrf=(await fetch('/api/v1/auth/csrf').then(r=>r.json())).csrfToken;
    async function call(path,method,body,status,key){const form=body instanceof FormData;const headers={'X-CSRF-Token':csrf};if(body!==undefined&&!form)headers['Content-Type']='application/json';if(key)headers['Idempotency-Key']=crypto.randomUUID();const r=await fetch(path,{method,headers,body:body===undefined?undefined:form?body:JSON.stringify(body)});const data=await r.json();if(r.status!==status)throw Error(r.status+' '+JSON.stringify(data));return data;}
    const p=await call('/api/v2/projects','POST',{name:'Registry',stack:'Java'},201,true);
    const j=await call('/api/v2/journals','POST',{title:'Registry',body:'Body',projectId:p.id,entryDate:'2026-09-23'},201,true);
    const d=await call('/api/v2/journals/'+j.id+'/documents','POST',{title:'',content:[]},201);
    if(d.attachments.length)throw Error('New document not empty');
    async function upload(size,status,name='a.txt',type='text/plain'){const f=new FormData();f.append('documentId',d.id);f.append('file',new File(['a'.repeat(size)],name,{type}));return call('/api/v2/attachments','POST',f,status);}
    const ids=[];for(let i=0;i<100;i++)ids.push((await upload(i+1,201,'item-'+i+'.txt')).id);
    const temp=await upload(10485760,201);const over=await upload(10485761,413);if(over.code!=='PAYLOAD_TOO_LARGE')throw Error('Wrong size code');
    const empty=await upload(0,400);const invalid=await upload(1,400,'bad.svg','image/svg+xml');if(empty.code!=='VALIDATION_ERROR'||invalid.code!=='VALIDATION_ERROR')throw Error('Wrong file validation code');
    const saved=await call('/api/v2/documents/'+d.id,'PUT',{title:'Registry',content:[],attachmentIds:ids},200);if(saved.attachments.length!==100)throw Error('Save missing metadata');
    const inUse=await call('/api/v2/attachments/'+ids[0],'DELETE',undefined,409);if(inUse.code!=='ATTACHMENT_IN_USE')throw Error('Wrong attached delete code');
    window.unsavedRegistry={ids,uploaded:temp};
    return {documentId:d.id};
  })()`);
  await cdp('Page.reload',{ignoreCache:true});await sleep(800);await ready();
  const result=await evaluate(`(async()=>{
    if(window.unsavedRegistry!==undefined)throw Error('Registry survived reload');
    const r=await fetch('/api/v2/documents/${created.documentId}');const d=await r.json();if(r.status!==200||d.attachments.length!==100)throw Error('Restore failed');
    if(JSON.stringify(d.attachmentIds)!==JSON.stringify(d.attachments.map(a=>a.id)))throw Error('ID mismatch');
    for(const a of d.attachments){if(Object.keys(a).length!==8||a.documentId!==d.id||a.status!=='ATTACHED'||!a.createdAt||a.contentType!=='text/plain')throw Error('Metadata missing');const f=await fetch(a.contentUrl);if(f.status!==200||(await f.arrayBuffer()).byteLength!==a.size)throw Error('Restored content failed');}
    const api=await fetch('/v3/api-docs').then(r=>r.json());for(const name of ['DeletedDocument','DeletedAttachment'])if(!api.components.schemas[name].required.includes('deletedId'))throw Error('Missing required deletedId');
    const csrf=(await fetch('/api/v1/auth/csrf').then(r=>r.json())).csrfToken;
    async function call(path,method,body){const r=await fetch(path,{method,headers:{'X-CSRF-Token':csrf,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});if(r.status!==200)throw Error('Mutation '+r.status);return r.json();}
    const pub=await call('/api/v2/documents/'+d.id+'/publish','POST');if(pub.attachments.length!==100)throw Error('Publish lost registry');
    const detached=await call('/api/v2/documents/'+d.id,'PUT',{title:'Detached',content:[],attachmentIds:[]});if(detached.attachments.length)throw Error('Detach failed');
    const a=d.attachments[0];if((await call('/api/v2/attachments/'+a.id,'DELETE')).deletedId!==a.id)throw Error('Bad deletedId');
    if((await fetch(a.contentUrl)).status!==404)throw Error('Deleted content accessible');
    if((await call('/api/v2/documents/'+d.id,'DELETE')).deletedId!==d.id)throw Error('Bad document deletedId');
    if((await fetch('/api/v2/documents/'+d.id)).status!==404)throw Error('Deleted document accessible');
    return {result:'PASS',restored:100,allEightFields:true,allFilesReadable:true,reloadDiscardedRegistry:true,additionalTempExcluded:true,defaultFileMaxAccepted:10485760,fileOversizeRejected:true,swaggerRendered:true,deletedIdRequired:true,detachAndDelete:true};
  })()`);
  await writeFile(output+'/browser-restoration.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{await cdp('Page.close').catch(()=>{});ws.close();}
