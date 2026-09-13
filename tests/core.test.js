const test = require('node:test');
const assert = require('node:assert/strict');
const G = require('../app/src/main/assets/core.js');
function student(id='a') {return {id,name:'طالب تجريبي',classroom:'البستان',shift:'morning',phone:'',notes:'',goals:'',archived:false,plans:{speech:{total:2,weekly:1},behavior:{total:4,weekly:2}}};}
function session(id='s',start='2030-05-05T09:00') {return {id,type:'speech',start,duration:30,studentIds:['a'],status:'scheduled',reminderMinutes:15,note:'',attendance:{},seriesId:''};}
function fixture() {const s=G.emptyState();s.students=[student()];return s;}
test('empty database and valid student round-trip',()=>{G.validateState(G.emptyState());const s=fixture();G.validateState(JSON.parse(JSON.stringify(s)));});
test('group attendance counts independently; absent and excused never consume credits',()=>{
 const s=fixture();s.students.push(student('b'),student('c'));s.sessions=[{...session(),studentIds:['a','b','c'],status:'completed',attendance:{a:{status:'present',note:'تقدم',progress:4},b:{status:'absent',note:'',progress:0},c:{status:'excused',note:'',progress:0}}}];G.validateState(s);
 assert.equal(G.studentStats(s,'a','speech').completed,1);assert.equal(G.studentStats(s,'a','speech').remaining,1);assert.equal(G.studentStats(s,'b','speech').remaining,2);assert.equal(G.studentStats(s,'b').absent,1);assert.equal(G.studentStats(s,'c').completed,0);
 s.sessions[0].status='scheduled';assert.equal(G.studentStats(s,'a').completed,0);assert.equal(G.studentStats(s,'a').scheduled,1);
});
test('excess in speech never consumes behavior budget; repeat completion is derived not decremented',()=>{
 const s=fixture();s.sessions=[1,2,3].map(n=>({...session(String(n)),status:'completed',attendance:{a:{status:'present',note:'',progress:2}}}));
 assert.equal(G.studentStats(s,'a').remaining,4);assert.equal(G.studentStats(s,'a').completed,3);assert.equal(G.studentStats(s,'a').weekly,3);assert.deepEqual(G.studentStats(s,'a'),G.studentStats(s,'a'));
 s.sessions[0].status='cancelled';assert.equal(G.studentStats(s,'a').completed,2);
});
test('specialist conflicts: partial and containing overlaps, adjacent appointments allowed',()=>{
 const s=fixture();s.sessions=[session()];assert.equal(G.conflicts(s,session('x','2030-05-05T09:15')).length,1);assert.equal(G.conflicts(s,session('x','2030-05-05T08:45')).length,1);assert.equal(G.conflicts(s,session('x','2030-05-05T09:30')).length,0);assert.equal(G.conflicts(s,session(),'s').length,0);assert.equal(G.conflicts(s,{...session('x','2030-05-05T08:30'),duration:90}).length,1);s.sessions[0].status='cancelled';assert.equal(G.conflicts(s,session()).length,0);
});
test('recurrence preserves chosen days, unique IDs, exact count and separate attendance',()=>{
 const items=G.generateSessions(session(),[0,2,4],3);assert.equal(items.length,9);assert.equal(new Set(items.map(s=>s.id)).size,9);assert.equal(new Set(items.map(s=>s.seriesId)).size,1);assert.ok(items.every(s=>[0,2,4].includes(G.weekday(s.start))));assert.equal(items[0].start,'2030-05-05T09:00');assert.equal(items[8].start,'2030-05-23T09:00');items[0].attendance.a={status:'present'};assert.deepEqual(items[1].attendance,{});assert.throws(()=>G.generateSessions(session(),[],3));assert.throws(()=>G.generateSessions(session(),[0],27));
});
test('Gaza dates are calendar-valid and independent of workstation timezone',()=>{
 assert.equal(G.toEpoch('2030-02-30T09:00'),-1);assert.equal(G.toEpoch('2030-05-05T25:00'),-1);assert.equal(G.toEpoch('invalid'),-1);assert.equal(G.nowLocal(G.toEpoch('2030-05-05T09:00')),'2030-05-05T09:00');assert.equal(G.addDays('2028-02-28',1),'2028-02-29');assert.equal(G.weekStart('2030-05-09'),'2030-05-05');
});
test('reminders skip completed/cancelled sessions, retain automatic start without advance and hide private details',()=>{
 const s=fixture();s.sessions=[session(),{...session('completed','2030-05-06T09:00'),status:'completed'},{...session('cancelled','2030-05-06T10:00'),status:'cancelled'},{...session('off','2030-05-06T11:00'),reminderMinutes:-1}];s.reminders=[{id:'r',title:'معلومة حساسة',at:'2030-05-05T08:00',enabled:true}];const items=G.alarmItems(s,G.toEpoch('2030-05-05T07:00'));assert.equal(items.length,4);assert.equal(items[1].at,G.toEpoch('2030-05-05T08:45'));assert.equal(items[2].at,G.toEpoch('2030-05-05T09:00'));assert.equal(items[3].id,'start-session-off');assert.ok(!JSON.stringify(items).includes('معلومة حساسة'));assert.ok(!JSON.stringify(items).includes('طالب تجريبي'));assert.equal(G.alarmItems(s,G.toEpoch('2030-05-06T11:01')).length,0);
});
test('30-minute, one-day and 20-hour advance reminders each retain an independent start alarm',()=>{
 for(const lead of [30,1440,1200]) {const s=fixture();s.sessions=[{...session(),reminderMinutes:lead}];const start=G.toEpoch(s.sessions[0].start), advance=start-lead*60000;
   const alarms=G.alarmItems(s,advance-1);assert.equal(alarms.length,2);assert.deepEqual(alarms.map(a=>a.at),[advance,start]);assert.equal(new Set(alarms.map(a=>a.id)).size,2);
   const remaining=G.alarmItems(s,advance+1);assert.equal(remaining.length,1);assert.equal(remaining[0].id,'start-session-s');assert.equal(remaining[0].at,start);
 }
});
test('zero or disabled advance produces exactly one automatic start alarm',()=>{
 for(const lead of [-1,0]) {const s=fixture();s.sessions=[{...session(),reminderMinutes:lead}];const at=G.toEpoch(s.sessions[0].start);const alarms=G.alarmItems(s,at-1);assert.equal(alarms.length,1);assert.equal(alarms[0].id,'start-session-s');assert.equal(alarms[0].at,at);assert.equal(G.alarmItems(s,at).length,0);}
});
test('start alarms follow rescheduling and vanish on cancellation or completion',()=>{
 const s=fixture();s.sessions=[session()];const now=G.toEpoch('2030-05-04T00:00');s.sessions[0].start='2030-05-05T10:00';let alarms=G.alarmItems(s,now);assert.equal(alarms.length,2);assert.equal(alarms[1].at,G.toEpoch('2030-05-05T10:00'));
 for(const status of ['cancelled','completed']){s.sessions[0].status=status;assert.equal(G.alarmItems(s,now).length,0);}
});
test('recurring group sessions get one pair of alarms per occurrence, not per child',()=>{
 const s=fixture();s.students.push(student('b'),student('c'));s.sessions=G.generateSessions({...session(),studentIds:['a','b','c']},[0,2],2);const alarms=G.alarmItems(s,G.toEpoch('2030-05-01T00:00'));assert.equal(s.sessions.length,4);assert.equal(alarms.length,8);assert.equal(new Set(alarms.map(a=>a.id)).size,8);
});
test('backup rejects duplicate/orphan IDs, invalid completion and dangerous numeric inputs',()=>{
 let s=fixture();s.students.push(student());assert.throws(()=>G.validateState(s));s=fixture();s.sessions=[{...session(),studentIds:['missing']}];assert.throws(()=>G.validateState(s));s=fixture();s.sessions=[{...session(),status:'completed'}];assert.throws(()=>G.validateState(s));s=fixture();s.students[0].plans.speech.total=-1;assert.throws(()=>G.validateState(s));s=fixture();s.sessions=[{...session(),duration:NaN}];assert.throws(()=>G.validateState(s));s=fixture();s.version=99;assert.throws(()=>G.validateState(s));
});
test('escapes all HTML-sensitive user content',()=>{assert.equal(G.escapeHtml('<img onerror="x"> & \'مرحبا\''),'&lt;img onerror=&quot;x&quot;&gt; &amp; &#39;مرحبا&#39;');});
test('import cannot use prototype names as attendance dictionary keys',()=>{
 for(const bad of ['__proto__','constructor','prototype','bad/id','<script>']) {const s=fixture();s.students[0].id=bad;assert.throws(()=>G.validateState(s));}
});
test('sessions that cross midnight still occupy specialist time next day',()=>{
 const s=fixture();s.sessions=[{...session('late','2030-05-05T23:45'),duration:60}];assert.equal(G.conflicts(s,session('early','2030-05-06T00:15')).length,1);assert.equal(G.conflicts(s,session('next','2030-05-06T00:45')).length,0);
});
