"""0.6 native end-to-end persistence. --fresh only clears this emulator test app."""
import argparse,json,re,subprocess,time,sqlite3,xml.etree.ElementTree as ET
from pathlib import Path
from datetime import date,timedelta
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',default='emulator-5554');p.add_argument('--fresh',action='store_true');a=p.parse_args()
assert a.serial.startswith('emulator-'),'This script only drives its own emulator.'
root=Path(__file__).resolve().parents[1];out=root/'review/local-data-0.6.0';out.mkdir(parents=True,exist_ok=True)
package='com.agenda.local';report=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],capture_output=True,check=True,timeout=120)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda06.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda06.xml'))
def nodes(label,attr='content-desc'):return [n for n in tree().iter('node') if n.get(attr)==label]
def find(label,attr='content-desc'):
 n=nodes(label,attr);assert n,'Missing '+label;return n[0]
def bounds(n):return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
def point(x,y):adb('shell','input','tap',str(x),str(y));time.sleep(.6)
def tap(label,attr='content-desc'):
 x,y,r,b=bounds(find(label,attr));assert r>x and b>y,'Offscreen '+label;point((x+r)//2,(y+b)//2)
def input_text(label,value):tap(label);adb('shell','input','text',value)
def hide_keyboard():adb('shell','input','keyevent','4');time.sleep(.3)
def capture(name):
 assert any(package in l for l in adb('shell','dumpsys','activity','activities').splitlines() if 'ResumedActivity' in l)
 t=tree();(out/f'{name}.xml').write_text(ET.tostring(t,encoding='unicode'),encoding='utf-8')
 (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
def save():(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
def check(name,value,**details):
 report.append(dict(check=name,passed=bool(value),**details));save();assert value,name
def start():adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(1)
def checked(label):return find(label).get('checked')=='true'
try:
 print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
 if a.fresh:adb('shell','pm','clear',package)
 start();capture('01-empty-first-launch')
 check('fresh-user-data-has-no-demo-records',bool(nodes('还没有事项','text')))
 today=date.fromisoformat(adb('shell','date','+%F').strip());due=today+timedelta(days=1)
 tap('新建任务');tap('创建对象','text');input_text('名称','Course06');hide_keyboard();tap('创建','text')
 check('object-created',bool(nodes('Course06','text')))
 tap('新建任务');input_text('名称','Task06');hide_keyboard();tap('随手做','text');tap('归属、日期、意愿','text');tap('Course06','text')
 tap('日期','text');tap('明天','text');tap('意愿','text');tap('想做','text');tap('该做','text');tap('创建','text')
 check('task-created',bool(nodes('Task06','text')))
 tap('说明','text');tap('添加说明','text');input_text('说明标题','Note06');input_text('说明内容','Body06');hide_keyboard();tap('保存说明','text');tap('关闭')
 check('note-title-is-in-graph',bool(nodes('Note06','text')))
 fixture=out/'Material06.txt';fixture.write_text('Agenda persistent material 0.6\n',encoding='utf-8')
 adb('push',str(fixture),'/sdcard/Download/Material06.txt');adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/Material06.txt')
 tap('材料','text');tap('添加材料','text');tap('Show roots');tap('Downloads','text');tap('Material06.txt','text');time.sleep(.6);tap('关闭');capture('02-persistent-things')
 check('material-imported',bool(nodes('Material06.txt','text')))
 tap('Task06','text');tap('安排日程','text');input_text('日程显示名称','Alias06');input_text('具体时间','09:30');hide_keyboard();capture('03-reference-schedule-form');tap('保存日程')
 tap('日程');tap('展开日期：'+today.isoformat());capture('04-reference-schedule-created')
 check('reference-alias-and-time-visible',bool(nodes('Alias06','text')) and bool(nodes('09:30','text')))
 tap('完成：Alias06');check('complete-from-schedule',checked('完成：Alias06'))
 tap('行动');capture('05-action-completion-synced')
 check('completed-task-leaves-action-and-urgent',not nodes('Task06','text') and bool(nodes('一周内暂无待完成任务','text')))
 tap('事项');tap('Task06','text');check('things-completion-synced',bool(nodes('已完成','text')))
 tap('安排日程','text');input_text('日程显示名称','AnotherAlias06');input_text('具体时间','09:30');hide_keyboard();tap('保存日程')
 check('duplicate-reference-rejected-with-draft-kept',bool(nodes('这条任务已安排在同一日期、时段和时间，请调整后重试','text')) and bool(nodes('AnotherAlias06','text')))
 capture('06-duplicate-reference-error');tap('关闭');tap('放弃编辑','text')
 tap('日程');tap('展开日期：'+today.isoformat())
 tap(f'{today.month}月{today.day}日晚上添加日程');tap('临时安排','text');input_text('临时安排内容','Walk06');hide_keyboard();tap('保存日程');tap('完成：Walk06')
 check('temporary-entry-completes',checked('完成：Walk06'))
 tap('事项');tap('切换事项视图');tap('事项卡片：Course06');check('board-reads-shared-completion',checked('恢复未完成：Task06'))
 tap('日程');tap('切换日程视图');capture('07-full-day-before-restart')
 adb('shell','am','force-stop',package)
 # Copy an idle database plus WAL into the review folder; only synthetic emulator data.
 dbdir=out/'database';dbdir.mkdir(exist_ok=True)
 for name in ['agenda.db','agenda.db-wal','agenda.db-shm']:(dbdir/name).unlink(missing_ok=True)
 for name in ['agenda.db','agenda.db-wal']:
  try:(dbdir/name).write_bytes(adb('exec-out','run-as',package,'cat','databases/'+name,binary=True))
  except subprocess.CalledProcessError:pass
 db=sqlite3.connect(str(dbdir/'agenda.db'));db.row_factory=sqlite3.Row
 task=dict(db.execute('SELECT * FROM agenda_tasks').fetchone());entries=[dict(row) for row in db.execute('SELECT * FROM agenda_schedule')]
 notes=[dict(row) for row in db.execute('SELECT * FROM agenda_notes')];materials=[dict(row) for row in db.execute('SELECT * FROM agenda_materials')]
 check('database-retains-task-fields',task['name']=='Task06' and task['effort']=='quick' and task['wants']==1 and task['should']==1 and task['completed']==1 and task['dueDay']==(due-date(1970,1,1)).days)
 check('database-no-duplicate-and-temporary-independent',len(entries)==2 and sum(row['taskId'] is None for row in entries)==1 and next(row for row in entries if row['taskId'] is None)['completed']==1)
 check('database-retains-note-title-body',len(notes)==1 and notes[0]['title']=='Note06' and notes[0]['body']=='Body06')
 check('database-retains-material-id-not-filename-identity',len(materials)==1 and materials[0]['fileKey']==materials[0]['id'] and materials[0]['name']=='Material06.txt')
 copied=adb('exec-out','run-as',package,'cat','files/materials/'+materials[0]['fileKey'],binary=True)
 check('private-material-copy-survives-process-stop',copied==fixture.read_bytes())
 (out/'persisted-records.json').write_text(json.dumps(dict(task=task,schedule=entries,notes=notes,materials=materials),ensure_ascii=False,indent=2),encoding='utf-8');db.close()
 start();capture('08-restored-full-day')
 check('datastore-restores-page-and-full-mode',bool(nodes('Alias06','text')) and not any(n.get('content-desc','').startswith('展开日期：') for n in tree().iter('node')))
 check('schedule-completion-survives-process-restart',checked('完成：Alias06') and checked('完成：Walk06'))
 tap('事项');check('datastore-restores-board-mode',bool(nodes('事项卡片：Course06')))
 tap('切换事项视图');tap('Note06','text');check('note-body-restored',bool(nodes('Body06','text')));tap('关闭')
 tap('Material06.txt','text');capture('09-material-restored');check('material-open-entry-restored',bool(nodes('打开材料','text')));tap('关闭')
 pid=adb('shell','pidof',package).strip();errors=adb('logcat','-d',f'--pid={pid}','-s','AndroidRuntime:E','AgendaGlass:W')
 (out/'runtime-errors.txt').write_text(errors,encoding='utf-8');check('no-crash-or-backdrop-failure','FATAL EXCEPTION' not in errors and 'preparation failed' not in errors)
except Exception as e:report.append(dict(error=str(e)));save();raise
save();print(json.dumps(report,ensure_ascii=False,indent=2))
