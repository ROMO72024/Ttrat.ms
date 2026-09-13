(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.Ghars = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const ZONE = 'Asia/Gaza';
  const TYPES = ['speech', 'behavior'];
  const dtf = new Intl.DateTimeFormat('en-CA', {timeZone:ZONE,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
  function parts(ms) {const p={};dtf.formatToParts(new Date(ms)).forEach(x=>p[x.type]=x.value);return p;}
  function nowLocal(ms=Date.now()) {const p=parts(ms);return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;}
  function today() {return nowLocal().slice(0,10);}
  function id() {if(typeof crypto!=='undefined'&&crypto.randomUUID)return crypto.randomUUID();return Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,12);}
  function emptyState() {return {version:1,students:[],sessions:[],reminders:[],settings:{defaultReminder:-1}};}
  function validLocal(s) {
    if(typeof s!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(s))return false;
    const d=new Date(s+':00Z');return Number.isFinite(d.getTime())&&d.toISOString().slice(0,16)===s&&Number(s.slice(0,4))>=2000&&Number(s.slice(0,4))<=2100;
  }
  function toEpoch(s) {
    if(!validLocal(s))return -1;
    if(typeof Native!=='undefined'&&Native.toEpoch) {const n=Number(Native.toEpoch(s));return Number.isFinite(n)?n:-1;}
    // Match all nearby UTC offsets. A skipped spring-forward time is invalid;
    // for an autumn repeated hour choose the first instant, matching java.time.
    const nominal=Date.parse(s+':00Z'), candidates=[];
    const offsets=new Set();
    for(const delta of [-86400000,0,86400000]) {const t=nominal+delta;offsets.add(Date.parse(nowLocal(t)+':00Z')-t);}
    offsets.forEach(offset=>{const t=nominal-offset;if(nowLocal(t)===s)candidates.push(t);});
    return candidates.length?Math.min(...candidates):-1;
  }
  function addDays(date,n) {const d=new Date(date.slice(0,10)+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10);}
  function weekday(date) {return new Date(date.slice(0,10)+'T12:00:00Z').getUTCDay();}
  function weekStart(date) {return addDays(date,-weekday(date));}
  function requireThat(ok,message) {if(!ok)throw new Error(message);}
  function textField(s,key,max=2000,required=false) {requireThat(typeof s[key]==='string'&&s[key].length<=max&&(!required||s[key].trim().length>0),'بيانات غير صالحة: '+key);}
  function integer(v,min,max) {return Number.isInteger(v)&&v>=min&&v<=max;}
  function safeId(value) {return typeof value==='string'&&/^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$/.test(value)&&!['constructor','prototype','__proto__'].includes(value);}
  function validateState(state) {
    requireThat(state&&typeof state==='object'&&state.version===1,'صيغة الملف أو إصداره غير مدعوم');
    requireThat(Array.isArray(state.students)&&state.students.length<=5000&&Array.isArray(state.sessions)&&state.sessions.length<=30000&&Array.isArray(state.reminders)&&state.reminders.length<=2000,'حجم البيانات غير صالح');
    requireThat(state.settings&&integer(state.settings.defaultReminder,-1,10080),'إعداد التذكير غير صالح');
    const studentIds=new Set();
    for(const s of state.students) {
      requireThat(s&&typeof s==='object','ملف طالب غير صالح');requireThat(safeId(s.id),'معرف طالب غير صالح');requireThat(!studentIds.has(s.id),'معرف طالب مكرر');studentIds.add(s.id);
      textField(s,'name',200,true);textField(s,'classroom',200);textField(s,'phone',100);textField(s,'notes',20000);textField(s,'goals',20000);
      requireThat(['morning','evening'].includes(s.shift)&&typeof s.archived==='boolean','فترة الطالب غير صالحة');
      requireThat(s.plans&&TYPES.every(t=>s.plans[t]&&integer(s.plans[t].total,0,10000)&&integer(s.plans[t].weekly,0,50)),'خطة الجلسات غير صالحة');
    }
    const sessionIds=new Set();
    for(const s of state.sessions) {
      requireThat(s&&typeof s==='object','جلسة غير صالحة');requireThat(safeId(s.id),'معرف جلسة غير صالح');requireThat(!sessionIds.has(s.id),'معرف جلسة مكرر');sessionIds.add(s.id);
      requireThat(TYPES.includes(s.type)&&['scheduled','completed','cancelled'].includes(s.status),'نوع أو حالة الجلسة غير صالح');
      requireThat(validLocal(s.start)&&toEpoch(s.start)>0,'موعد غير صالح وفق توقيت غزة');
      requireThat(integer(s.duration,5,480)&&integer(s.reminderMinutes,-1,10080),'مدة الجلسة أو تذكيرها غير صالح');
      requireThat(Array.isArray(s.studentIds)&&s.studentIds.length>0&&s.studentIds.length<=50&&new Set(s.studentIds).size===s.studentIds.length&&s.studentIds.every(x=>studentIds.has(x)),'طلاب الجلسة غير صالحين');
      textField(s,'note',20000);textField(s,'seriesId',120);
      requireThat(s.attendance&&typeof s.attendance==='object'&&!Array.isArray(s.attendance),'سجل الحضور غير صالح');
      for(const [sid,a] of Object.entries(s.attendance)) {requireThat(s.studentIds.includes(sid)&&a&&['present','absent','excused'].includes(a.status),'حالة حضور غير صالحة');textField(a,'note',20000);requireThat(integer(a.progress,0,5),'تقييم الجلسة غير صالح');}
      requireThat(s.status!=='completed'||s.studentIds.every(sid=>Object.prototype.hasOwnProperty.call(s.attendance,sid)),'يجب تسجيل حضور كل طالب عند تسليم الجلسة');
    }
    const reminderIds=new Set();
    for(const r of state.reminders) {requireThat(r&&typeof r==='object','تذكير غير صالح');requireThat(safeId(r.id),'معرف تذكير غير صالح');requireThat(!reminderIds.has(r.id),'معرف تذكير مكرر');reminderIds.add(r.id);textField(r,'title',300,true);requireThat(validLocal(r.at)&&toEpoch(r.at)>0&&typeof r.enabled==='boolean','موعد التذكير غير صالح');}
    return state;
  }
  function studentStats(state,sid,type) {
    const student=state.students.find(s=>s.id===sid), types=type?[type]:TYPES;
    const result={total:0,completed:0,remaining:0,weekly:0,scheduled:0,absent:0};
    if(!student)return result;
    for(const t of types) {if(!TYPES.includes(t))continue;result.total+=student.plans[t].total;result.weekly+=student.plans[t].weekly;}
    for(const s of state.sessions) {
      if(!s.studentIds.includes(sid)||!types.includes(s.type))continue;
      if(s.status==='scheduled')result.scheduled++;
      const a=s.attendance[sid];
      if(s.status==='completed'&&a) {if(a.status==='present')result.completed++;if(a.status==='absent')result.absent++;}
    }
    // Sum per-type remaining so excess sessions in one therapy never consume the other plan.
    result.remaining=types.reduce((sum,t)=>{if(!TYPES.includes(t))return sum;const completed=state.sessions.filter(s=>s.type===t&&s.status==='completed'&&s.studentIds.includes(sid)&&s.attendance[sid]?.status==='present').length;return sum+Math.max(0,student.plans[t].total-completed);},0);
    return result;
  }
  function conflicts(state,candidate,ignoreId) {
    if(candidate.status==='cancelled')return [];
    const a=toEpoch(candidate.start),b=a+candidate.duration*60000;
    if(a<0)return [];
    return state.sessions.filter(s=>{if(s.id===ignoreId||s.status==='cancelled')return false;const c=toEpoch(s.start);return a<c+s.duration*60000&&c<b;});
  }
  function generateSessions(draft,weekdays,weeks) {
    requireThat(integer(weeks,1,26),'اختاري تكرارًا بين أسبوع و٢٦ أسبوعًا');
    requireThat(Array.isArray(weekdays)&&weekdays.length>0&&weekdays.every(x=>integer(x,0,6)),'اختاري يومًا واحدًا على الأقل');
    requireThat(validLocal(draft.start),'موعد البداية غير صالح');
    const seriesId=weeks>1||weekdays.length>1?id():'';
    const result=[];
    for(let i=0;i<weeks*7;i++) {const date=addDays(draft.start,i);if(!weekdays.includes(weekday(date)))continue;const start=date+draft.start.slice(10);requireThat(toEpoch(start)>0,'يتعذر إنشاء موعد أثناء تغير التوقيت الصيفي: '+start);result.push({...JSON.parse(JSON.stringify(draft)),id:id(),start,seriesId,attendance:{},status:'scheduled'});}
    return result;
  }
  function alarmItems(state,now=Date.now()) {
    const result=[];
    for(const s of state.sessions) {
      if(s.status!=='scheduled')continue;
      const start=toEpoch(s.start);
      const detail='موعد الجلسة '+s.start.slice(11)+' • افتحي غرس للتفاصيل';
      // Start is automatic even without an advance reminder. Distinct IDs keep
      // dismissing/snoozing the advance event from consuming the start event.
      if(start>now)result.push({id:'start-session-'+s.id,title:s.type==='speech'?'حان موعد جلسة نطق':'حان موعد جلسة سلوك',detail,at:start});
      if(s.reminderMinutes>0) {
        const at=start-s.reminderMinutes*60000;
        if(at>now)result.push({id:'session-'+s.id,title:s.type==='speech'?'تذكير جلسة نطق':'تذكير جلسة سلوك',detail,at});
      }
    }
    for(const r of state.reminders) {const at=toEpoch(r.at);if(r.enabled&&at>now)result.push({id:'reminder-'+r.id,title:'تذكير خاص من غرس',detail:'افتحي التطبيق لعرض التذكير',at});}
    return result.sort((a,b)=>a.at-b.at);
  }
  function escapeHtml(value) {return String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
  return {ZONE,TYPES,emptyState,id,today,nowLocal,addDays,weekStart,weekday,toEpoch,validateState,studentStats,conflicts,generateSessions,alarmItems,escapeHtml};
});
