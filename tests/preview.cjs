// Optional local visual smoke. Requires Playwright; not part of the dependency-free Node test suite.
const {chromium}=require('playwright');
const {pathToFileURL}=require('node:url');
const path=require('node:path');
(async()=>{
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:1});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
    let logged=false,enabled=false;
    const state={version:1,students:[{id:'s1',name:'أحمد محمد علي — اختبار',classroom:'البستان',shift:'morning',phone:'',notes:'',goals:'',archived:false,plans:{speech:{total:20,weekly:2},behavior:{total:0,weekly:0}}}],sessions:[],reminders:[],settings:{defaultReminder:-1},_cloud:{serverId:'school',accountId:'account'}};
    window.Native={cloudIsLoggedIn:()=>logged,cloudLoginInfo:()=>JSON.stringify({url:'https://script.google.com/macros/s/example/exec'}),cloudPrepareLogin:()=>setTimeout(()=>window.onCloudLogin(true,{accountName:'أ. مريم · مساحة تجريبية',legacyStudents:1,offline:false}),50),cloudActivate:()=>{logged=true;return '';},cloudSignOut:()=>{logged=false;return '';},cloudStatus:()=>JSON.stringify({accountName:'أ. مريم · مساحة تجريبية',configured:true,verified:true,enabled,pending:1,students:1,conflicts:[],lastSync:''}),cloudAction:a=>{if(a==='enable')enabled=true;return '';},loadState:()=>logged?JSON.stringify(state):'{"error":"التطبيق مقفل"}',getStatus:()=>JSON.stringify({exact:true,notifications:true,fullScreen:true,batteryOptimized:false,alarmVolume:7}),syncAlarms:()=>'',toEpoch:s=>Date.parse(s+'+03:00')};
  });
  await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/index.html')).href);
  await page.screenshot({path:path.resolve(__dirname,'../../login-preview.png'),fullPage:true});
  await page.locator('#cloud-login-code').fill('001234');await page.locator('#cloud-login-form button[type=submit]').click();
  await page.locator('[data-action=confirm-action]').click();
  await page.locator('[data-action=go-settings]').first().click();
  await page.screenshot({path:path.resolve(__dirname,'../../settings-mobile-preview.png'),fullPage:true});
  await page.setViewportSize({width:1365,height:900});
  await page.screenshot({path:path.resolve(__dirname,'../../settings-desktop-preview.png'),fullPage:true});
  if(errors.length)throw Error(errors.join('\n'));
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('Horizontal overflow');
  await browser.close();console.log('Visual smoke passed: login, explicit binding, settings and desktop layout, no page errors.');
})().catch(e=>{console.error(e);process.exit(1);});
