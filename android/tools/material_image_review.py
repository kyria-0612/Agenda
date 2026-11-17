"""0.6.2 material metadata and internal image viewer. Emulator-only, no data clearing."""
import argparse,json,re,sqlite3,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image,ImageDraw
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--resume',action='store_true');a=p.parse_args()
serial='emulator-5554';package='com.agenda.local';root=Path(__file__).resolve().parents[1]
out=root/'review/material-image-0.6.2';out.mkdir(parents=True,exist_ok=True);results=[]
def adb(*args,binary=False):
 r=subprocess.run([a.adb,'-s',serial,*args],capture_output=True,check=True,timeout=120)
 return r.stdout if binary else r.stdout.decode('utf-8',errors='replace')
def tree():
 adb('shell','uiautomator','dump','/sdcard/agenda-image.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/agenda-image.xml'))
def nodes(label,attr='content-desc'):return [n for n in tree().iter('node') if n.get(attr)==label]
def tap(label,attr='content-desc'):
 found=nodes(label,attr);assert found,'Missing '+label
 x,y,r,b=map(int,re.findall(r'\d+',found[0].get('bounds')));assert r>x and b>y
 adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.5)
def text(label,value):tap(label);adb('shell','input','text',value)
def capture(name):
 (out/f'{name}.xml').write_text(ET.tostring(tree(),encoding='unicode'),encoding='utf-8')
 (out/f'{name}.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
def check(name,passed):
 results.append(dict(check=name,passed=bool(passed)))
 (out/'report.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8');assert passed,name
def hide():adb('shell','input','keyevent','4');time.sleep(.4)
def localdb(label):
 folder=out/label;folder.mkdir(exist_ok=True)
 for name in ('agenda.db','agenda.db-wal','agenda.db-shm'):(folder/name).unlink(missing_ok=True)
 for name in ('agenda.db','agenda.db-wal'):
  try:(folder/name).write_bytes(adb('exec-out','run-as',package,'cat','databases/'+name,binary=True))
  except subprocess.CalledProcessError:pass
 return sqlite3.connect(folder/'agenda.db')
def start():
 adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(1)
 for _ in range(3):
  if foreground():return
  adb('shell','input','keyevent','4');time.sleep(.5)
  adb('shell','am','start','-W','-n',package+'/com.agenda.app.MainActivity');time.sleep(1)
def foreground():return any(package in l for l in adb('shell','dumpsys','activity','activities').splitlines() if 'ResumedActivity' in l)
def import_file(filename):
 tap('材料','text');tap('添加材料','text')
 if not nodes(filename,'text'):
  tap('Show roots');tap('Downloads','text')
 tap(filename,'text');time.sleep(1)
adb('shell','am','force-stop',package)
with (sqlite3.connect(out/'before'/'agenda.db') if a.resume else localdb('before')) as db:
 check('upgrades-version-two-database',db.execute('PRAGMA user_version').fetchone()[0]==2)
 before={t:db.execute('SELECT * FROM '+t+' ORDER BY id').fetchall() for t in ('agenda_objects','agenda_tasks','agenda_schedule','agenda_notes','agenda_weekly_plans')}
 oldmaterials=db.execute('SELECT id,name,fileKey,sha256 FROM agenda_materials ORDER BY id').fetchall()
print(adb('install','-r',str(root/'app/build/outputs/apk/debug/app-debug.apk')),flush=True)
# Synthetic grid fixture, not a product asset or a user image.
fixture=out/'Material062.png';im=Image.new('RGB',(1200,800),(238,238,238));draw=ImageDraw.Draw(im)
for y in range(0,800,100):
 for x in range(0,1200,100):draw.rectangle((x,y,x+99,y+99),fill=(0,173,181) if (x//100+y//100)%2 else (59,79,98))
draw.rectangle((450,300,750,500),fill=(250,250,250));im.save(fixture)
broken=out/'Broken062.png';broken.write_bytes(b'not an image')
for file in (fixture,broken):
 adb('push',str(file),'/sdcard/Download/'+file.name)
 adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/'+file.name)
start();tap('事项')
if nodes('事项卡片：Course06'):tap('切换事项视图')
import_file(fixture.name);capture('01-import-metadata-form')
check('metadata-form-after-import',bool(nodes('材料显示标题')) and bool(nodes('材料文字说明')))
text('材料显示标题','Image062');text('材料文字说明','LocalImageDescription062');hide();tap('保存材料信息','text')
check('title-and-description-in-details',bool(nodes('Image062','text')) and bool(nodes('LocalImageDescription062','text')))
capture('02-material-details');tap('查看图片','text');time.sleep(1);capture('03-internal-image-fit')
check('image-stays-inside-agenda',foreground() and bool(nodes('关闭图片')) and bool(nodes('Image062')) and not nodes('图片暂时无法显示','text'))
preview='图片预览，双指缩放，双击放大或复位，缩放 100%'
n=nodes(preview)[0];x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')));cx=str((x+r)//2);cy=str((y+b)//2)
adb('shell','input','tap',cx,cy);adb('shell','input','tap',cx,cy);time.sleep(.5)
check('double-tap-zooms-image',bool(nodes('图片预览，双指缩放，双击放大或复位，缩放 250%')));capture('04-image-zoomed')
adb('shell','input','swipe',cx,cy,str(int(cx)+180),str(int(cy)+120),'300');time.sleep(.4)
check('drag-remains-in-viewer',foreground() and bool(nodes('关闭图片')))
tap('复位','text');check('reset-returns-to-fit',bool(nodes(preview)))
tap('关闭图片');check('close-returns-to-material-details',bool(nodes('LocalImageDescription062','text')));tap('关闭')
check('graph-uses-custom-title',bool(nodes('Image062','text')) and not nodes(fixture.name,'text'))
tap('切换事项视图');tap('事项卡片：Course06');check('board-uses-custom-title',bool(nodes('Image062','text')))
tap('切换事项视图');tap('搜索');text('搜索内容','LocalImageDescription062');hide()
check('search-finds-material-description-and-displays-title',bool(nodes('Image062','text')));tap('关闭')
import_file(broken.name);tap('保存材料信息','text');tap('查看图片','text');time.sleep(1)
check('broken-image-shows-local-retry',bool(nodes('图片暂时无法显示','text')) and bool(nodes('重试','text')) and foreground());capture('05-broken-image-error')
tap('重试','text');time.sleep(.5);check('retry-keeps-local-error',bool(nodes('图片暂时无法显示','text')) and foreground());tap('关闭图片');tap('关闭')
adb('shell','am','force-stop',package)
with localdb('after') as db:
 check('schema-upgraded-to-three',db.execute('PRAGMA user_version').fetchone()[0]==3)
 check('tasks-schedules-weekplans-and-other-records-unchanged',all(db.execute('SELECT * FROM '+t+' ORDER BY id').fetchall()==rows for t,rows in before.items()))
 check('old-material-identity-files-and-digests-unchanged',all(db.execute('SELECT id,name,fileKey,sha256 FROM agenda_materials WHERE id=?',(row[0],)).fetchone()==row for row in oldmaterials))
 row=db.execute('SELECT id,fileKey,title,description,name FROM agenda_materials WHERE name=?',(fixture.name,)).fetchone()
 check('metadata-persists-separately-from-original-name',row[2:] == ('Image062','LocalImageDescription062',fixture.name))
 check('private-image-copy-unchanged',adb('exec-out','run-as',package,'cat','files/materials/'+row[1],binary=True)==fixture.read_bytes())
start();tap('Image062','text');check('metadata-survives-process-restart',bool(nodes('LocalImageDescription062','text')))
tap('查看图片','text');time.sleep(1);check('image-opens-after-process-restart',foreground() and bool(nodes('关闭图片')) and bool(nodes('Image062')) and not nodes('图片暂时无法显示','text'));capture('06-restored-image');tap('关闭图片');tap('关闭')
print(json.dumps(results,ensure_ascii=False,indent=2))
