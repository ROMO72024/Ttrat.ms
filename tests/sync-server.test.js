const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const clone=x=>JSON.parse(JSON.stringify(x));
function fixture(){
  let uuid=0;const properties={GHARS_BOOK_ID:'book',GHARS_SERVER_ID:'school-one'},cache={},sheets={};
  class Range{
    constructor(sheet,row,col,height=1,width=1){Object.assign(this,{sheet,row,col,height,width});}
    getValues(){return Array.from({length:this.height},(_,y)=>Array.from({length:this.width},(_,x)=>this.sheet.rows[this.row+y-1]?.[this.col+x-1]??''));}
    getFormulas(){return Array.from({length:this.height},(_,y)=>Array.from({length:this.width},(_,x)=>this.sheet.formulas[(this.row+y)+':'+(this.col+x)]||''));}
    setValues(rows){assert.equal(rows.length,this.height);rows.forEach((row,y)=>{assert.equal(row.length,this.width);row.forEach((v,x)=>new Range(this.sheet,this.row+y,this.col+x).setValue(v));});return this;}
    setValue(value){const row=this.sheet.rows[this.row-1]||(this.sheet.rows[this.row-1]=[]),key=this.row+':'+this.col;delete this.sheet.formulas[key];if(typeof value==='string'&&value.startsWith("'"))value=value.slice(1);else if(typeof value==='string'&&value.startsWith('='))this.sheet.formulas[key]=value;row[this.col-1]=value;return this;}
    setNumberFormat(){return this;}setBackground(){return this;}setFontColor(){return this;}setFontWeight(){return this;}setWrap(){return this;}setDataValidation(){return this;}
    protect(){const self={getDescription:()=>'',setDescription:()=>self,addEditor(){},removeEditors(){},getEditors:()=>[],canDomainEdit:()=>false};return self;}
  }
  class Sheet{
    constructor(name,headers){this.name=name;this.rows=[clone(headers)];this.formulas={};this.maxRows=1000;this.maxCols=26;}
    getName(){return this.name;}getLastRow(){let n=this.rows.length;while(n>0&&!(this.rows[n-1]||[]).some(x=>x!==''&&x!==undefined))n--;return n;}
    getRange(row,col,height,width){if(typeof row==='string')return new Range(this,1,1,this.maxRows,1);return new Range(this,row,col,height,width);}
    getMaxRows(){return this.maxRows;}getMaxColumns(){return this.maxCols;}insertRowsAfter(_,n){this.maxRows+=n;}insertColumnsAfter(_,n){this.maxCols+=n;}
    setRightToLeft(){return this;}setFrozenRows(){return this;}setRowHeight(){}setColumnWidth(){}hideColumns(){}hideSheet(){}getProtections(){return [];}
  }
  const book={getId:()=> 'book',getSheetByName:n=>sheets[n],insertSheet:n=>(sheets[n]=new Sheet(n,[]))};
  const validation={requireCheckbox(){return this;},requireNumberBetween(){return this;},requireValueInList(){return this;},setAllowInvalid(){return this;},build(){return this;}};
  const context={console,Date,Map,Set,JSON,Object,Array,String,Number,Boolean,Math,Error,RegExp,
    SpreadsheetApp:{openById:id=>{assert.equal(id,'book');return book;},getActiveSpreadsheet:()=>book,flush(){},getUi:()=>({alert(){}}),newDataValidation:()=>validation,ProtectionType:{RANGE:'range'}},
    PropertiesService:{getScriptProperties:()=>({getProperty:k=>properties[k]||null,setProperty:(k,v)=>{properties[k]=v;}})},
    LockService:{getScriptLock:()=>({waitLock(){},tryLock:()=>true,releaseLock(){}})},
    Utilities:{getUuid:()=> (++uuid).toString(16).padStart(32,'0')},
    CacheService:{getScriptCache:()=>({get:k=>cache[k],put:(k,v)=>{cache[k]=v;},remove:k=>{delete cache[k];}})},
    Session:{getEffectiveUser:()=>({getEmail:()=> 'owner@example.test'})},
    ContentService:{MimeType:{JSON:'json'},createTextOutput:text=>({text,setMimeType(){return this;}})}
  };
  vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'../google-apps-script/Code.gs'),'utf8'),context);
  const constants=vm.runInContext('GHARS_SYNC',context);
  for(const [name,headers] of [[constants.accounts,constants.accountHeaders],[constants.students,constants.studentHeaders],[constants.data,constants.dataHeaders]])sheets[name]=new Sheet(name,headers);
  const accounts=sheets[constants.accounts],students=sheets[constants.students],data=sheets[constants.data];
  accounts.rows.push(['accountA','الأخصائية الأولى','001234',true],['accountB','الأخصائية الثانية','B56789',true]);
  const request=(body,device='phone1')=>clone(context.handleRequest_({protocol:2,deviceId:device,code:'001234',...clone(body)}));
  const login=(code='001234',device='phone1')=>request({action:'login',code},device);
  const sync=(records,code='001234',accountId='accountA',device='phone1')=>request({action:'sync',code,serverId:'school-one',accountId,records},device);
  return {context,constants,accounts,students,data,properties,request,login,sync};
}
function student(id='s1',changes={}){return {id:'student:'+id,base:null,values:{name:'أحمد محمد علي',speechTotal:20,behaviorTotal:0,classroom:'روضة',shift:'morning',phone:'0591234567',speechWeekly:2,behaviorWeekly:0,archived:false,notes:'ملاحظة خاصة',goals:'هدف المتابعة',...changes}};}
function session(id='x1',changes={}){return {id:'session:'+id,base:null,values:{type:'speech',start:'2026-10-01T09:00',duration:30,reminderMinutes:10,status:'completed',note:'متابعة',seriesId:'',studentIds:['s1'],attendance:{s1:{status:'present',note:'جيد',progress:3}},...changes}};}
function editing(record,changes={}){return {id:record.id,base:clone(record.values),values:{...clone(record.values),...changes}};}
test('setup repeats without deleting students or changing existing plain codes',()=>{const f=fixture();f.sync([student()]);const count=f.students.getLastRow();f.context.setupGharsSync();f.context.setupGharsSync();assert.equal(f.students.getLastRow(),count);assert.equal(f.accounts.rows[1][2],'001234');});
test('same code on two phones identifies the same immutable workspace; other code is isolated',()=>{const f=fixture();assert.equal(f.login('001234','phone1').account.id,f.login('001234','phone2').account.id);f.sync([student(),session()]);assert.equal(f.sync([],'001234','accountA','phone2').records.length,2);assert.equal(f.sync([],'B56789','accountB','phone3').records.length,0);assert.throws(()=>f.sync([],'B56789','accountA'),/مساحة مختلفة/);});
test('changing a plain code retains the workspace and data, rejects old code',()=>{const f=fixture();f.sync([student()]);f.accounts.rows[1][2]='NEW5678';assert.throws(()=>f.login('001234'),/الكود غير صحيح/);assert.equal(f.login('NEW5678').account.id,'accountA');const r=f.sync([],'NEW5678','accountA');assert.equal(r.records[0].values.name,'أحمد محمد علي');assert.equal(f.students.rows[1][2],'NEW5678');});
test('disabled and duplicate codes fail without returning private data',()=>{const f=fixture();f.accounts.rows[1][3]=false;assert.throws(()=>f.login(),/الكود غير صحيح/);f.accounts.rows[2][2]='001234';assert.throws(()=>f.login(),/مكرر/);});
test('repeated 150-student import and lost-response retry never duplicate IDs',()=>{const f=fixture(),records=Array.from({length:150},(_,i)=>student('s'+i,{name:'طالب اختبار '+i}));const first=f.sync(records);assert.equal(first.records.length,150);const retried=f.sync(records);assert.equal(retried.records.length,150);assert.equal(retried.conflicts.length,0);assert.equal(f.students.getLastRow(),151);assert.equal(f.data.getLastRow(),151);});
test('completed attendance is derived once from shared session IDs, not overwritten by a second empty phone',()=>{const f=fixture();f.sync([student(),session()]);assert.equal(f.students.rows[1][12],1);assert.equal(f.students.rows[1][14],19);f.sync([],'001234','accountA','phone2');assert.equal(f.students.rows[1][12],1);f.sync([student(),session()]);assert.equal(f.students.rows[1][12],1);});
test('accountant adds code + full name + 20 credits; defaults safely reach the correct account',()=>{const f=fixture();f.students.rows.push(['','','B56789','مريم أحمد محمود',20,'','','','','','',false]);const b=f.sync([],'B56789','accountB');assert.equal(b.records.length,1);assert.equal(b.records[0].values.speechTotal,20);assert.equal(b.records[0].values.notes,'');assert(f.students.rows[1][1].startsWith('st_'));assert.equal(f.sync([]).records.length,0);});
test('phone weekly-plan edit and accountant purchased-credit edit merge independently',()=>{const f=fixture(),base=f.sync([student()]).records[0];f.students.rows[1][4]=30;const r=f.sync([editing(base,{speechWeekly:3})]);assert.equal(r.records[0].values.speechTotal,30);assert.equal(r.records[0].values.speechWeekly,3);assert.equal(r.conflicts.length,0);});
test('competing edits to the same field remain conflicts; no last-clock-wins overwrite',()=>{const f=fixture(),base=f.sync([student()]).records[0];f.students.rows[1][4]=30;const r=f.sync([editing(base,{speechTotal:25})]);assert.equal(r.records[0].values.speechTotal,30);assert.deepEqual(r.conflicts,[{id:'student:s1',field:'speechTotal'}]);assert.equal(f.students.rows[1][4],30);});
test('second phone session edit merges, repeated attendance never consumes extra credit',()=>{const f=fixture(),start=f.sync([student(),session()]);const base=start.records.find(r=>r.id==='session:x1');f.sync([editing(base,{note:'ملاحظة هاتف ثانٍ'})],'001234','accountA','phone2');const r=f.sync([editing(base,{duration:45})]);const s=r.records.find(r=>r.id==='session:x1');assert.equal(s.values.note,'ملاحظة هاتف ثانٍ');assert.equal(s.values.duration,45);assert.equal(f.students.rows[1][12],1);});
test('malformed / orphan session or duplicate IDs cannot mutate stored data',()=>{const f=fixture();f.sync([student()]);const before=JSON.stringify(f.data.rows);assert.throws(()=>f.sync([session('orphan',{studentIds:['missing'],attendance:{missing:{status:'present',note:'',progress:0}}})]),/غير موجود/);assert.throws(()=>f.sync([student(),student()]),/مكرر/);assert.equal(JSON.stringify(f.data.rows),before);});
test('physical sheet-row deletion is repaired from stable journal; archive is the supported removal',()=>{const f=fixture();const base=f.sync([student()]).records[0];f.students.rows.splice(1,1);assert.equal(f.sync([]).records.length,1);assert.equal(f.students.rows[1][1],'s1');f.sync([editing(base,{archived:true})]);assert.equal(f.students.rows[1][11],true);});
test('same personal name with different IDs is never silently merged',()=>{const f=fixture();const r=f.sync([student('one'),student('two')]);assert.equal(r.records.length,2);});
test('new account IDs are assigned once and not derived from names/codes',()=>{const f=fixture();f.accounts.rows.push(['','أخصائية ثالثة','THIRD123',true]);const first=f.login('THIRD123').account.id;f.accounts.rows[3][2]='OTHER123';assert.equal(f.login('OTHER123').account.id,first);});
test('formula injection through app strings remains literal; formulas typed in registration are rejected',()=>{const f=fixture();f.sync([student('s1',{name:'=IMPORTXML("url")',phone:'+970591234567'})]);assert.equal(f.students.rows[1][3],'=IMPORTXML("url")');assert.equal(f.students.formulas['2:4'],undefined);f.students.getRange(2,4).setValue('=1+1');assert.throws(()=>f.sync([]),/صيغاً/);});
test('API endpoint neither exposes credentials nor names without an active code',()=>{const f=fixture();f.sync([student()]);assert(!f.context.doGet().text.includes('أحمد'));const denied=JSON.parse(f.context.doPost({postData:{contents:JSON.stringify({protocol:2,action:'sync',deviceId:'bad',code:'XXXXX',records:[]})}}).text);assert.equal(denied.ok,false);assert.equal(denied.code,'AUTH');assert(!JSON.stringify(denied).includes('أحمد'));});
test('per-device failed-login throttling and invalid giant JSON cells fail safely',()=>{const f=fixture();for(let i=0;i<10;i++)assert.throws(()=>f.login('badcode','brute'));assert.throws(()=>f.login('badcode','brute'),/محاولات كثيرة/);const huge=session('large',{note:'x'.repeat(19000),attendance:{s1:{status:'present',note:'y'.repeat(30000),progress:0}}});assert.throws(()=>f.sync([student(),huge]),/طويل جداً/);});
