"""Weekly-plan UI and in-place upgrade check; drives an emulator only, never clears data."""
import argparse,json,re,sqlite3,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',default='emulator-5554');a=p.parse_args()
assert a.serial.startswith('emulator-')
root=Path(__file__).resolve().parents[1];out=root/'review/weekly-plan-0.6.1';out.mkdir(parents=True,exist_ok=True)
package='com.agenda.local';results=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',a.serial,*args],capture_output=True,check=True,timeout=120)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda-week.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-week.xml'))
def nodes(label,attr='content-desc'):return [n for n in tree().iter('node') if n.get(attr)==label]
def tap(label,attr='content-desc'):
 found=nodes(label,attr);assert found,'Missing '+label
 x,y,r,b=map(int,re.findall(r'\d+',found[0].get('bounds')));assert r>x and b>y
 adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.6)
def capture(name):
 (out/f'{name}.xml').write_text(ET.tostring(tree(),encoding='unicode'),encoding='utf-8')
 (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
def check(name,passed):
 results.append(dict(check=name,passed=bool(passed)))
 (out/'report.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
 assert passed,name
def dbcopy(label):
 folder=out/label;folder.mkdir(exist_ok=True)
 for name in ('agenda.db','agenda.db-wal','agenda.db-shm'):(folder/name).unlink(missing_ok=True)
 for name in ('agenda.db','agenda.db-wal'):
  try:(folder/name).write_bytes(adb('exec-out','run-as',package,'cat','databases/'+name,binary=True))
  except subprocess.CalledProcessError:pass
 return sqlite3.connect(folder/'agenda.db')
adb('shell','am','force-stop',package)
with dbcopy('before') as db:
 before={t:db.execute('SELECT * FROM '+t+' ORDER BY id').fetchall() for t in ('agenda_objects','agenda_tasks','agenda_schedule','agenda_notes','agenda_materials')}
 check('upgrades-existing-version-one-data',db.execute('PRAGMA user_version').fetchone()[0]==1 and bool(before['agenda_tasks']))
print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(1)
tap('日程');tap('周计划');capture('01-independent-add-button')
check('dedicated-add-button',bool(nodes('添加计划','text')))
tap('添加计划','text');tap('保存计划','text')
check('empty-input-error',bool(nodes('请先填写计划内容','text')))
label='Week061-'+str(int(time.time()))
tap('周计划内容');adb('shell','input','text',label);adb('shell','input','keyevent','4');time.sleep(.4)
tap('保存计划','text');check('plan-created-in-week-list',bool(nodes(label,'text')))
tap('完成周计划：'+label);check('independent-completion',nodes('完成周计划：'+label)[0].get('checked')=='true');capture('02-completed-plan')
tap('上一周','text');check('previous-week-has-own-list',not nodes(label,'text'));capture('03-history-week')
tap('下一周','text');check('switching-back-retains-plan',bool(nodes(label,'text')))
adb('shell','am','force-stop',package)
with dbcopy('after') as db:
 check('schema-upgraded-to-two',db.execute('PRAGMA user_version').fetchone()[0]==2)
 check('all-existing-records-preserved-and-no-task-or-schedule-created',all(db.execute('SELECT * FROM '+t+' ORDER BY id').fetchall()==rows for t,rows in before.items()))
 row=db.execute('SELECT weekStartDay,text,completed FROM agenda_weekly_plans WHERE text=?',(label,)).fetchone()
 check('weekly-record-stored-separately',row is not None and row[2]==1)
 check('week-begins-monday',(row[0]+3)%7==0)
adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(1);tap('周计划')
check('completed-plan-survives-process-restart',bool(nodes('完成周计划：'+label)) and nodes('完成周计划：'+label)[0].get('checked')=='true')
capture('04-restored-plan');tap('关闭')
print(json.dumps(results,ensure_ascii=False,indent=2))
