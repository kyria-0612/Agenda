"""Daily reminder, spatial slots, appearance, leisure and v2 backup; emulator only."""
import argparse,hashlib,io,json,re,sqlite3,subprocess,time,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];out=root/'review/daily-features-0.10.0';out.mkdir(parents=True,exist_ok=True)
serial='emulator-5554';package='com.agenda.local';report=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',serial,*args],capture_output=True,check=True,timeout=120)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 for attempt in range(3):
  try:adb('shell','uiautomator','dump','/sdcard/agenda-daily.xml');break
  except subprocess.CalledProcessError:
   if attempt==2:raise
   time.sleep(.5)
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-daily.xml'))
def nodes(label,attr='content-desc'):return [n for n in tree().iter('node') if n.get(attr)==label]
def click(node):
 x,y,r,b=map(int,re.findall(r'\d+',node.get('bounds')));assert r>x and b>y
 adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.4)
def tap(label,attr='content-desc'):
 for attempt in range(4):
  found=nodes(label,attr)
  if found:click(found[0]);return
  time.sleep(.4)
 raise AssertionError('Missing '+label)
def wait(label,attr='text'):
 for _ in range(5):
  if nodes(label,attr):return
  time.sleep(.3)
 raise AssertionError('Timed out '+label)
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
def start():adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(.7)
def current_id():
 text=adb('exec-out','run-as',package,'cat','files/agenda-workspace.json')
 if text.startswith('{'):return json.loads(text)['active']
 assert 'No such file' in text;return 'legacy'
def prefix():return 'databases' if current_id()=='legacy' else 'files/workspaces/'+current_id()
def dbcopy(label):
 folder=out/label;folder.mkdir(exist_ok=True)
 for name in ('agenda.db','agenda.db-wal','agenda.db-shm'):(folder/name).unlink(missing_ok=True)
 for name in ('agenda.db','agenda.db-wal'):
  try:(folder/name).write_bytes(adb('exec-out','run-as',package,'cat',prefix()+'/'+name,binary=True))
  except subprocess.CalledProcessError:pass
 db=sqlite3.connect(folder/'agenda.db');db.row_factory=sqlite3.Row;return db
def choose_file(filename):
 matches=[n for n in tree().iter('node') if n.get('content-desc','').startswith(filename+',')]
 if matches:click(matches[0]);time.sleep(.8);return
 if not nodes(filename,'text') and nodes('Show roots'):tap('Show roots');tap('Downloads','text')
 tap(filename,'text')
def save_document(filename):
 if nodes('Show roots'):tap('Show roots');tap('Downloads','text')
 field=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText');old=field.get('text','')
 click(field);adb('shell','input','keyevent','123')
 if old:adb('shell','input','keyevent',*(['67']*len(old)))
 adb('shell','input','text',filename);hide();tap('android:id/button1','resource-id')
 if nodes('REPLACE','text'):tap('REPLACE','text')
 wait('备份已导出')
def bounds(label):return tuple(map(int,re.findall(r'\d+',nodes(label)[0].get('bounds'))))
def open_leisure():
 tap('行动')
 if not nodes('管理休闲活动','text'):tap('行动卡片：休闲活动')
 tap('管理休闲活动','text')

# Seed an uncompleted task using the already installed 0.9 UI, before its first v5 reminder check.
start();tap('事项');tap('新建任务');replace('名称','','Tomorrow10');hide();tap('轻量做','text');tap('归属、日期、意愿','text');tap('日期','text');tap('明天','text');tap('创建','text')
adb('shell','am','force-stop',package)
with dbcopy('before-upgrade') as db:
 task_ids=[r[0] for r in db.execute('SELECT id FROM agenda_tasks ORDER BY id')]
 check('upgrade-starts-with-existing-v4-data',db.execute('PRAGMA user_version').fetchone()[0]==4)
print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
start();wait('近三天的截止提醒');capture('01-daily-reminder')
check('first-foreground-shows-only-upcoming-uncompleted-task',bool(nodes('Tomorrow10','text')) and not nodes('Changed09','text'));tap('关闭')
adb('shell','am','force-stop',package);start();check('same-day-restart-does-not-repeat-reminder',not nodes('近三天的截止提醒','text'))
tap('事项')
if not nodes('事项卡片：Course07'):tap('切换事项视图')
left=bounds('事项卡片：Course07');right=bounds('事项卡片：Project07')
gesture=adb('shell','am','instrument','-w','-e','class','com.agenda.data.local.NativeGestureTest','-e','agendaGesture','true',
 '-e','fromX',str((left[0]+left[2])//2),'-e','fromY',str((left[1]+left[3])//2),'-e','toX',str((right[0]+right[2])//2),'-e','toY',str((left[1]+left[3])//2),
 'com.agenda.data.test/androidx.test.runner.AndroidJUnitRunner')
(out/'gesture-test.txt').write_text(gesture,encoding='utf-8');assert 'OK (1 test)' in gesture;time.sleep(.5)
check('long-press-drag-changes-spatial-column',bounds('事项卡片：Course07')[0]>left[0]);capture('02-board-position-saved')
adb('shell','am','force-stop',package);start();check('board-position-survives-restart',bounds('事项卡片：Course07')[0]>left[0])
open_leisure();tap('保存活动','text');check('leisure-empty-input-shows-error',bool(nodes('请先填写休闲活动','text')))
replace('休闲活动内容','','Read10');hide();tap('保存活动','text');tap('编辑休闲活动：Read10');replace('休闲活动内容','Read10','Walk10');hide();tap('保存活动','text')
check('leisure-can-be-created-and-edited',bool(nodes('Walk10','text')))
tap('删除休闲活动：Walk10');tap('确认删除','text');tap('撤销删除：Walk10');tap('管理休闲活动','text')
check('leisure-delete-can-be-undone',bool(nodes('Walk10','text')));capture('03-leisure-management');tap('关闭')
tap('日程');tap('背景与文字','text');tap('背景颜色：雾蓝');replace('日程背景文字','留些空白，也留些期待。','Keep10');hide();tap('保存背景与文字','text')
check('solid-background-and-caption-saved',bool(nodes('Keep10','text')))
tap('背景与文字','text');tap('图片','text');choose_file('Material062.png');tap('保存背景与文字','text');time.sleep(.6);capture('04-custom-image-background')
with dbcopy('features') as db:
 check('schema-five-and-existing-tasks-preserved',db.execute('PRAGMA user_version').fetchone()[0]==5 and [r[0] for r in db.execute('SELECT id FROM agenda_tasks ORDER BY id')]==task_ids)
 appearance=dict(db.execute('SELECT * FROM agenda_appearance').fetchone());asset=dict(db.execute('SELECT * FROM agenda_visual_assets').fetchone())
 check('image-background-and-caption-are-real-data',appearance['assetId']==asset['id'] and appearance['text']=='Keep10')
 check('leisure-is-independent-of-tasks',db.execute('SELECT text FROM agenda_leisure').fetchone()[0]=='Walk10' and db.execute('SELECT count(*) FROM agenda_tasks').fetchone()[0]==2)
tap('背景与文字','text');replace('日程背景文字','Keep10','Draft10');hide();tap('更换背景图片','text');choose_file('Broken062.png');tap('保存背景与文字','text')
wait('背景未保存，请检查图片或可用空间；填写内容已保留');check('invalid-image-keeps-draft',bool(nodes('Draft10','text')))
with dbcopy('failed-background') as db:check('failed-image-does-not-publish-caption-or-asset',dict(db.execute('SELECT * FROM agenda_appearance').fetchone())==appearance and db.execute('SELECT count(*) FROM agenda_visual_assets').fetchone()[0]==1)
tap('关闭');tap('放弃编辑','text')
tap('行动');tap('备份与恢复');tap('导出完整备份','text');save_document('Agenda10.agenda.zip')
exported=adb('exec-out','cat','/sdcard/Download/Agenda10.agenda.zip',binary=True);(out/'Agenda10.agenda.zip').write_bytes(exported)
with zipfile.ZipFile(io.BytesIO(exported)) as archive:
 manifest=json.loads(archive.read('manifest.json'));r=manifest['records']
 check('v2-backup-includes-all-new-features',manifest['formatVersion']==2 and len(r['assets'])==1 and len(r['leisure'])==1 and len(r['layouts'])==3 and len(r['reminders'])>=1)
 check('background-bytes-and-digest-in-backup',hashlib.sha256(archive.read('assets/'+asset['id'])).hexdigest()==asset['sha256'])
tap('关闭');open_leisure();tap('编辑休闲活动：Walk10');replace('休闲活动内容','Walk10','Alter10');hide();tap('保存活动','text');tap('关闭')
tap('日程');tap('背景与文字','text');tap('纯色','text');replace('日程背景文字','Keep10','ChangedBackground10');hide();tap('保存背景与文字','text')
tap('行动');tap('备份与恢复');tap('选择备份恢复','text');choose_file('Agenda10.agenda.zip');wait('已校验的备份');capture('05-v2-restore-preview');tap('恢复并替换当前数据','text');wait('备份与恢复','content-desc')
check('v2-restore-does-not-repeat-daily-reminder',not nodes('近三天的截止提醒','text'))
with dbcopy('restored-features') as db:
 check('v2-restore-recovers-caption-image-and-leisure',dict(db.execute('SELECT * FROM agenda_appearance').fetchone())==appearance and db.execute('SELECT text FROM agenda_leisure').fetchone()[0]=='Walk10' and db.execute('SELECT id FROM agenda_visual_assets').fetchone()[0]==asset['id'])
 check('v2-restore-recovers-spatial-slots',any(row[1:]==[1,0] for row in r['layouts'] if row[0] in [q[0] for q in db.execute("SELECT id FROM agenda_objects WHERE name='Course07'")]))
tap('事项');check('restored-board-uses-saved-space',bounds('事项卡片：Course07')[0]>left[0])
adb('shell','am','force-stop',package);start();check('new-features-and-reminder-history-survive-restart',not nodes('近三天的截止提醒','text') and bounds('事项卡片：Course07')[0]>left[0])
tap('日程');check('restored-caption-visible',bool(nodes('Keep10','text')));capture('06-restored-background')
tap('行动');tap('备份与恢复');tap('选择备份恢复','text');choose_file('Agenda09.agenda.zip');wait('已校验的备份');capture('07-v1-compatible-preview');tap('取消恢复','text');tap('关闭')
with dbcopy('after-v1-cancel') as db:check('old-v1-backup-can-be-previewed-without-changing-new-features',db.execute('SELECT text FROM agenda_leisure').fetchone()[0]=='Walk10' and db.execute('SELECT text FROM agenda_appearance').fetchone()[0]=='Keep10')
print(json.dumps(report,ensure_ascii=False,indent=2))
