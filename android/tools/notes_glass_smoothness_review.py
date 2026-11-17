"""0.5 native review: titled notes, prepared first-frame glass and date collapse."""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',default='emulator-5554');p.add_argument('--dates-only',action='store_true');a=p.parse_args()
assert a.serial.startswith('emulator-'),'This review only drives its test emulator.'
root=Path(__file__).resolve().parents[1];out=root/'review/notes-glass-smoothness-0.5.0';out.mkdir(parents=True,exist_ok=True)
report=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],capture_output=True,check=True,timeout=90)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda05.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda05.xml'))
def find(label,attr='content-desc'):
 nodes=[n for n in tree().iter('node') if n.get(attr)==label];assert nodes,'Missing '+label
 return nodes[0]
def bounds(n):return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def point(x,y,delay=.65):adb('shell','input','tap',str(x),str(y));time.sleep(delay)
def tap(label,attr='content-desc'):
 x,y,r,b=bounds(find(label,attr));point((x+r)//2,(y+b)//2)
def capture(name):
 assert any('com.agenda.motiondemo' in l for l in adb('shell','dumpsys','activity','activities').splitlines() if 'ResumedActivity' in l)
 t=tree();(out/f'{name}.xml').write_text(ET.tostring(t,encoding='unicode'),encoding='utf-8')
 (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
def check(name,value,**details):
 report.append(dict(check=name,passed=bool(value),**details));save();assert value,name
def save():(out/('date-final-report.json' if a.dates_only else 'report.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
def logs(tag):return adb('logcat','-d',f'--pid={pid}','-s',tag+':D')
try:
 print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
 adb('shell','am','force-stop','com.agenda.motiondemo');adb('shell','am','start','-W','-n','com.agenda.motiondemo/com.agenda.app.MainActivity');time.sleep(.8)
 pid=adb('shell','pidof','com.agenda.motiondemo').strip()
 if not a.dates_only:
  capture('01-brand-and-note-titles')
  x,y,r,b=bounds(find('Agenda品牌'));check('brand-still-centered-and-same-height',abs((x+r)/2-540)<2 and b-y==53,heightPx=b-y)
  check('graph-shows-note-title-not-body',find('报告要求','text') is not None and all(n.get('text')!='说明测量过程与结果' for n in tree().iter('node')))
  tap('报告要求','text');capture('02-existing-note-body')
  check('note-title-opens-body',find('说明测量过程与结果','text') is not None)
  tap('关闭');tap('说明','text');tap('添加说明','text');tap('保存说明','text')
  check('empty-note-title-is-rejected',find('请先填写说明标题','text') is not None)
  tap('说明标题');adb('shell','input','text','NoteTitle05');tap('说明内容');adb('shell','input','text','PrivateBody05');adb('shell','input','keyevent','111')
  capture('03-note-title-body-form');tap('保存说明','text');capture('04-note-list-titles-only')
  check('note-list-hides-body',find('NoteTitle05','text') is not None and all(n.get('text')!='PrivateBody05' for n in tree().iter('node')))
  tap('关闭');check('graph-new-note-hides-body',find('NoteTitle05','text') is not None and all(n.get('text')!='PrivateBody05' for n in tree().iter('node')))
  tap('NoteTitle05','text');capture('05-created-note-open');check('created-note-opens-its-body',find('PrivateBody05','text') is not None);tap('关闭')
  tap('搜索');tap('搜索内容');adb('shell','input','text','PrivateBody05');time.sleep(.4)
  check('search-body-returns-note-title',find('NoteTitle05','text') is not None);tap('关闭')
  tap('新建演示任务');capture('06-glass-final-material-first-mount');tap('关闭')
  ready=[tuple(map(int,m)) for m in re.findall(r'(\d+)\|[^|\r\n]+\|(\d+)',logs('AgendaGlassReady'))]
  first=[int(n) for n in re.findall(r'(\d+)\|prepared=true',logs('AgendaGlassFirstFrame'))]
  check('all-visible-glass-frames-have-prepared-material',len(first)>=4 and all(any(t<=frame for t,_ in ready) for frame in first),firstMounts=len(first),preparationMs=[ms for _,ms in ready])
  (out/'glass-first-frame.txt').write_text(logs('AgendaGlassReady')+logs('AgendaGlassFirstFrame'),encoding='utf-8')
 tap('日程');capture('07-dates-compact')
 closed=[n for n in tree().iter('node') if n.get('content-desc','').startswith('展开日期：')]
 chosen=min(closed,key=lambda n:abs((bounds(n)[0]+bounds(n)[2])/2-540)).get('content-desc')
 date=chosen.split('：')[1]
 tap(chosen)
 x,y,r,b=bounds(find('收起日期：'+date));point(x+35,y+35)
 adb('shell','dumpsys','gfxinfo','com.agenda.motiondemo','reset')
 for cycle in range(3):
  tap(chosen)
  n=find('收起日期：'+date);x,y,r,b=bounds(n)
  check(f'date-center-{cycle}',abs((x+r)/2-540)<4,center=(x+r)/2)
  if cycle==0:capture('08-date-expanded')
  old=logs('AgendaDateMotion');marker=max(int(n) for n in re.findall(r'(\d+)\|'+re.escape(date)+r'\|',old))
  point(x+35,y+35)
  frames=[tuple(map(int,m)) for m in re.findall(r'(\d+)\|'+re.escape(date)+r'\|(\d+)\|(\d+)',logs('AgendaDateMotion')) if int(m[0])>=marker]
  details=[int(n) for n in re.findall(r'(\d+)\|[^|]+\|composed',logs('AgendaDateDetails')) if int(n)>marker]
  check(f'collapse-keeps-detail-composition-stable-{cycle}',not details,newDetailTrees=len(details))
  check(f'collapse-has-continuous-size-frames-{cycle}',len({(w,h) for _,w,h in frames})>=6,distinctSizes=len({(w,h) for _,w,h in frames}))
  # Ignore the idle interval before the first changing frame.
  gaps=[b[0]-a[0] for a,b in zip(frames[1:],frames[2:])]
  report.append(dict(collapseCycle=cycle,maxMeasuredSizeGapMs=max(gaps,default=0)))
 capture('09-dates-collapsed')
 (out/'final-0.5-gfxinfo.txt').write_text(adb('shell','dumpsys','gfxinfo','com.agenda.motiondemo','framestats'),encoding='utf-8')
 (out/'date-native-motion.txt').write_text(logs('AgendaDateMotion')+logs('AgendaDateDetails'),encoding='utf-8')
 errors=adb('logcat','-d',f'--pid={pid}','-s','AndroidRuntime:E','AgendaGlass:W')
 (out/'runtime-errors.txt').write_text(errors,encoding='utf-8')
 check('no-crash-or-backdrop-failure','FATAL EXCEPTION' not in errors and 'preparation failed' not in errors)
except Exception as e:report.append(dict(error=str(e)));save();raise
save();print(json.dumps(report,ensure_ascii=False,indent=2))
