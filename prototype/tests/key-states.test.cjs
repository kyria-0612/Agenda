const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app.js'), 'utf8').replace('render();checkDailyReminder();', '');
function boot(raw, { unavailable = false } = {}) {
  const stored = new Map(raw === undefined ? [] : [['agenda-interaction-prototype-v1', raw]]);
  const elements = new Map();
  const element = selector => {
    if (!elements.has(selector)) elements.set(selector, { innerHTML: '', hidden: false, children: [], addEventListener() {}, classList: {add(){},remove(){}}, querySelector(){return null} });
    return elements.get(selector);
  };
  const context = vm.createContext({
    console, Date, Map, Set, JSON, Math, String, Number, Boolean, Array, Error,
    document: {hidden:false, querySelector:element, querySelectorAll:()=>[], addEventListener(){}},
    window:{addEventListener(){}}, setInterval(){},setTimeout(){},clearTimeout(){},
    localStorage:{getItem:k=>{if(unavailable)throw Error('denied');return stored.get(k)??null},setItem:(k,v)=>{if(unavailable)throw Error('full');stored.set(k,v)}}
  });
  vm.runInContext(source, context);
  return {run:code=>vm.runInContext(code, context), stored, elements};
}
let checks = 0;
function test(name, run) {run();checks++;console.log('PASS '+name)}
const empty = JSON.stringify({objects:[],tasks:[]});
test('fresh install retains demo content',()=>assert.equal(boot().run('data.tasks.length'),6));
test('valid empty storage stays empty and migrates collections',()=>{const b=boot(empty);assert.equal(b.run('data.tasks.length+data.schedule.length+data.weekPlans.length'),0);assert.equal(b.run('storageIssue'),null)});
test('corrupt data loads a safe empty screen and stays recoverable',()=>{const b=boot('{broken');assert.equal(b.run('storageIssue'),'damaged');assert.equal(b.run('data.tasks.length'),0);assert.equal(b.stored.get('agenda-interaction-prototype-v1'),'{broken');assert.equal(b.run('save()'),true);assert.equal(b.stored.get('agenda-interaction-prototype-v1-recovery'),'{broken')});
test('malformed nested records are caught before rendering',()=>{for(const value of [{objects:[null],tasks:[]},{objects:[],tasks:[null]},{objects:[],tasks:[],schedule:[null]},{objects:[{id:'o',name:'x',notes:[null]}],tasks:[]}])assert.equal(boot(JSON.stringify(value)).run('storageIssue'),'damaged')});
test('blocked storage cannot crash boot or report successful persistence',()=>{const b=boot(undefined,{unavailable:true});assert.equal(b.run('save()'),false);assert.equal(b.run('storageIssue'),'unavailable');b.run('checkDailyReminder()')});
test('graph and board both offer creation in an empty state',()=>{const b=boot(empty);b.run('renderGraph()');assert.match(b.elements.get('#main').innerHTML,/data-empty-create/);b.run('renderBoard()');assert.match(b.elements.get('#main').innerHTML,/还没有事项/)});
test('action distinguishes not started and all completed',()=>{const b=boot(empty);assert.match(b.run("actionCard('随手做')"),/还没有这类任务/);b.run("data.tasks.push({id:'t',name:'x',category:'随手做',done:true})");assert.match(b.run("actionCard('随手做')"),/这一类任务都完成了/)});
test('reminders exclude today, overdue, completed and dates beyond day three',()=>{const b=boot(empty);b.run("data.tasks=[{id:'late',due:'2026-10-19'},{id:'today',due:'2026-10-20'},{id:'a',due:'2026-10-21'},{id:'b',due:'2026-10-23'},{id:'far',due:'2026-10-24'},{id:'done',due:'2026-10-22',done:true},{id:'none'}]");assert.equal(b.run("reminderTasks(new Date(2026,9,20)).map(t=>t.id).join(',')"),'a,b')});
test('calendar offsets cross month and year boundaries',()=>{const b=boot(empty);assert.equal(b.run('dayOffset(new Date(2026,11,30),3)'),'2027-01-02');assert.equal(b.run('dayOffset(new Date(2028,1,28),1)'),'2028-02-29')});
test('reminder checks wait for an open editor and only show once per day',()=>{const b=boot();b.run("document.querySelector=s=>s==='#overlay-root'?{children:[]} : null;let shown=0;openOverlay=()=>shown++;checkDailyReminder();checkDailyReminder()");assert.equal(b.run('shown'),1);const editor=boot();editor.run("document.querySelector=()=>({children:[{}]});checkDailyReminder()");assert.equal(editor.run('reminderDays.size'),0)});
test('due labels distinguish today and overdue, excluding completed tasks',()=>{const b=boot(empty);b.run('today=new Date(2026,9,20)');assert.match(b.run("dueText({due:'2026-10-19'})"),/已逾期/);assert.match(b.run("dueText({due:'2026-10-20'})"),/今天到期/);assert.doesNotMatch(b.run("dueText({due:'2026-10-19',done:true})"),/已逾期/)});
test('completion from a schedule reference updates every reference',()=>{const b=boot();b.run("renderSchedule=()=>{};toast=()=>{};data.schedule.push({id:'copy',taskId:'t-report'});toggleSchedule('s-course')");assert.equal(b.run("taskById('t-report').done&&data.schedule.filter(x=>x.taskId==='t-report').every(x=>x.done)"),true);b.run("toggleSchedule('copy')");assert.equal(b.run("taskById('t-report').done"),false)});
test('temporary schedule completion leaves task states untouched',()=>{const b=boot();b.run("renderSchedule=()=>{};toast=()=>{};toggleSchedule('s-walk')");assert.equal(b.run("data.tasks.some(t=>t.done)"),false);assert.equal(b.run("data.schedule.find(x=>x.id==='s-walk').done"),true)});
test('expired uploaded file has a relink action',()=>{const b=boot();b.run("let panelBody='';openOverlay=(title,body)=>panelBody=body;data.objects[0].materials[0].source='upload';openMaterial('o-course','m-lecture')");assert.match(b.run('panelBody'),/文件连接已失效/);assert.match(b.run('panelBody'),/material-relink/)});
test('empty week has an explanation and add action',()=>{const b=boot(empty);b.run("ui.main='schedule';ui.weekOpen=true;renderWeekPopover()");assert.match(b.elements.get('#week-root').innerHTML,/本周还没有计划/);assert.match(b.elements.get('#week-root').innerHTML,/data-add-week/)});
console.log(`${checks} state checks passed`);
