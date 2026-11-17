package com.agenda.data.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenda.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.util.UUID
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class TaskDetailsPersistenceTest {
    private class Sandbox(base:Context,val folder:File):ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getFilesDir()=File(folder,"files").apply {mkdirs()}
        override fun getCacheDir()=File(folder,"cache").apply {mkdirs()}
        override fun getDatabasePath(name:String)=if(name.startsWith('/')) File(name) else File(folder,"databases/$name").also {it.parentFile?.mkdirs()}
    }
    private lateinit var context:Sandbox
    private lateinit var db:AgendaDatabase
    private lateinit var repo:RoomAgendaRepository
    private lateinit var details:DailyFeatureRepository
    private lateinit var manager:WorkspaceManager
    private val gate=LocalDataGate()
    private val opened=mutableListOf<AgendaDatabase>()
    private val now=System.currentTimeMillis()
    @Before fun setup() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        context=Sandbox(base,File(base.cacheDir,"task-details-${UUID.randomUUID()}").apply {mkdirs()})
        manager=WorkspaceManager(context);db=open(manager.resolve().databaseName)
        repo=RoomAgendaRepository(db,gate);details=DailyFeatureRepository(db,gate)
    }
    private fun open(path:String)=AgendaDatabase.open(context,path).also {opened+=it}
    @After fun cleanup() {opened.forEach {it.close()};context.folder.deleteRecursively()}
    private suspend fun material(owner:String)=LocalMaterialStore(context,db,gate).importSource(owner,"shared.txt","text/plain") {ByteArrayInputStream("one copy".toByteArray())}.id
    private fun deletion()=RoomDeletionRepository(db,clock={now},gate=gate)
    private fun backup():LocalBackupStore {
        val prefs=object:PreferencesRepository {override val preferences=flowOf(UserPreferences());override suspend fun save(preferences:UserPreferences) {}}
        return LocalBackupStore(context,db,prefs,gate,manager.resolve(),manager)
    }
    private suspend fun remove(target:DeleteTarget):String {
        val service=deletion();service.delete(service.previewDelete(target));return db.agenda().deletions().single().token
    }
    @Test fun descriptionAndSharedLinksSurviveMoveAndReopen():Unit=runBlocking {
        val owner=repo.createObject("材料对象");val other=repo.createObject("新归属");val file=material(owner)
        val task=repo.createTask(TaskDraft("独立任务"));details.saveTaskDetails(task,"任务说明",listOf(file,file))
        repo.updateTask(task,TaskDraft("移动任务",objectId=other));repo.setCompleted(task,true)
        val next=RoomAgendaRepository(open(manager.resolve().databaseName)).snapshot.first()
        assertEquals("任务说明",next.tasks.single().description);assertEquals(listOf(file),next.tasks.single().materialIds)
        assertEquals(other,next.tasks.single().objectId);assertTrue(next.tasks.single().completed)
        assertEquals(1,next.objects.sumOf {it.materials.size});assertEquals(owner,next.objects.single {it.materials.isNotEmpty()}.id)
    }
    @Test fun staleMaterialSelectionDoesNotPartiallySaveDescription():Unit=runBlocking {
        val task=repo.createTask(TaskDraft("任务"));details.saveTaskDetails(task,"已保存",emptyList())
        try {details.saveTaskDetails(task,"不应发布",listOf(UUID.randomUUID().toString()));fail("missing material accepted")}
        catch(expected:AgendaFailure.NotFound) {}
        assertEquals("已保存",repo.snapshot.first().tasks.single().description)
    }
    @Test fun deletingTaskPreservesSharedMaterialAndUndoRestoresDetails():Unit=runBlocking {
        val owner=repo.createObject("对象");val file=material(owner);val task=repo.createTask(TaskDraft("任务"))
        details.saveTaskDetails(task,"说明",listOf(file));val before=repo.snapshot.first()
        assertEquals(1,deletion().previewDelete(DeleteTarget(DeleteKind.TASK,task)).materialLinks)
        val token=remove(DeleteTarget(DeleteKind.TASK,task));assertEquals(file,repo.snapshot.first().objects.single().materials.single().id)
        assertTrue(db.agenda().taskMaterials().isEmpty());deletion().undoDelete(token);assertEquals(before,repo.snapshot.first())
    }
    @Test fun deletingMaterialUnlinksAllTasksAndUndoRestoresBothLinks():Unit=runBlocking {
        val owner=repo.createObject("对象");val file=material(owner)
        val ids=(1..2).map {repo.createTask(TaskDraft("任务$it"))};ids.forEach {details.saveTaskDetails(it,"说明",listOf(file))}
        val token=remove(DeleteTarget(DeleteKind.MATERIAL,file));val snapshot=repo.snapshot.first()
        assertEquals(2,snapshot.tasks.size);assertTrue(snapshot.tasks.all {it.description=="说明" && it.materialIds.isEmpty()})
        deletion().undoDelete(token);assertTrue(repo.snapshot.first().tasks.all {it.materialIds==listOf(file)})
    }
    @Test fun deletingObjectCapturesExternalLinksWithoutDeletingExternalTasks():Unit=runBlocking {
        val owner=repo.createObject("对象");val file=material(owner)
        val owned=repo.createTask(TaskDraft("所属任务",objectId=owner));val external=repo.createTask(TaskDraft("外部任务"))
        listOf(owned,external).forEach {details.saveTaskDetails(it,"说明",listOf(file))};val before=repo.snapshot.first()
        val token=remove(DeleteTarget(DeleteKind.OBJECT,owner));assertEquals(external,repo.snapshot.first().tasks.single().id)
        assertTrue(repo.snapshot.first().tasks.single().materialIds.isEmpty());deletion().undoDelete(token);assertEquals(before,repo.snapshot.first())
    }
    @Test fun v3BackupRestoresDetailsAndReferencesWithOnlyOneFileCopy():Unit=runBlocking {
        val owner=repo.createObject("对象");val file=material(owner)
        repeat(2) {val task=repo.createTask(TaskDraft("任务$it"));details.saveTaskDetails(task,"说明$it",listOf(file))}
        details.initialize();val before=repo.snapshot.first();val store=backup()
        val bytes=ByteArrayOutputStream().also {store.exportTo({it})}.toByteArray();var count=0;var manifest:JSONObject?=null
        ZipInputStream(ByteArrayInputStream(bytes)).use {zip->while(true) {val entry=zip.nextEntry ?: break
            if(entry.name.startsWith("materials/")) count++
            if(entry.name=="manifest.json") manifest=JSONObject(String(zip.readBytes()))
        }}
        assertEquals(1,count);assertEquals(3,manifest!!.getInt("formatVersion"));assertEquals(2,manifest!!.getJSONObject("records").getJSONArray("taskMaterials").length())
        store.restore(store.prepareSource({ByteArrayInputStream(bytes)}));assertEquals(before,RoomAgendaRepository(open(manager.resolve().databaseName)).snapshot.first())
    }
    @Test fun legacyV2RecordsDefaultTaskDetailsAndLinks():Unit=runBlocking {
        val task=TaskRow(UUID.randomUUID().toString(),"旧任务",null,null,null,false,false,false,1,"new")
        val rows=JSONObject(DeletedBundle(tasks=listOf(task)).encode()).put("version",2);rows.remove("taskMaterials")
        val manifest=JSONObject().put("formatVersion",2).put("createdAt",1).put("records",rows).put("preferences",BackupCodec.preferencesJson(UserPreferences()))
        val decoded=BackupCodec.decode(manifest.toString().toByteArray(),BackupLimits())
        assertEquals("",decoded.records.tasks.single().description);assertTrue(decoded.records.taskMaterials.isEmpty())
    }
    @Test fun backupRejectsDanglingAndDuplicateMaterialLinks():Unit=runBlocking {
        val task=TaskRow(UUID.randomUUID().toString(),"任务",null,null,null,false,false,false,1)
        val link=TaskMaterialRow(task.id,UUID.randomUUID().toString())
        for(links in listOf(listOf(link),listOf(link,link))) {
            try {BackupCodec.validate(BackupContents(DeletedBundle(tasks=listOf(task),taskMaterials=links),UserPreferences(),1),BackupLimits());fail("invalid links accepted")}
            catch(expected:IllegalArgumentException) {}
        }
    }
    @Test fun v5MigrationPreservesTaskAndLegacyUndo():Unit=runBlocking {
        db.close();val path=context.getDatabasePath("agenda.db");path.delete();File(path.path+"-wal").delete();File(path.path+"-shm").delete()
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val schema=JSONObject(assets.open("com.agenda.data.local.AgendaDatabase/5.json").bufferedReader().use {it.readText()}).getJSONObject("database")
        val id=UUID.randomUUID().toString();val undone=UUID.randomUUID().toString()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use {old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {val entity=entities.getJSONObject(i);val name=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",name));val indexes=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",name))}
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO agenda_revision(id,value) VALUES(0,1)")
            old.execSQL("INSERT INTO agenda_tasks VALUES(?,?,NULL,NULL,NULL,1,1,0,1)",arrayOf(id,"旧任务"))
            val legacy=JSONObject(DeletedBundle(tasks=listOf(TaskRow(undone,"撤销",null,null,null,false,false,false,1))).encode()).put("version",2)
            legacy.remove("taskMaterials")
            old.execSQL("INSERT INTO agenda_delete_undo VALUES(?,?,?,?,?)",arrayOf("token","撤销",legacy.toString(),now,now+8000));old.version=5
        }
        db=open(path.absolutePath);repo=RoomAgendaRepository(db)
        assertEquals(6,db.openHelper.readableDatabase.version);assertEquals("",repo.snapshot.first().tasks.single().description)
        assertTrue(repo.snapshot.first().tasks.single().completed);deletion().undoDelete("token");assertEquals(2,repo.snapshot.first().tasks.size)
    }
}
