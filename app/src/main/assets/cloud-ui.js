(function () {
  'use strict';
  const U=window.GharsUI, e=U.e, native=!!window.Native;
  const labels={name:'اسم الطالب',speechTotal:'إجمالي النطق',behaviorTotal:'إجمالي السلوك',classroom:'الصف',shift:'الفترة',phone:'الهاتف',speechWeekly:'النطق أسبوعياً',behaviorWeekly:'السلوك أسبوعياً',archived:'الأرشفة',notes:'ملاحظات الملف',goals:'الأهداف',start:'الموعد',duration:'المدة',type:'نوع الجلسة',reminderMinutes:'التذكير المسبق',status:'حالة الجلسة',note:'ملاحظات الجلسة',seriesId:'مجموعة التكرار',studentIds:'الطلاب المشاركون',attendance:'تسجيل الحضور'};
  let reviewSnapshot=[];
  let loginBusy=false,loginError='';
  function needsLogin(){return native&&!Native.cloudIsLoggedIn();}
  function loginPage(){
    return `<main class="cloud-login"><section class="cloud-login-card"><img src="logo.png" alt="شعار غرس"><p class="eyebrow">مدرسة وروضة غرس الحديثة</p><h1>أهلًا بكِ في مساحتك</h1><p class="subtitle">ملفاتك وجلساتك، في مكان واحد.</p><form id="cloud-login-form"><label for="cloud-login-code">كود الأخصائية</label><input id="cloud-login-code" name="code" type="password" minlength="5" maxlength="32" autocomplete="off" dir="ltr" placeholder="أدخلي الكود" required ${loginBusy?'disabled':''}><small>أدخلي الكود الذي أعطتكِ إياه إدارة غرس.</small>${loginError?`<p class="cloud-error" role="alert">${e(loginError)}</p>`:''}<button type="submit" class="button primary" ${loginBusy?'disabled':''}>${loginBusy?'جارٍ التحقق…':'دخول إلى مساحتي'}</button></form><p class="cloud-footnote">تحتاجين الإنترنت عند الدخول لأول مرة. بعدها يمكنك فتح مساحتك المحفوظة والعمل دون إنترنت.</p></section></main>`;
  }
  function status(){if(!native)return {conflicts:[],students:0};try{return JSON.parse(Native.cloudStatus());}catch(_){return {error:'تعذر قراءة حالة المزامنة',conflicts:[]};}}
  function call(action){const error=Native.cloudAction(action);if(error)throw Error(error);U.redraw();}
  function value(x){return x&&typeof x==='object'?JSON.stringify(x,null,2):typeof x==='boolean'?(x?'مؤرشف':'نشط'):x==='morning'?'صباحية':x==='evening'?'مسائية':String(x==null?'':x)||'فارغ';}
  function lastDate(x){if(!x)return 'لم تتم المزامنة بعد';try{return new Date(x).toLocaleString('ar-EG',{timeZone:'Asia/Gaza'});}catch(_){return x;}}
  function banner(){
    if(!native||needsLogin())return '';
    const s=status(),conflicts=(s.conflicts||[]).length,overlaps=(s.overlaps||[]).length;
    if(!s.configured)return '';
    const text=s.busy?'جارٍ الاتصال بالشيت…':!s.enabled?'المزامنة متوقفة · فعّليها من الربط':s.error?'تعذرت آخر محاولة: '+s.error:conflicts?'هناك اختلافات تحتاج مراجعتك':overlaps?'هناك مواعيد متداخلة؛ راجعيها من إعدادات الربط':s.pending?Number(s.pending).toLocaleString('ar-EG')+' سجلات تنتظر المزامنة':!s.lastSync?'بانتظار أول تأكيد من الشيت':'آخر تأكيد من الشيت '+lastDate(s.lastSync);
    return `<div class="cloud-strip ${s.error||conflicts?'cloud-warning':''}" role="status"><span class="cloud-light ${s.busy?'cloud-pulse':''}"></span><span class="grow">${e(s.accountName||'')} · ${e(text)}</span>${s.enabled?U.button('جلب الشيت','cloud-pull','primary small','','download'):''}${U.button('الربط','go-settings','ghost small','','settings')}${U.button('خروج','cloud-logout','ghost small','','')}</div>`;
  }
  function panel(){
    const s=status(),count=(s.conflicts||[]).length;
    return `<section class="panel cloud-panel"><div class="cloud-heading"><div><p class="eyebrow">${e(s.accountName||'مساحة الأخصائية')}</p><h2>مساحتك واحدة… على أكثر من هاتف</h2><p>الطلاب والخطط والجلسات والحضور تتزامن داخل مساحة الكود نفسه. التذكيرات الشخصية محلية على هذا الهاتف.</p></div><img src="logo.png" alt="شعار غرس"></div><div class="cloud-stats"><div><small>آخر مزامنة ناجحة</small><strong>${e(lastDate(s.lastSync))}</strong></div><div><small>سجلات تنتظر المزامنة</small><strong>${Number(s.pending||0).toLocaleString('ar-EG')}</strong></div><div><small>ملفات أكد الشيت حفظها</small><strong>${s.syncedStudents>=0?Number(s.syncedStudents).toLocaleString('ar-EG'):'لم يؤكد بعد'}</strong></div><div><small>تعارضات للمراجعة</small><strong>${count.toLocaleString('ar-EG')}</strong></div></div>${s.error?`<p class="cloud-error" role="alert">${e(s.error)}${s.errorCode?' · '+e(s.errorCode):''}</p>`:''}${s.message?`<p class="settings-note">${e(s.message)}</p>`:''}${s.busy?'<p class="settings-note">جارٍ الاتصال… يمكنك متابعة العمل المحلي.</p>':''}<div class="setting-actions cloud-actions">${s.verified&&!s.enabled?U.button(s.lastSync?'استئناف المزامنة':'رفع الأسماء الحالية وتفعيل المزامنة','cloud-enable','primary small','','upload'):''}${s.enabled?U.button('جلب تعديلات الشيت','cloud-pull','primary small','','download')+U.button('رفع تعديلات الهاتف','cloud-sync','small','','upload')+U.button('إيقاف المزامنة','cloud-pause','ghost small','',''):''}${count?U.button('مراجعة الاختلافات','cloud-review','secondary small','','note'):''}${s.overlaps?.length?U.button('مراجعة المواعيد المتداخلة','cloud-overlaps','secondary small','','calendar'):''}${U.button('تسجيل الخروج / تبديل الأخصائية','cloud-logout','small','','')}</div><p class="settings-note">${native?'تُرفع تعديلات الهاتف تلقائياً عند الاتصال. أثناء فتح التطبيق نحاول جلب تعديلات الشيت كل ١٥ ثانية؛ زر الجلب لا يرسل قيم الهاتف. العمل دون إنترنت مستمر، وقد يتأخر التحديث في الخلفية بسبب قيود Android.':'معاينة فقط: الدخول بالكود والمزامنة متاحان داخل تطبيق Android.'}</p><div class="cloud-footnote">قبل أول رفع: صدّري نسخة احتياطية، وتأكدي أن الكود يخص صاحبة الأسماء الحالية. تغيير الكود في الشيت لا يغيّر معرّف المساحة أو يمسح أسماءها. ملف الشيت يحتوي بيانات الطلاب والجلسات؛ لا تشاركيه للعامة.</div></section>`;
  }
  function review(){
    reviewSnapshot=status().conflicts||[];
    if(!reviewSnapshot.length){U.closeModal();U.refresh();U.notify('لا توجد اختلافات معلّقة.');return;}
    U.showModal('مراجعة الاختلافات',`<p class="explain">تغيّرت المعلومة نفسها في الهاتف والشيت. لم نحذف قيمة الهاتف ولم نستبدل قيمة المحاسب تلقائياً. اختاري لكل سطر القيمة الصحيحة؛ اختيار الهاتف يُرسل في المزامنة التالية.</p>${reviewSnapshot.map(c=>`<article class="cloud-conflict"><h3>${e(c.name)} <small>· ${e(labels[c.field]||c.field)}</small></h3><div class="cloud-choices"><div><small>على الهاتف</small><p>${e(value(c.local))}</p><button type="button" class="button small secondary" data-action="cloud-resolve" data-id="${e(c.id)}" data-field="${e(c.field)}" data-choice="local">اعتماد الهاتف</button></div><div><small>في المساحة المشتركة</small><p>${e(value(c.remote))}</p><button type="button" class="button small" data-action="cloud-resolve" data-id="${e(c.id)}" data-field="${e(c.field)}" data-choice="remote">اعتماد الشيت</button></div></div></article>`).join('')}`,U.button('أراجع لاحقاً','modal-close','ghost','',''),null,false);
  }
  function action(name,data){
    if(!native){U.notify('الربط متاح داخل تطبيق Android.',true);return;}
    switch(name){
      case 'cloud-logout':U.confirmDialog('تسجيل الخروج؟','تبقى بيانات هذه المساحة محفوظة ولا تختلط بمساحة أخرى. تتوقف المزامنة حتى الدخول مجدداً.','تسجيل الخروج',()=>{const err=Native.cloudSignOut();if(err)throw Error(err);window.onCloudSignedOut();});break;
      case 'cloud-enable':{
        const s=status(); U.confirmDialog('تفعيل المزامنة؟',`ستُرفع ملفات هذه الأخصائية (${s.students||0} طالباً) والجلسات والحضور والملاحظات إلى مساحة الكود في الشيت، وتُجلب إضافات المحاسب والأجهزة الأخرى. هل حفظتِ نسخة احتياطية وتأكدتِ من صاحبة الكود؟`,'نعم، ارفعي وفعّلي',()=>{call('enable');U.notify('تفعّلت المزامنة؛ عند توفر الإنترنت يبدأ نقل البيانات.');});break;
      }
      case 'cloud-sync':call('sync');break;
      case 'cloud-pull':call('pull');break;
      case 'cloud-pause':U.confirmDialog('إيقاف المزامنة؟','لن تُحذف بيانات الهاتف أو الشيت. قد تكون المحاولة الجارية أرسلت بعض التعديلات بالفعل. سيستمر العمل المحلي حتى تعيدي التفعيل.','إيقاف',()=>{call('pause');U.refresh();});break;
      case 'cloud-review':review();break;
      case 'cloud-overlaps':{
        const list=status().overlaps||[];
        U.showModal('مواعيد تحتاج مراجعة',`<p class="explain">قد ينشئ هاتفان موعدَين متداخلَين أثناء انقطاع الاتصال. احتفظنا بالموعدين؛ افتحي أحدهما لتعديله أو إلغائه. تظهر هنا أول ٢٠ حالة.</p>${list.map(x=>`<article class="cloud-conflict"><p>${e(x.start)} ↔ ${e(x.otherStart)}</p>${U.button('الموعد الأول','session-detail','small',x.id,'calendar')}${U.button('الموعد الثاني','session-detail','small',x.otherId,'calendar')}</article>`).join('')||'<p>لا توجد تداخلات.</p>'}`,U.button('إغلاق','modal-close','ghost','',''));break;
      }
      case 'cloud-resolve':{
        const viewed=reviewSnapshot.find(c=>c.id===data.id&&c.field===data.field);
        if(!viewed)throw Error('أعيدي فتح قائمة الاختلافات.');
        const err=Native.cloudResolve(data.id,data.field,data.choice,JSON.stringify(viewed.remote));
        if(err)throw Error(err);U.refresh();review();break;
      }
    }
  }
  window.GharsCloud={panel,banner,action,needsLogin,loginPage,refreshPending:false};
  window.onCloudChanged=function(finished){
    if(needsLogin()){U.redraw();return;}
    try {
      if(U.modalOpen()){window.GharsCloud.refreshPending=true;U.redraw();}
      else {U.refresh();}
      if(finished){const s=status();if(s.error)U.notify(s.error,true);}
    }catch(err){U.notify(err.message||'تعذر تحديث حالة المزامنة',true);}
  };
  document.addEventListener('submit',event=>{
    if(event.target.id!=='cloud-login-form')return;event.preventDefault();if(loginBusy)return;
    const form=event.target;if(!form.reportValidity())return;
    const code=form.elements.namedItem('code').value.trim();
    try {
      // The native bridge reads the bundled school address, or the saved connection.
      // Reading it here also works offline; the user supplies only her code.
      const info=JSON.parse(Native.cloudLoginInfo());
      const url=info&&typeof info.url==='string'?info.url.trim():'';
      if(!url){loginError='اتصال المدرسة غير مُعدّ في هذه النسخة. تواصلي مع الإدارة لتحديث التطبيق.';U.redraw();return;}
      loginBusy=true;loginError='';Native.cloudPrepareLogin(url,code);U.redraw();
    }catch(_){
      loginBusy=false;loginError='تعذر بدء تسجيل الدخول. أغلقي التطبيق وافتحيه مجددًا، ثم حاولي مرة أخرى.';U.redraw();
    }
  });
  window.onCloudLogin=function(ok,summary){
    loginBusy=false;if(!ok){loginError=summary.message||'تعذر الدخول';U.redraw();return;}
    loginError='';U.redraw();const legacy=Number(summary.legacyStudents||0);
    U.confirmDialog('مساحة '+summary.accountName,legacy?
      'هذا الهاتف يحتوي '+legacy+' طالباً من النسخة السابقة. هل هذه الأسماء تخص «'+summary.accountName+'»؟ تأكيدك يربطها بهذه المساحة محلياً ويحفظ نسخة أمان؛ لن تُرفع قبل الضغط على زر رفع الأسماء. إن كان الكود خطأ فاختاري تراجع.':
      (summary.offline?'سيتم فتح البيانات المحفوظة لهذه الأخصائية دون إنترنت.':'سيتم فتح مساحة هذه الأخصائية. بيانات أي أخصائية أخرى على الهاتف تبقى في مساحتها المنفصلة.'),
      legacy?'نعم، هذه صاحبة الأسماء':'فتح المساحة',()=>{
        const err=Native.cloudActivate();if(err)throw Error(err);window.GharsCloud.refreshPending=false;U.refresh();
        U.notify(legacy?'الأسماء محفوظة. خذي نسخة احتياطية ثم فعّلي الرفع من الإعدادات.':summary.offline?'فُتحت المساحة المحفوظة دون إنترنت.':'تم الدخول؛ جارٍ تحديث المساحة عند توفر الاتصال.');
      });
  };
  window.onCloudSignedOut=function(){loginBusy=false;loginError='';reviewSnapshot=[];U.clear();};
  U.redraw();
})();
