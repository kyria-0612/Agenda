package com.agenda.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenda.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalPersistenceTest {
    private lateinit var context:Context
    private lateinit var database:AgendaDatabase
    private lateinit var repository:RoomAgendaRepository
    private lateinit var databaseName:String
    @Before fun prepare() {
        context=ApplicationProvider.getApplicationContext()
        databaseName="test-${UUID.randomUUID()}.db"
        database=AgendaDatabase.open(context,databaseName)
        repository=RoomAgendaRepository(database)
    }
    @After fun clean():Unit=runBlocking {
        val owned=database.agenda().materials().map {it.fileKey}+database.agenda().deletions().flatMap {DeletedBundle.decode(it.payload).materials.map {row->row.fileKey}}
        database.close()
        context.deleteDatabase(databaseName)
        owned.forEach {File(context.filesDir,"materials/$it").delete()}
    }
    private suspend inline fun <reified T:Throwable> rejects(noinline action:suspend ()->Unit) {
        try {action();fail("Expected ${T::class.java.simpleName}")}
        catch(e:Throwable) {assertTrue("Expected ${T::class.java.simpleName}, got $e",e is T)}
    }
    @Test fun firstLaunchIsEmptyAndUsesVersionedSchema():Unit=runBlocking {
        assertEquals(EmptyAgenda,repository.snapshot.first())
        assertEquals(6,database.openHelper.readableDatabase.version)
    }
    @Test fun editingAndMovingTaskPreservesIdentityCompletionAndReferenceNames():Unit=runBlocking {
        val first=repository.createObject("原对象");val second=repository.createObject("新对象")
        val id=repository.createTask(TaskDraft("原任务",Effort.QUICK,first,LocalDate.of(2026,10,29)))
        val date=LocalDate.of(2026,10,29)
        repository.createSchedule(ScheduleDraft(date,Period.MORNING,taskId=id))
        repository.createSchedule(ScheduleDraft(date,Period.EVENING,taskId=id,displayName="独立名称"))
        repository.setCompleted(id,true)
        val draft=TaskDraft(" 改名任务 ",Effort.FOCUS,second,date.plusDays(3),true,true)
        repository.updateTask(id,draft)
        var snapshot=repository.snapshot.first()
        assertEquals(1,snapshot.tasks.size);assertEquals(id,snapshot.tasks.single().id)
        assertTrue(snapshot.tasks.single().completed);assertEquals(second,snapshot.tasks.single().objectId)
        assertEquals(setOf("改名任务","独立名称"),snapshot.schedule.map {it.name(snapshot.tasks)}.toSet())
        val before=snapshot
        rejects<AgendaFailure.NotFound> {repository.updateTask(id,draft.copy(objectId="missing"))}
        rejects<AgendaFailure.InvalidInput> {repository.updateTask(id,draft.copy(name="  "))}
        assertEquals(before,repository.snapshot.first())
        repository.updateTask(id,TaskDraft("改名任务"))
        database.close();database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        snapshot=repository.snapshot.first()
        assertNull(snapshot.tasks.single().objectId);assertNull(snapshot.tasks.single().due);assertNull(snapshot.tasks.single().effort)
        assertTrue(snapshot.tasks.single().completed);assertEquals(2,snapshot.schedule.size)
    }
    @Test fun objectNoteAndWeeklyTextEditsKeepOwnedRecordsAndWeekCompletion():Unit=runBlocking {
        val owner=repository.createObject("对象")
        val note=repository.addNote(owner,"原标题","原正文")
        val material=LocalMaterialStore(context,database).importSource(owner,"keep.txt","text/plain") {ByteArrayInputStream("keep".toByteArray())}
        val week=repository.addWeeklyPlan(LocalDate.of(2026,12,31),"原计划")
        repository.setWeeklyPlanCompleted(week,true)
        val originalWeek=repository.snapshot.first().weeklyPlans.single()
        repository.renameObject(owner," 新对象名 ");repository.updateNote(note," 新标题 ","新正文\n第二行")
        repository.updateWeeklyPlan(week," 改后计划 ")
        rejects<AgendaFailure.InvalidInput> {repository.renameObject(owner,"")}
        rejects<AgendaFailure.InvalidInput> {repository.updateNote(note,"标题"," ")}
        rejects<AgendaFailure.InvalidInput> {repository.updateWeeklyPlan(week," ")}
        database.close();database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        val snapshot=repository.snapshot.first();val obj=snapshot.objects.single()
        assertEquals(owner,obj.id);assertEquals("新对象名",obj.name)
        assertEquals(AgendaNote(note,"新标题","新正文\n第二行"),obj.notes.single())
        assertEquals(material.id,obj.materials.single().id)
        assertEquals("keep",LocalMaterialStore(context,database).copyFor(material.id).readText())
        assertEquals(originalWeek.copy(text="改后计划"),snapshot.weeklyPlans.single())
        assertTrue(snapshot.tasks.isEmpty() && snapshot.schedule.isEmpty())
        rejects<AgendaFailure.NotFound> {repository.renameObject("missing","名称")}
        rejects<AgendaFailure.NotFound> {repository.updateNote("missing","标题","正文")}
        rejects<AgendaFailure.NotFound> {repository.updateWeeklyPlan("missing","内容")}
    }
    @Test fun scheduleEditingExcludesSelfButRejectsOtherDuplicatesAtomically():Unit=runBlocking {
        val task=repository.createTask(TaskDraft("任务"));val date=LocalDate.of(2026,10,29)
        val draft=ScheduleDraft(date,Period.MORNING,taskId=task)
        val first=repository.createSchedule(draft);val second=repository.createSchedule(draft.copy(period=Period.EVENING))
        repository.updateSchedule(first,draft.copy(displayName="独立名称"))
        val before=repository.snapshot.first()
        rejects<AgendaFailure.DuplicateSchedule> {repository.updateSchedule(second,draft.copy(displayName="不同名字"))}
        rejects<AgendaFailure.NotFound> {repository.updateSchedule(first,draft.copy(taskId="missing"))}
        assertEquals(before,repository.snapshot.first())
        repository.updateSchedule(first,draft.copy(date=date.plusDays(2),period=Period.AFTERNOON,time=LocalTime.of(14,30),displayName="新名字"))
        database.close();database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        val updated=repository.snapshot.first().schedule.single {it.id==first}
        assertEquals(date.plusDays(2),updated.date);assertEquals(Period.AFTERNOON,updated.period);assertEquals(LocalTime.of(14,30),updated.time)
        assertEquals("新名字",updated.displayName);assertEquals(task,updated.taskId)
    }
    @Test fun scheduleSourceChangesKeepAppropriateCompletionWithoutMutatingTasks():Unit=runBlocking {
        val date=LocalDate.of(2026,10,29)
        val task=repository.createTask(TaskDraft("未完成任务"))
        val entry=repository.createSchedule(ScheduleDraft(date,Period.NOON,title="临时内容"))
        repository.setScheduleCompleted(entry,true)
        repository.updateSchedule(entry,ScheduleDraft(date.plusDays(1),Period.EVENING,title="新临时内容"))
        assertTrue(repository.snapshot.first().schedule.single().completed)
        repository.updateSchedule(entry,ScheduleDraft(date,Period.NOON,taskId=task))
        var snapshot=repository.snapshot.first()
        assertFalse(snapshot.schedule.single().isCompleted(snapshot.tasks));assertFalse(snapshot.tasks.single().completed)
        repository.setCompleted(task,true)
        repository.updateSchedule(entry,ScheduleDraft(date,Period.NOON,title="转为独立"))
        snapshot=repository.snapshot.first()
        assertTrue(snapshot.schedule.single().completed);assertTrue(snapshot.tasks.single().completed)
        repository.setScheduleCompleted(entry,false)
        assertTrue(repository.snapshot.first().tasks.single().completed)
    }
    @Test fun concurrentEditAndCompletionDoNotOverwriteEachOther():Unit=runBlocking {
        val task=repository.createTask(TaskDraft("任务"))
        coroutineScope {
            launch(Dispatchers.Default) {repository.updateTask(task,TaskDraft("改后任务",Effort.LIGHT))}
            launch(Dispatchers.Default) {repository.setCompleted(task,true)}
        }
        val updated=repository.snapshot.first().tasks.single()
        assertTrue(updated.completed);assertEquals("改后任务",updated.name);assertEquals(Effort.LIGHT,updated.effort)
    }
    @Test fun weeklyPlansAreIndependentPersistedAndGroupedByMonday():Unit=runBlocking {
        val sunday=LocalDate.of(2027,1,3)
        val task=repository.createTask(TaskDraft("同名内容",due=sunday))
        val entry=repository.createSchedule(ScheduleDraft(sunday,Period.NOON,taskId=task))
        val plan=repository.addWeeklyPlan(sunday," 同名内容 ")
        repository.addWeeklyPlan(sunday.plusDays(1),"下一周")
        rejects<AgendaFailure.InvalidInput> {repository.addWeeklyPlan(sunday,"  ")}
        repository.setWeeklyPlanCompleted(plan,true)
        var snapshot=repository.snapshot.first()
        assertEquals(1,snapshot.tasks.size);assertEquals(1,snapshot.schedule.size)
        assertFalse(snapshot.tasks.single().completed);assertFalse(snapshot.schedule.single().isCompleted(snapshot.tasks))
        repository.setScheduleCompleted(entry,true);repository.setWeeklyPlanCompleted(plan,false)
        database.close();database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        snapshot=repository.snapshot.first()
        assertEquals(2,snapshot.weeklyPlans.size)
        val restored=snapshot.weeklyPlans.single {it.id==plan}
        assertEquals(LocalDate.of(2026,12,28),restored.weekStart);assertEquals("同名内容",restored.text);assertFalse(restored.completed)
        assertTrue(snapshot.tasks.single().completed)
        assertTrue(urgentTasks(snapshot.tasks,sunday).isEmpty())
        assertEquals(LocalDate.of(2027,1,4),snapshot.weeklyPlans.single {it.id!=plan}.weekStart)
        rejects<AgendaFailure.NotFound> {repository.setWeeklyPlanCompleted("missing",true)}
    }
    @Test fun versionOneUpgradesWithoutLosingExistingRecords():Unit=runBlocking {
        database.close();context.deleteDatabase(databaseName)
        val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val schema=org.json.JSONObject(assets.open("com.agenda.data.local.AgendaDatabase/1.json").bufferedReader().use {it.readText()}).getJSONObject("database")
        context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null).use {old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val name=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",name))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",name))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO agenda_revision(id,value) VALUES(0,1)")
            old.execSQL("INSERT INTO agenda_objects(id,name,createdAt) VALUES('owner','课程',1)")
            old.execSQL("INSERT INTO agenda_tasks(id,name,objectId,effort,dueDay,completed,wants,should,createdAt) VALUES('task','旧任务','owner',NULL,NULL,1,0,0,1)")
            old.execSQL("INSERT INTO agenda_notes(id,objectId,title,body,createdAt) VALUES('note','owner','旧标题','旧正文',1)")
            old.execSQL("INSERT INTO agenda_materials(id,objectId,name,mimeType,fileKey,sizeBytes,sha256,createdAt) VALUES('material','owner','旧材料','text/plain','old-key',3,'digest',1)")
            old.execSQL("INSERT INTO agenda_schedule(id,dateDay,period,minuteKey,taskId,title,displayName,completed,createdAt) VALUES('entry',1,'morning',-1,'task','','旧别名',0,1)")
            old.version=1
        }
        database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        val snapshot=repository.snapshot.first()
        assertEquals(6,database.openHelper.readableDatabase.version)
        assertEquals("task",snapshot.tasks.single().id);assertTrue(snapshot.tasks.single().completed)
        assertEquals("旧正文",snapshot.objects.single().notes.single().body)
        assertEquals("material",snapshot.objects.single().materials.single().id)
        assertEquals("旧别名",snapshot.schedule.single().name(snapshot.tasks));assertTrue(snapshot.weeklyPlans.isEmpty())
        repository.addWeeklyPlan(LocalDate.of(2026,10,29),"迁移后计划")
        assertEquals(1,repository.snapshot.first().weeklyPlans.size)
    }
    @Test fun versionTwoPreservesMaterialsAndWeekPlansAndAllowsMetadataEditing():Unit=runBlocking {
        database.close();context.deleteDatabase(databaseName)
        val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val schema=org.json.JSONObject(assets.open("com.agenda.data.local.AgendaDatabase/2.json").bufferedReader().use {it.readText()}).getJSONObject("database")
        context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null).use {old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val name=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",name))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",name))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO agenda_revision(id,value) VALUES(0,1)")
            old.execSQL("INSERT INTO agenda_objects(id,name,createdAt) VALUES('owner','课程',1)")
            old.execSQL("INSERT INTO agenda_materials(id,objectId,name,mimeType,fileKey,sizeBytes,sha256,createdAt) VALUES('material','owner','original.png','image/png','old-key',3,'digest',1)")
            old.execSQL("INSERT INTO agenda_weekly_plans(id,weekStartDay,text,completed,createdAt) VALUES('week',0,'周计划',1,1)")
            old.version=2
        }
        database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        var snapshot=repository.snapshot.first()
        val original=database.agenda().material("material")!!
        assertEquals("original.png",snapshot.objects.single().materials.single().displayTitle)
        assertTrue(snapshot.weeklyPlans.single().completed)
        repository.updateMaterial("material"," 显示标题 "," 第一行\n第二行 ")
        database.close();database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        snapshot=repository.snapshot.first()
        val material=snapshot.objects.single().materials.single()
        assertEquals("显示标题",material.displayTitle);assertEquals("第一行\n第二行",material.description)
        assertEquals(original,database.agenda().material("material")!!.copy(title="",description=""))
        assertTrue(snapshot.weeklyPlans.single().completed)
        repository.updateMaterial("material","","")
        assertEquals("original.png",repository.snapshot.first().objects.single().materials.single().displayTitle)
        rejects<AgendaFailure.NotFound> {repository.updateMaterial("missing","title","description")}
    }
    @Test fun imagePreviewDecodesBoundedCopyAndMetadataNeverChangesBytes():Unit=runBlocking {
        val owner=repository.createObject("图片")
        val source=android.graphics.Bitmap.createBitmap(4000,3000,android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.CYAN)
        val bytes=ByteArrayOutputStream().use {source.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);it.toByteArray()}
        source.recycle()
        val store=LocalMaterialStore(context,database)
        val material=store.importSource(owner,"image.png","image/png") {ByteArrayInputStream(bytes)}
        repository.updateMaterial(material.id,"封面","图片说明")
        val preview=store.image(material.id)
        assertTrue(preview.width<=2048 && preview.height<=2048);assertEquals(4.0/3,preview.width.toDouble()/preview.height,.01)
        preview.recycle()
        assertArrayEquals(bytes,store.copyFor(material.id).readBytes())
        val bad=store.importSource(owner,"broken.png","image/png") {ByteArrayInputStream("broken".toByteArray())}
        rejects<android.graphics.ImageDecoder.DecodeException> {store.image(bad.id)}
        assertEquals("封面",repository.snapshot.first().objects.single().materials.first {it.id==material.id}.displayTitle)
    }
    @Test fun objectTaskNoteAndLocalScheduleSurviveDatabaseReopen():Unit=runBlocking {
        val objectId=repository.createObject("光电课程")
        val due=LocalDate.of(2026,12,31)
        val taskId=repository.createTask(TaskDraft("报告",Effort.FOCUS,objectId,due,wants=true,should=true))
        val noteId=repository.addNote(objectId,"报告要求","测量过程和结果")
        val entryId=repository.createSchedule(ScheduleDraft(due,Period.MORNING,LocalTime.of(9,15),taskId,displayName="课堂报告"))
        database.close();database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database)
        val data=repository.snapshot.first()
        val task=data.tasks.single()
        assertEquals(taskId,task.id);assertEquals(objectId,task.objectId);assertEquals(due,task.due)
        assertTrue(task.wants && task.should);assertEquals(Effort.FOCUS,task.effort)
        assertEquals(noteId,data.objects.single().notes.single().id)
        assertEquals("测量过程和结果",data.objects.single().notes.single().body)
        val entry=data.schedule.single()
        assertEquals(entryId,entry.id);assertEquals(due,entry.date);assertEquals(LocalTime.of(9,15),entry.time)
        assertEquals("课堂报告",entry.name(data.tasks))
    }
    @Test fun completionSharesTaskAcrossReferencesWhileTemporaryRemainsIndependent():Unit=runBlocking {
        val date=LocalDate.of(2026,10,28)
        val taskId=repository.createTask(TaskDraft("任务"))
        val first=repository.createSchedule(ScheduleDraft(date,Period.MORNING,taskId=taskId))
        repository.createSchedule(ScheduleDraft(date.plusDays(1),Period.AFTERNOON,taskId=taskId))
        val temp=repository.createSchedule(ScheduleDraft(date,Period.EVENING,title="散步"))
        repository.setScheduleCompleted(first,true)
        var data=repository.snapshot.first()
        assertTrue(data.tasks.single().completed)
        assertTrue(data.schedule.filter {it.taskId==taskId}.all {it.isCompleted(data.tasks)})
        assertFalse(data.schedule.first {it.id==temp}.isCompleted(data.tasks))
        repository.setScheduleCompleted(temp,true);repository.setCompleted(taskId,false)
        data=repository.snapshot.first()
        assertTrue(data.schedule.first {it.id==temp}.isCompleted(data.tasks))
        assertTrue(data.schedule.filter {it.taskId==taskId}.none {it.isCompleted(data.tasks)})
    }
    @Test fun nullTimeAndAliasesCannotBypassDuplicateReferenceCheck():Unit=runBlocking {
        val task=repository.createTask(TaskDraft("任务"));val date=LocalDate.of(2026,10,28)
        val draft=ScheduleDraft(date,Period.MORNING,taskId=task)
        repository.createSchedule(draft)
        rejects<AgendaFailure.DuplicateSchedule> {repository.createSchedule(draft.copy(displayName="另一个名字"))}
        val row=database.agenda().schedule().single()
        rejects<SQLiteConstraintException> {database.agenda().insertSchedule(row.copy(id=UUID.randomUUID().toString()))}
        repository.createSchedule(draft.copy(time=LocalTime.of(9,0)))
        rejects<AgendaFailure.DuplicateSchedule> {repository.createSchedule(draft.copy(time=LocalTime.of(9,0),displayName="仍是重复"))}
        repository.createSchedule(draft.copy(time=LocalTime.of(10,0)))
        assertEquals(3,repository.snapshot.first().schedule.size)
    }
    @Test fun concurrentDuplicateWritesCommitExactlyOnce():Unit=runBlocking {
        val task=repository.createTask(TaskDraft("任务"))
        val draft=ScheduleDraft(LocalDate.now(),Period.NOON,taskId=task)
        val results=(1..2).map {async(Dispatchers.Default) {
            try {repository.createSchedule(draft);true} catch(e:AgendaFailure.DuplicateSchedule) {false}
        }}.awaitAll()
        assertEquals(1,results.count {it});assertEquals(1,repository.snapshot.first().schedule.size)
    }
    @Test fun missingParentsFailWithoutLeavingPartialData():Unit=runBlocking {
        rejects<AgendaFailure.NotFound> {repository.createTask(TaskDraft("任务",objectId="missing"))}
        rejects<AgendaFailure.NotFound> {repository.addNote("missing","标题","正文")}
        rejects<AgendaFailure.NotFound> {repository.createSchedule(ScheduleDraft(LocalDate.now(),Period.NOON,taskId="missing"))}
        rejects<SQLiteConstraintException> {database.agenda().insertTask(TaskRow("invalid","任务","missing",null,null,false,false,false,0))}
        assertEquals(EmptyAgenda,repository.snapshot.first())
    }
    @Test fun sameNameMaterialsKeepSeparatePrivateCopiesAfterReopen():Unit=runBlocking {
        val objectId=repository.createObject("资料")
        var store=LocalMaterialStore(context,database)
        val first=store.importSource(objectId,"same.txt","text/plain") {ByteArrayInputStream("first".toByteArray())}
        val second=store.importSource(objectId,"same.txt","text/plain") {ByteArrayInputStream("second".toByteArray())}
        assertNotEquals(first.id,second.id)
        database.close();database=AgendaDatabase.open(context,databaseName);repository=RoomAgendaRepository(database);store=LocalMaterialStore(context,database)
        assertEquals(2,repository.snapshot.first().objects.single().materials.size)
        assertEquals("first",store.copyFor(first.id).readText());assertEquals("second",store.copyFor(second.id).readText())
        assertEquals(64,database.agenda().material(first.id)!!.sha256.length)
    }
    @Test fun failedCopyPublishesNothingAndCleansItsTemporaryFile():Unit=runBlocking {
        val objectId=repository.createObject("资料")
        val folder=File(context.filesDir,"materials")
        val before=folder.list()?.toSet().orEmpty()
        val store=LocalMaterialStore(context,database)
        rejects<IOException> {store.importSource(objectId,"broken.txt","text/plain") {
            object:InputStream() {override fun read():Int=throw IOException("test read failure")}
        }}
        assertTrue(repository.snapshot.first().objects.single().materials.isEmpty())
        assertEquals(before,folder.list()?.toSet().orEmpty())
    }
    @Test fun preferencesAreReadByARecreatedRepository():Unit=runBlocking {
        val preferences=LocalPreferences(context)
        val desired=UserPreferences(page=2,board=true,fullDays=true)
        preferences.save(desired)
        assertEquals(desired,LocalPreferences(context).preferences.first())
        preferences.save(UserPreferences())
    }
    @Test fun startupRecoveryOnlyRemovesUnreferencedManagedFiles():Unit=runBlocking {
        val owner=repository.createObject("资料")
        val store=LocalMaterialStore(context,database)
        val valid=store.importSource(owner,"valid.txt","text/plain") {ByteArrayInputStream("keep".toByteArray())}
        val folder=File(context.filesDir,"materials")
        val orphan=File(folder,UUID.randomUUID().toString()).apply {writeText("uncommitted")}
        val partial=File(folder,"${UUID.randomUUID()}.part").apply {writeText("partial")}
        val unknown=File(folder,"unknown-${UUID.randomUUID()}.txt").apply {writeText("leave")}
        try {
            store.recoverOrphans()
            assertFalse(orphan.exists());assertFalse(partial.exists());assertTrue(unknown.exists())
            assertEquals("keep",store.copyFor(valid.id).readText())
        } finally {unknown.delete();orphan.delete();partial.delete()}
    }
}

