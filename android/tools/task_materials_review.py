"""Task details, shared image access, v3 backup and object search; emulator only."""
from pathlib import Path
source=Path(__file__).with_name('daily_features_review.py').read_text(encoding='utf-8')
exec(compile(source.split('# Seed')[0],str(Path(__file__)), 'exec'))
out=root/'review/task-materials-0.11.0';out.mkdir(parents=True,exist_ok=True);report=[]
def search_task():
 tap('搜索');replace('搜索内容','','Changed09');hide();click(nodes('Changed09','text')[-1])
def edit_details(old,new,link):
 tap('编辑说明与关联材料','text');replace('任务说明',old,new);hide()
 selected=nodes('关联材料：'+material_title)[0].get('selected')=='true'
 if selected!=link:tap('关联材料：'+material_title)
 tap('保存说明与关联','text')
start()
with dbcopy('baseline') as db:
 task_id=db.execute("SELECT id FROM agenda_tasks WHERE name='Changed09'").fetchone()[0]
 material=dict(db.execute("SELECT * FROM agenda_materials WHERE name='Material062.png'").fetchone())
 material_title=material['title'] or material['name']
 before_count=db.execute('SELECT count(*) FROM agenda_materials').fetchone()[0]
search_task();edit_details('','Details11',True)
check('task-description-and-material-visible',bool(nodes('Details11','text')) and bool(nodes(material_title,'text')));capture('01-task-details')
tap(material_title,'text');tap('查看图片','text');wait('关闭图片','content-desc')
check('linked-image-opens-in-app',bool(nodes('关闭图片')));capture('02-linked-image-viewer');tap('关闭图片');tap('关闭')
adb('shell','am','force-stop',package);start();search_task()
check('task-details-survive-restart',bool(nodes('Details11','text')) and bool(nodes(material_title,'text')));tap('关闭')
with dbcopy('saved') as db:
 check('association-uses-one-existing-material',db.execute('SELECT count(*) FROM agenda_materials').fetchone()[0]==before_count and db.execute('SELECT materialId FROM agenda_task_materials WHERE taskId=?',(task_id,)).fetchone()[0]==material['id'])
tap('行动');tap('备份与恢复');tap('导出完整备份','text');save_document('Agenda11.agenda.zip')
exported=adb('exec-out','cat','/sdcard/Download/Agenda11.agenda.zip',binary=True);(out/'Agenda11.agenda.zip').write_bytes(exported)
with zipfile.ZipFile(io.BytesIO(exported)) as archive:
 manifest=json.loads(archive.read('manifest.json'));records=manifest['records']
 check('v3-backup-contains-description-and-link',manifest['formatVersion']==3 and any(row[0]==task_id and row[9]=='Details11' for row in records['tasks']) and [task_id,material['id']] in records['taskMaterials'])
 check('backup-does-not-duplicate-linked-file',len([p for p in archive.namelist() if p=='materials/'+material['id']])==1)
tap('关闭');search_task();edit_details('Details11','ChangedDetails11',False);tap('关闭')
tap('备份与恢复');tap('选择备份恢复','text');choose_file('Agenda11.agenda.zip');wait('已校验的备份');capture('03-v3-preview');tap('恢复并替换当前数据','text');wait('备份与恢复','content-desc')
search_task();check('v3-restore-recovers-description-and-shared-link',bool(nodes('Details11','text')) and bool(nodes(material_title,'text')));tap('关闭')
tap('备份与恢复');tap('选择备份恢复','text');choose_file('Agenda10.agenda.zip');wait('已校验的备份');tap('取消恢复','text');tap('关闭')
with dbcopy('after-v2-cancel') as db:check('v2-preview-does-not-change-v3-task-details',db.execute('SELECT description FROM agenda_tasks WHERE id=?',(task_id,)).fetchone()[0]=='Details11' and db.execute('SELECT count(*) FROM agenda_task_materials').fetchone()[0]==1)
tap('搜索');replace('搜索内容','','Course07');hide();click(nodes('Course07','text')[-1]);time.sleep(.6)
check('object-search-focuses-graph-instead-of-editing',bool(nodes('Course07','text')) and not nodes('保存修改','text') and not nodes('关闭'));capture('04-object-search-focus')
course=nodes('Course07','text')[0].get('bounds');tap('行动');tap('事项')
check('graph-context-survives-page-switch',nodes('Course07','text')[0].get('bounds')==course)
with dbcopy('final') as db:check('final-v6-database-integrity',db.execute('PRAGMA quick_check').fetchone()[0]=='ok' and not db.execute('PRAGMA foreign_key_check').fetchall())
print(json.dumps(report,ensure_ascii=False,indent=2))
