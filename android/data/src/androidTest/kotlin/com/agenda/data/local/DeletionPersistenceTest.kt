package com.agenda.data.local

import android.content.Context
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeletionPersistenceTest {
    private lateinit var context:Context
    private lateinit var db:AgendaDatabase
    private lateinit var repository:RoomAgendaRepository
    private lateinit var deletions:RoomDeletionRepository
    private lateinit var name:String
    private var now=100_000L
    @Before fun prepare() {
        context=ApplicationProvider.getApplicationContext();name="delete-${UUID.randomUUID()}.db"
        db=AgendaDatabase.open(context,name);repository=RoomAgendaRepository(db);deletions=RoomDeletionRepository(db,clock={now})
    }
    @After fun clean():Unit=runBlocking {
        val keys=db.agenda().materials().map {it.fileKey}+db.agenda().deletions().flatMap {DeletedBundle.decode(it.payload).materials.map {m->m.fileKey}}
        db.close();context.deleteDatabase(name);keys.forEach {File(context.filesDir,"materials/$it").delete()}
    }
    private suspend inline fun <reified T:Throwable> rejects(noinline action:suspend ()->Unit) {
        try {action();fail("Expected ${T::class.java.simpleName}")}
        catch(e:Throwable) {assertTrue("Expected ${T::class.java.simpleName}, got $e",e is T)}
    }
    private suspend fun remove(kind:DeleteKind,id:String):String {
        deletions.delete(deletions.previewDelete(DeleteTarget(kind,id)))
        return deletions.pendingDeletions.first().last().token
    }
    private fun reopen() {
        db.close();db=AgendaDatabase.open(context,name);repository=RoomAgendaRepository(db);deletions=RoomDeletionRepository(db,clock={now})
    }
    @Test fun objectCascadeUndoSurvivesReopenAndKeepsAllIdsAndAttachmentBytes():Unit=runBlocking {
        val owner=repository.createObject("课程 \"一\"");val other=repository.createObject("其他")
        val date=LocalDate.of(2026,10,29)
        val first=repository.createTask(TaskDraft("任务一",Effort.FOCUS,owner,date,true,true))
        repository.setCompleted(first,true)
        val second=repository.createTask(TaskDraft("任务二",objectId=owner))
        val independent=repository.createTask(TaskDraft("独立"))
        val otherTask=repository.createTask(TaskDraft("其他任务",objectId=other))
        repository.createSchedule(ScheduleDraft(date,Period.MORNING,taskId=first,displayName="独立名称"))
        repository.createSchedule(ScheduleDraft(date.plusDays(1),Period.MORNING,taskId=first))
        repository.createSchedule(ScheduleDraft(date,Period.NOON,taskId=second))
        repository.createSchedule(ScheduleDraft(date,Period.EVENING,taskId=independent))
        repository.createSchedule(ScheduleDraft(date,Period.EVENING,taskId=otherTask))
        repository.createSchedule(ScheduleDraft(date,Period.NOON,title="临时"))
        repository.addNote(owner,"标题🙂","正文\n第二行")
        repository.addWeeklyPlan(date,"独立周计划")
        var store=LocalMaterialStore(context,db)
        val material=store.importSource(owner,"same.txt","text/plain") {ByteArrayInputStream("original bytes".toByteArray())}
        repository.updateMaterial(material.id,"材料标题","材料说明\n多行")
        val before=repository.snapshot.first()
        val preview=deletions.previewDelete(DeleteTarget(DeleteKind.OBJECT,owner))
        assertEquals(2,preview.tasks);assertEquals(3,preview.schedule);assertEquals(1,preview.materials);assertEquals(1,preview.notes)
        deletions.delete(preview)
        var snapshot=repository.snapshot.first()
        assertEquals(listOf(other),snapshot.objects.map {it.id});assertEquals(2,snapshot.tasks.size);assertEquals(3,snapshot.schedule.size)
        assertEquals(before.weeklyPlans,snapshot.weeklyPlans)
        store.recoverOrphans();assertTrue(File(context.filesDir,"materials/${material.id}").exists())
        reopen();store=LocalMaterialStore(context,db)
        val pending=deletions.pendingDeletions.first().single()
        assertEquals(now+DELETE_UNDO_MILLIS,pending.expiresAt)
        deletions.undoDelete(pending.token)
        snapshot=repository.snapshot.first();assertEquals(before,snapshot)
        assertEquals("original bytes",store.copyFor(material.id).readText());assertTrue(deletions.pendingDeletions.first().isEmpty())
    }
    @Test fun deletingTaskOnlyRemovesItsReferencesAndUndoKeepsSharedCompletion():Unit=runBlocking {
        val owner=repository.createObject("对象");val task=repository.createTask(TaskDraft("任务",objectId=owner))
        repository.addNote(owner,"标题","正文")
        val date=LocalDate.now()
        repository.createSchedule(ScheduleDraft(date,Period.MORNING,taskId=task))
        repository.createSchedule(ScheduleDraft(date.plusDays(1),Period.MORNING,taskId=task))
        repository.createSchedule(ScheduleDraft(date,Period.EVENING,title="独立"));repository.setCompleted(task,true)
        val before=repository.snapshot.first();val token=remove(DeleteKind.TASK,task)
        val after=repository.snapshot.first()
        assertEquals(before.objects,after.objects);assertTrue(after.tasks.isEmpty());assertEquals(1,after.schedule.size)
        deletions.undoDelete(token);assertEquals(before,repository.snapshot.first())
    }
    @Test fun individualMaterialNoteScheduleAndWeeklyDeletesAreIsolated():Unit=runBlocking {
        val owner=repository.createObject("对象");val task=repository.createTask(TaskDraft("任务",objectId=owner))
        val note=repository.addNote(owner,"标题","正文")
        val file=LocalMaterialStore(context,db).importSource(owner,"keep.txt","text/plain") {ByteArrayInputStream("keep".toByteArray())}
        val entry=repository.createSchedule(ScheduleDraft(LocalDate.now(),Period.NOON,taskId=task))
        val week=repository.addWeeklyPlan(LocalDate.now(),"周计划");repository.setWeeklyPlanCompleted(week,true)
        val before=repository.snapshot.first()
        for(target in listOf(DeleteTarget(DeleteKind.MATERIAL,file.id),DeleteTarget(DeleteKind.NOTE,note),DeleteTarget(DeleteKind.SCHEDULE,entry),DeleteTarget(DeleteKind.WEEKLY,week))) {
            val preview=deletions.previewDelete(target)
            assertEquals(0,preview.tasks);assertEquals(0,preview.objects)
            val token=remove(target.kind,target.id)
            assertEquals(before.tasks,repository.snapshot.first().tasks)
            deletions.undoDelete(token);assertEquals(before,repository.snapshot.first())
        }
    }
    @Test fun changedPreviewCannotDeleteNewlyAssociatedData():Unit=runBlocking {
        val owner=repository.createObject("对象");val task=repository.createTask(TaskDraft("任务",objectId=owner))
        val preview=deletions.previewDelete(DeleteTarget(DeleteKind.OBJECT,owner))
        repository.createSchedule(ScheduleDraft(LocalDate.now(),Period.NOON,taskId=task))
        val before=repository.snapshot.first()
        rejects<AgendaFailure.DeleteChanged> {deletions.delete(preview)}
        assertEquals(before,repository.snapshot.first());assertTrue(deletions.pendingDeletions.first().isEmpty())
        assertEquals(1,deletions.previewDelete(preview.target).schedule)
        rejects<AgendaFailure.NotFound> {deletions.previewDelete(DeleteTarget(DeleteKind.TASK,"missing"))}
    }
    @Test fun expirationCleansOnlyManagedCopiesAndDoesNotTouchOriginalOrOtherMaterial():Unit=runBlocking {
        val owner=repository.createObject("对象")
        val source=File(context.cacheDir,"original-${UUID.randomUUID()}.txt").apply {writeText("original")}
        try {
            val store=LocalMaterialStore(context,db)
            val file=store.importSource(owner,"original.txt","text/plain") {source.inputStream()}
            val other=store.importSource(owner,"other.txt","text/plain") {ByteArrayInputStream("other".toByteArray())}
            val copy=File(context.filesDir,"materials/${file.id}")
            val token=remove(DeleteKind.MATERIAL,file.id)
            now+=DELETE_UNDO_MILLIS-1;deletions.expireDeletions();store.recoverOrphans();assertTrue(copy.exists())
            now++;reopen();deletions.expireDeletions();LocalMaterialStore(context,db).recoverOrphans()
            assertFalse(copy.exists());assertEquals("original",source.readText())
            assertEquals("other",LocalMaterialStore(context,db).copyFor(other.id).readText())
            rejects<AgendaFailure.UndoExpired> {deletions.undoDelete(token)}
            assertTrue(deletions.pendingDeletions.first().isEmpty())
        } finally {source.delete()}
    }
    @Test fun overlappingDeletesRequireParentRestorationAndNeverPartiallyRestore():Unit=runBlocking {
        val owner=repository.createObject("对象");val task=repository.createTask(TaskDraft("任务",objectId=owner))
        val before=repository.snapshot.first()
        val childToken=remove(DeleteKind.TASK,task)
        // IDs are UUIDs; select tokens by membership rather than createdAt tie order.
        deletions.delete(deletions.previewDelete(DeleteTarget(DeleteKind.OBJECT,owner)))
        val parentToken=deletions.pendingDeletions.first().single {it.token!=childToken}.token
        rejects<AgendaFailure.UndoConflict> {deletions.undoDelete(childToken)}
        assertEquals(EmptyAgenda,repository.snapshot.first());assertEquals(2,deletions.pendingDeletions.first().size)
        deletions.undoDelete(parentToken);deletions.undoDelete(childToken)
        assertEquals(before,repository.snapshot.first())
    }
    @Test fun duplicateReferenceBlocksUndoWithoutReplacingCurrentRecords():Unit=runBlocking {
        val task=repository.createTask(TaskDraft("任务"));val draft=ScheduleDraft(LocalDate.now(),Period.MORNING,taskId=task)
        val first=repository.createSchedule(draft);val token=remove(DeleteKind.SCHEDULE,first)
        val second=repository.createSchedule(draft.copy(displayName="新安排"));val before=repository.snapshot.first()
        rejects<AgendaFailure.UndoConflict> {deletions.undoDelete(token)}
        assertEquals(before,repository.snapshot.first());assertEquals(second,repository.snapshot.first().schedule.single().id)
        assertEquals(1,deletions.pendingDeletions.first().size)
    }
    @Test fun clockRollbackCannotExtendUndoAndConcurrentCommandsCommitOnce():Unit=runBlocking {
        val task=repository.createTask(TaskDraft("任务"));val preview=deletions.previewDelete(DeleteTarget(DeleteKind.TASK,task))
        val results=(1..2).map {async(Dispatchers.Default) {
            try {deletions.delete(preview);true} catch(e:AgendaFailure.DeleteChanged) {false}
        }}.awaitAll()
        assertEquals(1,results.count {it});val token=deletions.pendingDeletions.first().single().token
        val undo=(1..2).map {async(Dispatchers.Default) {
            try {deletions.undoDelete(token);true} catch(e:AgendaFailure.UndoExpired) {false}
        }}.awaitAll();assertEquals(1,undo.count {it})
        val next=remove(DeleteKind.TASK,task);now--
        rejects<AgendaFailure.UndoExpired> {deletions.undoDelete(next)}
        deletions.expireDeletions();assertTrue(deletions.pendingDeletions.first().isEmpty())
    }
    @Test fun accessibilityDurationIsRecordedAndStillHasAnExactExpiry():Unit=runBlocking {
        deletions=RoomDeletionRepository(db,clock={now},undoDuration={20_000L})
        val task=repository.createTask(TaskDraft("任务"));val preview=deletions.previewDelete(DeleteTarget(DeleteKind.TASK,task))
        assertEquals(20_000L,preview.undoMillis);deletions.delete(preview)
        val pending=deletions.pendingDeletions.first().single();assertEquals(now+20_000,pending.expiresAt)
        now+=19_999;deletions.undoDelete(pending.token)
        val next=remove(DeleteKind.TASK,task);now+=20_000
        rejects<AgendaFailure.UndoExpired> {deletions.undoDelete(next)}
        deletions.expireDeletions();assertTrue(deletions.pendingDeletions.first().isEmpty())
    }
    @Test fun versionThreeUpgradeKeepsExistingMaterialMetadata():Unit=runBlocking {
        db.close();context.deleteDatabase(name)
        val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val schema=org.json.JSONObject(assets.open("com.agenda.data.local.AgendaDatabase/3.json").bufferedReader().use {it.readText()}).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use {old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO agenda_revision(id,value) VALUES(0,1)")
            old.execSQL("INSERT INTO agenda_objects(id,name,createdAt) VALUES('owner','课程',1)")
            old.execSQL("INSERT INTO agenda_materials(id,objectId,name,mimeType,fileKey,sizeBytes,sha256,createdAt,title,description) VALUES('material','owner','original.png','image/png','key',3,'digest',1,'显示标题','文字说明')")
            old.version=3
        }
        db=AgendaDatabase.open(context,name);repository=RoomAgendaRepository(db);deletions=RoomDeletionRepository(db,clock={now})
        val material=repository.snapshot.first().objects.single().materials.single()
        assertEquals(6,db.openHelper.readableDatabase.version)
        assertEquals("显示标题",material.title);assertEquals("文字说明",material.description)
        val before=repository.snapshot.first();val token=remove(DeleteKind.MATERIAL,material.id);deletions.undoDelete(token)
        assertEquals(before,repository.snapshot.first())
    }
}

