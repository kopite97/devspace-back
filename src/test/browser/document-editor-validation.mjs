// Real Chrome, isolated test login and database. No external Node dependencies.
import {mkdir,writeFile,rm} from 'node:fs/promises';
const output='build/reports/plan0015/browser';await mkdir(output,{recursive:true});
const base=process.env.DOCUMENT_VALIDATION_BASE||'http://127.0.0.1:18080';
const page=await fetch('http://127.0.0.1:19223/json/new?'+base+'/browser-test/login',{method:'PUT'}).then(r=>r.json());
const ws=new WebSocket(page.webSocketDebuggerUrl);await new Promise(r=>ws.addEventListener('open',r,{once:true}));
let seq=0;const pending=new Map();ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(!m.id)return;const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);});
const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}));});
async function evaluate(expression){const r=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;}
const expected=[['POST','/api/v2/journals/{journalId}/documents'],['GET','/api/v2/journals/{journalId}/documents'],['GET','/api/v2/documents/{documentId}'],['PUT','/api/v2/documents/{documentId}'],['DELETE','/api/v2/documents/{documentId}'],['POST','/api/v2/documents/{documentId}/publish'],['POST','/api/v2/attachments'],['GET','/api/v2/attachments/{attachmentId}/content'],['DELETE','/api/v2/attachments/{attachmentId}']];
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function operation(method,path){await evaluate(`(()=>{for(const e of document.querySelectorAll('button,[role="button"]')){const words=e.innerText.trim().replace(/\\s+/g,' ').split(' ');if(words[0]===${JSON.stringify(method)}&&words[1]===${JSON.stringify(path)}){e.click();return;}}throw Error('Operation control missing');})()`);await sleep(250);}
try {
  let visible=[];
  for(let i=0;i<120;i++){visible=await evaluate(`(()=>{const expected=${JSON.stringify(expected)};return expected.filter(([m,p])=>[...document.querySelectorAll('button,[role="button"]')].some(e=>{const t=e.innerText.trim().replace(/\\s+/g,' ').split(' ');return t[0]===m&&t[1]===p;}));})()`);if(visible.length===9)break;await sleep(250);}
  if(visible.length!==9)throw Error('Missing visible operations: '+JSON.stringify(visible));
  const runtime=await evaluate(`(async()=>{
    const csrf=(await fetch('/api/v1/auth/csrf').then(r=>r.json())).csrfToken;window.documentCsrf=csrf;const results=[];
    async function call(method,path,body,status,key){const headers={'X-CSRF-Token':csrf};if(body!==undefined)headers['Content-Type']='application/json';if(key)headers['Idempotency-Key']=key;const r=await fetch(path,{method,headers,...(body===undefined?{}:{body:JSON.stringify(body)})});const text=await r.text();if(r.status!==status)throw Error(method+' '+path+': '+r.status+' '+text);if(r.headers.get('Cache-Control')!=='no-store')throw Error('Missing no-store');results.push({method,path,status,revision:r.headers.get('X-Workspace-Data-Revision')});return JSON.parse(text);}
    const project=await call('POST','/api/v2/projects',{name:'Document browser validation',stack:'Java'},201,crypto.randomUUID());
    const journal=await call('POST','/api/v2/journals',{title:'Document journal',projectId:project.id,body:'Preserved journal body',entryDate:'2026-09-23'},201,crypto.randomUUID());
    await call('GET','/api/v2/journals/'+journal.id,undefined,200);
    const path='/api/v2/journals/'+journal.id+'/documents';await call('GET',path,undefined,200);
    const doc=await call('POST',path,{title:'',content:[]},201);window.documentValidationDoc=doc.id;window.documentValidationJournal=journal.id;
    const form=new FormData();form.append('documentId',doc.id);form.append('file',new File(['hello 한글'],'hello.txt',{type:'text/plain'}));
    const uploaded=await fetch('/api/v2/attachments',{method:'POST',headers:{'X-CSRF-Token':csrf},body:form});if(uploaded.status!==201)throw Error('Upload '+uploaded.status+' '+await uploaded.text());const file=await uploaded.json();if(file.status!=='TEMP')throw Error('Expected TEMP');
    const content=[{id:'unknown-block',type:'opaque',props:{n:17,attachmentId:file.id}}];
    const saved=await call('PUT','/api/v2/documents/'+doc.id,{title:'Browser saved',content,attachmentIds:[file.id]},200);if(saved.attachmentIds[0]!==file.id)throw Error('Attachment not reconciled');
    const restored=await call('GET','/api/v2/documents/'+doc.id,undefined,200);if(JSON.stringify(restored.content)!==JSON.stringify(content))throw Error('Content changed');
    const binary=await fetch(file.contentUrl);if(binary.status!==200||await binary.text()!=='hello 한글'||binary.headers.get('X-Content-Type-Options')!=='nosniff')throw Error('Binary/headers mismatch');
    await call('DELETE','/api/v2/attachments/'+file.id,undefined,409);
    const published=await call('POST','/api/v2/documents/'+doc.id+'/publish',undefined,200);const again=await call('POST','/api/v2/documents/'+doc.id+'/publish',undefined,200);if(published.publishedAt!==again.publishedAt)throw Error('Publication timestamp changed');
    const list=await call('GET',path,undefined,200);if(list.total!==1||list.items[0].content!==undefined)throw Error('Summary mismatch');
    await call('PUT','/api/v2/documents/'+doc.id,{title:'Browser saved',content,attachmentIds:[]},200);await call('DELETE','/api/v2/attachments/'+file.id,undefined,200);await call('GET',file.contentUrl,undefined,404);
    window.documentValidationCall=call;return {results,documentId:doc.id,journalId:journal.id,upload:file};
  })()`);
  await operation('GET','/api/v2/documents/{documentId}');
  await evaluate(`[...document.querySelectorAll('button')].find(e=>e.innerText.trim()==='Try it out').click()`);await sleep(250);
  await evaluate(`(()=>{const input=[...document.querySelectorAll('input')].find(e=>e.placeholder?.includes('documentId'));if(!input)throw Error('documentId input missing');input.focus();})()`);
  await cdp('Page.bringToFront');await cdp('Input.insertText',{text:runtime.documentId});

  await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});await sleep(1200);await evaluate(`[...document.querySelectorAll('button')].find(e=>e.innerText.trim()==='Execute').click()`);
  let executed=false;for(let i=0;i<100;i++){executed=await evaluate(`document.body.innerText.includes('"documentSpaceId"')&&document.body.innerText.includes('Server response')&&document.body.innerText.includes('Browser saved')`);if(executed)break;await sleep(100);}if(!executed)throw Error('Swagger detail execution failed');
  await operation('GET','/api/v2/documents/{documentId}');await operation('POST','/api/v2/attachments');
  await evaluate(`[...document.querySelectorAll('button')].find(e=>e.innerText.trim()==='Try it out').click()`);await sleep(500);
  await evaluate(`(()=>{if(!document.querySelector('input[type=file]'))throw Error('Multipart file selector missing');})()`);
  await operation('POST','/api/v2/attachments');await operation('GET','/api/v2/attachments/{attachmentId}/content');
  const binaryRendered=await evaluate(`document.body.innerText.includes('application/pdf')&&document.body.innerText.includes('Content-Disposition')`);if(!binaryRendered)throw Error('Binary contract not rendered');
  const deletion=await evaluate(`(async()=>{const document=await window.documentValidationCall('DELETE','/api/v2/documents/'+window.documentValidationDoc,undefined,200);const journal=await window.documentValidationCall('DELETE','/api/v2/journals/'+window.documentValidationJournal+'?revision=1',undefined,200);return {document,journal};})()`);
  const evidence={result:'DOCUMENT_EDITOR_BROWSER_PASS',renderedOperations:visible,swaggerDetailExecute:true,multipartFileSelector:true,binaryRendered,runtime,deletion};await writeFile(output+'/result.json',JSON.stringify(evidence,null,2));await rm(output+'/failure.json',{force:true});
  await writeFile(output+'/swagger.html',await evaluate('document.documentElement.outerHTML'));const screenshot=await cdp('Page.captureScreenshot',{format:'png'});await writeFile(output+'/swagger.png',Buffer.from(screenshot.data,'base64'));console.log(JSON.stringify(evidence));
}catch(error){await writeFile(output+'/failure.json',JSON.stringify({error:String(error),page:await evaluate('document.body.innerText')},null,2));throw error;}
finally{await cdp('Page.close');ws.close();}
