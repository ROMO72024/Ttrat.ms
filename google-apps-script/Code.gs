/**
 * GHARS Sync 1.1.0 / protocol 2 — multiple specialist spaces and multiple phones.
 * Bound to a NEW private Google Sheet. Run setupGharsSync, then deploy a Web app.
 * Specialist codes are PLAIN TEXT in the accounts tab, as requested.
 * The whole workbook must only be shared with trusted management/accounting staff.
 */
const GHARS_SYNC=Object.freeze({
  protocol:2, accounts:'الأخصائيات', students:'طلاب غرس', data:'بيانات التطبيق',
  shared:['name','speechTotal','behaviorTotal','classroom','shift','phone','speechWeekly','behaviorWeekly','archived'],
  studentFields:['name','speechTotal','behaviorTotal','classroom','shift','phone','speechWeekly','behaviorWeekly','archived','notes','goals'],
  sessionFields:['type','start','duration','reminderMinutes','status','note','seriesId','studentIds','attendance'],
  accountHeaders:['معرّف المساحة — لا تعدّل','اسم الأخصائية','كود الدخول','حالة الحساب'],
  studentHeaders:['معرّف المساحة — لا تعدّل','معرّف الطالب — لا تعدّل','كود الأخصائية','اسم الطالب الثلاثي','إجمالي جلسات النطق','إجمالي جلسات السلوك','الصف / المجموعة','الفترة','هاتف ولي الأمر','نطق أسبوعياً','سلوك أسبوعياً','مؤرشف','نطق منفّذ — تلقائي','سلوك منفّذ — تلقائي','نطق متبقٍ — تلقائي','سلوك متبقٍ — تلقائي','آخر مزامنة','حالة الصف'],
  dataHeaders:['معرّف المساحة','معرّف السجل','البيانات — لا تعدّل','آخر تعديل']
});
function onOpen(){
  SpreadsheetApp.getUi().createMenu('غرس · المزامنة')
    .addItem('١. تهيئة النظام','setupGharsSync').addItem('٢. عرض رابط التطبيق','showGharsConnection')
    .addItem('فحص الحسابات والطلاب','checkGharsSheet').addToUi();
}
function fail_(code,message){const e=new Error(message);e.code=code;e.publicMessage=message;throw e;}
function json_(value){return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);}
function headers_(sheet,expected){
  const actual=sheet.getRange(1,1,1,expected.length).getValues()[0];
  if(actual.some((x,i)=>x!==expected[i]))fail_('HEADERS','عناوين ورقة «'+sheet.getName()+'» غير مطابقة. لا تغيّر أسماء الأعمدة أو ترتيبها.');
}
function book_(){const id=PropertiesService.getScriptProperties().getProperty('GHARS_BOOK_ID');if(!id)fail_('SETUP','شغّل setupGharsSync أولاً.');return SpreadsheetApp.openById(id);}
function sheet_(name,headers){const s=book_().getSheetByName(name);if(!s)fail_('SETUP','ورقة '+name+' غير موجودة.');headers_(s,headers);return s;}
function setupGharsSync(){
  const lock=LockService.getScriptLock();lock.waitLock(30000);
  try{
    const book=SpreadsheetApp.getActiveSpreadsheet();if(!book)throw Error('افتح Apps Script من داخل Google Sheets.');
    const props=PropertiesService.getScriptProperties();
    if(props.getProperty('GHARS_BOOK_ID')&&props.getProperty('GHARS_BOOK_ID')!==book.getId())throw Error('هذا السكربت مربوط بجدول آخر.');
    for(const [name,heads] of [[GHARS_SYNC.accounts,GHARS_SYNC.accountHeaders],[GHARS_SYNC.students,GHARS_SYNC.studentHeaders],[GHARS_SYNC.data,GHARS_SYNC.dataHeaders]]){
      let s=book.getSheetByName(name);if(!s)s=book.insertSheet(name);
      if(s.getMaxColumns()<heads.length)s.insertColumnsAfter(s.getMaxColumns(),heads.length-s.getMaxColumns());
      if(s.getLastRow())headers_(s,heads);else s.getRange(1,1,1,heads.length).setValues([heads]);
      s.setRightToLeft(true).setFrozenRows(1);s.setRowHeight(1,48);
      s.getRange(1,1,1,heads.length).setBackground('#981765').setFontColor('white').setFontWeight('bold').setWrap(true);
    }
    const accounts=book.getSheetByName(GHARS_SYNC.accounts),students=book.getSheetByName(GHARS_SYNC.students),data=book.getSheetByName(GHARS_SYNC.data);
    accounts.getRange(2,1,accounts.getMaxRows()-1,3).setNumberFormat('@');
    accounts.getRange(2,4,accounts.getMaxRows()-1,1).setDataValidation(SpreadsheetApp.newDataValidation().requireValueInList(['مفعّل','موقوف'],true).setAllowInvalid(false).build());
    accounts.setColumnWidth(2,240);accounts.setColumnWidth(3,180);accounts.hideColumns(1);
    students.getRange(2,1,students.getMaxRows()-1,4).setNumberFormat('@');
    students.getRange(2,7,students.getMaxRows()-1,3).setNumberFormat('@');
    students.getRange(2,5,students.getMaxRows()-1,2).setDataValidation(SpreadsheetApp.newDataValidation().requireNumberBetween(0,10000).setAllowInvalid(false).build());
    students.getRange(2,10,students.getMaxRows()-1,2).setDataValidation(SpreadsheetApp.newDataValidation().requireNumberBetween(0,50).setAllowInvalid(false).build());
    students.getRange(2,8,students.getMaxRows()-1,1).setDataValidation(SpreadsheetApp.newDataValidation().requireValueInList(['صباحية','مسائية'],true).setAllowInvalid(false).build());
    students.getRange(2,12,students.getMaxRows()-1,1).setDataValidation(SpreadsheetApp.newDataValidation().requireCheckbox().build());
    students.getRange(2,13,students.getMaxRows()-1,6).setBackground('#eff4e7');
    students.setColumnWidth(4,240);students.setColumnWidth(9,160);students.hideColumns(1,2);
    const owner=Session.getEffectiveUser();
    for(const [s,a1,label] of [[accounts,'A:A','GHARS account IDs'],[students,'A:B','GHARS student IDs'],[students,'M:R','GHARS counts'],[data,'A:D','GHARS records']]){
      if(s.getProtections(SpreadsheetApp.ProtectionType.RANGE).some(p=>p.getDescription()===label))continue;
      const p=s.getRange(a1).protect().setDescription(label);p.addEditor(owner);
      p.removeEditors(p.getEditors().filter(u=>u.getEmail()!==owner.getEmail()));if(p.canDomainEdit())p.setDomainEdit(false);
    }
    data.hideSheet();props.setProperty('GHARS_BOOK_ID',book.getId());
    if(!props.getProperty('GHARS_SERVER_ID'))props.setProperty('GHARS_SERVER_ID',Utilities.getUuid());
    SpreadsheetApp.flush();
  }finally{lock.releaseLock();}
  SpreadsheetApp.getUi().alert('تمت التهيئة دون حذف بيانات. أضف اسم الأخصائية وكوداً من ٥ إلى ٣٢ حرفاً/رقماً في ورقة «الأخصائيات» وفعّل الحساب، ثم انشر كتطبيق ويب. أكواد الدخول ظاهرة وليست مشفرة. لا تعِد كتابة الطلاب القدامى قبل رفعهم من الهاتف.');
}
function showGharsConnection(){
  const url=ScriptApp.getService().getUrl()||'انشر كتطبيق ويب أولاً وانسخ رابط /exec.';
  SpreadsheetApp.getUi().alert('رابط المدرسة المشترك لكل الهواتف:\n\n'+url+'\n\nالكود الخاص بكل أخصائية موجود في ورقة «الأخصائيات». لا تجعل ملف الشيت عاماً.');
}
function validId_(id){return typeof id==='string'&&/^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$/.test(id)&&!['constructor','prototype','__proto__'].includes(id);}
function plain_(value){return typeof value==='string'&&/^[=+\-@]/.test(value)?"'"+value:value;}
function same_(a,b){
  if(a===b)return true;if(a===null||b===null||typeof a!=='object'||typeof b!=='object')return false;
  if(Array.isArray(a)!==Array.isArray(b))return false;
  const ak=Object.keys(a),bk=Object.keys(b);return ak.length===bk.length&&ak.every(k=>Object.prototype.hasOwnProperty.call(b,k)&&same_(a[k],b[k]));
}
function accounts_(){
  const sheet=sheet_(GHARS_SYNC.accounts,GHARS_SYNC.accountHeaders),n=Math.max(0,sheet.getLastRow()-1);
  if(n>500)fail_('LIMIT','عدد حسابات الأخصائيات يتجاوز الحد المسموح.');
  const rows=n?sheet.getRange(2,1,n,4).getValues():[],forms=n?sheet.getRange(2,1,n,4).getFormulas():[];
  const ids=new Set(),codes=new Set(),result=[];
  rows.forEach((r,i)=>{
    if(r.slice(0,3).every(x=>x===''))return;
    const name=String(r[1]).trim(),code=String(r[2]).trim(),active=['','مفعّل',true,'TRUE'].includes(r[3]);
    if(!['','مفعّل','موقوف',true,false,'TRUE','FALSE'].includes(r[3]))fail_('ACCOUNTS','حالة الحساب يجب أن تكون مفعّل أو موقوف.');
    if(forms[i].some(Boolean)||!name||name.length>200||!/^[A-Za-z0-9]{5,32}$/.test(code))fail_('ACCOUNTS','راجع ورقة الأخصائيات: اسم وكود من ٥–٣٢ حرفاً إنجليزياً أو رقماً، بدون صيغ أو فراغات.');
    if(codes.has(code))fail_('ACCOUNTS','هناك كود دخول مكرر في ورقة الأخصائيات. يجب أن يكون لكل مساحة كود مختلف.');codes.add(code);
    let id=String(r[0]);if(id&&(!validId_(id)||ids.has(id)))fail_('ACCOUNTS','معرّف مساحة غير صالح أو مكرر.');
    if(!id){id='a_'+Utilities.getUuid().replace(/-/g,'');sheet.getRange(i+2,1).setNumberFormat('@').setValue(id);}
    ids.add(id);result.push({id,name,code,active});
  });return result;
}
function authorize_(request,accounts){
  const code=typeof request.code==='string'?request.code.trim():'';
  const device=validId_(request.deviceId)?request.deviceId:'unknown';
  const cache=CacheService.getScriptCache(),key='login-'+device,count=Number(cache.get(key)||0);
  if(count>=10)fail_('RATE_LIMIT','محاولات كثيرة من هذا الجهاز. انتظر دقيقة ثم جرّب.');
  const account=accounts.find(a=>a.active&&a.code===code);
  if(!account){cache.put(key,String(count+1),60);fail_('AUTH','الكود غير صحيح أو الحساب موقوف. راجع المحاسب.');}
  cache.remove(key);return account;
}
function integer_(value,max,label){
  if(value==='')return 0;
  const x=typeof value==='string'?value.trim().replace(/[٠-٩]/g,c=>String(c.charCodeAt(0)-1632)).replace(/[۰-۹]/g,c=>String(c.charCodeAt(0)-1776)):value;
  if(!['number','string'].includes(typeof x)||!(/^\d+$/).test(String(x))||!Number.isSafeInteger(Number(x))||Number(x)>max)fail_('ROW',label+': أدخل عدداً صحيحاً بين ٠ و'+max+'.');return Number(x);
}
function string_(o,key,max,required){if(typeof o[key]!=='string'||o[key].length>max||(required&&!o[key].trim()))fail_('RECORD','حقل نصي غير صالح: '+key);}
function number_(o,key,max,min){if(typeof o[key]!=='number'||!Number.isInteger(o[key])||o[key]<(min||0)||o[key]>max)fail_('RECORD','حقل رقمي غير صالح: '+key);}
function validate_(id,v){
  if(JSON.stringify(v||{}).length>45000)fail_('LIMIT','أحد السجلات طويل جداً لخلية Google Sheets. اختصر ملاحظاته قبل المزامنة؛ البيانات المحلية لم تُحذف.');
  if(typeof id!=='string'||!/^(student|session):/.test(id)||!validId_(id.split(':')[1])||id.split(':').length!==2||!v||typeof v!=='object'||Array.isArray(v))fail_('RECORD','سجل غير صالح.');
  const kind=id.split(':')[0],expected=kind==='student'?GHARS_SYNC.studentFields:GHARS_SYNC.sessionFields;
  if(Object.keys(v).length!==expected.length||expected.some(k=>!Object.prototype.hasOwnProperty.call(v,k)))fail_('RECORD','حقول السجل غير متوافقة مع نسخة التطبيق.');
  if(kind==='student'){
    for(const [key,max,required] of [['name',200,true],['classroom',200,false],['phone',100,false],['notes',20000,false],['goals',20000,false]])string_(v,key,max,required);
    if(!['morning','evening'].includes(v.shift)||typeof v.archived!=='boolean')fail_('RECORD','الفترة أو الأرشفة غير صالحة.');
    for(const type of ['speech','behavior']){number_(v,type+'Total',10000);number_(v,type+'Weekly',50);}
  }else{
    if(!['speech','behavior'].includes(v.type)||!['scheduled','completed','cancelled'].includes(v.status))fail_('RECORD','نوع الجلسة أو حالتها غير صالح.');
    string_(v,'start',16,true);string_(v,'note',20000,false);string_(v,'seriesId',120,false);
    if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v.start)||!validDate_(v.start))fail_('RECORD','تاريخ الجلسة غير صالح.');
    number_(v,'duration',480,5);number_(v,'reminderMinutes',10080,-1);
    if(!Array.isArray(v.studentIds)||!v.studentIds.length||v.studentIds.length>50||v.studentIds.some(x=>!validId_(x))||new Set(v.studentIds).size!==v.studentIds.length)fail_('RECORD','قائمة حضور غير صالحة.');
    if(!v.attendance||typeof v.attendance!=='object'||Array.isArray(v.attendance))fail_('RECORD','الحضور غير صالح.');
    Object.keys(v.attendance).forEach(id=>{
      const a=v.attendance[id];if(!v.studentIds.includes(id)||!a||!['present','absent','excused'].includes(a.status))fail_('RECORD','حضور لطالب غير مرتبط.');
      string_(a,'note',20000,false);number_(a,'progress',5);
    });
    if(v.status==='completed'&&v.studentIds.some(id=>!v.attendance[id]))fail_('RECORD','أكمل حضور كل المشاركين.');
  }return v;
}
function validDate_(s){
  const [y,m,d,h,t]=s.match(/\d+/g).map(Number);if(y<2000||y>2100||h>23||t>59)return false;
  const date=new Date(Date.UTC(y,m-1,d,h,t));return date.getUTCFullYear()===y&&date.getUTCMonth()===m-1&&date.getUTCDate()===d;
}
function gridValues_(r,row){
  const shift=String(r[7]).trim(),archive=r[11];
  if(shift&&!['صباحية','مسائية','morning','evening'].includes(shift))fail_('ROW','الصف '+row+': اختر صباحية أو مسائية.');
  if(!['',true,false,'TRUE','FALSE'].includes(archive))fail_('ROW','الصف '+row+': استخدم مربع الأرشفة.');
  return {name:String(r[3]).trim(),speechTotal:integer_(r[4],10000,'نطق / الصف '+row),behaviorTotal:integer_(r[5],10000,'سلوك / الصف '+row),classroom:String(r[6]).trim(),shift:['مسائية','evening'].includes(shift)?'evening':'morning',phone:String(r[8]).trim(),speechWeekly:integer_(r[9],50,'النطق الأسبوعي'),behaviorWeekly:integer_(r[10],50,'السلوك الأسبوعي'),archived:archive===true||archive==='TRUE'};
}
function grid_(accounts){
  const s=sheet_(GHARS_SYNC.students,GHARS_SYNC.studentHeaders),n=Math.max(0,s.getLastRow()-1);
  if(n>10000)fail_('LIMIT','ورقة الطلاب أكبر من الحد المسموح.');
  const rows=n?s.getRange(2,1,n,18).getValues():[],formulas=n?s.getRange(2,1,n,12).getFormulas():[],ids=new Set(),out=[];
  rows.forEach((r,i)=>{
    if(r.slice(0,12).every(x=>x===''||x===false))return;
    try{
      if(formulas[i].some(Boolean))fail_('ROW','الصف '+(i+2)+': لا تستخدم صيغاً في حقول التسجيل.');
      const account=r[0]?accounts.find(a=>a.id===String(r[0])):accounts.find(a=>a.code===String(r[2]).trim());
      if(!account)fail_('ROW','الصف '+(i+2)+': كود الأخصائية غير موجود. أضفه في ورقة الأخصائيات أولاً.');
      const id=String(r[1]);if(id&&(!validId_(id)||ids.has(account.id+':'+id)))fail_('ROW','الصف '+(i+2)+': معرّف طالب مكرر أو غير صالح.');
      if(id)ids.add(account.id+':'+id);
      const values=gridValues_(r,i+2);validate_('student:'+(id||'new'),Object.assign({notes:'',goals:''},values));
      out.push({account,id,row:i+2,values,raw:r});
    }catch(e){s.getRange(i+2,18).setValue(e.publicMessage||'راجع الصف');throw e;}
  });return {sheet:s,rows:out};
}
function data_(){
  const s=sheet_(GHARS_SYNC.data,GHARS_SYNC.dataHeaders),n=Math.max(0,s.getLastRow()-1),out=new Map();
  if(n>40000)fail_('LIMIT','سجل المزامنة أكبر من الحد المسموح.');
  if(n)s.getRange(2,1,n,4).getValues().forEach((r,i)=>{
    if(r.every(x=>x===''))return;
    const account=String(r[0]),id=String(r[1]),key=account+'|'+id;
    if(!validId_(account)||out.has(key))fail_('DATA','معرّف مكرر أو تالف في بيانات التطبيق. لا تعدّل الورقة التقنية.');
    let values;try{values=JSON.parse(r[2]);}catch(_){fail_('DATA','سجل JSON تالف في بيانات التطبيق.');}
    validate_(id,values);out.set(key,{account,id,values,row:i+2,stamp:r[3]});
  });return {sheet:s,records:out};
}
function planRecord_(remote,request){
  const values=JSON.parse(JSON.stringify(remote)),conflicts=[];
  Object.keys(remote).forEach(field=>{
    const local=request.values[field],base=request.base&&request.base[field];
    if(request.base&&same_(local,base))return;if(same_(local,remote[field]))return;
    if(request.base&&same_(remote[field],base))values[field]=local;
    else conflicts.push({id:request.id,field});
  });return {values,conflicts};
}
function append_(sheet,rows,columns){
  if(!rows.length)return;
  const start=sheet.getLastRow()+1,end=start+rows.length-1;
  if(end>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),end-sheet.getMaxRows());
  if(sheet.getRange(start,1,rows.length,columns).getValues().some(r=>r.some(v=>v!==''&&v!==false)))fail_('BUSY','أضيفت بيانات أثناء الكتابة. أعد المحاولة.');
  sheet.getRange(start,1,rows.length,columns).setValues(rows);
}
function writeData_(data,accountId,records,stamp){
  const add=[];
  records.forEach((record,id)=>{
    const prior=data.records.get(accountId+'|'+id);
    if(prior){if(!same_(prior.values,record.values))data.sheet.getRange(prior.row,3,1,2).setValues([[JSON.stringify(record.values),stamp]]);}
    else add.push([accountId,id,JSON.stringify(record.values),stamp]);
  });append_(data.sheet,add,4);
}
function counts_(records){
  const out=new Map();records.forEach((r,id)=>{if(id.startsWith('student:'))out.set(id.slice(8),{speech:0,behavior:0});});
  records.forEach((r,id)=>{const s=r.values;if(!id.startsWith('session:')||s.status!=='completed')return;
    s.studentIds.forEach(id=>{if(s.attendance[id].status==='present')out.get(id)[s.type]++;});
  });return out;
}
function writeGrid_(grid,account,records,stamp,conflicts){
  const add=[],counts=counts_(records),existing=new Map(grid.rows.filter(r=>r.account.id===account.id&&r.id).map(r=>[r.id,r]));
  records.forEach((record,key)=>{
    if(!key.startsWith('student:'))return;
    const id=key.slice(8),v=record.values,old=existing.get(id),m=counts.get(id),summary=[m.speech,m.behavior,Math.max(0,v.speechTotal-m.speech),Math.max(0,v.behaviorTotal-m.behavior),stamp,conflicts.some(c=>c.id===key)?'تعارض: راجع التطبيق':'متزامن'];
    const editable=GHARS_SYNC.shared.map(field=>field==='shift'?(v[field]==='morning'?'صباحية':'مسائية'):plain_(v[field]));
    if(!old){add.push([account.id,id,account.code,...editable,...summary]);return;}
    GHARS_SYNC.shared.forEach((field,i)=>{
      if(same_(old.values[field],v[field]))return;
      const raw=grid.sheet.getRange(old.row,1,1,12).getValues()[0];
      if(String(raw[0])!==account.id||String(raw[1])!==id)fail_('BUSY','تغيّر ترتيب الصفوف أثناء المزامنة. أعد المحاولة.');
      const latest=gridValues_(raw,old.row);
      if(!same_(latest[field],old.values[field])&&!same_(latest[field],v[field]))fail_('BUSY','تغيّرت خلية أثناء المزامنة. أعد المحاولة بعد إتمام التعديل.');
      grid.sheet.getRange(old.row,i+4).setValue(editable[i]);
    });
  });
  append_(grid.sheet,add,18);
  // Technical columns only: batched to keep 150+ student imports fast.
  const n=Math.max(0,grid.sheet.getLastRow()-1);if(!n)return;
  const rows=grid.sheet.getRange(2,1,n,18).getValues(),technical=rows.map(r=>r.slice(12,18));
  rows.forEach((r,i)=>{
    if(String(r[0])!==account.id)return;const id=String(r[1]),record=records.get('student:'+id);if(!record)return;
    const v=record.values,m=counts.get(id);
    technical[i]=[m.speech,m.behavior,Math.max(0,v.speechTotal-m.speech),Math.max(0,v.behaviorTotal-m.behavior),stamp,conflicts.some(c=>c.id==='student:'+id)?'تعارض: راجع التطبيق':'متزامن'];
    if(String(r[2])!==account.code)grid.sheet.getRange(i+2,3).setNumberFormat('@').setValue(account.code);
  });
  grid.sheet.getRange(2,13,n,6).setValues(technical);
}
function checkGharsSheet(){
  const lock=LockService.getScriptLock();lock.waitLock(30000);
  try{const accounts=accounts_(),grid=grid_(accounts);SpreadsheetApp.getUi().alert('الحسابات: '+accounts.length+'\nالطلاب: '+grid.rows.length+'\nلا يتم حذف بيانات من الهاتف بهذا الفحص.');}
  finally{lock.releaseLock();}
}
function doGet(){return json_({ok:true,service:'Ghars Sync',protocol:2,message:'الدخول بالكود من تطبيق غرس. هذا الرابط لا يعرض الأسماء.'});}
function doPost(event){
  try{
    const body=event&&event.postData&&event.postData.contents;if(!body||body.length>5*1024*1024)fail_('BAD_REQUEST','حجم الطلب غير صالح.');
    return json_(handleRequest_(JSON.parse(body)));
  }catch(e){return json_({ok:false,code:e.code||'SERVER_ERROR',message:e.publicMessage||'تعذرت المزامنة. راجع إعدادات النشر وسلامة أوراق غرس.'});}
}
function handleRequest_(request){
  if(!request||request.protocol!==2||!['login','sync'].includes(request.action)||!validId_(request.deviceId))fail_('PROTOCOL','نسخة التطبيق أو السكربت غير متوافقة.');
  const lock=LockService.getScriptLock();if(!lock.tryLock(25000))fail_('BUSY','مزامنة أخرى قيد التنفيذ. أعد المحاولة.');
  try{
    const accounts=accounts_(),account=authorize_(request,accounts),serverId=PropertiesService.getScriptProperties().getProperty('GHARS_SERVER_ID'),stamp=new Date().toISOString();
    const identity={ok:true,protocol:2,serverId,account:{id:account.id,name:account.name},serverTime:stamp};
    if(request.action==='login')return identity;
    if(request.serverId!==serverId||request.accountId!==account.id)fail_('BINDING','الكود يخص مساحة مختلفة. سجّل خروجاً وادخل بالكود الصحيح؛ لم تُنقل بياناتك.');
    if(!Array.isArray(request.records)||request.records.length>35000)fail_('LIMIT','عدد السجلات أكبر من الحد المسموح.');
    const incoming=new Map();request.records.forEach(r=>{
      if(!r||incoming.has(r.id))fail_('RECORD','سجل مكرر في طلب المزامنة.');
      validate_(r.id,r.values);if(r.base!==null)validate_(r.id,r.base);incoming.set(r.id,r);
    });
    const grid=grid_(accounts),data=data_(),records=new Map(),conflicts=[];
    data.records.forEach(r=>{if(r.account===account.id)records.set(r.id,{id:r.id,values:JSON.parse(JSON.stringify(r.values))});});
    const ownRows=grid.rows.filter(r=>r.account.id===account.id);
    if(request.records.length&&request.records.every(r=>r.base===null)&&!records.size&&ownRows.some(r=>!r.id||!incoming.has('student:'+r.id)))
      fail_('INITIAL_IMPORT','قبل أول رفع للأسماء القديمة، اترك صفوف هذه الأخصائية في ورقة الطلاب فارغة. لا تعِد إدخال الأسماء يدوياً.');
    ownRows.forEach(row=>{
      if(!row.id){
        const fresh=grid.sheet.getRange(row.row,1,1,12).getValues()[0];
        if(!same_(fresh,row.raw.slice(0,12)))fail_('BUSY','تغيّر صف أثناء المزامنة. أعد المحاولة.');
        row.id='st_'+Utilities.getUuid().replace(/-/g,'');
        grid.sheet.getRange(row.row,1,1,2).setNumberFormat('@').setValues([[account.id,row.id]]);
      }
      const id='student:'+row.id,old=records.get(id),values=Object.assign({notes:'',goals:''},old?old.values:{},row.values);
      records.set(id,{id,values});
    });
    request.records.forEach(r=>{
      const old=records.get(r.id);
      if(!old){records.set(r.id,{id:r.id,values:JSON.parse(JSON.stringify(r.values))});return;}
      const plan=planRecord_(old.values,r);records.set(r.id,{id:r.id,values:plan.values});conflicts.push(...plan.conflicts);
    });
    let studentCount=0,sessionCount=0;
    records.forEach((record,id)=>{
      validate_(id,record.values);
      if(id.startsWith('student:'))studentCount++;else{
        sessionCount++;if(record.values.studentIds.some(s=>!records.has('student:'+s)))fail_('RECORD','جلسة تشير إلى طالب غير موجود في مساحة الأخصائية.');
      }
    });
    if(studentCount>5000||sessionCount>30000)fail_('LIMIT','المساحة تتجاوز الحد المسموح.');
    // Journal full state FIRST. A lost response retries by immutable IDs without duplicate rows.
    writeData_(data,account.id,records,stamp);
    writeGrid_(grid,account,records,stamp,conflicts);
    SpreadsheetApp.flush();
    // Capture edits typed during the request as next remote state, never erase them with a row snapshot.
    const latest=grid_(accounts);
    latest.rows.filter(r=>r.account.id===account.id).forEach(row=>{
      if(!row.id)fail_('BUSY','أُضيف صف أثناء المزامنة. أعد المحاولة.');
      const rec=records.get('student:'+row.id);
      if(!rec)fail_('BUSY','تغيّرت سجلات الورقة. أعد المحاولة.');
      Object.assign(rec.values,row.values);
    });
    writeData_(data_(),account.id,records,stamp);
    return Object.assign(identity,{records:Array.from(records.values()),conflicts});
  }finally{lock.releaseLock();}
}
