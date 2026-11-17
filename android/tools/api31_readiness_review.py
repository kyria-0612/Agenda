"""Android 12 native flow on a dedicated, initially empty emulator."""
from pathlib import Path
source=Path(__file__).with_name('daily_features_review.py').read_text(encoding='utf-8')
exec(compile(source.split('# Seed')[0],str(Path(__file__)), 'exec'))
serial='emulator-5556';out=root/'review/release-api31-0.11.0';out.mkdir(parents=True,exist_ok=True);report=[]
import os
if os.environ.get('AGENDA_API31_RESUME')=='caption':
 report=[r for r in json.loads((out/'report.json').read_text(encoding='utf-8')) if r['passed']]
else:
 import os
 if os.environ.get('AGENDA_API31_RESUME')=='reference':
  report=[r for r in json.loads((out/'report.json').read_text(encoding='utf-8')) if r['passed']]
 else:
  start()
  if nodes('关闭'):tap('关闭')
  if (out/'report.json').exists():report=json.loads((out/'report.json').read_text(encoding='utf-8'))
  if not report:check('minimum-supported-api31',adb('shell','getprop','ro.build.version.sdk').strip()=='31')
  if nodes('还没有事项','text'):
   check('first-launch-is-empty',True)
   tap('新建任务');replace('名称','','API31Task');hide();tap('创建','text')
  tap('搜索');replace('搜索内容','','API31Task');hide();click(nodes('API31Task','text')[-1]);tap('编辑说明与关联材料','text')
  replace('任务说明','','API31Description');hide();tap('保存说明与关联','text')
  check('task-description-saves-on-api31',bool(nodes('API31Description','text')));capture('01-task-description-glass')
  tap('安排日程','text');tap('保存日程');tap('日程')
  day=adb('shell','date','+%Y-%m-%d').strip();tap('展开日期：'+day)
  check('shared-task-reference-displays',bool(nodes('API31Task','text')));tap('完成：API31Task')
  with dbcopy('completed') as db:check('schedule-completion-updates-original-task',db.execute('SELECT completed FROM agenda_tasks').fetchone()[0]==1)
  month,number=map(int,day.split('-')[1:]);tap(f'{month}月{number}日上午添加日程');tap('引用任务','text')
  check('completed-tasks-excluded-from-new-reference',not nodes('引用任务：API31Task'))
 hierarchy=tree();parents={child:parent for parent in hierarchy.iter() for child in parent};button=next(n for n in hierarchy.iter('node') if n.get('content-desc')=='保存日程')
 check('empty-reference-save-disabled',parents[button].get('enabled')=='false')
 tap('临时安排','text');replace('临时安排内容','','TempDraft31');hide();tap('引用任务','text');tap('临时安排','text')
 check('temporary-draft-preserved-across-type-switch',bool(nodes('TempDraft31','text')))
 tap('关闭');tap('放弃编辑','text');tap('周计划');tap('添加计划','text');replace('周计划内容','','Week31');hide();tap('保存计划','text')
 with dbcopy('weekly') as db:check('weekly-record-is-independent',db.execute('SELECT count(*) FROM agenda_tasks').fetchone()[0]==1 and db.execute('SELECT count(*) FROM agenda_weekly_plans').fetchone()[0]==1)
 tap('关闭');tap('背景与文字','text');tap('背景颜色：雾蓝');replace('日程背景文字','留些空白，也留些期待。','Caption31');hide();tap('保存背景与文字','text')
day=adb('shell','date','+%Y-%m-%d').strip()
if nodes('收起日期：'+day):tap('收起日期：'+day);time.sleep(.8)
check('appearance-saves-on-api31',bool(nodes('Caption31','text')));capture('02-schedule-api31')
adb('shell','am','force-stop',package);start();check('api31-data-survives-restart',bool(nodes('Caption31','text')))
with dbcopy('final') as db:check('api31-schema-six-and-integrity',db.execute('PRAGMA user_version').fetchone()[0]==6 and db.execute('PRAGMA quick_check').fetchone()[0]=='ok' and not db.execute('PRAGMA foreign_key_check').fetchall())
print(json.dumps(report,ensure_ascii=False,indent=2))


