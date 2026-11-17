package com.agenda.data.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenda.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class BackupPersistenceTest {
    private class Sandbox(base:Context,val folder:File):ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getFilesDir()=File(folder,"files").apply {mkdirs()}
        override fun getCacheDir()=File(folder,"cache").apply {mkdirs()}
        override fun getDatabasePath(name:String)=if(name.startsWith('/')) File(name) else File(folder,"databases/$name").also {it.parentFile?.mkdirs()}
    }
    private class MemoryPreferences:PreferencesRepository {
        val state=MutableStateFlow(UserPreferences())
        override val preferences=state.asStateFlow()
        override suspend fun save(preferences:UserPreferences) {state.value=preferences}
    }
    private lateinit var context:Sandbox
    private lateinit var manager:WorkspaceManager
    private lateinit var db:AgendaDatabase
    private lateinit var gate:LocalDataGate
    private lateinit var repository:RoomAgendaRepository
    private lateinit var materials:LocalMaterialStore
    private lateinit var preferences:MemoryPreferences
    private lateinit var store:LocalBackupStore
    private lateinit var location:WorkspaceLocation
    private val opened=mutableListOf<AgendaDatabase>()
    @Before fun prepare() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        context=Sandbox(base,File(base.cacheDir,"backup-test-${UUID.randomUUID()}").apply {mkdirs()})
        manager=WorkspaceManager(context);location=manager.resolve();gate=LocalDataGate()
        db=open(location);repository=RoomAgendaRepository(db,gate);preferences=MemoryPreferences()
        materials=LocalMaterialStore(context,db,gate,location.materials);store=service()
    }
    @After fun clean() {opened.forEach {it.close()};context.folder.deleteRecursively()}
    private fun open(location:WorkspaceLocation)=AgendaDatabase.open(context,location.databaseName).also {opened+=it}
    private fun service(limits:BackupLimits=BackupLimits(),space:()->Long={Long.MAX_VALUE},hook:(RestoreStep)->Unit={}):LocalBackupStore=
        LocalBackupStore(context,db,preferences,gate,location,manager,limits,space,hook)
    private suspend fun sample():String {
        val owner=repository.createObject("课程🙂")
        val task=repository.createTask(TaskDraft("报告",Effort.FOCUS,owner,LocalDate.of(2026,12,31),true,true))
        repository.setCompleted(task,true);repository.addNote(owner,"报告要求","过程\n结果")
        materials.importSource(owner,"same.txt","text/plain") {ByteArrayInputStream("first bytes".toByteArray())}
        val second=materials.importSource(owner,"same.txt","text/plain") {ByteArrayInputStream("second bytes".toByteArray())}
        repository.updateMaterial(second.id,"材料标题","材料说明\n第二行")
        repository.createSchedule(ScheduleDraft(LocalDate.of(2026,12,31),Period.MORNING,taskId=task,displayName="独立显示名称"))
        val temp=repository.createSchedule(ScheduleDraft(LocalDate.of(2026,12,31),Period.EVENING,title="散步"));repository.setScheduleCompleted(temp,true)
        val week=repository.addWeeklyPlan(LocalDate.of(2026,12,31),"周计划");repository.setWeeklyPlanCompleted(week,true)
        preferences.save(UserPreferences(2,true,true));return owner
    }
    private suspend fun archive():ByteArray=ByteArrayOutputStream().also {output->store.exportTo({output})}.toByteArray()
    private suspend fun prepared(bytes:ByteArray)=store.prepareSource({ByteArrayInputStream(bytes)})
    private suspend inline fun <reified T:Throwable> rejects(noinline action:suspend ()->Unit):T {
        try {action();fail("Expected ${T::class.java.simpleName}")}
        catch(e:Throwable) {assertTrue("Expected ${T::class.java.simpleName}, got $e",e is T);return e as T}
        throw AssertionError()
    }
    private fun entries(bytes:ByteArray):LinkedHashMap<String,ByteArray> {
        val result=linkedMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use {zip->while(true) {val entry=zip.nextEntry ?: break;result[entry.name]=zip.readBytes()}}
        return result
    }
    private fun zip(entries:Map<String,ByteArray>):ByteArray=ByteArrayOutputStream().also {output->ZipOutputStream(output).use {zip->entries.forEach {(name,data)->zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()}}}.toByteArray()
    private fun modifiedManifest(bytes:ByteArray,change:(JSONObject)->Unit):ByteArray {
        val entries=entries(bytes);val manifest=JSONObject(String(entries.getValue("manifest.json"),Charsets.UTF_8));change(manifest)
        entries["manifest.json"]=manifest.toString().toByteArray();return zip(entries)
    }
    @Test fun fullRoundTripRestoresStableIdsFilesAndSettingsAndCanRecoverPreRestoreSnapshot():Unit=runBlocking {
        sample();val original=repository.snapshot.first();val originalPreferences=preferences.state.value
        val bytes=archive();val incoming=prepared(bytes)
        repository.createTask(TaskDraft("恢复前新增"));preferences.save(UserPreferences(1,false,false))
        val before=repository.snapshot.first();val beforePreferences=preferences.state.value
        val generation=store.restore(incoming)
        assertEquals(generation,manager.resolve().id)
        rejects<AgendaFailure.WorkspaceChanged> {repository.createTask(TaskDraft("旧写入"))}
        val selected=manager.resolve();val newDb=open(selected);val newGate=LocalDataGate()
        val newPreferences=LocalPreferences(context,newGate,selected.preferencesFile,selected.seed)
        val newRepo=RoomAgendaRepository(newDb,newGate);val newMaterials=LocalMaterialStore(context,newDb,newGate,selected.materials)
        assertEquals(original,newRepo.snapshot.first());assertEquals(originalPreferences,newPreferences.preferences.first())
        val copies=original.objects.single().materials.associate {it.id to File(location.materials,it.id).readBytes().toList()}
        original.objects.single().materials.forEach {assertEquals(copies[it.id],newMaterials.copyFor(it.id).readBytes().toList())}
        val next=LocalBackupStore(context,newDb,newPreferences,newGate,selected,manager)
        val snapshot=next.snapshots().single();val recovery=next.prepareSnapshot(snapshot.id)
        next.restore(recovery)
        val recovered=manager.resolve();val restoredDb=open(recovered)
        assertEquals(before,RoomAgendaRepository(restoredDb).snapshot.first())
        assertEquals(beforePreferences,LocalPreferences(context,file=recovered.preferencesFile,seed=recovered.seed).preferences.first())
    }
    @Test fun emptyDatasetCanReplacePopulatedDatasetWithoutMerging():Unit=runBlocking {
        val empty=archive();sample();store.restore(prepared(empty))
        assertEquals(EmptyAgenda,RoomAgendaRepository(open(manager.resolve())).snapshot.first())
        assertEquals(1,store.snapshots().size)
    }
    @Test fun damagedTruncatedOrUnsupportedArchivesLeaveActiveDataUnchanged():Unit=runBlocking {
        sample();val bytes=archive();val before=repository.snapshot.first()
        for(bad in listOf("not zip".toByteArray(),bytes.copyOf(bytes.size-12),modifiedManifest(bytes) {it.put("formatVersion",99)})) {
            val error=rejects<BackupException> {prepared(bad)}
            assertTrue(error.problem in setOf(BackupProblem.INVALID,BackupProblem.UNSUPPORTED))
            assertEquals(before,repository.snapshot.first());assertEquals("legacy",manager.resolve().id)
        }
    }
    @Test fun missingExtraTraversalAndDigestMismatchesAreRejectedBeforeSwitch():Unit=runBlocking {
        sample();val bytes=archive();val before=repository.snapshot.first()
        val original=entries(bytes);val file=original.keys.first {it.startsWith("materials/")}
        val variants=listOf(zip(LinkedHashMap(original).apply {remove(file)}),
            zip(LinkedHashMap(original).apply {put("../outside.txt","escape".toByteArray())}),
            zip(LinkedHashMap(original).apply {put(file,original.getValue(file).copyOf().also {it[0]=(it[0].toInt() xor 1).toByte()})}),
            modifiedManifest(bytes) {it.getJSONObject("records").getJSONArray("materials").getJSONArray(0).put(4,"../../outside")})
        variants.forEach {rejects<BackupException> {prepared(it)}}
        assertEquals(before,repository.snapshot.first());assertFalse(File(context.folder,"outside.txt").exists());assertEquals("legacy",manager.resolve().id)
    }
    @Test fun danglingReferencesDuplicateSchedulesAndInvalidWeekDatesAreRejected():Unit=runBlocking {
        sample();val bytes=archive()
        val variants=listOf(
            modifiedManifest(bytes) {it.getJSONObject("records").getJSONArray("tasks").getJSONArray(0).put(2,UUID.randomUUID().toString())},
            modifiedManifest(bytes) {val list=it.getJSONObject("records").getJSONArray("schedule");val referenced=(0 until list.length()).map {i->list.getJSONArray(i)}.first {row->!row.isNull(4)};val duplicate=org.json.JSONArray(referenced.toString()).put(0,UUID.randomUUID().toString());list.put(duplicate)},
            modifiedManifest(bytes) {it.getJSONObject("records").getJSONArray("weekly").getJSONArray(0).put(1,LocalDate.of(2026,12,29).toEpochDay())})
        variants.forEach {rejects<BackupException> {prepared(it)}}
        assertEquals("legacy",manager.resolve().id);assertTrue(store.snapshots().isEmpty())
    }
    @Test fun duplicateZipEntryNamesAreRejected():Unit=runBlocking {
        sample();val bytes=archive();val names=entries(bytes).keys.filter {it.startsWith("materials/")}
        val duplicate=String(bytes,Charsets.ISO_8859_1).replace(names[1],names[0]).toByteArray(Charsets.ISO_8859_1)
        assertEquals(BackupProblem.INVALID,rejects<BackupException> {prepared(duplicate)}.problem)
        assertEquals("legacy",manager.resolve().id)
    }
    @Test fun manifestRecordAndFileLimitsRejectOversizedInputs():Unit=runBlocking {
        sample();val bytes=archive()
        for(limits in listOf(BackupLimits(manifestBytes=32),BackupLimits(recordCount=1),BackupLimits(fileBytes=1),BackupLimits(archiveBytes=32))) {
            assertEquals(BackupProblem.LIMIT,rejects<BackupException> {service(limits).prepareSource({ByteArrayInputStream(bytes)})}.problem)
        }
        assertEquals("legacy",manager.resolve().id)
    }
    @Test fun insufficientSpaceStopsPreparationAndRestorationBeforeChangingData():Unit=runBlocking {
        sample();val bytes=archive();val before=repository.snapshot.first()
        assertEquals(BackupProblem.NO_SPACE,rejects<BackupException> {service(space={0}).prepareSource({ByteArrayInputStream(bytes)})}.problem)
        val incoming=prepared(bytes)
        assertEquals(BackupProblem.NO_SPACE,rejects<BackupException> {service(space={0}).restore(incoming)}.problem)
        assertEquals(before,repository.snapshot.first());assertTrue(store.snapshots().isEmpty());assertEquals("legacy",manager.resolve().id)
    }
    @Test fun spaceDroppingDuringStreamingStopsWithoutChangingCurrentData():Unit=runBlocking {
        sample();val before=repository.snapshot.first();var probes=0
        val guarded=service(space={if(probes++==0) Long.MAX_VALUE else 0})
        val error=rejects<BackupException> {guarded.prepareSource({ByteArrayInputStream(ByteArray(2*1024*1024))})}
        assertEquals(BackupProblem.NO_SPACE,error.problem);assertEquals(before,repository.snapshot.first())
        assertTrue(manager.stagingRoot.listFiles().orEmpty().isEmpty());assertEquals("legacy",manager.resolve().id)
    }
    @Test fun incompleteExportDoesNotChangeSourceAndCleansItsStaging():Unit=runBlocking {
        sample();val before=repository.snapshot.first()
        rejects<IOException> {store.exportTo({object:OutputStream() {override fun write(b:Int) {throw IOException("provider failure")}}})}
        assertEquals(before,repository.snapshot.first());assertTrue(manager.stagingRoot.listFiles().orEmpty().isEmpty())
    }
    @Test fun activeUndoBlocksExportAndRestoreWithoutEndingUndo():Unit=runBlocking {
        sample();val bytes=archive();val incoming=prepared(bytes)
        val task=repository.snapshot.first().tasks.single().id
        val deletion=RoomDeletionRepository(db,gate=gate)
        deletion.delete(deletion.previewDelete(DeleteTarget(DeleteKind.TASK,task)))
        val token=deletion.pendingDeletions.first().single().token
        assertEquals(BackupProblem.PENDING_UNDO,rejects<BackupException> {archive()}.problem)
        assertEquals(BackupProblem.PENDING_UNDO,rejects<BackupException> {store.restore(incoming)}.problem)
        assertTrue(store.snapshots().isEmpty());deletion.undoDelete(token);assertEquals(1,repository.snapshot.first().tasks.size)
    }
    @Test fun snapshotFailurePreservesActiveDatasetAndDoesNotPublishNewOne():Unit=runBlocking {
        sample();val incoming=prepared(archive());val before=repository.snapshot.first()
        File(location.materials,before.objects.single().materials.first().id).delete()
        assertEquals(BackupProblem.SNAPSHOT_FAILED,rejects<BackupException> {store.restore(incoming)}.problem)
        assertEquals(before,repository.snapshot.first());assertEquals("legacy",manager.resolve().id);assertTrue(store.snapshots().isEmpty())
    }
    @Test fun changedStagedCopyCannotBypassValidationAtConfirmation():Unit=runBlocking {
        sample();val incoming=prepared(archive());val before=repository.snapshot.first()
        val material=before.objects.single().materials.first()
        File(manager.stagingRoot,"${incoming.handle}/materials/${material.id}").writeText("tampered")
        rejects<BackupException> {store.restore(incoming)}
        assertEquals(before,repository.snapshot.first());assertEquals("legacy",manager.resolve().id)
    }
    private class SimulatedCrash:Error()
    @Test fun interruptionsBeforePointerPublicationKeepOldDataset():Unit=runBlocking {
        sample();val bytes=archive();repository.createTask(TaskDraft("保留当前"));val before=repository.snapshot.first()
        for(step in listOf(RestoreStep.VALIDATED,RestoreStep.SNAPSHOT_SAVED,RestoreStep.NEW_READY)) {
            val incoming=prepared(bytes)
            rejects<SimulatedCrash> {service(hook={if(it==step) throw SimulatedCrash()}).restore(incoming)}
            assertEquals("legacy",WorkspaceManager(context).resolve().id);assertEquals(before,repository.snapshot.first())
            manager.cleanStartupStaging()
        }
    }
    @Test fun interruptionAfterPointerPublicationResolvesWholeNewDatasetOnStartup():Unit=runBlocking {
        sample();val original=repository.snapshot.first();val incoming=prepared(archive())
        repository.createTask(TaskDraft("只在旧数据"));val before=repository.snapshot.first()
        rejects<SimulatedCrash> {service(hook={if(it==RestoreStep.POINTER_PUBLISHED) throw SimulatedCrash()}).restore(incoming)}
        val selected=WorkspaceManager(context).resolve()
        assertTrue(selected.id!="legacy");assertEquals(original,RoomAgendaRepository(open(selected)).snapshot.first());assertEquals(before,repository.snapshot.first())
    }
    @Test fun invalidPendingDatasetRollsBackToOldDatasetOnStartup():Unit=runBlocking {
        sample();val before=repository.snapshot.first();val incoming=prepared(archive())
        rejects<SimulatedCrash> {service(hook={if(it==RestoreStep.POINTER_PUBLISHED) {
            manager.workspacesRoot.listFiles().orEmpty().forEach {File(it,"READY").delete()};throw SimulatedCrash()
        }}).restore(incoming)}
        assertEquals("legacy",WorkspaceManager(context).resolve().id);assertEquals(before,repository.snapshot.first())
    }
    @Test fun ordinarySwitchFailureRollsBackAndLeavesOldWritersUsable():Unit=runBlocking {
        sample();val incoming=prepared(archive())
        assertEquals(BackupProblem.SWITCH_FAILED,rejects<BackupException> {service(hook={if(it==RestoreStep.POINTER_PUBLISHED) throw IOException("test failure")}).restore(incoming)}.problem)
        assertEquals("legacy",manager.resolve().id);repository.createTask(TaskDraft("仍可写入"));assertEquals(2,repository.snapshot.first().tasks.size)
    }
    @Test fun exportFreezesConcurrentWritesUntilConsistentArchiveIsComplete():Unit=runBlocking {
        sample();val before=repository.snapshot.first();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val bytes=ByteArrayOutputStream()
        val exporting=async(Dispatchers.IO) {store.exportTo({bytes}) {if(it.startsWith("正在保存附件 1/")) {entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))}}}
        assertTrue(withContext(Dispatchers.IO) {entered.await(10,TimeUnit.SECONDS)})
        val editing=async(Dispatchers.IO) {repository.createTask(TaskDraft("归档之后"))}
        delay(100);assertFalse(editing.isCompleted);release.countDown();exporting.await();editing.await()
        store.restore(prepared(bytes.toByteArray()))
        assertEquals(before,RoomAgendaRepository(open(manager.resolve())).snapshot.first())
    }
    @Test fun writersQueuedDuringSwitchCannotModifyOldDatasetAfterCommit():Unit=runBlocking {
        sample();val before=repository.snapshot.first();val incoming=prepared(archive())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);var queued:Deferred<String>?=null
        try {
            service(hook={if(it==RestoreStep.NEW_READY) {
                val started=CountDownLatch(1)
                queued=scope.async {started.countDown();repository.createTask(TaskDraft("旧页面写入"))}
                assertTrue(started.await(10,TimeUnit.SECONDS))
            }}).restore(incoming)
            rejects<AgendaFailure.WorkspaceChanged> {queued!!.await()}
            assertEquals(before,repository.snapshot.first());assertEquals(before,RoomAgendaRepository(open(manager.resolve())).snapshot.first())
        } finally {scope.cancel()}
    }
    @Test fun snapshotsRetainLatestThreeAndCanBeExportedAgain():Unit=runBlocking {
        sample();val bytes=archive()
        var selected=location;var currentDb=db;var currentPrefs:PreferencesRepository=preferences;var currentGate=gate
        repeat(4) {
            val current=LocalBackupStore(context,currentDb,currentPrefs,currentGate,selected,manager)
            current.restore(current.prepareSource({ByteArrayInputStream(bytes)}))
            selected=manager.resolve();currentDb=open(selected);currentGate=LocalDataGate();currentPrefs=LocalPreferences(context,currentGate,selected.preferencesFile,selected.seed)
        }
        val current=LocalBackupStore(context,currentDb,currentPrefs,currentGate,selected,manager)
        assertEquals(3,current.snapshots().size)
        assertEquals(2,current.prepareSnapshot(current.snapshots().first().id).info.materials)
        opened.forEach {it.close()};manager.cleanStartupStaging()
        assertTrue(manager.workspacesRoot.listFiles().orEmpty().size<=2)
    }
}
