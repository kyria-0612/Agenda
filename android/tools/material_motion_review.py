"""Measure rendered card sizes, record real native motion, inspect the reference glass.
No whole-device settings or logs are modified. Physical targets require explicit flag.
"""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--adb',required=True);p.add_argument('--serial',required=True)
p.add_argument('--allow-physical',action='store_true');p.add_argument('--skip-install',action='store_true')
a=p.parse_args()
if not a.serial.startswith('emulator-') and not a.allow_physical: raise SystemExit('Physical target requires explicit authorization.')
root=Path(__file__).resolve().parents[1]
out=root/'review/material-motion-0.3.0'
out.mkdir(parents=True,exist_ok=True)
report=[]
def adb(*cmd,binary=False):
    r=subprocess.run([a.adb,'-s',a.serial,*cmd],check=True,capture_output=True,timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
    adb('shell','uiautomator','dump','/sdcard/agenda-material.xml')
    return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-material.xml'))
def find(label,attr='content-desc'):
    matches=[n for n in tree().iter('node') if n.get(attr)==label]
    if not matches: raise AssertionError('Missing '+label)
    return matches[0]
def bounds(n): return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(label,attr='content-desc'):
    x1,y1,x2,y2=bounds(find(label,attr))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.8)
def capture(name):
    state=adb('shell','dumpsys','activity','activities')
    assert any('com.agenda.motiondemo' in s for s in state.splitlines() if 'ResumedActivity' in s)
    t=tree();time.sleep(.15)
    (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    (out/f'{name}.xml').write_text(ET.tostring(t,encoding='unicode'),encoding='utf-8')
def check(name,result,**data):
    report.append({'check':name,'passed':bool(result),**data});save()
    if not result: raise AssertionError(name)
def save(): (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
try:
    if not a.skip_install: print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
    adb('shell','am','force-stop','com.agenda.motiondemo')
    adb('shell','am','start','-W','-n','com.agenda.motiondemo/com.agenda.app.MainActivity');time.sleep(1)
    tap('切换事项视图');capture('01-board-collapsed')
    x1,y1,x2,y2=bounds(find('事项卡片：光电课程'))
    header=(x1+45,y1+35)
    # Warm up text/font and renderer caches, then measure without the software
    # encoder competing with rendering. The video is captured separately below.
    for _ in range(2):
        adb('shell','input','tap',str(header[0]),str(header[1]));time.sleep(1)
    pid=adb('shell','pidof','com.agenda.motiondemo').strip()
    prior=adb('logcat','-d',f'--pid={pid}','-s','AgendaBoardMotion:D')
    marker=max(int(m) for m in re.findall(r'(\d+)\|course\|',prior))
    adb('shell','input','tap',str(header[0]),str(header[1]));time.sleep(1.0)
    logs=adb('logcat','-d',f'--pid={pid}','-s','AgendaBoardMotion:D')
    (out/'native-motion-frames.txt').write_text(logs,encoding='utf-8')
    frames=[tuple(map(int,m)) for m in re.findall(r'(\d+)\|course\|(\d+)\|(\d+)',logs) if int(m[0])>=marker]
    check('native-intermediate-size-frames',len(set((w,h) for _,w,h in frames))>=6,distinctSizes=len(set((w,h) for _,w,h in frames)))
    check('width-and-height-both-grow',frames[-1][1]>frames[0][1] and frames[-1][2]>frames[0][2],start=frames[0][1:],end=frames[-1][1:])
    check('visible-elastic-overshoot',max(w for _,w,_ in frames)>frames[-1][1]+2,
          maxWidth=max(w for _,w,_ in frames),restWidth=frames[-1][1])
    capture('02-board-expanded')
    video=subprocess.Popen([a.adb,'-s',a.serial,'shell','screenrecord','--time-limit','5','--bit-rate','2000000','/sdcard/agenda-board-0.3.mp4'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    time.sleep(.4)
    adb('shell','input','tap',str(header[0]),str(header[1]));time.sleep(.18)
    adb('shell','input','tap',str(header[0]),str(header[1]));time.sleep(.18)
    adb('shell','input','tap',str(header[0]),str(header[1]));time.sleep(1)
    capture('03-board-after-interruption')
    check('interruptible-return-to-rest',bounds(find('事项卡片：光电课程'))[2]-bounds(find('事项卡片：光电课程'))[0] <= (x2-x1)+3)
    stdout,stderr=video.communicate(timeout=20)
    if video.returncode==0:
        adb('pull','/sdcard/agenda-board-0.3.mp4',str(out/'board-motion.mp4'))
    else: report.append({'videoWarning':stderr.decode('utf-8',errors='replace')})
    tap('切换事项视图');tap('新建演示任务');time.sleep(.5);capture('04-reference-create-glass')
    tap('关闭');tap('课程讲义.pdf','text');capture('05-material-glass')
    tap('关闭');tap('搜索');capture('06-search-glass');tap('关闭')
    tap('日程');tap('周计划演示');capture('07-week-glass');tap('关闭')
    errors=adb('logcat','-d',f'--pid={pid}','-s','AndroidRuntime:E','AgendaGlass:W')
    (out/'runtime-errors.txt').write_text(errors,encoding='utf-8')
    check('blur-prepared-and-no-crash','FATAL EXCEPTION' not in errors and 'opaque fallback' not in errors)
except Exception as e:
    report.append({'error':str(e)});save();raise
save();print(json.dumps(report,ensure_ascii=False,indent=2))
