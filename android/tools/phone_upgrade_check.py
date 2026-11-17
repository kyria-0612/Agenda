"""Read-only upgrade evidence; stores table digests, never personal record contents."""
import argparse, hashlib, json, sqlite3, subprocess, tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--stage',choices=['before','after'],required=True);p.add_argument('--version',choices=['0.10.0','0.11.0'],default='0.10.0');a=p.parse_args()
out=Path(__file__).resolve().parents[1]/('review/phone-upgrade-'+a.version);out.mkdir(parents=True,exist_ok=True)
before=json.loads((out/'before.json').read_text()) if a.stage=='after' else None
adb=r'D:\allAboutAppCache\Android\sdk\platform-tools\adb.exe'
serial='be9trwqkug7xzhm7';package='com.agenda.local'
def run(*args):
 return subprocess.run([adb,'-s',serial,*args],capture_output=True,check=True,timeout=60).stdout
pointer=run('exec-out','run-as',package,'cat','files/agenda-workspace.json')
active=json.loads(pointer)['active'] if pointer.startswith(b'{') else 'legacy'
if active!='legacy':
 import uuid
 assert str(uuid.UUID(active))==active
prefix='databases' if active=='legacy' else 'files/workspaces/'+active
result={}
with tempfile.TemporaryDirectory(prefix='agenda-upgrade-') as folder:
 for name in ('agenda.db','agenda.db-wal'):
  data=run('exec-out','run-as',package,'cat',prefix+'/'+name)
  if data.startswith(b'SQLite format 3') or (name.endswith('-wal') and data[:4] in [b'7\x7f\x06\x82',b'7\x7f\x06\x83']):
   Path(folder,name).write_bytes(data)
 db=sqlite3.connect(Path(folder,'agenda.db'))
 try:
  result['schema']=db.execute('PRAGMA user_version').fetchone()[0]
  tables=['agenda_objects','agenda_tasks','agenda_materials','agenda_notes','agenda_schedule','agenda_weekly_plans']
  if a.version=='0.11.0':tables+=['agenda_board_layout','agenda_leisure','agenda_visual_assets','agenda_appearance','agenda_reminder_checks']
  for name in tables:
   columns=before[name]['columns'] if before and 'columns' in before[name] else [r[1] for r in db.execute('PRAGMA table_info('+name+')')]
   fields=','.join('"'+c+'"' for c in columns)
   rows=db.execute('SELECT '+fields+' FROM '+name+' ORDER BY '+fields).fetchall()
   result[name]={'count':len(rows),'sha256':hashlib.sha256(json.dumps(rows,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()}
   if a.version=='0.11.0':result[name]['columns']=columns
  if before and a.version=='0.11.0':
   assert db.execute("SELECT count(*) FROM agenda_tasks WHERE description!=''").fetchone()[0]==0
   assert db.execute('SELECT count(*) FROM agenda_task_materials').fetchone()[0]==0
 finally:db.close()
(out/(a.stage+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
if a.stage=='after':
 same=all(before[k]==v for k,v in result.items() if k!='schema')
 print(json.dumps({'core_tables_unchanged':same,'schema_before':before['schema'],'schema_after':result['schema']}));assert same
else:print('Read-only baseline recorded; personal record contents were not retained.')
