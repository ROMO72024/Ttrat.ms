// Freeze time so future session fixtures remain stable in later GitHub builds.
const Date=class extends globalThis.Date {
  constructor(...args){super(...(args.length?args:['2026-09-13T12:00:00Z']));}
  static now(){return globalThis.Date.parse('2026-09-13T12:00:00Z');}
};
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const events={},nodes={},storage={};let failSave=false;
function node(){return {innerHTML:'',textContent:'',style:{},className:'',classList:{add(){},remove(){},toggle(){}},querySelector(){return null},querySelectorAll(){return []},scrollIntoView(){},focus(){}};}
['app','modal-root','toast','form-error','f-weeks','f-reminderMinutes','repeat-fields'].forEach(x=>nodes[x]=node());
const doc={getElementById:id=>nodes[id],querySelector:q=>q==='[data-form-error]'?nodes['form-error']:nodes[q.replace(/^#/,'')],querySelectorAll:()=>[],addEventListener:(t,f)=>events[t]=f,activeElement:null,body:{style:{}}};
const context={document:doc,localStorage:{getItem:k=>storage[k]||null,setItem:(k,v)=>{if(failSave)throw Error('disk full');storage[k]=v;}},Intl,Date,JSON,Set,Map,Math,Number,String,Object,Array,Error,Boolean,console,setTimeout:()=>0,clearTimeout(){},scrollTo(){},crypto:require('crypto').webcrypto};context.window=context;context.globalThis=context;
vm.createContext(context);for(const file of ['core.js','app.js'])vm.runInContext(fs.readFileSync(require('path').join(__dirname,'../app/src/main/assets',file),'utf8'),context,{filename:file});
const click=(action,id='',rest={})=>events.click({target:{closest:()=>({dataset:{action,id,...rest}})}});
function submit(values,selected=[],weekdays=[]){nodes['form-error'].textContent='';const elements={namedItem:n=>elements[n]};for(const [key,val] of Object.entries(values))elements[key]=typeof val==='object'?val:{value:String(val)};events.submit({preventDefault(){},target:{id:'modal-form',elements,reportValidity:()=>true,querySelectorAll:q=>q==='input[name=studentIds]:checked'?selected.map(value=>({value})):q==='input[name=weekdays]:checked'?weekdays.map(value=>({value:String(value)})):[]}});}
const current=()=>JSON.parse(storage['ghars-state-v1']||JSON.stringify(context.Ghars.emptyState()));
const html=()=>nodes['modal-root'].innerHTML;
function input(name){const tag=html().match(new RegExp('<input\\b[^>]*\\b(?:name|id)="'+name+'"[^>]*>'));assert(tag,'Input '+name+' exists');return tag[0];}
function inputValue(name){return input(name).match(/\bvalue="([^"]*)"/)?.[1]??'';}
function select(name){const tag=html().match(new RegExp('<select\\b[^>]*\\bname="'+name+'"[^>]*>[\\s\\S]*?</select>'));assert(tag,'Select '+name+' exists');return tag[0];}
function selectedValue(name){return select(name).match(/<option\b[^>]*value="([^"]*)"[^>]*\bselected\b/)?.[1];}
function assertBlank(names){for(const name of names)assert.equal(inputValue(name),'',name+' must begin blank');}
const newStudent={name:'طالب اختبار <script>',classroom:'الصف الأول',shift:'morning',phone:'',notes:'ملاحظة',goals:'هدف',speechTotal:12,speechWeekly:2,behaviorTotal:6,behaviorWeekly:1};
const sessionValues={type:'speech',date:'2026-10-05',time:'09:00',duration:30,note:'مشتركة',reminderEnabled:{checked:true},reminderMinutes:15,repeat:{checked:false}};
assert(nodes.app.innerHTML.includes('جدول اليوم'));
click('student-new');assertBlank(['name','classroom','phone','speechTotal','speechWeekly','behaviorTotal','behaviorWeekly']);assert.equal(selectedValue('shift'),'');assert(select('shift').includes('required'));
submit({...newStudent,speechWeekly:''});assert.equal(current().students.length,0);assert(nodes['form-error'].textContent.includes('أكملي إجمالي'));
submit({...newStudent,shift:''});assert.equal(current().students.length,0);assert(nodes['form-error'].textContent.includes('اختاري الفترة'));
submit(newStudent);assert.equal(current().students.length,1);
click('student-new');submit({...newStudent,name:'طالبة ثانية',classroom:'الصف الثاني',behaviorTotal:'',behaviorWeekly:''});assert.equal(current().students.length,2);assert.deepEqual(current().students[1].plans.behavior,{total:0,weekly:0});
const ids=current().students.map(x=>x.id);
const firstStudent=JSON.stringify(current().students[0]);click('student-edit',ids[0]);assert.equal(inputValue('speechTotal'),'12');assert.equal(inputValue('speechWeekly'),'2');assert.equal(inputValue('behaviorTotal'),'6');assert.equal(selectedValue('shift'),'morning');submit(newStudent);assert.equal(JSON.stringify(current().students[0]),firstStudent);
click('student-edit',ids[1]);assert.equal(inputValue('behaviorTotal'),'0');assert.equal(inputValue('behaviorWeekly'),'0');click('modal-close');
click('session-new');assertBlank(['duration','time','weeks','reminderMinutes','roster-search']);assert.equal(inputValue('date'),context.Ghars.today());assert.equal(selectedValue('type'),'');assert(select('type').includes('required'));assert(!input('reminderEnabled').includes('checked'));assert(input('reminderMinutes').includes('disabled'));assert(input('weeks').includes('disabled'));assert(!/<input[^>]*name="weekdays"[^>]*checked/.test(html()));assert(!/<input[^>]*name="studentIds"[^>]*checked/.test(html()));assert(html().includes('تنبيه عند بدء الجلسة مفعّل تلقائيًا'));
events.change({target:{id:'reminder-enabled',checked:true}});assert.equal(nodes['f-reminderMinutes'].disabled,false);
submit({...sessionValues,type:''},ids);assert.equal(current().sessions.length,0);assert(nodes['form-error'].textContent.includes('اختاري نوع الجلسة'));
submit({...sessionValues,duration:''},ids);assert.equal(current().sessions.length,0);assert(nodes['form-error'].textContent.includes('الحقول الرقمية'));
submit({...sessionValues,reminderMinutes:''},ids);assert.equal(current().sessions.length,0);assert(nodes['form-error'].textContent.includes('الحقول الرقمية'));
submit(sessionValues,ids);assert.equal(current().sessions.length,1);const sid=current().sessions[0].id;assert(context.Ghars.alarmItems(current()).some(x=>x.id==='session-'+sid));assert(context.Ghars.alarmItems(current()).some(x=>x.id==='start-session-'+sid));
const savedSession=JSON.stringify(current().sessions[0]);click('session-edit',sid);assert.equal(inputValue('duration'),'30');assert.equal(inputValue('time'),'09:00');assert.equal(inputValue('reminderMinutes'),'15');assert.equal(selectedValue('type'),'speech');assert(input('reminderEnabled').includes('checked'));submit(sessionValues,ids);assert.equal(JSON.stringify(current().sessions[0]),savedSession);
click('session-complete',sid);assert.equal(selectedValue('attendance-0'),'');assert.equal(selectedValue('progress-0'),'');
submit({'attendance-0':'','attendance-1':'absent','progress-0':'','progress-1':'',note:''});assert.equal(current().sessions[0].status,'scheduled');assert(nodes['form-error'].textContent.includes('اختاري الحضور'));
submit({'attendance-0':'present','attendance-1':'absent','progress-0':4,'progress-1':'','note-0':'تقدم جيد','note-1':'اعتذار',note:'تمت الأنشطة'});assert.equal(current().sessions[0].status,'completed');assert.equal(context.Ghars.studentStats(current(),ids[0]).completed,1);assert.equal(context.Ghars.studentStats(current(),ids[1]).completed,0);assert.equal(current().sessions[0].attendance[ids[1]].progress,0);
click('session-complete',sid);assert.equal(selectedValue('attendance-0'),'present');assert.equal(selectedValue('attendance-1'),'absent');assert.equal(selectedValue('progress-0'),'4');assert.equal(selectedValue('progress-1'),'0');click('modal-close');
click('session-new');submit({...sessionValues,type:'behavior',time:'09:15',reminderEnabled:{checked:false}},ids);assert.equal(current().sessions.length,1);assert(nodes['form-error'].textContent.includes('تعارض'));
click('session-new');submit({...sessionValues,type:'behavior',time:'10:00',repeat:{checked:true},weeks:''},ids,[1,3]);assert.equal(current().sessions.length,1);assert(nodes['form-error'].textContent.includes('الحقول الرقمية'));
submit({...sessionValues,type:'behavior',time:'10:00',repeat:{checked:true},weeks:2},ids);assert.equal(current().sessions.length,1);assert(nodes['form-error'].textContent.includes('اختاري يومًا'));
submit({...sessionValues,type:'behavior',time:'10:00',repeat:{checked:true},weeks:2},ids,[1,3]);assert.equal(current().sessions.length,5);
click('student-profile',ids[0]);assert(nodes.app.innerHTML.includes('&lt;script&gt;'));assert(!nodes.app.innerHTML.includes('طالب اختبار <script>'));
click('student-session',ids[0]);assert(new RegExp('name="studentIds" value="'+ids[0]+'" checked').test(html()));assert.equal(inputValue('time'),'');click('modal-close');
click('session-day','',{date:'2026-11-03'});assert.equal(inputValue('date'),'2026-11-03');assert.equal(inputValue('time'),'');click('modal-close');
for(const page of ['home','schedule','students','reminders','settings']){click('navigate','',{page});assert(nodes.app.innerHTML.includes('page-footer'));}
assert(!nodes.app.innerHTML.includes('default-reminder'));assert(nodes.app.innerHTML.includes('1.0.1'));assert(nodes.app.innerHTML.includes('تنبيه تلقائي عند بدء الجلسة'));
click('reminder-new');assertBlank(['title','time']);assert.equal(inputValue('date'),context.Ghars.today());submit({title:'مراجعة الخطة',date:'2099-02-10',time:'10:30',enabled:{checked:true}});assert.equal(current().reminders.length,1);
click('reminder-edit',current().reminders[0].id);assert.equal(inputValue('title'),'مراجعة الخطة');assert.equal(inputValue('date'),'2099-02-10');assert.equal(inputValue('time'),'10:30');click('modal-close');
const near=context.Ghars.nowLocal(Date.now()+10*60000),priorCount=current().sessions.length;
click('session-new');submit({...sessionValues,date:near.slice(0,10),time:near.slice(11,16),reminderMinutes:15},ids);assert.equal(current().sessions.length,priorCount);assert(nodes['form-error'].textContent.includes('وقت التذكير'));
submit({...sessionValues,date:near.slice(0,10),time:near.slice(11,16),reminderMinutes:0},ids);assert.equal(current().sessions.length,priorCount+1);
const cancelledId=current().sessions.at(-1).id;assert(context.Ghars.alarmItems(current()).some(x=>x.id==='start-session-'+cancelledId));assert(!context.Ghars.alarmItems(current()).some(x=>x.id==='session-'+cancelledId));
click('session-cancel',cancelledId);click('confirm-action');assert.equal(current().sessions.find(x=>x.id===cancelledId).status,'cancelled');
click('session-new');submit({...sessionValues,date:near.slice(0,10),time:near.slice(11,16),reminderEnabled:{checked:false},reminderMinutes:''},ids);assert.equal(current().sessions.at(-1).reminderMinutes,-1);
const startOnlyId=current().sessions.at(-1).id;assert(context.Ghars.alarmItems(current()).some(x=>x.id==='start-session-'+startOnlyId));assert(!context.Ghars.alarmItems(current()).some(x=>x.id==='session-'+startOnlyId));click('session-detail',startOnlyId);assert(html().includes('تنبيه تلقائي عند البدء'));assert(!html().includes('المنبه غير مفعّل'));click('modal-close');
click('session-edit',startOnlyId);assert.equal(inputValue('reminderMinutes'),'');assert(!input('reminderEnabled').includes('checked'));click('modal-close');
click('session-reopen',cancelledId);click('confirm-action');assert(html().includes('إعادة جدولة الجلسة'));
const later=context.Ghars.nowLocal(Date.now()+90*60000);submit({...sessionValues,date:later.slice(0,10),time:later.slice(11,16),reminderMinutes:0},ids);assert.equal(current().sessions.find(x=>x.id===cancelledId).status,'scheduled');
failSave=true;const beforeFailure=JSON.stringify(current());click('student-new');submit({...newStudent,name:'لن يحفظ'});assert.equal(JSON.stringify(current()),beforeFailure);assert(nodes['form-error'].textContent.includes('disk full'));
console.log('UI smoke passed: all new form defaults blank except date, explicit shift/type/attendance, complete optional plan pairs, no numeric fallback, unchanged saved edits including zero values, contextual selection, recurrence validation, optional advance alarm copy and fields, no default reminder preference, group attendance/counts, escaping, conflicts, custom reminders and failed-save preservation.');
