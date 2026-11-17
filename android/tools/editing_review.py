"""Native editing regression; uses existing synthetic emulator data, never phone input."""
import argparse,json,re,sqlite3,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);a=p.parse_args()
serial='emulator-5554';package='com.agenda.local';root=Path(__file__).resolve().parents[1]
out=root/'review/editing-0.7.0';out.mkdir(parents=True,exist_ok=True);report=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',serial,*args],capture_output=True,check=True,timeout=120)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda-edit.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-edit.xml'))
def nodes(label,attr='content-desc'):return [n for n in tree().iter('node') if n.get(attr)==label]
def tap(label,attr='content-desc'):
 found=nodes(label,attr);assert found,'Missing '+label
 x,y,r,b=map(int,re.findall(r'\d+',found[0].get('bounds')));assert r>x and b>y,'Offscreen '+label
 adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.5)
def replace(label,old,new):
 tap(label);adb('shell','input','keyevent','123')
 if old:adb('shell','input','keyevent',*(['67']*len(old)))
 if new:adb('shell','input','text',new)
def hide():adb('shell','input','keyevent','4');time.sleep(.3)
def capture(name):
 (out/f'{name}.xml').write_text(ET.tostring(tree(),encoding='unicode'),encoding='utf-8')
 (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
def check(name,passed):
 report.append(dict(check=name,passed=bool(passed)))
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');assert passed,name
def database(label):
 folder=out/label;folder.mkdir(exist_ok=True)
 for name in ('agenda.db','agenda.db-wal','agenda.db-shm'):(folder/name).unlink(missing_ok=True)
 for name in ('agenda.db','agenda.db-wal'):
  try:(folder/name).write_bytes(adb('exec-out','run-as',package,'cat','databases/'+name,binary=True))
  except subprocess.CalledProcessError:pass
 db=sqlite3.connect(folder/'agenda.db');db.row_factory=sqlite3.Row;return db
def start():
 adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(1)
def top():adb('shell','input','swipe','900','650','900','1800','350');time.sleep(.4)
adb('shell','am','force-stop',package)
with database('before') as db:
 task=dict(db.execute("SELECT * FROM agenda_tasks WHERE name='Task06'").fetchone())
 note=dict(db.execute("SELECT * FROM agenda_notes WHERE title='Note06'").fetchone())
 plan=dict(db.execute('SELECT * FROM agenda_weekly_plans LIMIT 1').fetchone())
 original_schedule=dict(db.execute("SELECT * FROM agenda_schedule WHERE displayName='Alias06'").fetchone())
 materials=[dict(row) for row in db.execute('SELECT * FROM agenda_materials ORDER BY id')]
print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
start();tap('事项')
if nodes('事项卡片：Course06'):tap('切换事项视图')
tap('Task06','text');tap('安排日程','text');tap('保存日程')
tap('新建任务');tap('创建对象','text');replace('名称','','Project07');hide();tap('创建','text')
tap('材料','text');tap('编辑对象','text');replace('名称','Course06','Course07');hide();capture('01-object-edit');tap('保存修改','text')
check('object-rename-visible',bool(nodes('Course07','text')))
tap('Task06','text');tap('编辑任务','text');replace('名称','Task06','Task07');hide();tap('专注做','text')
tap('归属、日期、意愿','text');tap('Project07','text');capture('02-task-edit-move');tap('保存修改','text')
check('renamed-task-visible',bool(nodes('Task07','text')))
tap('Task07','text');check('completion-and-new-owner-preserved',bool(nodes('已完成','text')) and bool(nodes('归属：Project07','text')));tap('关闭')
tap('Note06','text');tap('编辑说明','text');replace('说明标题','Note06','Note07');replace('说明内容','Body06','Body07');hide();capture('03-note-edit');tap('保存说明','text')
check('note-title-updated-in-graph',bool(nodes('Note07','text')))
tap('Task07','text');tap('编辑任务','text');replace('名称','Task07','');hide();tap('保存修改','text')
check('blank-name-rejected',bool(nodes('请先填写名称','text')))
replace('名称','','Draft07');hide();tap('关闭');check('dirty-edit-protected',bool(nodes('还有未保存的内容，要放弃吗？','text')));tap('继续编辑','text')
check('continue-edit-retains-draft',bool(nodes('Draft07','text')));tap('关闭');tap('放弃编辑','text')
check('discard-does-not-rename-task',bool(nodes('Task07','text')) and not nodes('Draft07','text'))
tap('日程');check('task-name-following-and-alias-separate',bool(nodes('Task07','text')) and bool(nodes('Alias06','text')))
tap('编辑日程：Alias06');replace('日程显示名称','Alias06','Alias07');replace('具体时间','09:30','10:15');hide();tap('下午','text');capture('04-schedule-edit');tap('保存日程')
check('schedule-alias-edited',bool(nodes('Alias07','text')) and bool(nodes('10:15','text')))
tap('编辑日程：Walk06');replace('临时安排内容','Walk06','Walk07');hide();tap('保存日程')
check('temporary-edit-keeps-completion',bool(nodes('完成：Walk07')) and nodes('完成：Walk07')[0].get('checked')=='true')
tap('周计划');tap('编辑周计划：'+plan['text']);replace('周计划内容',plan['text'],'Week07');hide();tap('保存计划','text')
check('weekly-text-edited-and-completion-kept',bool(nodes('完成周计划：Week07')) and nodes('完成周计划：Week07')[0].get('checked')=='true');capture('05-weekly-edit');tap('关闭')
adb('shell','am','force-stop',package)
with database('after') as db:
 updated=dict(db.execute('SELECT * FROM agenda_tasks WHERE id=?',(task['id'],)).fetchone())
 owner=db.execute("SELECT id FROM agenda_objects WHERE name='Project07'").fetchone()[0]
 check('task-id-fields-completion-persisted',updated['name']=='Task07' and updated['id']==task['id'] and updated['objectId']==owner and updated['completed']==task['completed'] and updated['dueDay']==task['dueDay'] and updated['effort']=='focus')
 check('note-id-and-body-persisted',dict(db.execute('SELECT * FROM agenda_notes WHERE id=?',(note['id'],)).fetchone())==dict(note,title='Note07',body='Body07'))
 check('week-id-and-independent-state-persisted',dict(db.execute('SELECT * FROM agenda_weekly_plans WHERE id=?',(plan['id'],)).fetchone())==dict(plan,text='Week07'))
 edited=dict(db.execute('SELECT * FROM agenda_schedule WHERE id=?',(original_schedule['id'],)).fetchone())
 check('schedule-id-reference-and-edits-persisted',edited==dict(original_schedule,displayName='Alias07',period='afternoon',minuteKey=615))
 check('materials-untouched-by-object-and-task-edits',[dict(row) for row in db.execute('SELECT * FROM agenda_materials ORDER BY id')]==materials)
 check('no-spurious-task-or-note-records',db.execute('SELECT count(*) FROM agenda_tasks').fetchone()[0]==1 and db.execute('SELECT count(*) FROM agenda_notes').fetchone()[0]==1)
start();check('schedule-edits-survive-process-restart',bool(nodes('Alias07','text')) and bool(nodes('Walk07','text')))
tap('事项');tap('Note07','text');check('note-body-survives-process-restart',bool(nodes('Body07','text')));capture('06-restored-edits');tap('关闭')
print(json.dumps(report,ensure_ascii=False,indent=2))
