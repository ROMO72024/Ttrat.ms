const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
test('native login gate, wrong code, explicit legacy binding, sync consent and logout clear the visible workspace',()=>{
  const bundledUrl=JSON.parse(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/sync-config.json'),'utf8')).url;
  assert.match(bundledUrl,/^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec$/);
  const handlers={},nodes={};let loggedIn=false,enabled=false,selected='',selectedUrl='',loginUrl=bundledUrl,bridgeFailure=false,calls=[];
  const state={version:1,students:[{id:'s1',name:'اسم قديم خاص',classroom:'روضة',shift:'morning',phone:'',notes:'',goals:'',archived:false,plans:{speech:{total:20,weekly:2},behavior:{total:0,weekly:0}}}],sessions:[],reminders:[],settings:{defaultReminder:-1},_cloud:{serverId:'school',accountId:'accountA'}};
  function node(){return {innerHTML:'',textContent:'',className:'',style:{},classList:{add(){},remove(){},toggle(){}},querySelector(){return null},querySelectorAll(){return []},focus(){},scrollIntoView(){},get firstChild(){return this.innerHTML?'child':null;}};}
  for(const id of ['app','modal-root','toast','form-error'])nodes[id]=node();
  const doc={getElementById:id=>nodes[id],querySelector:q=>q==='[data-form-error]'?nodes['form-error']:nodes[q.replace('#','')],querySelectorAll:()=>[],activeElement:null,body:{style:{}},addEventListener:(t,f)=>(handlers[t]||(handlers[t]=[])).push(f)};
  const native={cloudIsLoggedIn:()=>loggedIn,cloudLoginInfo:()=>JSON.stringify({url:loginUrl}),cloudPrepareLogin:(url,code)=>{if(bridgeFailure)throw Error('bridge unavailable');selected=code;selectedUrl=url;calls.push('login');},cloudActivate:()=>{loggedIn=true;return '';},cloudSignOut:()=>{loggedIn=false;enabled=false;return '';},cloudStatus:()=>JSON.stringify({accountName:'أخصائية اختبار',configured:true,verified:true,enabled,pending:1,students:1,conflicts:[],lastSync:''}),cloudAction:action=>{calls.push(action);if(action==='enable')enabled=true;return '';},loadState:()=>loggedIn?JSON.stringify(state):JSON.stringify({error:'التطبيق مقفل'}),getStatus:()=>JSON.stringify({exact:true,notifications:true}),syncAlarms:()=>'',toEpoch:s=>Date.parse(s+'+03:00')};
  const context={document:doc,Native:native,Date,Intl,JSON,Set,Map,Math,Number,String,Object,Array,Error,Boolean,console,setTimeout:()=>0,clearTimeout(){},scrollTo(){},crypto:require('node:crypto').webcrypto};context.window=context;context.globalThis=context;vm.createContext(context);
  for(const file of ['core.js','app.js','cloud-ui.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets',file),'utf8'),context,{filename:file});
  const emit=(type,event)=>{for(const f of handlers[type]||[])f(event);};
  const click=(action,extra={})=>emit('click',{target:{closest:()=>({dataset:{action,...extra}})}});
  const submit=code=>emit('submit',{preventDefault(){},target:{id:'cloud-login-form',reportValidity:()=>true,elements:{namedItem:n=>{assert.equal(n,'code','Login must not depend on a URL form field');return {value:code};}}}});
  assert(nodes.app.innerHTML.includes('كود الأخصائية'));assert(!nodes.app.innerHTML.includes('اسم قديم خاص'));assert(!nodes.app.innerHTML.includes('التطبيق مقفل'));
  const assertCodeOnly=()=>{assert.equal((nodes.app.innerHTML.match(/<input\b/g)||[]).length,1);assert(!nodes.app.innerHTML.includes('cloud-login-url'));assert(!nodes.app.innerHTML.includes('cloud-school-url'));assert(!nodes.app.innerHTML.includes('script.google.com'));};
  assertCodeOnly();
  loginUrl='';submit('001234');assert(nodes.app.innerHTML.includes('اتصال المدرسة غير مُعدّ'));assert.equal(calls.length,0);assertCodeOnly();
  loginUrl=bundledUrl;bridgeFailure=true;submit('001234');assert(nodes.app.innerHTML.includes('تعذر بدء تسجيل الدخول'));assert(!nodes.app.innerHTML.includes('جارٍ التحقق'));bridgeFailure=false;
  submit('wrong');assert.equal(selected,'wrong');assert.equal(selectedUrl,bundledUrl);context.onCloudLogin(false,{message:'الكود غير صحيح'});assert(nodes.app.innerHTML.includes('الكود غير صحيح'));assert(!loggedIn);
  submit('001234');context.onCloudLogin(true,{accountName:'أخصائية اختبار',legacyStudents:150,offline:false});assert(nodes['modal-root'].innerHTML.includes('150'));assert(!loggedIn);assert(!calls.includes('enable'));
  click('confirm-action');assert(loggedIn);assert(nodes.app.innerHTML.includes('اسم قديم خاص'));assert(!enabled);
  click('go-settings');assert(nodes.app.innerHTML.includes('رفع الأسماء الحالية وتفعيل المزامنة'));click('cloud-enable');assert(!enabled);click('confirm-action');assert(enabled);
  click('cloud-logout');assert(loggedIn);click('confirm-action');assert(!loggedIn);assert(nodes.app.innerHTML.includes('كود الأخصائية'));assert(!nodes.app.innerHTML.includes('اسم قديم خاص'));assert.equal(state.students.length,1);assertCodeOnly();
  submit('001234');assert.equal(selected,'001234');assert.equal(selectedUrl,bundledUrl);context.onCloudLogin(true,{accountName:'أخصائية اختبار',legacyStudents:0,offline:true});assert(nodes['modal-root'].innerHTML.includes('دون إنترنت'));click('confirm-action');assert(loggedIn);assert(nodes.app.innerHTML.includes('اسم قديم خاص'));
});
