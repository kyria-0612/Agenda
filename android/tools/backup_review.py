"""Native backup/restore through SAF; emulator only, never clears or drives a phone."""
import argparse,hashlib,io,json,re,sqlite3,subprocess,time,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--resume-export',action='store_true');a=p.parse_args()
root=Path(__file__).resolve().parents[1];out=root/'review/backup-0.9.0';out.mkdir(parents=True,exist_ok=True)
serial='emulator-5554';package='com.agenda.local';report=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',serial,*args],capture_output=True,check=True,timeout=120)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda-backup.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-backup.xml'))
def nodes(label,attr='content-desc'):return [n for n in tree().iter('node') if n.get(attr)==label]
def click(node):
 x,y,r,b=map(int,re.findall(r'\d+',node.get('bounds')));assert r>x and b>y
 adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.5)
def tap(label,attr='content-desc'):
 found=nodes(label,attr);assert found,'Missing '+label;click(found[0])
def wait(label,attr='text'):
 for _ in range(5):
  if nodes(label,attr):return
  time.sleep(.5)
 raise AssertionError('Timed out '+label)
def replace(label,old,new):
 tap(label);adb('shell','input','keyevent','123')
 if old:adb('shell','input','keyevent',*(['67']*len(old)))
 adb('shell','input','text',new)
def capture(name):
 (out/f'{name}.xml').write_text(ET.tostring(tree(),encoding='unicode'),encoding='utf-8')
 (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
def check(name,passed):
 report.append(dict(check=name,passed=bool(passed)))
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');assert passed,name
def choose_downloads():
 if nodes('Show roots'):tap('Show roots');tap('Downloads','text')
def save_document(filename):
 choose_downloads()
 fields=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText'];assert fields,'Filename field'
 field=fields[0];old=field.get('text','');click(field);adb('shell','input','keyevent','123')
 if old:adb('shell','input','keyevent',*(['67']*len(old)))
 adb('shell','input','text',filename);adb('shell','input','keyevent','4');tap('android:id/button1','resource-id')
 if nodes('Replace','text'):tap('Replace','text')
 elif nodes('REPLACE','text'):tap('REPLACE','text')
 wait('备份已导出')
def choose_file(filename):
 if not nodes(filename,'text'):choose_downloads()
 tap(filename,'text')
def current_id():
 text=adb('exec-out','run-as',package,'cat','files/agenda-workspace.json')
 if text.startswith('{'):
  value=json.loads(text);assert not value['pending'];return value['active']
 assert 'No such file' in text;return 'legacy'
def dbcopy(label):
 generation=current_id();prefix='databases' if generation=='legacy' else 'files/workspaces/'+generation
 folder=out/label;folder.mkdir(exist_ok=True)
 for name in ('agenda.db','agenda.db-wal','agenda.db-shm'):(folder/name).unlink(missing_ok=True)
 for name in ('agenda.db','agenda.db-wal'):
  try:(folder/name).write_bytes(adb('exec-out','run-as',package,'cat',prefix+'/'+name,binary=True))
  except subprocess.CalledProcessError:pass
 db=sqlite3.connect(folder/'agenda.db');db.row_factory=sqlite3.Row;return db
def start():adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(.7)
def read_export(filename):
 data=adb('exec-out','cat','/sdcard/Download/'+filename,binary=True);(out/filename).write_bytes(data)
 with zipfile.ZipFile(io.BytesIO(data)) as zip:
  assert zip.testzip() is None
  manifest=json.loads(zip.read('manifest.json'))
  for m in manifest['records']['materials']:assert hashlib.sha256(zip.read('materials/'+m[0])).hexdigest()==m[6]
  return manifest
if not a.resume_export:
 print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
 start();tap('行动');tap('备份与恢复');capture('01-backup-entry');tap('导出完整备份','text')
save_document('Agenda09.agenda.zip');capture('02-export-success')
manifest=read_export('Agenda09.agenda.zip');check('saf-export-includes-all-records-and-verified-copies',manifest['formatVersion']==1 and len(manifest['records']['tasks'])==1 and len(manifest['records']['materials'])==2)
check('export-includes-material-title-description-and-settings',any(m[8]=='Image062' and m[9]=='LocalImageDescription062' for m in manifest['records']['materials']) and manifest['preferences']==dict(page=1,board=False,fullDays=True))
tap('关闭');tap('事项');tap('Task07','text');tap('编辑任务','text');replace('名称','Task07','Changed09');adb('shell','input','keyevent','4');tap('保存修改','text')
tap('切换事项视图');tap('日程');tap('切换日程视图');tap('行动');tap('备份与恢复')
tap('选择备份恢复','text');choose_file('Agenda09.agenda.zip');wait('已校验的备份');capture('03-validated-restore-preview')
with dbcopy('before-restore') as db:check('validation-does-not-change-current-data',db.execute('SELECT name FROM agenda_tasks').fetchone()[0]=='Changed09')
tap('恢复并替换当前数据','text');wait('备份与恢复','content-desc')
check('restore-switches-complete-workspace',current_id()!='legacy')
tap('事项');check('restore-applies-original-name-and-board-setting',bool(nodes('Task07','text')) and not nodes('事项卡片：Project07'))
tap('Image062','text');tap('查看图片','text');wait('关闭图片','content-desc');check('restored-private-image-opens-internally',bool(nodes('Image062')) and not nodes('图片暂时无法显示','text'));capture('04-restored-image');tap('关闭图片');tap('关闭')
with dbcopy('restored') as db:
 check('restore-is-replacement-with-stable-task-and-reference-ids',db.execute('SELECT id,name,completed FROM agenda_tasks').fetchone()[:]==tuple(manifest['records']['tasks'][0][i] for i in (0,1,5)) and db.execute('SELECT count(*) FROM agenda_schedule').fetchone()[0]==len(manifest['records']['schedule']))
tap('行动');tap('备份与恢复')
snapshot_nodes=[n for n in tree().iter('node') if n.get('content-desc','').startswith('恢复快照：')]
check('automatic-pre-restore-snapshot-is-visible',len(snapshot_nodes)==1)
snapshot_id=snapshot_nodes[0].get('content-desc').split('：')[1];click(snapshot_nodes[0]);wait('已校验的备份');capture('05-recovery-snapshot-preview')
tap('恢复并替换当前数据','text');wait('备份与恢复','content-desc');tap('事项')
check('snapshot-restore-applies-previous-board-setting',bool(nodes('事项卡片：Project07')))
tap('事项卡片：Project07');check('snapshot-restores-pre-restore-task-name',bool(nodes('Changed09','text')))
adb('shell','am','force-stop',package);start()
check('workspace-selection-and-settings-survive-process-restart',bool(nodes('事项卡片：Project07')) and current_id()!='legacy')
tap('事项卡片：Project07');check('restored-task-survives-process-restart',bool(nodes('Changed09','text')))
tap('行动');tap('备份与恢复');tap('导出快照：'+snapshot_id);save_document('AgendaSnapshot09.agenda.zip')
snapshot=read_export('AgendaSnapshot09.agenda.zip');check('recovery-snapshot-can-be-exported-as-valid-backup',snapshot['records']['tasks'][0][1]=='Changed09')
bad=out/'BrokenBackup09.agenda.zip';bad.write_bytes(b'not a ZIP archive')
adb('push',str(bad),'/sdcard/Download/'+bad.name);adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/'+bad.name)
tap('选择备份恢复','text');choose_file(bad.name);wait('备份损坏、内容不完整或校验失败，当前数据未改变');capture('06-invalid-backup-error')
with dbcopy('after-invalid') as db:check('invalid-backup-rejected-with-current-data-intact',db.execute('SELECT name FROM agenda_tasks').fetchone()[0]=='Changed09')
check('automatic-snapshots-retained-after-second-restore',len([n for n in tree().iter('node') if n.get('content-desc','').startswith('恢复快照：')])==2)
tap('关闭');print(json.dumps(report,ensure_ascii=False,indent=2))
