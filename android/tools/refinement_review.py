"""Native review of 0.4: geometry, full-card taps, unified motion and creation."""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',default='emulator-5554');a=p.parse_args()
assert a.serial.startswith('emulator-'),'This review only drives its test emulator.'
root=Path(__file__).resolve().parents[1];out=root/'review/refinement-0.4.0';out.mkdir(parents=True,exist_ok=True)
report=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],capture_output=True,check=True,timeout=90)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda04.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda04.xml'))
def find(label,attr='content-desc'):
 nodes=[n for n in tree().iter('node') if n.get(attr)==label]
 assert nodes,'Missing '+label
 return nodes[0]
def bounds(n):return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def point(x,y):adb('shell','input','tap',str(x),str(y));time.sleep(.6)
def tap(label,attr='content-desc'):
 x,y,r,b=bounds(find(label,attr));point((x+r)//2,(y+b)//2)
def capture(name):
 state=adb('shell','dumpsys','activity','activities')
 assert any('com.agenda.motiondemo' in l for l in state.splitlines() if 'ResumedActivity' in l)
 t=tree();(out/f'{name}.xml').write_text(ET.tostring(t,encoding='unicode'),encoding='utf-8')
 (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
def check(name,value,**details):
 report.append(dict(check=name,passed=bool(value),**details));save();assert value,name
def save():(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
try:
 print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
 adb('shell','am','force-stop','com.agenda.motiondemo');adb('shell','am','start','-W','-n','com.agenda.motiondemo/com.agenda.app.MainActivity');time.sleep(1)
 capture('01-centered-graph')
 t=tree();names={'完成实验报告','复习第三章','课程讲义.pdf','实验数据.png','页面架构.png','探索事项 UI','整理交互规则'}
 boxes=[bounds(n) for n in t.iter('node') if n.get('text') in names]
 center=(min(b[0] for b in boxes)+max(b[2] for b in boxes))/2
 check('graph-content-horizontally-centered',abs(center-540)<24,contentCenter=center,viewportCenter=540)
 tap('切换事项视图');capture('02-board-rest')
 x,y,r,b=bounds(find('事项卡片：光电课程'))
 point(x+35,b-25);capture('03-board-expanded-from-body')
 expanded=bounds(find('事项卡片：光电课程'))
 check('whole-collapsed-card-tap-expands',expanded[2]-expanded[0]>r-x+50)
 point(expanded[0]+25,expanded[1]+20)
 # Warmed measurement without software video encoding.
 pid=adb('shell','pidof','com.agenda.motiondemo').strip()
 prior=adb('logcat','-d',f'--pid={pid}','-s','AgendaBoardMotion:D')
 marker=max(map(int,re.findall(r'(\d+)\|course\|',prior)))
 point(x+35,b-25)
 logs=adb('logcat','-d',f'--pid={pid}','-s','AgendaBoardMotion:D')
 frames=[tuple(map(int,m)) for m in re.findall(r'(\d+)\|course\|(\d+)\|(\d+)',logs) if int(m[0])>=marker]
 (out/'native-motion.txt').write_text(logs,encoding='utf-8')
 check('continuous-container-size-frames',len({(w,h) for _,w,h in frames})>=6,distinctSizes=len({(w,h) for _,w,h in frames}))
 check('restrained-width-overshoot',max(w for _,w,h in frames)-frames[-1][1]<=12,maxWidth=max(w for _,w,h in frames),restWidth=frames[-1][1])
 tap('行动');tap('行动卡片：随手做');capture('04-action-same-container')
 tap('事项');tap('切换事项视图');tap('新建演示任务');capture('05-translucent-glass')
 tap('名称');adb('shell','input','text','Review04');adb('shell','input','keyevent','111')
 tap('归属、日期、意愿','text');tap('日期','text');tap('明天','text')
 adb('shell','input','swipe','520','1850','520','1000','500');time.sleep(.5);capture('06-date-calendar')
 check('calendar-separates-weekday-and-day-values',any(n.get('content-desc','').startswith('截止日期：') for n in tree().iter('node')))
 adb('shell','input','swipe','520','950','520','1820','500');time.sleep(.5)
 tap('创建','text')
 check('task-created',any(n.get('text')=='Review04' for n in tree().iter('node')))
 tap('说明','text');tap('添加说明','text');tap('说明标题');adb('shell','input','text','Review-title');tap('说明内容');adb('shell','input','text','Review-note');adb('shell','input','keyevent','111');tap('保存说明','text');capture('07-note-created')
 check('note-created-in-object',any(n.get('text')=='Review-title' for n in tree().iter('node')))
 tap('关闭');tap('材料','text');capture('08-material-entry')
 check('separate-material-create-entry',find('添加材料','text') is not None)
 fixture=out/'agenda04.txt';fixture.write_text('Agenda 0.4 attachment import verification.\n',encoding='utf-8')
 adb('push',str(fixture),'/sdcard/Download/agenda04.txt')
 adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/agenda04.txt')
 tap('添加材料','text');tap('Show roots');tap('Downloads','text');tap('agenda04.txt','text');time.sleep(.7)
 capture('10-material-imported')
 paths=adb('shell','run-as','com.agenda.motiondemo','find','files/demo-materials','-name','agenda04.txt').strip().splitlines()
 check('material-import-created-private-copy',bool(paths))
 check('material-private-copy-matches-file',adb('exec-out','run-as','com.agenda.motiondemo','cat',paths[-1],binary=True)==fixture.read_bytes())
 tap('关闭');tap('日程')
 t=tree();closed=[n for n in t.iter('node') if n.get('content-desc','').startswith('展开日期：')]
 target=closed[len(closed)//2].get('content-desc');tap(target);capture('09-hanging-date-expanded')
 opened=next(n for n in tree().iter('node') if n.get('content-desc','').startswith('收起日期：'))
 x,y,r,b=bounds(opened)
 check('expanded-date-focused',abs((x+r)/2-540)<30,center=(x+r)/2)
 errors=adb('logcat','-d',f'--pid={pid}','-s','AndroidRuntime:E','AgendaGlass:W')
 (out/'runtime-errors.txt').write_text(errors,encoding='utf-8')
 check('no-app-crash-or-blur-fallback','FATAL EXCEPTION' not in errors and 'opaque fallback' not in errors)
except Exception as e:
 report.append(dict(error=str(e)));save();raise
save();print(json.dumps(report,ensure_ascii=False,indent=2))
