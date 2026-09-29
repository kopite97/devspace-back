import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import http from 'node:http';
const base=process.env.DOCUMENT_VALIDATION_BASE||'http://127.0.0.1:18081';
const directory='.gradle/plan0015-container';await mkdir(directory,{recursive:true});
const mode=process.argv[2]||'verify';
let login;
for(let attempt=0;attempt<50;attempt++){try{login=await fetch(base+'/browser-test/login',{redirect:'manual'});if(login.status===302)break;}catch{}await new Promise(r=>setTimeout(r,1000));}
if(login?.status!==302)throw Error('Validation login unavailable');
const cookie=login.headers.getSetCookie().map(c=>c.split(';')[0]).join('; ');
const csrf=(await fetch(base+'/api/v1/auth/csrf',{headers:{Cookie:cookie}}).then(r=>r.json())).csrfToken;
const calls=[];
async function request(method,path,body,status,key){const headers={Cookie:cookie,Origin:base,'X-CSRF-Token':csrf};if(key)headers['Idempotency-Key']=key;
  if(body!==undefined&&!(body instanceof FormData)){headers['Content-Type']='application/json';body=JSON.stringify(body);}
  const response=await fetch(base+path,{method,headers,body});const text=await response.text();if(response.status!==status)throw Error(method+' '+path+': '+response.status+' '+text);calls.push({method,path,status});return JSON.parse(text);
}
async function upload(documentId,bytes,filename='persist.txt'){const form=new FormData();form.append('documentId',documentId);form.append('file',new File([bytes],filename,{type:'text/plain'}));return request('POST','/api/v2/attachments',form,201);}
async function content(attachment,status=200){const response=await fetch(base+attachment.contentUrl,{headers:{Cookie:cookie}});if(response.status!==status)throw Error('Content status '+response.status+' expected '+status);const bytes=Buffer.from(await response.arrayBuffer());if(status===200){if(bytes.length!==attachment.size)throw Error('Length mismatch');if(attachment.hash&&createHash('sha256').update(bytes).digest('hex')!==attachment.hash)throw Error('Hash mismatch');}return bytes;}
let state;
if(mode==='seed') {
  const p=await request('POST','/api/v2/projects',{name:'Mount recovery',stack:'Java'},201,crypto.randomUUID());
  const j=await request('POST','/api/v2/journals',{title:'Persistent journal',projectId:p.id,body:'original body',entryDate:'2026-09-23'},201,crypto.randomUUID());
  const collection='/api/v2/journals/'+j.id+'/documents';const d=await request('POST',collection,{title:'Persistence',content:[]},201);
  const bytes=Buffer.alloc(10485760,65);const attached=await upload(d.id,bytes);attached.hash=createHash('sha256').update(bytes).digest('hex');await content(attached);
  const rejected=new FormData();rejected.append('documentId',d.id);rejected.append('file',new File([Buffer.alloc(10485761,65)],'over.txt',{type:'text/plain'}));await request('POST','/api/v2/attachments',rejected,413);
  const oversizedRequest=new FormData();oversizedRequest.append('documentId',d.id);oversizedRequest.append('file',new File([Buffer.alloc(10485760,65)],'one.txt',{type:'text/plain'}));oversizedRequest.append('extra',new File([Buffer.alloc(2097152,65)],'two.txt',{type:'text/plain'}));await request('POST','/api/v2/attachments',oversizedRequest,413);
  await request('PUT','/api/v2/documents/'+d.id,{title:'Persistence',content:[{type:'opaque',id:'one',props:{file:attached.id}}],attachmentIds:[attached.id]},200);
  const pending=await upload(d.id,'pending');const expired=await upload(d.id,'expired');const child=await request('POST',collection,{title:'Parent failure',content:[]},201);const childFile=await upload(child.id,'parent delayed');
  state={project:p,journal:j,document:d,attached,pending,expired,child,childFile};await writeFile(directory+'/state.json',JSON.stringify(state,null,2));
} else {
  state=JSON.parse(await readFile(directory+'/state.json','utf8'));
  if(mode==='fault') {
    await content(state.attached,503);await request('DELETE','/api/v2/attachments/'+state.pending.id,undefined,503);
    await request('DELETE','/api/v2/documents/'+state.child.id,undefined,200);await request('GET','/api/v2/documents/'+state.child.id,undefined,404);
    await request('POST','/api/v2/attachments',(()=>{const form=new FormData();form.append('documentId',state.document.id);form.append('file',new File(['blocked'],'blocked.txt',{type:'text/plain'}));return form;})(),503);
  } else if(mode==='verify') {
    await content(state.attached);const doc=await request('GET','/api/v2/documents/'+state.document.id,undefined,200);if(!doc.attachmentIds.includes(state.attached.id))throw Error('Lost ATTACHED relation');
  } else if(mode==='streams') {
    const stalled=[];
    async function slow(){return new Promise((resolve,reject)=>{const request=http.get(base+state.attached.contentUrl,{headers:{Cookie:cookie}},response=>{response.pause();response.on('error',()=>{});resolve({request,response});});request.on('error',reject);});}
    try {
      for(let i=0;i<16;i++){const stream=await slow();if(stream.response.statusCode!==200)throw Error('Stream slot '+i+' rejected');stalled.push(stream);}
      const excess=await fetch(base+state.attached.contentUrl,{headers:{Cookie:cookie}});if(![200,503].includes(excess.status))throw Error('Unexpected concurrent response: '+excess.status);await excess.arrayBuffer();calls.push({wireExcessStatus:excess.status,note:'Docker Desktop may buffer an entire 10 MiB response; controlled file-read tests establish the active-stream bound'});
      await writeFile(directory+'/streams-ready.json',JSON.stringify({paused:16}));await new Promise(r=>setTimeout(r,7000));
    }finally{for(const stream of stalled){stream.response.destroy();stream.request.destroy();}}
    await new Promise(r=>setTimeout(r,1000));await content(state.attached);
    const timeout=await slow();await new Promise(r=>setTimeout(r,32000));timeout.response.destroy();timeout.request.destroy();await new Promise(r=>setTimeout(r,1000));await content(state.attached);
    calls.push({slowClients:16,afterAbortReadable:true,afterDelayReadable:true});
  } else if(mode==='recovered') {
    await content(state.attached);await content(state.pending,404);await content(state.expired,404);await content(state.childFile,404);
    await request('GET','/api/v2/documents/'+state.child.id,undefined,404);
  } else if(mode==='cleanup') {
    await request('DELETE','/api/v2/documents/'+state.document.id,undefined,200);await request('DELETE','/api/v2/journals/'+state.journal.id+'?revision=1',undefined,200);
  } else throw Error('Unknown validation mode');
}
const result={mode,result:'PASS',calls,attachedId:state.attached.id,bytes:state.attached.size};await writeFile(directory+'/'+mode+'-result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
