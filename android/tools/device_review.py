"""ADB smoke review for this demo, restricted to an explicitly selected emulator.

Usage: python tools/device_review.py --serial emulator-5554 --adb D:/.../adb.exe
Requires a booted emulator and a successfully built APK. Emits screenshots and a JSON report.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('This automated review only installs on an explicitly selected emulator.')
root = Path(__file__).resolve().parents[1]
out = root / 'review'
out.mkdir(exist_ok=True)
report = []

def adb(*command, binary=False):
    result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, check=True, timeout=90)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/agenda-review.xml')
    xml = adb('exec-out', 'cat', '/sdcard/agenda-review.xml')
    return ET.fromstring(xml)

def tap(label, attr='content-desc', required=True):
    nodes = [n for n in tree().iter('node') if n.get(attr)==label]
    if not nodes:
        if required: raise AssertionError(f'Control not found: {label}')
        return False
    bounds = re.findall(r'\d+', nodes[0].get('bounds', ''))
    x1,y1,x2,y2 = map(int,bounds)
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.7)
    return True

def capture(name):
    (out/f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    (out/f'{name}.xml').write_text(ET.tostring(tree(), encoding='unicode'),encoding='utf-8')
    report.append({'capture': name, 'device': args.serial})

adb('install', '-r', str(root/'app/build/outputs/apk/debug/app-debug.apk'))
adb('shell','am','force-stop','com.agenda.motiondemo')
adb('shell','am','start','-W','-n','com.agenda.motiondemo/com.agenda.app.MainActivity')
time.sleep(2)
capture('01-graph')
tap('切换事项视图')
tap('光电课程',attr='text')
capture('02-board-expanded')
tap('行动')
capture('03-action')
tap('完成：整理实验记录')
assert any(n.get('text')=='复习第三章' for n in tree().iter('node')), 'Urgent candidate did not refill'
report.append({'check':'urgent-completion-refills','passed':True})
capture('04-action-completed')
tap('日程')
from datetime import date
tap(str(date.today().day),attr='text',required=False)
capture('05-schedule-hanging')
tap('切换日程视图')
capture('06-schedule-full')
tap('搜索')
capture('07-glass-search')
adb('shell','input','keyevent','4')
tap('事项')
tap('新建演示任务')
capture('08-glass-create')
tap('名称')
adb('shell','input','text','DemoTask')
time.sleep(.5)
tap('关闭')
assert any(n.get('text')=='继续编辑' for n in tree().iter('node')), 'Dirty input was discarded without prompt'
tap('继续编辑',attr='text')
assert any(n.get('text')=='DemoTask' for n in tree().iter('node')), 'Draft was lost'
report.append({'check':'dirty-draft-close-protection','passed':True})
tap('关闭')
tap('放弃编辑',attr='text')
adb('shell','input','keyevent','4')
(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
