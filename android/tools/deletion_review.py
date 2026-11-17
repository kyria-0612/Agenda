"""Deletion, undo and startup expiry; emulator synthetic data only, never drives a phone."""
import argparse,json,re,sqlite3,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--finish-expiry',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parents[1];out=root/'review/deletion-0.8.0';out.mkdir(parents=True,exist_ok=True)
serial='emulator-5554';package='com.agenda.local';report=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',serial,*args],capture_output=True,check=True,timeout=120)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda-delete.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-delete.xml'))
def nodes(label,attr='content-desc'):return [n for n in tree().iter('node') if n.get(attr)==label]
def tap(label,attr='content-desc',pause=.35):
 found=nodes(label,attr);assert found,'Missing '+label
 x,y,r,b=map(int,re.findall(r'\d+',found[0].get('bounds')));assert r>x and b>y,'Offscreen '+label
 adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(pause)
def capture(name,xml=True):
 if xml:(out/f'{name}.xml').write_text(ET.tostring(tree(),encoding='unicode'),encoding='utf-8')
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
def start():adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(.4)
def confirm():tap('确认删除','text',pause=.1)
tables=('agenda_objects','agenda_tasks','agenda_materials','agenda_notes','agenda_schedule','agenda_weekly_plans')
if a.finish_expiry:
 import hashlib
 report=[entry for entry in json.loads((out/'report.json').read_text(encoding='utf-8')) if entry.get('check')!='expired-private-copy-cleaned']
 with sqlite3.connect(out/'before'/'agenda.db') as db:
  db.row_factory=sqlite3.Row
  material=dict(db.execute("SELECT * FROM agenda_materials WHERE name='Material06.txt'").fetchone())
 result=subprocess.run([a.adb,'-s',serial,'shell','run-as',package,'ls','files/materials/'+material['fileKey']],capture_output=True)
 (out/'cleanup-evidence.txt').write_bytes(result.stdout+result.stderr)
 check('expired-private-copy-cleaned',result.returncode!=0)
 check('original-download-file-untouched',hashlib.sha256(adb('exec-out','cat','/sdcard/Download/Material06.txt',binary=True)).hexdigest()==material['sha256'])
 start();tap('Image062','text');tap('查看图片','text');time.sleep(.4)
 check('other-material-still-opens-inside-app',bool(nodes('Image062')) and bool(nodes('关闭图片')) and not nodes('图片暂时无法显示','text'))
 capture('05-other-image-after-cleanup');tap('关闭图片');tap('关闭')
 print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(0)
adb('shell','am','force-stop',package)
with database('before') as db:
 check('upgrades-existing-v3-data',db.execute('PRAGMA user_version').fetchone()[0]==3)
 before={table:[dict(r) for r in db.execute('SELECT * FROM '+table+' ORDER BY id')] for table in tables}
 material=next(row for row in before['agenda_materials'] if row['name']=='Material06.txt')
 originalbytes=adb('exec-out','run-as',package,'cat','files/materials/'+material['fileKey'],binary=True)
print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
start();tap('事项');tap('Task07','text');tap('删除任务','text')
check('task-preview-shows-reference-count',bool(nodes('关联日程','text')) and bool(nodes('2 项','text')))
capture('01-task-delete-preview');tap('取消','text');check('cancel-keeps-task',bool(nodes('Task07','text')))
tap('Task07','text');tap('删除任务','text');confirm();capture('02-undo-notice',xml=False);tap('撤销删除：Task07',pause=.1)
check('task-group-restored-by-undo',bool(nodes('Task07','text')))
tap('材料','text');tap('删除对象','text')
check('object-preview-shows-material-and-note-counts',bool(nodes('材料','text')) and bool(nodes('3 项','text')) and bool(nodes('说明','text')))
capture('03-object-delete-preview');confirm()
adb('shell','am','force-stop',package);start();tap('撤销删除：Course07',pause=.1)
check('undo-survives-process-restart-within-window',bool(nodes('Course07','text')) and bool(nodes('Note07','text')) and bool(nodes('Image062','text')))
tap('Note07','text');tap('删除说明','text');confirm();tap('撤销删除：Note07',pause=.1)
check('note-delete-undo-restores-title',bool(nodes('Note07','text')))
tap('日程');tap('编辑日程：Alias07');tap('删除日程','text')
check('single-schedule-preview-has-no-task-cascade',bool(nodes('日程','text')) and not nodes('任务','text'))
confirm();tap('撤销删除：Alias07',pause=.1)
check('schedule-delete-undo-keeps-task-reference',bool(nodes('Alias07','text')) and bool(nodes('Task07','text')))
tap('周计划');tap('删除周计划：Week07');confirm();tap('撤销删除：Week07',pause=.1);tap('周计划')
check('weekly-delete-undo-keeps-completion',bool(nodes('完成周计划：Week07')) and nodes('完成周计划：Week07')[0].get('checked')=='true');tap('关闭')
adb('shell','am','force-stop',package)
with database('after-undo') as db:
 check('schema-migrated-to-four',db.execute('PRAGMA user_version').fetchone()[0]==4)
 check('all-business-records-identical-after-group-undos',all([dict(r) for r in db.execute('SELECT * FROM '+table+' ORDER BY id')]==rows for table,rows in before.items()))
 check('undo-journal-empty-after-restoration',db.execute('SELECT count(*) FROM agenda_delete_undo').fetchone()[0]==0)
 check('attachment-bytes-restored-unchanged',adb('exec-out','run-as',package,'cat','files/materials/'+material['fileKey'],binary=True)==originalbytes)
start();tap('事项');tap('Material06.txt','text');tap('删除材料','text');capture('04-material-delete-preview');confirm()
adb('shell','am','force-stop',package)
time.sleep(8.3)
start();check('expired-deletion-stays-deleted-after-restart',not nodes('Material06.txt','text') and not nodes('撤销删除：Material06.txt'))
adb('shell','am','force-stop',package)
with database('after-expiry') as db:
 check('only-selected-material-deleted',all([dict(r) for r in db.execute('SELECT * FROM '+table+' ORDER BY id')]==(list(filter(lambda row:row['id']!=material['id'],rows)) if table=='agenda_materials' else rows) for table,rows in before.items()))
 check('expired-journal-cleared',db.execute('SELECT count(*) FROM agenda_delete_undo').fetchone()[0]==0)
 result=subprocess.run([a.adb,'-s',serial,'shell','run-as',package,'ls','files/materials/'+material['fileKey']],capture_output=True)
 check('expired-private-copy-cleaned',result.returncode!=0)
 check('original-download-file-untouched',adb('exec-out','cat','/sdcard/Download/Material06.txt',binary=True)==originalbytes)
start();tap('Image062','text');tap('查看图片','text');time.sleep(.4)
check('other-material-still-opens-inside-app',bool(nodes('Image062')) and bool(nodes('关闭图片')) and not nodes('图片暂时无法显示','text'))
capture('05-other-image-after-cleanup');tap('关闭图片');tap('关闭')
print(json.dumps(report,ensure_ascii=False,indent=2))
