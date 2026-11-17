const $ = (s, root = document) => root.querySelector(s);
const $$ = (s, root = document) => [...root.querySelectorAll(s)];
const icon = name => `<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const periods = ['上午','中午','下午','晚上'];
const categories = ['随手做','轻量做','专注做'];
const storageKey = 'agenda-interaction-prototype-v1';
const dateKey = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
const dateFromKey = key => {const [y,m,d]=key.split('-').map(Number);return new Date(y,m-1,d)};
const dayOffset = (base, count) => {const d=new Date(base.getFullYear(),base.getMonth(),base.getDate()+count);return dateKey(d)};
const dateLabel = key => {const d=dateFromKey(key);return `${d.getMonth()+1} 月 ${d.getDate()} 日`};
const weekday = key => ['周日','周一','周二','周三','周四','周五','周六'][dateFromKey(key).getDay()];
const uid = prefix => `${prefix}-${Math.random().toString(36).slice(2,9)}`;
let today = new Date();
let storageIssue = null;
let recoveryRaw = null;

function seed(){
  return {
    objects:[
      {id:'o-course',name:'光电课程',materials:[{id:'m-lecture',name:'课程讲义.pdf'},{id:'m-data',name:'实验数据.png'}],notes:[{id:'n-course',name:'报告要求',body:'整理实验数据，并在报告中说明测量过程与结果。'}]},
      {id:'o-app',name:'日程软件',materials:[{id:'m-map',name:'页面架构.png'},{id:'m-decisions',name:'设计决策.md'}],notes:[{id:'n-app',name:'空间展开思路',body:'关系与卡片都尽量在原位展开，减少页面跳转。'}]}
    ],
    tasks:[
      {id:'t-report',name:'完成实验报告',objectId:'o-course',done:false,category:'专注做',wants:false,should:true,due:dayOffset(today,1),description:'按课程要求完成报告。',subtasks:['检查实验数据','写结果分析']},
      {id:'t-chapter',name:'复习第三章',objectId:'o-course',done:false,category:'轻量做',wants:false,should:true,due:dayOffset(today,3),description:'',subtasks:[]},
      {id:'t-ui',name:'探索事项 UI',objectId:'o-app',done:false,category:'专注做',wants:true,should:false,due:null,description:'比较关系视图与任务板的操作体验。',subtasks:[]},
      {id:'t-rules',name:'整理交互规则',objectId:'o-app',done:false,category:'轻量做',wants:false,should:true,due:null,description:'',subtasks:[]},
      {id:'t-record',name:'整理实验记录',objectId:null,done:false,category:'随手做',wants:false,should:true,due:dayOffset(today,2),description:'',subtasks:[]},
      {id:'t-paper',name:'读一篇论文',objectId:null,done:false,category:'专注做',wants:true,should:false,due:null,description:'',subtasks:[]}
    ],
    schedule:[
      {id:'s-course',date:dateKey(today),period:'上午',time:'09:00',taskId:'t-report',title:'',done:false},
      {id:'s-record',date:dateKey(today),period:'下午',time:'',taskId:'t-record',title:'',done:false},
      {id:'s-walk',date:dateKey(today),period:'晚上',time:'',taskId:null,title:'散步',done:false}
    ],
    weekPlans:[{id:'w-1',text:'完成实验报告',done:false},{id:'w-2',text:'整理原型中的交互规则',done:false}],
    leisure:['散步','听音乐'],boardOrder:['o-course','o-app','t-record','t-paper'],quote:'留些空白，也留些期待。',background:'#f4f4f2'
  };
}

function loadData(){
  try{
    const raw=localStorage.getItem(storageKey);if(raw===null)return seed();
    recoveryRaw=raw;const value=JSON.parse(raw);
    if(!value||!['objects','tasks'].every(k=>Array.isArray(value[k]))||
      !value.objects.every(o=>o&&typeof o.id==='string'&&typeof o.name==='string'&&['materials','notes'].every(k=>o[k]===undefined||Array.isArray(o[k])&&o[k].every(x=>x&&typeof x.id==='string'&&typeof x.name==='string')))||
      !value.tasks.every(t=>t&&typeof t.id==='string'&&typeof t.name==='string'))throw Error('invalid data');
    for(const key of ['schedule','weekPlans','leisure','boardOrder']){
      if(value[key]!==undefined&&!Array.isArray(value[key]))throw Error('invalid collection');
      value[key]??=[];
    }
    if(!value.schedule.every(x=>x&&typeof x.id==='string'&&typeof x.date==='string'&&periods.includes(x.period))||
       !value.weekPlans.every(x=>x&&typeof x.id==='string'&&typeof x.text==='string')||
       !value.leisure.every(x=>typeof x==='string')||!value.boardOrder.every(x=>typeof x==='string'))throw Error('invalid entries');
    value.objects.forEach(o=>{o.materials??=[];o.notes??=[]});
    value.tasks.forEach(t=>{if(!Array.isArray(t.subtasks))t.subtasks=[]});
    recoveryRaw=null;return value;
  }catch{storageIssue=recoveryRaw===null?'unavailable':'damaged';return {objects:[],tasks:[],schedule:[],weekPlans:[],leisure:[],boardOrder:[],quote:'',background:'#f4f4f2'}}
}
const data=loadData();
const ui={main:'things',thingsMode:'graph',expandedAction:null,scheduleView:'hanging',expandedDay:null,weekOpen:false,day:dateKey(today),expandedBoard:null,graphCollapsed:new Set(),graph:{scale:0,tx:0,ty:0,width:540,height:750},graphPointers:new Map(),graphGesture:null,ignoreNodeClick:false,boardDrag:null};
let toastTimer=0;
function renderStorageStatus(){
  const root=$('#storage-status');if(!root)return;root.hidden=!storageIssue;
  root.innerHTML=storageIssue?`<span>${storageIssue==='damaged'?'本机数据无法读取，原始内容已保留。':'修改仅保留在当前页面，尚未存到本机。'}</span><button data-retry-save>重试保存</button>${recoveryRaw!==null?'<button data-export-recovery>导出原始数据</button>':''}`:'';
}
function save(){
  try{
    // Keep unreadable source intact before allowing a replacement to be saved.
    if(recoveryRaw!==null)localStorage.setItem(storageKey+'-recovery',recoveryRaw);
    localStorage.setItem(storageKey,JSON.stringify(data));recoveryRaw=null;storageIssue=null;renderStorageStatus();return true;
  }catch{if(storageIssue!=='damaged')storageIssue='unavailable';renderStorageStatus();return false}
}
function toast(message){const el=$('#toast');el.textContent=storageIssue?`${message} · 尚未保存到本机`:message;el.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),2300)}
const taskById=id=>data.tasks.find(t=>t.id===id);
const objectById=id=>data.objects.find(o=>o.id===id);
function allTopItems(){return [...data.objects.map(o=>o.id),...data.tasks.filter(t=>!t.objectId).map(t=>t.id)]}
function normalizeOrder(){const current=allTopItems();data.boardOrder=[...data.boardOrder.filter(id=>current.includes(id)),...current.filter(id=>!data.boardOrder.includes(id))]}
function openOverlay(title,body,onReady,variant=""){$('#overlay-root').innerHTML=`<div class="overlay ${variant?`overlay-${variant}`:''}"><section class="sheet ${variant?`sheet-${variant}`:''}" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="sheet-head"><h2>${esc(title)}</h2><button class="close" data-close aria-label="关闭">${icon('close')}</button></div>${body}</section></div>`;const overlay=$('.overlay');overlay.addEventListener('click',e=>{if(e.target===overlay||e.target.closest('[data-close]'))requestCloseOverlay()});onReady?.($('.sheet'));}
function closeOverlay(){$('#overlay-root').innerHTML=''}
function requestCloseOverlay(afterClose){
  const root=$('#overlay-root');if(!root.querySelector('[data-dirty]')){closeOverlay();afterClose?.();return}
  if(root.querySelector('.discard-prompt'))return;
  const panel=root.querySelector('.sheet,.quick-create'),prompt=document.createElement('div');prompt.className='discard-prompt';prompt.setAttribute('role','alert');
  prompt.innerHTML='<p>还有未保存的内容，要放弃这次编辑吗？</p><div><button data-keep-editing>继续编辑</button><button data-discard-editing>放弃编辑</button></div>';
  panel.append(prompt);prompt.querySelector('[data-keep-editing]').onclick=()=>{prompt.remove();panel.querySelector('input:not([type=hidden]),textarea,select,button')?.focus()};prompt.querySelector('[data-discard-editing]').onclick=()=>{closeOverlay();afterClose?.()};prompt.scrollIntoView({block:'nearest'});prompt.querySelector('button').focus();
}
// Track edits without changing the existing lightweight form layout.
for(const event of ['input','change'])document.addEventListener(event,e=>{const panel=e.target.closest('.sheet,.quick-create');if(panel&&!e.target.matches('#search-input,#material-relink'))panel.dataset.dirty='true'});
document.addEventListener('click',e=>{
  if(e.target.closest('[data-intent],[data-quick-category],[data-quick-parent],[data-quick-date],[data-quick-intent]')){const panel=e.target.closest('.sheet,.quick-create');if(panel)panel.dataset.dirty='true'}
  const control=e.target.closest('#header-left,#header-add,#header-search,#header-extra,[data-main]');
  if(control&&$('#overlay-root [data-dirty]')){e.preventDefault();e.stopImmediatePropagation();requestCloseOverlay(()=>control.click())}
},true);
window.addEventListener('beforeunload',e=>{if(storageIssue||$('#overlay-root [data-dirty]')){e.preventDefault();e.returnValue=''}});
function render(){renderStorageStatus();renderHeader();renderPrimaryNav();if(ui.main==='things')renderThings();else if(ui.main==='action')renderAction();else renderSchedule();renderWeekPopover()}
function renderHeader(){const left=$('#header-left'),extra=$('#header-extra');
  if(ui.main==='things'){left.hidden=false;left.innerHTML=icon('flip');left.setAttribute('aria-label',ui.thingsMode==='graph'?'翻牌切换到任务板':'翻牌切换到关系视图');extra.hidden=true;extra.removeAttribute('aria-expanded');}
  else if(ui.main==='action'){left.hidden=true;extra.hidden=true;extra.removeAttribute('aria-expanded');}
  else{left.hidden=false;left.innerHTML=icon(ui.scheduleView==='hanging'?'view-cards':'view-hanging');left.setAttribute('aria-label',ui.scheduleView==='hanging'?'切换到完整卡片流':'切换到悬挂卡片');extra.hidden=false;extra.innerHTML=icon('week');extra.setAttribute('aria-label','查看周计划');extra.setAttribute('aria-expanded',String(ui.weekOpen));extra.title='查看周计划';}
  left.title=left.getAttribute('aria-label');
  $('#header-add').setAttribute('aria-label',ui.main==='schedule'?'新增日程':ui.main==='action'?'新增休闲活动':'新增对象或任务');
}
function renderPrimaryNav(){$$('.primary-nav button').forEach(b=>{if(b.dataset.main===ui.main)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current')})}
function setMain(main){ui.main=main;if(main!=='schedule')ui.weekOpen=false;if($('.quick-create-layer'))closeOverlay();$('.primary-nav').classList.remove('revealed');render()}

function graphLayout(){const nodes=[],edges=[];let y=120;
  function node(id,name,kind,x,yy,payload,parent){nodes.push({id,name,kind,x,y:yy,payload,parent});if(parent)edges.push([parent,id])}
  data.objects.forEach(object=>{
    const tasks=data.tasks.filter(t=>t.objectId===object.id),materials=object.materials||[],notes=object.notes||[];
    const groupHeight=Math.max(290,190+Math.max(tasks.length,materials.length)*44+notes.length*34);
    node(object.id,object.name,'object',270,y,{type:'object',id:object.id});
    const branches=[['tasks','任务',180,y-47],['materials','材料',370,y-38],['notes','说明',375,y+92]];
    branches.forEach(([kind,label,x,yy])=>node(`${object.id}:${kind}`,label,'branch',x,yy,{type:'branch',objectId:object.id,branch:kind},object.id));
    tasks.forEach((t,i)=>node(t.id,t.name,'task',90,y-103+i*47,{type:'task',id:t.id},`${object.id}:tasks`));
    materials.forEach((m,i)=>node(m.id,m.name,'file',450,y-95+i*48,{type:'material',objectId:object.id,id:m.id},`${object.id}:materials`));
    notes.forEach((n,i)=>node(n.id,n.name,'note',450,y+143+i*42,{type:'note',objectId:object.id,id:n.id},`${object.id}:notes`));
    y+=groupHeight;
  });
  data.tasks.filter(t=>!t.objectId).forEach((t,i)=>node(t.id,t.name,'task',i%2?395:145,y+15+Math.floor(i/2)*62,{type:'task',id:t.id}));
  return {nodes,edges,width:540,height:Math.max(650,y+80+Math.ceil(data.tasks.filter(t=>!t.objectId).length/2)*62)};
}
function graphVisible(node,map){let current=node;while(current.parent){if(ui.graphCollapsed.has(current.parent))return false;current=map.get(current.parent)}return true}
function renderThings(){if(ui.thingsMode==='graph')renderGraph();else renderBoard()}
function renderGraph(){if(!allTopItems().length){$('#main').innerHTML=emptyState('从一件小事开始','只填名称，就能记下任务或创建对象。','新建任务','data-empty-create');return}const layout=graphLayout();ui.graph.width=layout.width;ui.graph.height=layout.height;const map=new Map(layout.nodes.map(n=>[n.id,n]));const visible=layout.nodes.filter(n=>graphVisible(n,map));
  const paths=layout.edges.filter(([_,to])=>visible.some(n=>n.id===to)).map(([from,to])=>{const a=map.get(from),b=map.get(to);return `<path d="M${a.x},${a.y} C${(a.x+b.x)/2},${a.y} ${(a.x+b.x)/2},${b.y} ${b.x},${b.y}"/>`}).join('');
  $('#main').innerHTML=`<div class="graph-viewport" aria-label="有界关系画布"><div class="graph-stage" style="width:${layout.width}px;height:${layout.height}px"><svg class="graph-edges" width="${layout.width}" height="${layout.height}" viewBox="0 0 ${layout.width} ${layout.height}" aria-hidden="true">${paths}</svg>${visible.map(n=>`<button class="graph-node ${n.kind} ${n.kind==='task'&&taskById(n.id)?.done?'done':''}" style="left:${n.x}px;top:${n.y}px" data-graph-node="${esc(n.id)}" aria-label="${esc(n.name)}" ${n.kind==='object'||n.kind==='branch'?`aria-expanded="${!ui.graphCollapsed.has(n.id)}"`:''}>${esc(n.name)}</button>`).join('')}</div><button class="graph-focus" data-overview aria-label="全局概览" title="全局概览">${icon('overview')}</button></div>`;
  const viewport=$('.graph-viewport');if(!ui.graph.scale){ui.graph.scale=Math.min(.82,viewport.clientWidth/ui.graph.width);ui.graph.tx=(viewport.clientWidth-ui.graph.width*ui.graph.scale)/2;ui.graph.ty=88;}else{clampGraph(false)}applyGraphTransform();installGraphGestures(viewport);
}
function graphLimits(){const v=$('.graph-viewport');if(!v)return {minX:0,maxX:0,minY:0,maxY:0};const w=v.clientWidth,h=v.clientHeight,cw=ui.graph.width*ui.graph.scale,ch=ui.graph.height*ui.graph.scale,top=90,bottom=90,visibleHeight=Math.max(1,h-top-bottom),centerY=top+(visibleHeight-ch)/2;return {minX:cw<=w?(w-cw)/2:w-cw-16,maxX:cw<=w?(w-cw)/2:16,minY:ch<=visibleHeight?centerY:h-bottom-ch,maxY:ch<=visibleHeight?centerY:top}}
function applyGraphTransform(){const stage=$('.graph-stage');if(stage)stage.style.transform=`translate(${ui.graph.tx}px,${ui.graph.ty}px) scale(${ui.graph.scale})`}
function fitGraph(animated=true){const v=$('.graph-viewport');if(!v)return;const visibleHeight=Math.max(1,v.clientHeight-180);ui.graph.scale=Math.min(v.clientWidth/ui.graph.width,visibleHeight/ui.graph.height,.86);ui.graph.tx=(v.clientWidth-ui.graph.width*ui.graph.scale)/2;ui.graph.ty=90+(visibleHeight-ui.graph.height*ui.graph.scale)/2;const stage=$('.graph-stage');if(stage&&animated){stage.style.transition='transform .35s ease';setTimeout(()=>{if(stage.isConnected)stage.style.transition=''},380)}applyGraphTransform()}
function damp(value,min,max){if(value<min)return min+(value-min)*.27;if(value>max)return max+(value-max)*.27;return value}
function clampGraph(animated=true){const l=graphLimits();ui.graph.tx=Math.min(l.maxX,Math.max(l.minX,ui.graph.tx));ui.graph.ty=Math.min(l.maxY,Math.max(l.minY,ui.graph.ty));const stage=$('.graph-stage');if(stage&&animated){stage.style.transition='transform .26s cubic-bezier(.2,.8,.3,1)';setTimeout(()=>{if(stage.isConnected)stage.style.transition=''},300)}applyGraphTransform()}
function zoomGraph(factor,cx,cy){const v=$('.graph-viewport');if(!v)return;const rect=v.getBoundingClientRect(),x=cx-rect.left,y=cy-rect.top,old=ui.graph.scale,next=Math.min(1.6,Math.max(.42,old*factor));ui.graph.tx=x-(x-ui.graph.tx)*next/old;ui.graph.ty=y-(y-ui.graph.ty)*next/old;ui.graph.scale=next;clampGraph(false)}
function installGraphGestures(viewport){
  viewport.addEventListener('pointerdown',e=>{if(e.target.closest('.graph-focus'))return;const captureTarget=e.target.closest('.graph-node')||viewport;captureTarget.setPointerCapture(e.pointerId);ui.graphPointers.set(e.pointerId,{x:e.clientX,y:e.clientY});if(ui.graphPointers.size===1){ui.graphGesture={x:e.clientX,y:e.clientY,tx:ui.graph.tx,ty:ui.graph.ty,moved:false};}else if(ui.graphPointers.size===2){const [a,b]=[...ui.graphPointers.values()];ui.graphGesture={pinch:true,distance:Math.hypot(a.x-b.x,a.y-b.y),scale:ui.graph.scale,tx:ui.graph.tx,ty:ui.graph.ty,centerX:(a.x+b.x)/2,centerY:(a.y+b.y)/2};}},
  {passive:false});
  viewport.addEventListener('pointermove',e=>{if(!ui.graphPointers.has(e.pointerId))return;ui.graphPointers.set(e.pointerId,{x:e.clientX,y:e.clientY});const g=ui.graphGesture;if(!g)return;
    if(ui.graphPointers.size===2){const [a,b]=[...ui.graphPointers.values()],distance=Math.hypot(a.x-b.x,a.y-b.y),centerX=(a.x+b.x)/2,centerY=(a.y+b.y)/2,rect=viewport.getBoundingClientRect();const s=Math.min(1.6,Math.max(.42,g.scale*distance/g.distance));const anchorX=g.centerX-rect.left,anchorY=g.centerY-rect.top;ui.graph.scale=s;const l=graphLimits();ui.graph.tx=damp((centerX-rect.left)-(anchorX-g.tx)*s/g.scale,l.minX,l.maxX);ui.graph.ty=damp((centerY-rect.top)-(anchorY-g.ty)*s/g.scale,l.minY,l.maxY);ui.ignoreNodeClick=true;}
    else if(!g.pinch){const dx=e.clientX-g.x,dy=e.clientY-g.y;if(Math.hypot(dx,dy)>5){g.moved=true;ui.ignoreNodeClick=true;viewport.classList.add('dragging')}const l=graphLimits();ui.graph.tx=damp(g.tx+dx,l.minX,l.maxX);ui.graph.ty=damp(g.ty+dy,l.minY,l.maxY);}applyGraphTransform();e.preventDefault();}, {passive:false});
  function end(e){ui.graphPointers.delete(e.pointerId);if(!ui.graphPointers.size){viewport.classList.remove('dragging');clampGraph();setTimeout(()=>ui.ignoreNodeClick=false,70)}else{const p=[...ui.graphPointers.values()][0];ui.graphGesture={x:p.x,y:p.y,tx:ui.graph.tx,ty:ui.graph.ty,moved:true}}}
  viewport.addEventListener('pointerup',end);viewport.addEventListener('pointercancel',end);
  viewport.addEventListener('wheel',e=>{e.preventDefault();zoomGraph(e.deltaY<0?1.12:.89,e.clientX,e.clientY)},{passive:false});
}
function focusGraphNode(id){const layout=graphLayout(),map=new Map(layout.nodes.map(n=>[n.id,n]));let target=map.get(id);while(target?.parent){ui.graphCollapsed.delete(target.parent);target=map.get(target.parent)}ui.main='things';ui.thingsMode='graph';render();const node=$(`[data-graph-node="${CSS.escape(id)}"]`),view=$('.graph-viewport');if(!node||!view)return;const x=parseFloat(node.style.left),y=parseFloat(node.style.top);ui.graph.scale=Math.max(.78,ui.graph.scale);ui.graph.tx=view.clientWidth/2-x*ui.graph.scale;ui.graph.ty=view.clientHeight/2-y*ui.graph.scale;clampGraph();node.focus({preventScroll:true})}

function renderBoard(){normalizeOrder();if(!data.boardOrder.length){$('#main').innerHTML=emptyState('还没有事项','记下任务，也可以创建对象来整理相关内容。','新建任务','data-empty-create');return}const rows=[];for(let i=0;i<data.boardOrder.length;i+=2)rows.push(data.boardOrder.slice(i,i+2));$('#main').innerHTML=`<div class="board-scroll"><div class="board-rows">${rows.map(row=>{const expanded=row.indexOf(ui.expandedBoard),className=expanded===0?'left-expanded':expanded===1?'right-expanded':'';return `<div class="board-row ${className}">${row.map(id=>boardCard(id)).join('')}</div>`}).join('')}</div><p class="board-tip">轻点展开 · 长按移动 · 任意位置上下滑动</p></div>`;installBoardDrag()}
function boardCard(id){const o=objectById(id),t=taskById(id);if(!o&&!t)return '';const expanded=ui.expandedBoard===id;const children=o?`<span class="card-body">${data.tasks.filter(x=>x.objectId===id).map(x=>`<span class="board-task-row">${completionButton('task',x.id,x.done,x.name)}<button class="card-line ${x.done?'done':''}" data-task="${x.id}">${esc(x.name)}</button></span>`).join('')}${(o.materials||[]).map(m=>`<button class="card-line" data-material="${o.id}:${m.id}">${icon('file')}${esc(m.name)}</button>`).join('')}${(o.notes||[]).map(n=>`<button class="card-line" data-note="${o.id}:${n.id}">${icon('note')}${esc(n.name)}</button>`).join('')}<button class="card-line" data-object="${o.id}">${icon('plus')}添加材料或说明</button></span>`:`<span class="card-body"><span class="board-task-row">${completionButton('task',t.id,t.done,t.name)}<button class="card-line" data-task="${t.id}">查看任务详情</button></span></span>`;
  return `<article class="board-card" data-card="${id}" aria-expanded="${expanded}" role="button" tabindex="0"><small class="card-kind">${o?'对象':'独立任务'}</small><strong class="card-title">${esc((o||t).name)}</strong><small class="card-sub">${o?`${data.tasks.filter(x=>x.objectId===id).length} 项任务 · ${(o.materials||[]).length} 份材料`:t.done?'已完成':`${t.category||'未分类'}${t.due?' · '+dateLabel(t.due)+'截止':''}`}</small>${expanded?children:''}</article>`}
function installBoardDrag(){const cards=$$('.board-card');cards.forEach(card=>{let start=null,timer=0,dragging=false;function clear(){clearTimeout(timer);window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',end);window.removeEventListener('pointercancel',cancel)}
  function move(e){if(!start||e.pointerId!==start.id)return;const dx=e.clientX-start.x,dy=e.clientY-start.y;if(!dragging){if(Math.hypot(dx,dy)>9){clearTimeout(timer);start.moved=true}return}e.preventDefault();card.style.transform=`translate(${dx}px,${dy}px)`;const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('.board-card');cards.forEach(c=>c.classList.toggle('drag-target',c===target&&c!==card))}
  function end(e){if(!start||e.pointerId!==start.id)return;clearTimeout(timer);if(dragging){card.style.pointerEvents='none';const target=document.elementFromPoint(e.clientX,e.clientY)?.closest('.board-card');card.style.pointerEvents='';if(target&&target!==card){const order=data.boardOrder,from=order.indexOf(card.dataset.card),to=order.indexOf(target.dataset.card);order.splice(from,1);order.splice(to,0,card.dataset.card);save()}ui.expandedBoard=null;renderBoard();}else if(!start.moved){ui.expandedBoard=ui.expandedBoard===card.dataset.card?null:card.dataset.card;renderBoard()}clear();start=null}
  function cancel(){clear();start=null;if(dragging)renderBoard()}
  card.addEventListener('pointerdown',e=>{if(e.target.closest('.card-line,.check-button'))return;if(e.pointerType==='mouse'&&e.button!==0)return;start={id:e.pointerId,x:e.clientX,y:e.clientY,moved:false};dragging=false;timer=setTimeout(()=>{if(!start||start.moved)return;dragging=true;card.classList.add('dragging');if(navigator.vibrate)navigator.vibrate(12)},380);window.addEventListener('pointermove',move,{passive:false});window.addEventListener('pointerup',end);window.addEventListener('pointercancel',cancel)});
  card.addEventListener('keydown',e=>{if(e.target!==card)return;if(e.key==='Enter'||e.key===' '){e.preventDefault();ui.expandedBoard=ui.expandedBoard===card.dataset.card?null:card.dataset.card;renderBoard()}});
})}

function emptyState(title,body,label='',action=''){return `<div class="state-empty"><strong>${esc(title)}</strong><p>${esc(body)}</p>${label?`<button class="state-action" ${action}>${esc(label)}</button>`:''}</div>`}
function categoryEmpty(category){const completed=data.tasks.some(t=>t.category===category&&t.done);return emptyState(completed?'这一类任务都完成了':'还没有这类任务',completed?'完成的任务仍保留在事项中，可以取消完成。':'创建任务时可以选择适合的执行条件。',completed?'查看事项':'新建任务',completed?'data-main="things"':'data-empty-create')}
function actionCard(category){const leisure=category==='休闲活动',items=leisure?data.leisure:data.tasks.filter(t=>t.category===category&&!t.done),expanded=ui.expandedAction===category;
  const preview=items.length?items.slice(0,3).map(item=>`<span>${esc(leisure?item:item.name)}</span>`).join('')+(items.length>3?`<span>还有 ${items.length-3} 项</span>`:''):`<span>${leisure?'也可以什么都不做':data.tasks.some(t=>t.category===category&&t.done)?'这一类任务都完成了':'还没有这类任务'}</span>`;
  const detail=leisure?`<div class="action-card-detail">${items.length?items.map(item=>`<p>${esc(item)}</p>`).join(''):'<p>也可以什么都不做。</p>'}<button class="card-line" data-add-leisure>${icon('plus')}添加休闲活动</button></div>`:`<div class="action-card-detail">${items.length?taskList(items):categoryEmpty(category)}</div>`;
  return `<article class="action-card ${expanded?'expanded':''}" data-action-card="${category}" role="button" tabindex="0" aria-expanded="${expanded}" aria-label="${category}，${items.length} 项"><strong class="action-card-title">${category}</strong><small class="action-card-count">${items.length} 项</small><div class="action-card-preview">${preview}</div>${expanded?detail:''}</article>`}
function renderAction(){const old=$('.action-scroll'),scrollTop=old?.scrollTop||0,main=$('#main'),actionCategories=[...categories,'休闲活动'],rows=[];
  for(let i=0;i<actionCategories.length;i+=2){const pair=actionCategories.slice(i,i+2),expanded=pair.indexOf(ui.expandedAction);rows.push(`<div class="action-card-row ${expanded===0?'left-expanded':expanded===1?'right-expanded':''}">${pair.map(actionCard).join('')}</div>`)}
  main.innerHTML=`<div class="action-scroll"><h1 class="action-intro">现在，做点什么？</h1><div class="action-board">${rows.join('')}</div></div>`;
  $('.action-scroll').scrollTop=scrollTop;}
function completionButton(kind,id,done,name){return `<button type="button" class="check-button ${done?'checked':''}" data-toggle-${kind}="${esc(id)}" aria-label="${done?'取消完成':'完成'}${esc(name)}" aria-pressed="${done}">${done?icon('check'):''}</button>`}
function dueText(t){if(!t.due)return '未设期限';const key=dateKey(today);return `${dateLabel(t.due)} 截止${t.done?'':t.due<key?' · 已逾期':t.due===key?' · 今天到期':''}`}
function taskList(tasks){return tasks.length?tasks.map(t=>`<div class="task-result">${completionButton('task',t.id,t.done,t.name)}<button class="task-main" data-task="${t.id}">${esc(t.name)}<small>${t.objectId?esc(objectById(t.objectId)?.name)+' · ':''}${dueText(t)}${t.subtasks?.length?' · 下一步：'+esc(t.subtasks[0]):''}</small></button></div>`).join(''):'<p class="leisure-empty">这里暂时没有任务。</p>'}

function scheduleTitle(item){return item.taskId?taskById(item.taskId)?.name||'已删除的任务':item.title}
function dayItems(key,period){return data.schedule.filter(item=>item.date===key&&(!period||item.period===period))}
function dayDensity(key){const count=dayItems(key).length;return `<span class="density" aria-label="${count} 项日程">${[0,1,2].map(i=>`<i class="${i<count?'':'empty'}"></i>`).join('')}</span>`}
function displayDates(){return Array.from({length:9},(_,i)=>dayOffset(today,i-3))}
function renderSchedule(resetScroll=false){const main=$('#main'),old=main.querySelector('.schedule-scroll'),scrollTop=old&&!resetScroll?old.scrollTop:0,centerQuote=ui.scheduleView==='hanging'&&!ui.expandedDay;
  main.innerHTML=`<div class="schedule-scroll schedule-day-scroll ${centerQuote?'show-center-quote':''}">${ui.scheduleView==='hanging'?hangingMarkup():fullMarkup()}${centerQuote?`<button class="schedule-quote center-quote" data-edit-quote aria-label="编辑日程文字">${esc(data.quote||'写一两句话')}</button>`:ui.scheduleView==='full'?`<button class="schedule-quote" data-edit-quote aria-label="编辑日程文字">${esc(data.quote||'写一两句话')}</button>`:''}</div>`;
  const scheduleSurface=$('.schedule-scroll');scheduleSurface.style.backgroundColor=data.background||'#f4f4f2';scheduleSurface.style.backgroundImage=data.backgroundImage?`url("${data.backgroundImage}")`:'';scheduleSurface.style.backgroundSize='cover';scheduleSurface.style.backgroundPosition='center';
  scheduleSurface.style.setProperty('--schedule-anchor',`${Math.round($('#app').clientHeight*.19)}px`);scheduleSurface.scrollTop=scrollTop;
  setTimeout(centerScheduleDay,0);
}
function centerScheduleDay(){const strip=ui.scheduleView==='hanging'?$('.hanging-days'):$('.full-flow'),selected=ui.scheduleView==='hanging'?$(`.hang-day[data-day="${CSS.escape(ui.day)}"]`):$(`.full-day[data-date="${CSS.escape(ui.day)}"]`);if(!strip||!selected)return;const area=strip.getBoundingClientRect(),card=selected.getBoundingClientRect();strip.scrollLeft+=card.left-area.left-(area.width-card.width)/2}
function hangingMarkup(){return `<div class="hanging-track"><div class="hanging-days">${displayDates().map(key=>`<div class="hang-day ${ui.expandedDay===key?'selected':''}" data-day="${key}" role="button" tabindex="0" aria-expanded="${ui.expandedDay===key}"><span class="date">${dateFromKey(key).getMonth()+1}/${dateFromKey(key).getDate()}</span><span class="weekday">${weekday(key)}</span>${dayDensity(key)}${ui.expandedDay===key?`<div class="hang-expanded">${periods.map(period=>`<div class="hang-period">${period}<div class="hang-items">${dayItems(key,period).length?dayItems(key,period).map(x=>{const done=!!(x.done||x.taskId&&taskById(x.taskId)?.done);return `<div class="hang-item-row ${done?'done':''}">${completionButton('schedule',x.id,done,scheduleTitle(x))}<span class="hang-item-label">${esc(scheduleTitle(x))}</span></div>`}).join(''):'<span class="hang-empty">留白</span>'}</div></div>`).join('')}<button class="add-period" data-add-day="${key}">＋ 添加日程</button></div>`:''}</div>`).join('')}</div></div>`}
function fullMarkup(){return `<div class="full-flow">${displayDates().map(key=>`<article class="full-day" data-date="${key}"><small>${weekday(key)}</small><h3>${dateLabel(key)}</h3>${periods.map(period=>`<div class="full-period"><b>${period}</b>${dayItems(key,period).map(item=>{const done=!!(item.done||item.taskId&&taskById(item.taskId)?.done);return `<div class="full-item ${done?'done':''}">${completionButton('schedule',item.id,done,scheduleTitle(item))}<span>${esc(scheduleTitle(item))}${item.time?' · '+esc(item.time):''}</span></div>`}).join('')||'<p>留白</p>'}<button class="add-period" data-add-day="${key}" data-period="${period}">＋ 添加</button></div>`).join('')}</article>`).join('')}</div>`}
function renderWeekPopover(){const root=$('#week-root');if(!ui.weekOpen||ui.main!=='schedule'){root.innerHTML='';return}const d=new Date(today.getFullYear(),today.getMonth(),today.getDate()-((today.getDay()+6)%7)),start=dateKey(d),end=dayOffset(d,6);root.innerHTML=`<section class="week-popover" role="dialog" aria-label="本周计划"><div class="week-popover-head"><div><small>${dateLabel(start)} — ${dateLabel(end)}</small><h2>本周计划</h2></div><button class="week-close" data-close-week aria-label="关闭周计划">${icon('close')}</button></div><div class="week-list">${data.weekPlans.length?data.weekPlans.map(item=>`<div class="week-row ${item.done?'done':''}">${completionButton('week',item.id,item.done,item.text)}<span>${esc(item.text)}</span></div>`).join(''):emptyState('本周还没有计划','记下一件这周想做的事，不必安排到某一天。')}</div><button class="week-add" data-add-week>＋ 添加本周计划</button></section>`}

function openCreate(){if(ui.main==='schedule'){openScheduleAdd(ui.day);return}if(ui.main==='action'){openLeisureAdd();return}openTaskCreate()}
function finishCreate(id){save();closeOverlay();render();if(ui.thingsMode==='graph')focusGraphNode(id);toast('已创建')}
function openObjectCreate(initialName=''){
  const root=$('#overlay-root');
  root.innerHTML=`<div class="quick-create-layer"><section class="quick-create quick-object-create" role="dialog" aria-label="新对象"><div class="quick-create-head"><span>新对象</span><div><button type="button" class="quick-switch-link" data-quick-task>创建任务</button><button type="button" class="quick-close" data-quick-close aria-label="收起">${icon('close')}</button></div></div><label class="quick-name-label" for="quick-object-name">对象名称</label><input id="quick-object-name" class="quick-name" placeholder="给对象起个名字…" autocomplete="off"><p class="quick-object-hint">把相关任务、材料和说明放在一起。</p><div class="quick-create-bottom"><span class="quick-object-tip">只填名称即可</span><button type="button" class="quick-submit" data-create-object-submit>创建 ${icon('plus')}</button></div><p class="quick-error" role="alert" hidden></p></section></div>`;
  const panel=$('.quick-object-create',root),nameInput=$('#quick-object-name',panel);nameInput.value=initialName;
  function submit(){const name=nameInput.value.trim();if(!name){$('.quick-error',panel).hidden=false;$('.quick-error',panel).textContent='先写下对象名称';nameInput.focus();return}const id=uid('o');data.objects.push({id,name,materials:[],notes:[]});data.boardOrder.push(id);finishCreate(id)}
  $('[data-quick-close]',panel).onclick=()=>requestCloseOverlay();
  $('[data-quick-task]',panel).onclick=()=>openTaskCreate(nameInput.value);
  $('[data-create-object-submit]',panel).onclick=submit;
  nameInput.addEventListener('input',()=>{$('.quick-error',panel).hidden=true});
  nameInput.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();submit()}});
  nameInput.focus({preventScroll:true});
}
function openTaskCreate(initialName=''){
  const root=$('#overlay-root'),parents=`<button type="button" class="quick-option" data-quick-parent="" aria-pressed="true">独立任务</button>${data.objects.map(o=>`<button type="button" class="quick-option" data-quick-parent="${esc(o.id)}" aria-pressed="false">${esc(o.name)}</button>`).join('')}`;
  root.innerHTML=`<div class="quick-create-layer"><section class="quick-create" role="dialog" aria-label="新任务"><div class="quick-create-head"><span>新任务</span><div><button type="button" class="quick-switch-link" data-quick-object>创建对象</button><button type="button" class="quick-close" data-quick-close aria-label="收起">${icon('close')}</button></div></div><label class="quick-name-label" for="quick-name">任务名称</label><input id="quick-name" class="quick-name" placeholder="记下一件事…" autocomplete="off"><div class="quick-category-label"><span>适合怎么做</span><small>可跳过</small></div><div class="quick-categories" role="group" aria-label="执行条件分类">${categories.map(c=>`<button type="button" class="quick-category" data-quick-category="${c}" aria-pressed="false">${c}</button>`).join('')}</div><div class="quick-create-bottom"><button type="button" class="quick-details" data-quick-details aria-expanded="false"><span class="quick-details-mark" aria-hidden="true">＋</span><span>归属、日期、意愿</span></button><button type="button" class="quick-submit" data-quick-submit>创建 ${icon('plus')}</button></div><div class="quick-selected" aria-live="polite" hidden></div><div class="quick-more" hidden><div class="quick-tabs" role="tablist" aria-label="任务细节"><button type="button" role="tab" class="quick-tab" data-quick-tab="parent" aria-selected="true" aria-controls="quick-panel-parent">归属</button><button type="button" role="tab" class="quick-tab" data-quick-tab="date" aria-selected="false" aria-controls="quick-panel-date">日期</button><button type="button" role="tab" class="quick-tab" data-quick-tab="intent" aria-selected="false" aria-controls="quick-panel-intent">意愿</button></div><div id="quick-panel-parent" class="quick-panel" role="tabpanel" data-quick-panel="parent"><div class="quick-options" role="group" aria-label="所属对象">${parents}</div></div><div id="quick-panel-date" class="quick-panel" role="tabpanel" data-quick-panel="date" hidden><div class="quick-options" role="group" aria-label="截止日期"><button type="button" class="quick-option" data-quick-date="today" aria-pressed="false">今天</button><button type="button" class="quick-option" data-quick-date="tomorrow" aria-pressed="false">明天</button><input type="date" class="quick-date-input" aria-label="选择具体日期"></div></div><div id="quick-panel-intent" class="quick-panel" role="tabpanel" data-quick-panel="intent" hidden><div class="quick-options" role="group" aria-label="任务意愿"><button type="button" class="quick-option" data-quick-intent="wants" aria-pressed="false">想做</button><button type="button" class="quick-option" data-quick-intent="should" aria-pressed="false">该做</button></div><p class="quick-note">可以同时选择，也可以都不选。</p></div></div><p class="quick-error" role="alert" hidden></p></section></div>`;
  const panel=$('.quick-create',root),state={parent:null,due:null,dueLabel:'',category:null,wants:false,should:false};$('#quick-name',panel).value=initialName;
  function updateSummary(){const parentName=state.parent?objectById(state.parent)?.name||'独立任务':'独立任务',intent=[state.wants?'想做':'',state.should?'该做':''].filter(Boolean).join('、'),parts=[state.parent?parentName:'',state.dueLabel,intent].filter(Boolean),selected=$('.quick-selected',panel);selected.textContent=parts.join(' · ');selected.hidden=!parts.length;$('[data-quick-tab="parent"]',panel).setAttribute('aria-label',`归属：${parentName}`);$('[data-quick-tab="date"]',panel).setAttribute('aria-label',`日期：${state.dueLabel||'未设置'}`);$('[data-quick-tab="intent"]',panel).setAttribute('aria-label',`意愿：${intent||'未设置'}`)}
  panel.addEventListener('click',e=>{const b=e.target.closest('button');if(!b)return;
    if(b.hasAttribute('data-quick-close')){requestCloseOverlay();return}
    if(b.hasAttribute('data-quick-object')){openObjectCreate($('#quick-name',panel).value);return}
    if(b.dataset.quickCategory){state.category=state.category===b.dataset.quickCategory?null:b.dataset.quickCategory;$$('[data-quick-category]',panel).forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.quickCategory===state.category)));return}
    if(b.hasAttribute('data-quick-details')){const open=b.getAttribute('aria-expanded')!=='true';b.setAttribute('aria-expanded',String(open));$('.quick-details-mark',b).textContent=open?'－':'＋';$('.quick-more',panel).hidden=!open;return}
    if(b.dataset.quickTab){$$('[data-quick-tab]',panel).forEach(x=>x.setAttribute('aria-selected',String(x===b)));$$('[data-quick-panel]',panel).forEach(x=>x.hidden=x.dataset.quickPanel!==b.dataset.quickTab);return}
    if(b.hasAttribute('data-quick-parent')){state.parent=b.dataset.quickParent||null;$$('[data-quick-parent]',panel).forEach(x=>x.setAttribute('aria-pressed',String(x===b)));updateSummary();return}
    if(b.dataset.quickDate){const key=b.dataset.quickDate==='today'?dayOffset(today,0):dayOffset(today,1);state.due=state.due===key?null:key;state.dueLabel=state.due?(b.dataset.quickDate==='today'?'今天':'明天'):'';$$('[data-quick-date]',panel).forEach(x=>x.setAttribute('aria-pressed',String(x.dataset.quickDate===(state.dueLabel==='今天'?'today':state.dueLabel==='明天'?'tomorrow':''))));$('.quick-date-input',panel).value='';updateSummary();return}
    if(b.dataset.quickIntent){state[b.dataset.quickIntent]=!state[b.dataset.quickIntent];b.setAttribute('aria-pressed',String(state[b.dataset.quickIntent]));updateSummary();return}
    if(b.hasAttribute('data-quick-submit')){const name=$('#quick-name',panel).value.trim();if(!name){$('.quick-error',panel).hidden=false;$('.quick-error',panel).textContent='先写下任务名称';$('#quick-name',panel).focus();return}const id=uid('t');data.tasks.push({id,name,objectId:state.parent,done:false,category:state.category,wants:state.wants,should:state.should,due:state.due,description:'',subtasks:[]});if(!state.parent)data.boardOrder.push(id);else{ui.graphCollapsed.delete(state.parent);ui.graphCollapsed.delete(`${state.parent}:tasks`)}finishCreate(id)}
  });
  $('.quick-date-input',panel).addEventListener('change',e=>{state.due=e.target.value||null;state.dueLabel=state.due?dateLabel(state.due):'';$$('[data-quick-date]',panel).forEach(x=>x.setAttribute('aria-pressed','false'));updateSummary()});
  $('#quick-name',panel).addEventListener('input',()=>{$('.quick-error',panel).hidden=true});
  $('#quick-name',panel).addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();$('[data-quick-submit]',panel).click()}});
  $('#quick-name',panel).focus({preventScroll:true});
}
function openTask(id){const t=taskById(id);if(!t)return;openOverlay(t.name,`<label class="inline-check"><input type="checkbox" id="task-done" ${t.done?'checked':''}> 已完成</label><label for="task-description">说明</label><textarea id="task-description" placeholder="可以稍后补充">${esc(t.description||'')}</textarea><label for="task-due">截止日期</label><input type="date" id="task-due" value="${esc(t.due||'')}"><h3>意愿</h3><div class="choice-row"><button data-intent="wants" class="${t.wants?'active':''}">想做</button><button data-intent="should" class="${t.should?'active':''}">该做</button></div><label for="task-category">执行条件</label><select id="task-category"><option value="">暂不分类</option>${categories.map(c=>`<option ${t.category===c?'selected':''}>${c}</option>`).join('')}</select><label for="task-subtasks">内部子任务（每行一项）</label><textarea id="task-subtasks">${esc((t.subtasks||[]).join('\n'))}</textarea><button class="sheet-submit" data-save-task>保存</button>`,sheet=>{
  sheet.addEventListener('click',e=>{const b=e.target.closest('[data-intent]');if(b)b.classList.toggle('active')});$('[data-save-task]',sheet).onclick=()=>{t.done=$('#task-done',sheet).checked;t.description=$('#task-description',sheet).value;t.due=$('#task-due',sheet).value||null;t.category=$('#task-category',sheet).value||null;t.wants=!!$('[data-intent="wants"].active',sheet);t.should=!!$('[data-intent="should"].active',sheet);t.subtasks=$('#task-subtasks',sheet).value.split('\n').map(s=>s.trim()).filter(Boolean);data.schedule.filter(x=>x.taskId===t.id).forEach(x=>x.done=t.done);save();closeOverlay();render();toast('已保存')};
})}
function openObject(id){const o=objectById(id);if(!o)return;openOverlay(o.name,`<h3>任务</h3>${taskList(data.tasks.filter(t=>t.objectId===id))}<h3>材料</h3>${(o.materials||[]).map(m=>`<button class="sheet-result" data-material="${o.id}:${m.id}">${esc(m.name)}</button>`).join('')||'<p class="empty">暂无材料</p>'}<h3>说明</h3>${(o.notes||[]).map(n=>`<button class="sheet-result" data-note="${o.id}:${n.id}">${esc(n.name)}</button>`).join('')||'<p class="empty">暂无说明</p>'}<div class="choice-row"><button data-add-material="${o.id}">添加材料</button><button data-add-note="${o.id}">添加说明</button></div>`)}
const fileUrls=new Map();
function openMaterial(objectId,id){const m=objectById(objectId)?.materials?.find(x=>x.id===id);if(!m)return;const url=fileUrls.get(id);
  openOverlay(m.name,`<p>${url?'文件已就绪，可以在浏览器中打开。':m.source==='upload'?'刷新后文件连接已失效，请重新选择同名文件。':'这是一份示例材料，尚未关联文件。可以选择同名文件来打开。'}</p>${url?`<a class="state-action" href="${esc(url)}" target="_blank" rel="noopener">打开材料</a><p>若没有打开，可重新点击链接，或检查浏览器的文件查看支持。</p>`:'<label for="material-relink">重新关联文件</label><input id="material-relink" type="file" accept=".pdf,.ppt,.pptx,image/*,.txt,.md"><p class="error" role="alert" hidden></p>'}`,sheet=>{
    const input=$('#material-relink',sheet);if(input)input.onchange=()=>{const file=input.files[0];if(!file)return;if(file.name!==m.name){showFieldError(input,'请选择同名文件：'+m.name);return}fileUrls.set(id,URL.createObjectURL(file));m.source='upload';save();openMaterial(objectId,id)};
  })}
function openNote(objectId,id){const o=objectById(objectId),n=o?.notes?.find(x=>x.id===id);if(n)openOverlay(n.name,`<p>${esc(n.body)}</p>`)}
function openMaterialAdd(objectId){openOverlay('添加材料',`<label for="material-file">选择文件</label><input id="material-file" type="file" accept=".pdf,.ppt,.pptx,image/*,.txt,.md"><p>文件内容只在当前浏览器会话中可打开；刷新后保留文件名。</p><button class="sheet-submit" data-save-material>添加</button>`,sheet=>{$('[data-save-material]',sheet).onclick=()=>{const file=$('#material-file',sheet).files[0];if(!file)return showFieldError($('#material-file',sheet),'请先选择文件');const id=uid('m');objectById(objectId).materials.push({id,name:file.name,source:'upload'});fileUrls.set(id,URL.createObjectURL(file));save();closeOverlay();render();toast('材料已添加')}})}
function openNoteAdd(objectId){openOverlay('添加说明',`<label for="note-name">标题</label><input id="note-name" placeholder="说明名称"><label for="note-body">内容</label><textarea id="note-body"></textarea><button class="sheet-submit" data-save-note>添加</button>`,sheet=>{$('[data-save-note]',sheet).onclick=()=>{const name=$('#note-name',sheet).value.trim();if(!name)return showFieldError($('#note-name',sheet),'请填写说明标题');objectById(objectId).notes.push({id:uid('n'),name,body:$('#note-body',sheet).value});save();closeOverlay();render();toast('说明已添加')}})}

function showFieldError(input,message){const panel=input.closest('.sheet,.quick-create');let error=$('.field-error',panel);if(!error){error=document.createElement('p');error.className='field-error';error.id=uid('error');error.setAttribute('role','alert');panel.append(error)}error.hidden=false;error.textContent=message;input.setAttribute('aria-invalid','true');input.setAttribute('aria-describedby',error.id);input.focus();input.addEventListener('input',()=>{input.removeAttribute('aria-invalid');input.removeAttribute('aria-describedby');error.hidden=true},{once:true})}
function openScheduleAdd(date=ui.day,period='上午'){ui.day=date;openOverlay('添加日程',`<p class="sheet-date">${dateLabel(date)} · ${weekday(date)}</p><div class="choice-row" role="group" aria-label="日程类型"><button class="active" data-schedule-kind="reference">引用已有任务</button><button data-schedule-kind="temporary">临时日程项</button></div><div id="schedule-source"></div><label for="schedule-period">时间段</label><select id="schedule-period">${periods.map(p=>`<option ${p===period?'selected':''}>${p}</option>`).join('')}</select><label for="schedule-time">具体时间（可选）</label><input id="schedule-time" type="time"><p class="error" hidden></p><div class="sheet-footer"><span>给这一天留个安排</span><button class="sheet-submit" data-save-schedule>添加 ${icon('plus')}</button></div>`,sheet=>{
  let kind='reference',temporaryDraft='',referenceDraft='';const source=$('#schedule-source',sheet);function renderSource(){$('.error',sheet).hidden=true;$('[data-save-schedule]',sheet).disabled=kind==='reference'&&!data.tasks.some(t=>!t.done);$$('[data-schedule-kind]',sheet).forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.scheduleKind===kind)));source.innerHTML=kind==='reference'?`<label for="schedule-task">选择已有任务</label>${data.tasks.some(t=>!t.done)?'':emptyState('没有可引用的未完成任务','可以切换到临时日程项，直接记下安排。')}<select id="schedule-task" ${data.tasks.some(t=>!t.done)?'':'hidden'}>${data.tasks.filter(t=>!t.done).map(t=>`<option value="${t.id}" ${t.id===referenceDraft?'selected':''}>${esc(t.name)}</option>`).join('')}</select>`:`<label for="schedule-title">日程内容</label><input id="schedule-title" placeholder="只属于这一天的事项" value="${esc(temporaryDraft)}">`}
  $$('.choice-row button',sheet).forEach(btn=>btn.onclick=()=>{temporaryDraft=$('#schedule-title',sheet)?.value??temporaryDraft;referenceDraft=$('#schedule-task',sheet)?.value??referenceDraft;kind=btn.dataset.scheduleKind;$$('.choice-row button',sheet).forEach(b=>b.classList.toggle('active',b===btn));renderSource()});renderSource();
  $('[data-save-schedule]',sheet).onclick=()=>{const taskId=kind==='reference'?$('#schedule-task',sheet)?.value:null,title=kind==='temporary'?$('#schedule-title',sheet)?.value.trim():'';if(kind==='temporary'&&!title)return showFieldError($('#schedule-title',sheet),'请填写日程内容');if(kind==='reference'&&!taskId){$('.error',sheet).hidden=false;$('.error',sheet).textContent=kind==='reference'?'没有可引用的未完成任务':'请填写日程内容';return}if(taskId&&data.schedule.some(x=>x.taskId===taskId&&x.date===date&&x.period===$('#schedule-period',sheet).value&&x.time===$('#schedule-time',sheet).value)){const error=$('.error',sheet);error.hidden=false;error.textContent='这个任务已安排在相同时间，无需重复添加';return}data.schedule.push({id:uid('s'),date,period:$('#schedule-period',sheet).value,time:$('#schedule-time',sheet).value,taskId,title,done:false});save();closeOverlay();renderSchedule();toast('已加入日程')};
},'schedule')}
function toggleSchedule(id){const item=data.schedule.find(x=>x.id===id);if(!item)return;const next=!(item.taskId?taskById(item.taskId)?.done:item.done);item.done=next;if(item.taskId){const task=taskById(item.taskId);if(task)task.done=next;data.schedule.filter(x=>x.taskId===item.taskId).forEach(x=>x.done=next)}save();renderSchedule();toast(next?'已完成':'已恢复未完成')}
function openWeekAdd(){openOverlay('添加本周计划',`<label for="week-content">计划内容</label><input id="week-content" placeholder="这一周要做的事"><button class="sheet-submit" data-save-week>添加</button>`,sheet=>{$('[data-save-week]',sheet).onclick=()=>{const text=$('#week-content',sheet).value.trim();if(!text)return showFieldError($('#week-content',sheet),'请填写计划内容');data.weekPlans.push({id:uid('w'),text,done:false});save();closeOverlay();renderWeekPopover();toast('已加入本周计划')};$('#week-content',sheet).focus()})}
function openLeisureAdd(){openOverlay('添加休闲活动',`<label class="visually-hidden" for="leisure-name">活动名称</label><input class="sheet-name" id="leisure-name" placeholder="留一点时间，做喜欢的事…" autocomplete="off"><div class="sheet-footer"><span>散步、听音乐，或只是发会儿呆</span><button class="sheet-submit" data-save-leisure>添加 ${icon('plus')}</button></div>`,sheet=>{$('[data-save-leisure]',sheet).onclick=()=>{const name=$('#leisure-name',sheet).value.trim();if(!name)return showFieldError($('#leisure-name',sheet),'请填写活动名称');data.leisure.push(name);save();closeOverlay();renderAction();toast('已添加')};$('#leisure-name',sheet).addEventListener('keydown',e=>{if(e.key==='Enter')$('[data-save-leisure]',sheet).click()});$('#leisure-name',sheet).focus()},'leisure')}
function openQuoteEdit(){openOverlay('日计划背景与文字',`<label for="quote-input">写一两句话</label><textarea id="quote-input">${esc(data.quote)}</textarea><label for="background-input">背景颜色</label><input id="background-input" type="color" value="${esc(data.background||'#f4f4f2')}"><label for="background-file">或使用自己的图片</label><input id="background-file" type="file" accept="image/*"><label class="inline-check"><input type="checkbox" id="background-clear"> 移除已有图片</label><button class="sheet-submit" data-save-quote>保存</button>`,sheet=>{$('[data-save-quote]',sheet).onclick=async()=>{const button=$('[data-save-quote]',sheet),quote=$('#quote-input',sheet).value.trim(),background=$('#background-input',sheet).value,file=$('#background-file',sheet).files[0];let image=$('#background-clear',sheet).checked?'':data.backgroundImage;button.disabled=true;button.textContent='正在保存…';sheet.setAttribute('aria-busy','true');try{if(file)image=await shrinkImage(file);if(!sheet.isConnected)return;data.quote=quote;data.background=background;data.backgroundImage=image;save();closeOverlay();renderSchedule();toast('已更新日计划背景')}catch{if(sheet.isConnected)showFieldError($('#background-file',sheet),'无法读取图片，请换一张图片重试')}finally{button.disabled=false;button.textContent='保存';sheet.removeAttribute('aria-busy')}}})}
function shrinkImage(file){return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onerror=reject;reader.onload=()=>{const image=new Image();image.onerror=reject;image.onload=()=>{const ratio=Math.min(1,720/image.width,1280/image.height),canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(image.width*ratio));canvas.height=Math.max(1,Math.round(image.height*ratio));canvas.getContext('2d').drawImage(image,0,0,canvas.width,canvas.height);resolve(canvas.toDataURL('image/jpeg',.72))};image.src=reader.result};reader.readAsDataURL(file)})}

function searchItems(query){const q=query.toLocaleLowerCase().trim();if(!q)return [];
  return [...data.objects.filter(o=>o.name.toLocaleLowerCase().includes(q)).map(o=>({label:o.name,type:'对象',id:o.id})),
    ...data.tasks.filter(t=>(t.name+' '+(t.description||'')).toLocaleLowerCase().includes(q)).map(t=>({label:t.name,type:'任务',id:t.id})),
    ...data.objects.flatMap(o=>[...(o.materials||[]).filter(m=>m.name.toLocaleLowerCase().includes(q)).map(m=>({label:m.name,type:'材料',id:m.id,parent:o.id})),...(o.notes||[]).filter(n=>(n.name+' '+n.body).toLocaleLowerCase().includes(q)).map(n=>({label:n.name,type:'说明',id:n.id,parent:o.id}))])];
}
function openSearch(){openOverlay('搜索',`<input id="search-input" aria-label="搜索对象、任务、材料或说明" placeholder="对象、任务、材料、说明"><div class="sheet-list" id="search-results"></div>`,sheet=>{const input=$('#search-input',sheet);input.oninput=()=>{$('#search-results',sheet).innerHTML=searchItems(input.value).map((r,i)=>`<button class="sheet-result" data-search-result="${i}">${esc(r.label)}<small>${r.type}</small></button>`).join('')||`<p class="empty">${input.value.trim()?'没有找到相关内容':'输入关键词，让内容浮现'}</p>`};input.oninput();input.focus();sheet.addEventListener('click',e=>{const b=e.target.closest('[data-search-result]');if(!b)return;const r=searchItems(input.value)[Number(b.dataset.searchResult)];closeOverlay();if(r.type==='材料')openMaterial(r.parent,r.id);else if(r.type==='说明')openNote(r.parent,r.id);else if(r.type==='任务'){focusGraphNode(r.id);openTask(r.id)}else focusGraphNode(r.id)})},'search')}
const reminderDays=new Set();
function reminderTasks(now=new Date()){const key=dateKey(now);return data.tasks.filter(t=>!t.done&&t.due&&t.due>key&&t.due<=dayOffset(now,3)).sort((a,b)=>a.due.localeCompare(b.due))}
function checkDailyReminder(){
  const now=new Date(),key=dateKey(now),flag='agenda-reminder-shown-'+key;
  if(document.hidden||$('#overlay-root').children.length||reminderDays.has(key))return;
  try{if(localStorage.getItem(flag)){reminderDays.add(key);return}}catch{}
  reminderDays.add(key);try{localStorage.setItem(flag,'1')}catch{}
  const upcoming=reminderTasks(now);if(!upcoming.length)return;
  openOverlay('接下来三天',`<p class="reminder-caption">从明天起，未来三个自然日内截止的未完成任务；今天和逾期任务请在事项中查看。</p><div class="reminder-list">${upcoming.map(t=>`<button class="sheet-result" data-task="${esc(t.id)}"><span>${esc(t.name)}</span><small>${dateLabel(t.due)}</small></button>`).join('')}</div><div class="sheet-footer"><span>今天关闭后不再自动提醒</span><button class="sheet-submit" data-close>知道了 ${icon('check')}</button></div>`,null,'reminder');
}
function refreshLocalDay(){const now=new Date();if(dateKey(now)!==dateKey(today)){const previous=dateKey(today);today=now;if(ui.day===previous)ui.day=dateKey(now);ui.expandedDay=null;render()}checkDailyReminder()}
window.addEventListener('focus',refreshLocalDay);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshLocalDay()});
setInterval(refreshLocalDay,60000);

document.addEventListener('click',e=>{
  const b=e.target.closest('button,[data-day],[data-card],[data-action-card],[data-edit-quote]');if(!b)return;
  if(b.hasAttribute('data-retry-save')){if(save())toast('已保存到本机');return}
  if(b.hasAttribute('data-export-recovery')){const url=URL.createObjectURL(new Blob([recoveryRaw??''],{type:'application/json'})),link=document.createElement('a');link.href=url;link.download='agenda-recovery.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return}
  if(b.hasAttribute('data-empty-create')){openTaskCreate();return}
  if(b.dataset.main){setMain(b.dataset.main);return}
  if(b.id==='header-left'){if(ui.main==='things'){ui.thingsMode=ui.thingsMode==='graph'?'board':'graph';render()}else if(ui.main==='schedule'){ui.scheduleView=ui.scheduleView==='hanging'?'full':'hanging';if(ui.scheduleView==='hanging')ui.expandedDay=null;renderHeader();renderSchedule(true)}return}
  if(b.id==='header-extra'){if(ui.main==='schedule'){ui.weekOpen=!ui.weekOpen;renderHeader();renderWeekPopover()}return}
  if(b.id==='header-search'){openSearch();return}if(b.id==='header-add'){openCreate();return}
  if(b.hasAttribute('data-overview')){fitGraph();return}
  if(b.dataset.graphNode){if(ui.ignoreNodeClick)return;const layout=graphLayout(),node=layout.nodes.find(n=>n.id===b.dataset.graphNode);if(!node)return;const p=node.payload;if(p.type==='object'||p.type==='branch'){ui.graphCollapsed.has(node.id)?ui.graphCollapsed.delete(node.id):ui.graphCollapsed.add(node.id);renderGraph()}else if(p.type==='task')openTask(p.id);else if(p.type==='material')openMaterial(p.objectId,p.id);else openNote(p.objectId,p.id);return}
  if(b.dataset.task){openTask(b.dataset.task);return}if(b.dataset.object){openObject(b.dataset.object);return}if(b.dataset.material){const [o,m]=b.dataset.material.split(':');openMaterial(o,m);return}if(b.dataset.note){const [o,n]=b.dataset.note.split(':');openNote(o,n);return}
  if(b.dataset.actionCard){ui.expandedAction=ui.expandedAction===b.dataset.actionCard?null:b.dataset.actionCard;renderAction();return}
  if(b.hasAttribute('data-add-leisure')){openLeisureAdd();return}
  if(b.dataset.toggleTask){const t=taskById(b.dataset.toggleTask);if(t){t.done=!t.done;data.schedule.filter(x=>x.taskId===t.id).forEach(x=>x.done=t.done);save();if($('.overlay'))closeOverlay();render();toast(t.done?'已完成':'已恢复未完成')}return}
  if(b.dataset.toggleSchedule){toggleSchedule(b.dataset.toggleSchedule);return}
  if(b.dataset.addDay){openScheduleAdd(b.dataset.addDay,b.dataset.period||'上午');return}
  if(b.dataset.day){ui.day=b.dataset.day;ui.expandedDay=ui.expandedDay===b.dataset.day?null:b.dataset.day;renderSchedule();return}
  if(b.hasAttribute('data-close-week')){ui.weekOpen=false;renderHeader();renderWeekPopover();return}
  if(b.dataset.toggleWeek){const item=data.weekPlans.find(w=>w.id===b.dataset.toggleWeek);if(item){item.done=!item.done;save();renderWeekPopover()}return}
  if(b.hasAttribute('data-add-week')){openWeekAdd();return}if(b.hasAttribute('data-edit-quote')){openQuoteEdit();return}
  if(b.dataset.addMaterial){openMaterialAdd(b.dataset.addMaterial);return}if(b.dataset.addNote){openNoteAdd(b.dataset.addNote);return}
});
document.addEventListener('keydown',e=>{if(e.key==='Escape'){requestCloseOverlay();if(ui.weekOpen){ui.weekOpen=false;renderHeader();renderWeekPopover()}}if((e.key==='Enter'||e.key===' ')&&e.target.matches('.hang-day')){e.preventDefault();ui.day=e.target.dataset.day;ui.expandedDay=ui.expandedDay===e.target.dataset.day?null:e.target.dataset.day;renderSchedule()}if((e.key==='Enter'||e.key===' ')&&e.target.matches('.action-card')){e.preventDefault();ui.expandedAction=ui.expandedAction===e.target.dataset.actionCard?null:e.target.dataset.actionCard;renderAction()}});
document.addEventListener('pointerdown',e=>{if(ui.weekOpen&&!e.target.closest('#week-root,#header-extra,.overlay')){ui.weekOpen=false;renderHeader();renderWeekPopover()}});
const primaryNav=$('.primary-nav');
primaryNav.addEventListener('click',e=>{if(!e.target.closest('button'))primaryNav.classList.add('revealed')});
primaryNav.addEventListener('keydown',e=>{if(e.target===primaryNav&&(e.key==='Enter'||e.key===' ')){e.preventDefault();primaryNav.classList.add('revealed')}});
$('#main').addEventListener('pointerdown',()=>primaryNav.classList.remove('revealed'));
window.addEventListener('resize',()=>{if(ui.main==='things'&&ui.thingsMode==='graph'){const view=$('.graph-viewport');if(view){ui.graph.scale=Math.min(.82,view.clientWidth/ui.graph.width);ui.graph.tx=(view.clientWidth-ui.graph.width*ui.graph.scale)/2;ui.graph.ty=88;applyGraphTransform()}}const dayScroll=$('.schedule-day-scroll');if(dayScroll){dayScroll.style.setProperty('--schedule-anchor',`${Math.round($('#app').clientHeight*.19)}px`);requestAnimationFrame(centerScheduleDay)}});
render();checkDailyReminder();
