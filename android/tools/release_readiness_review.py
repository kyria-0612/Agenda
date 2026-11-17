"""Lifecycle and large-font checks on synthetic emulator data only."""
from pathlib import Path
source=Path(__file__).with_name('daily_features_review.py').read_text(encoding='utf-8')
exec(compile(source.split('# Seed')[0],str(Path(__file__)), 'exec'))
out=root/'review/release-readiness-0.11.0';out.mkdir(parents=True,exist_ok=True);report=[]
def rotate(value):
 adb('shell','settings','put','system','accelerometer_rotation','0')
 adb('shell','settings','put','system','user_rotation',str(value));time.sleep(1.4)
def discard():
 tap('关闭')
 if nodes('放弃编辑','text'):tap('放弃编辑','text')
def background_restart():
 adb('shell','input','keyevent','3');time.sleep(.7);adb('shell','am','kill',package);time.sleep(.5);start()
try:
 start()
 if nodes('关闭'):discard()
 tap('事项');tap('新建任务');replace('名称','','Rotate11');hide()
 rotate(1);check('task-draft-survives-rotation',bool(nodes('Rotate11','text')));capture('01-landscape-task-draft')
 rotate(0);check('task-draft-survives-portrait-return',bool(nodes('Rotate11','text')))
 background_restart();check('task-draft-survives-background-process-restart',bool(nodes('Rotate11','text')));discard()
 tap('日程');tap('周计划');tap('添加计划','text');replace('周计划内容','','WeekDraft11');hide()
 rotate(1);check('independent-week-draft-survives-rotation',bool(nodes('WeekDraft11','text')));rotate(0);discard()
 tap('背景与文字','text');replace('日程背景文字','Keep10','PhotoDraft11');hide()
 background_restart();check('appearance-draft-survives-process-restart',bool(nodes('PhotoDraft11','text')));discard()
 day=adb('shell','date','+%Y-%m-%d').strip();tap('展开日期：'+day)
 month,number=map(int,day.split('-')[1:]);tap(f'{month}月{number}日上午添加日程');tap('临时安排','text');replace('临时安排内容','','ScheduleDraft11');hide()
 rotate(1);check('temporary-schedule-draft-survives-rotation',bool(nodes('ScheduleDraft11','text')));rotate(0);discard()
 adb('shell','settings','put','system','font_scale','2.0');time.sleep(1)
 tap('背景与文字','text');capture('02-large-font-background')
 check('large-font-overlay-can-close',bool(nodes('关闭')));tap('关闭')
 check('large-font-navigation-reachable',all(nodes(label) for label in ('事项','行动','日程')))
 tap('事项');tap('新建任务');replace('名称','','Large11');hide();capture('03-large-font-task')
 check('large-font-task-draft-visible',bool(nodes('Large11','text')));discard()
finally:
 adb('shell','settings','put','system','font_scale','1.0');rotate(0)
print(json.dumps(report,ensure_ascii=False,indent=2))

