"""Regression review for the explicitly selected Agenda test device.

Physical devices require --allow-physical. Only Agenda is launched and captured.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--adb',required=True)
parser.add_argument('--serial',required=True)
parser.add_argument('--allow-physical',action='store_true')
parser.add_argument('--skip-install',action='store_true')
args=parser.parse_args()
if not args.serial.startswith('emulator-') and not args.allow_physical:
    raise SystemExit('Physical device review requires explicit --allow-physical.')
root=Path(__file__).resolve().parents[1]
out=root/'review/feedback-2026-10-27'
out.mkdir(parents=True,exist_ok=True)
results=[]

def adb(*cmd,binary=False):
    r=subprocess.run([args.adb,'-s',args.serial,*cmd],capture_output=True,timeout=90,check=True)
    return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')

def save_report():
    (out/'report.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')

def tree():
    adb('shell','uiautomator','dump','/sdcard/agenda-feedback.xml')
    return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-feedback.xml'))

def find(label,attr='content-desc'):
    return [n for n in tree().iter('node') if n.get(attr)==label
            and (attr!='text' or n.get('class')!='android.widget.EditText')]

def bounds(node):
    return tuple(map(int,re.findall(r'-?\d+',node.get('bounds',''))))

def tap(label,attr='content-desc'):
    nodes=find(label,attr)
    if not nodes: raise AssertionError('Missing control: '+label)
    x1,y1,x2,y2=bounds(nodes[0])
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.8)

def text(value):
    adb('shell','input','text',value)
    time.sleep(.4)

def capture(name):
    # Do not capture other apps if the user switches away while this script runs.
    state=adb('shell','dumpsys','activity','activities')
    resumed=[line for line in state.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line]
    if not any('com.agenda.motiondemo' in line for line in resumed):
        raise AssertionError('Agenda is not foreground; capture stopped.')
    hierarchy=tree()  # Wait for layout/animation idleness before reading the frame.
    time.sleep(.2)
    (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    (out/f'{name}.xml').write_text(ET.tostring(hierarchy,encoding='unicode'),encoding='utf-8')
    results.append({'capture':name});save_report()

def check(name,passed,**extra):
    results.append({'check':name,'passed':bool(passed),**extra});save_report()
    if not passed: raise AssertionError(name)

def brand_center():
    x1,_,x2,_=bounds(find('Agenda品牌')[0])
    check('brand-center',abs((x1+x2)/2-width/2)<=3,center=(x1+x2)/2,screenCenter=width/2)

try:
    if not args.skip_install:
        print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
    adb('shell','am','force-stop','com.agenda.motiondemo')
    adb('shell','am','start','-W','-n','com.agenda.motiondemo/com.agenda.app.MainActivity')
    time.sleep(1.2)
    width,height=map(int,re.findall(r'(\d+)x(\d+)',adb('shell','wm','size'))[-1])
    today=datetime.strptime(adb('shell','date','+%Y-%m-%d').strip(),'%Y-%m-%d').date()
    brand_center();capture('01-graph')
    # Releasing a single-pointer drag used to publish NaN and hide the graph.
    for ex,ey in [(8,height//2),(width-8,height//2),(width//2,200),(width//2,height-230)]:
        adb('shell','input','swipe',str(width//2),str(height//2),str(ex),str(ey),'450')
        time.sleep(.55)
    visible=find('光电课程','text')
    check('graph-survives-edge-drags',bool(visible) and bounds(visible[0])[2]>bounds(visible[0])[0])
    capture('02-graph-after-drags')
    tap('完成实验报告','text');capture('03-task-glass');tap('关闭')
    tap('课程讲义.pdf','text');capture('04-material-glass');tap('关闭')
    tap('切换事项视图');tap('光电课程','text');capture('05-board-expanded')
    tap('行动');brand_center();capture('06-action')
    tap('事项');tap('新建演示任务');capture('07-create-bottom')
    tap('名称');text('ReviewTask')
    tap('归属、日期、意愿','text')
    tap('光电课程','text')
    tap('日期','text');tap('明天','text')
    tap('意愿','text');tap('想做','text');tap('该做','text')
    capture('08-create-rich-fields')
    tap('创建','text')
    tap('搜索');capture('09-search-top')
    tap('搜索内容');text('ReviewTask');tap('ReviewTask','text')
    detail=tree()
    check('rich-task-owner-saved',any(n.get('text')=='归属：光电课程' for n in detail.iter('node')))
    expected=today+timedelta(days=1)
    check('rich-task-deadline-saved',any(n.get('text')==f'截止日期：{expected.year} 年 {expected.month} 月 {expected.day} 日' for n in detail.iter('node')))
    check('independent-intents-saved',any(n.get('text')=='想做 · 该做' for n in detail.iter('node')))
    capture('10-created-task-details');tap('关闭')
    tap('新建演示任务');tap('名称');text('Draft')
    tap('关闭')
    check('dirty-close-protected',bool(find('继续编辑','text')))
    tap('继续编辑','text')
    check('dirty-draft-retained',any(n.get('text')=='Draft' for n in tree().iter('node')))
    tap('关闭');tap('放弃编辑','text')
    tap('日程');brand_center()
    focus=today+timedelta(days=1)
    tap(f'展开日期：{focus}')
    time.sleep(.6)
    x1,_,x2,_=bounds(find(f'收起日期：{focus}')[0])
    check('expanded-date-centered',abs((x1+x2)/2-width/2)<24,center=(x1+x2)/2,screenCenter=width/2)
    capture('11-hanging-focused')
    tap('切换日程视图');capture('12-full-days')
    tap('周计划演示');capture('13-week-anchor')
    _,y1,_,_=bounds(find('本周计划','text')[0])
    check('week-under-header',y1<height*.25)
    tap('关闭')
    pid=adb('shell','pidof','com.agenda.motiondemo').strip()
    errors=adb('logcat','-d',f'--pid={pid}','-s','AndroidRuntime:E')
    check('no-runtime-crash','FATAL EXCEPTION' not in errors)
except Exception as exc:
    results.append({'error':str(exc)});save_report()
    raise
print(json.dumps(results,ensure_ascii=False,indent=2))
