package com.agenda.data.local

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenda.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.LocalDate
import java.util.UUID
import java.util.zip.*
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class DailyFeaturePersistenceTest {
    private class Sandbox(base:Context,val folder:File):ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getFilesDir()=File(folder,"files").apply {mkdirs()}
        override fun getCacheDir()=File(folder,"cache").apply {mkdirs()}
        override fun getDatabasePath(name:String)=if(name.startsWith('/')) File(name) else File(folder,"databases/$name").also {it.parentFile?.mkdirs()}
    }
    private lateinit var context:Sandbox
    private lateinit var db:AgendaDatabase
    private lateinit var gate:LocalDataGate
    private lateinit var repository:RoomAgendaRepository
    private lateinit var features:DailyFeatureRepository
    private lateinit var visuals:VisualAssetStore
    private val opened=mutableListOf<AgendaDatabase>()
    @Before fun prepare() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        context=Sandbox(base,File(base.cacheDir,"daily-test-${UUID.randomUUID()}").apply {mkdirs()})
        db=open(context.getDatabasePath("agenda.db").absolutePath);gate=LocalDataGate()
        repository=RoomAgendaRepository(db,gate);features=DailyFeatureRepository(db,gate);visuals=VisualAssetStore(context,db,gate)
    }
    private fun open(name:String)=AgendaDatabase.open(context,name).also {opened+=it}
    @After fun clean() {opened.forEach {it.close()};context.folder.deleteRecursively()}
    private suspend inline fun <reified T:Throwable> rejects(noinline action:suspend ()->Unit) {
        try {action();fail("Expected ${T::class.java.simpleName}")}
        catch(e:Throwable) {assertTrue("Expected ${T::class.java.simpleName}, got $e",e is T)}
    }
    private fun image():File=File(context.cacheDir,"original.png").also {file->
        val bitmap=android.graphics.Bitmap.createBitmap(80,40,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN);file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun reminderIsClaimedOnceAcrossReopenAndFiltersNaturalDays():Unit=runBlocking {
        val today=LocalDate.of(2026,12,30)
        val included=(1L..3L).map {repository.createTask(TaskDraft("任务$it",due=today.plusDays(it)))}
        for(offset in listOf(-1L,0L,4L)) repository.createTask(TaskDraft("排除$offset",due=today.plusDays(offset)))
        val done=repository.createTask(TaskDraft("完成",due=today.plusDays(1)));repository.setCompleted(done,true)
        repository.createTask(TaskDraft("无日期"))
        assertEquals(included.toSet(),features.claimReminder(today)!!.toSet());assertNull(features.claimReminder(today))
        val next=open(context.getDatabasePath("agenda.db").absolutePath)
        assertNull(DailyFeatureRepository(next).claimReminder(today))
        assertEquals(today,RoomAgendaRepository(next).snapshot.first().reminderChecks.single().date)
        assertNotNull(features.claimReminder(today.plusDays(1)))
    }
    @Test fun emptyAndConcurrentReminderChecksArePersistedExactlyOnce():Unit=runBlocking {
        val day=LocalDate.of(2026,10,29)
        val results=(1..2).map {async(Dispatchers.Default) {features.claimReminder(day)}}.awaitAll()
        assertEquals(1,results.count {it!=null});assertTrue(results.single {it!=null}!!.isEmpty())
        repository.createTask(TaskDraft("今天后来添加",due=day.plusDays(1)))
        assertNull(features.claimReminder(day));assertEquals(1,repository.snapshot.first().reminderChecks.size)
    }
    @Test fun spatialSlotsKeepGapsSwapAndSurviveReopenWithoutChangingTasks():Unit=runBlocking {
        val first=repository.createObject("一");val second=repository.createObject("二")
        val independent=repository.createTask(TaskDraft("独立任务"))
        val before=repository.snapshot.first();val secondPosition=before.placements.single {it.cardId==second}
        features.moveCard(first,secondPosition.column,secondPosition.row)
        val swapped=repository.snapshot.first()
        assertEquals(before.tasks,swapped.tasks);assertEquals(0,swapped.placements.single {it.cardId==second}.column)
        features.moveCard(first,1,3)
        repository.createObject("新对象")
        val reopened=RoomAgendaRepository(open(context.getDatabasePath("agenda.db").absolutePath)).snapshot.first()
        assertEquals(BoardPlacement(first,1,3),reopened.placements.single {it.cardId==first})
        assertEquals(4,reopened.placements.map {it.column to it.row}.distinct().size)
        assertEquals(independent,reopened.tasks.single().id)
        rejects<AgendaFailure.InvalidInput> {features.moveCard(first,2,0)}
        rejects<AgendaFailure.NotFound> {features.moveCard("missing",0,0)}
    }
    @Test fun leisureIsIndependentEditableAndSupportsGroupUndo():Unit=runBlocking {
        val id=features.saveLeisure(null," 散步 ");val before=repository.snapshot.first()
        assertTrue(before.tasks.isEmpty() && before.objects.isEmpty() && before.schedule.isEmpty())
        features.saveLeisure(id,"听音乐");assertEquals(LeisureActivity(id,"听音乐"),repository.snapshot.first().leisure.single())
        val deletion=RoomDeletionRepository(db,gate=gate)
        val preview=deletion.previewDelete(DeleteTarget(DeleteKind.LEISURE,id));assertEquals(1,preview.leisure);assertEquals(0,preview.tasks)
        deletion.delete(preview);assertTrue(repository.snapshot.first().leisure.isEmpty())
        deletion.undoDelete(deletion.pendingDeletions.first().single().token)
        assertEquals(id,repository.snapshot.first().leisure.single().id)
        rejects<AgendaFailure.InvalidInput> {features.saveLeisure(null," ")}
        rejects<AgendaFailure.NotFound> {features.saveLeisure("missing","内容")}
    }
    @Test fun deletingAndUndoingObjectRestoresItsSpatialSlot():Unit=runBlocking {
        val owner=repository.createObject("对象");features.moveCard(owner,1,4)
        val deletion=RoomDeletionRepository(db,gate=gate)
        deletion.delete(deletion.previewDelete(DeleteTarget(DeleteKind.OBJECT,owner)))
        assertTrue(repository.snapshot.first().placements.isEmpty())
        deletion.undoDelete(deletion.pendingDeletions.first().single().token)
        assertEquals(BoardPlacement(owner,1,4),repository.snapshot.first().placements.single())
    }
    @Test fun backgroundCopiesImageAndPureColorClearsOnlyManagedAsset():Unit=runBlocking {
        features.initialize();val source=image();val bytes=source.readBytes()
        visuals.save(0xFFE3E7EA,"第一行\n第二行",true,Uri.fromFile(source))
        val snapshot=repository.snapshot.first();val asset=snapshot.assets.single()
        assertEquals(asset.id,snapshot.appearance.assetId);assertEquals("第一行\n第二行",snapshot.appearance.text)
        assertArrayEquals(bytes,File(context.filesDir,"assets/${asset.id}").readBytes())
        val preview=visuals.image(asset.id);assertEquals(80,preview.width);preview.recycle()
        visuals.save(0xFFFAFAFA,"纯色",false,null)
        assertTrue(repository.snapshot.first().assets.isEmpty());assertFalse(File(context.filesDir,"assets/${asset.id}").exists())
        assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun invalidBackgroundDoesNotPublishImageOrTextAndCleansTemporaryFiles():Unit=runBlocking {
        features.initialize();val before=repository.snapshot.first()
        val bad=File(context.cacheDir,"bad.png").apply {writeText("not image")}
        rejects<Exception> {visuals.save(0xFFEEEEEE,"不应保存",true,Uri.fromFile(bad))}
        assertEquals(before,repository.snapshot.first());assertTrue(File(context.filesDir,"assets").listFiles().orEmpty().isEmpty())
        rejects<AgendaFailure.InvalidInput> {visuals.save(0xFFEEEEEE,"一\n二\n三",false,null)}
        assertEquals(before,repository.snapshot.first())
    }
    @Test fun v2BackupRestoresLayoutsLeisureBackgroundAndReminderHistory():Unit=runBlocking {
        features.initialize();val owner=repository.createObject("对象");features.moveCard(owner,1,3)
        features.saveLeisure(null,"散步");val day=LocalDate.of(2026,10,29);features.claimReminder(day)
        val source=image();visuals.save(0xFFEEEEEE,"背景文字",true,Uri.fromFile(source))
        val before=repository.snapshot.first();val manager=WorkspaceManager(context);val preferences=object:PreferencesRepository {
            override val preferences=flowOf(UserPreferences(2,true,false));override suspend fun save(preferences:UserPreferences) {}
        }
        val store=LocalBackupStore(context,db,preferences,gate,manager.resolve(),manager)
        val bytes=ByteArrayOutputStream().also {store.exportTo({it})}.toByteArray()
        val incoming=store.prepareSource({ByteArrayInputStream(bytes)})
        assertEquals(1,incoming.info.backgrounds);assertEquals(1,incoming.info.leisure)
        store.restore(incoming);val selected=manager.resolve();val restored=open(selected.databaseName)
        assertEquals(before,RoomAgendaRepository(restored).snapshot.first())
        assertNull(DailyFeatureRepository(restored).claimReminder(day))
        val asset=before.assets.single();assertArrayEquals(source.readBytes(),File(selected.assets,asset.id).readBytes())
    }
    @Test fun v1BackupRemainsImportableWithDefaultNewFeatures():Unit=runBlocking {
        features.initialize();val owner=repository.createObject("旧对象");features.moveCard(owner,1,4);features.saveLeisure(null,"不会在 v1 中")
        val manager=WorkspaceManager(context);val preferences=object:PreferencesRepository {
            override val preferences=flowOf(UserPreferences());override suspend fun save(preferences:UserPreferences) {}
        }
        val store=LocalBackupStore(context,db,preferences,gate,manager.resolve(),manager)
        val bytes=ByteArrayOutputStream().also {store.exportTo({it})}.toByteArray()
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use {zip->ZipInputStream(ByteArrayInputStream(bytes)).use {input->while(true) {
            val entry=input.nextEntry ?: break;var data=input.readBytes()
            if(entry.name=="manifest.json") {
                val manifest=JSONObject(String(data)).put("formatVersion",1);val records=manifest.getJSONObject("records").put("version",1)
                listOf("layouts","leisure","appearance","assets","reminders").forEach {records.remove(it)};data=manifest.toString().toByteArray()
            };zip.putNextEntry(ZipEntry(entry.name));zip.write(data);zip.closeEntry()
        }}}
        store.restore(store.prepareSource({ByteArrayInputStream(output.toByteArray())}))
        val restored=open(manager.resolve().databaseName);DailyFeatureRepository(restored).initialize()
        val snapshot=RoomAgendaRepository(restored).snapshot.first()
        assertEquals(owner,snapshot.objects.single().id);assertTrue(snapshot.leisure.isEmpty() && snapshot.assets.isEmpty())
        assertEquals(ScheduleAppearance(),snapshot.appearance);assertEquals(BoardPlacement(owner,0,0),snapshot.placements.single())
    }
    @Test fun v4MigrationAndLegacyUndoRemainValid():Unit=runBlocking {
        db.close();val path=context.getDatabasePath("agenda.db");path.delete();File(path.path+"-wal").delete();File(path.path+"-shm").delete()
        val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val schema=JSONObject(assets.open("com.agenda.data.local.AgendaDatabase/4.json").bufferedReader().use {it.readText()}).getJSONObject("database")
        val id=UUID.randomUUID().toString();val deleted=UUID.randomUUID().toString();val now=System.currentTimeMillis()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use {old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val name=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",name));val indexes=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",name))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO agenda_revision(id,value) VALUES(0,1)")
            old.execSQL("INSERT INTO agenda_objects(id,name,createdAt) VALUES(?,?,?)",arrayOf(id,"保留旧对象",1))
            val legacy=JSONObject(DeletedBundle(tasks=listOf(TaskRow(deleted,"旧撤销任务",null,null,null,true,false,false,1))).encode()).put("version",1)
            listOf("layouts","leisure","appearance","assets","reminders").forEach {legacy.remove(it)}
            old.execSQL("INSERT INTO agenda_delete_undo(token,name,payload,createdAt,expiresAt) VALUES(?,?,?,?,?)",arrayOf("token","旧撤销任务",legacy.toString(),now,now+8000));old.version=4
        }
        db=open(path.absolutePath);repository=RoomAgendaRepository(db);features=DailyFeatureRepository(db)
        assertEquals(6,db.openHelper.readableDatabase.version)
        RoomDeletionRepository(db,clock={now}).undoDelete("token");features.initialize()
        val snapshot=repository.snapshot.first();assertEquals(id,snapshot.objects.single().id);assertEquals(deleted,snapshot.tasks.single().id);assertTrue(snapshot.tasks.single().completed)
        assertEquals(2,snapshot.placements.size)
    }
}

