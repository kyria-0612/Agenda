package com.agenda.data.local

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import com.agenda.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

enum class RestoreStep { VALIDATED,SNAPSHOT_SAVED,NEW_READY,POINTER_PUBLISHED }

class LocalBackupStore(private val context:Context,private val database:AgendaDatabase,
    private val preferences:PreferencesRepository,private val gate:LocalDataGate,
    private val location:WorkspaceLocation,private val manager:WorkspaceManager,
    private val limits:BackupLimits=BackupLimits(),
    private val availableBytes:()->Long={StatFs(context.filesDir.absolutePath).availableBytes},
    private val restoreHook:(RestoreStep)->Unit={}) {
    private val dao=database.agenda()
    private fun ensureSpace(bytes:Long) {if(availableBytes()<Math.addExact(bytes,16L*1024*1024)) throw BackupException(BackupProblem.NO_SPACE)}
    private fun newStage():File {
        val folder=File(manager.stagingRoot,UUID.randomUUID().toString())
        if(!folder.mkdirs()) throw IOException("Staging unavailable")
        return folder
    }
    private suspend fun copy(input:InputStream,output:OutputStream,max:Long,digest:MessageDigest?=null,crc:CRC32?=null,checkSpace:Boolean=false,overflow:BackupProblem=BackupProblem.INVALID):Long {
        val buffer=ByteArray(64*1024);var total=0L;var checked=0L
        while(true) {
            currentCoroutineContext().ensureActive()
            val count=input.read(buffer);if(count<0) break
            total=Math.addExact(total,count.toLong());if(total>max) throw BackupException(overflow)
            output.write(buffer,0,count);digest?.update(buffer,0,count);crc?.update(buffer,0,count)
            if(checkSpace && total-checked>=1024*1024) {ensureSpace(0);checked=total}
        }
        return total
    }
    private suspend fun currentContents():BackupContents=database.withTransaction {
        dao.expireDeletions(System.currentTimeMillis())
        if(dao.deletions().isNotEmpty()) throw BackupException(BackupProblem.PENDING_UNDO)
        BackupContents(DeletedBundle(dao.objects(),dao.tasks(),dao.materials(),dao.notes(),dao.schedule(),dao.weeklyPlans(),
            dao.layouts(),dao.leisure(),listOfNotNull(dao.appearance()),dao.assets(),dao.reminderChecks(),dao.taskMaterials()),preferences.preferences.first(),System.currentTimeMillis())
    }
    private suspend fun writeArchive(file:File,contents:BackupContents,progress:(String)->Unit) {
        BackupCodec.validate(contents,limits)
        val manifest=BackupCodec.encode(contents);if(manifest.size>limits.manifestBytes) throw BackupException(BackupProblem.LIMIT)
        val files=contents.files()
        ensureSpace(contents.info().fileBytes+manifest.size)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use {raw->
            // Do not close the underlying descriptor until ZIP central directory and fsync complete.
            val zip=ZipOutputStream(BufferedOutputStream(object:FilterOutputStream(raw) {
                override fun write(b:ByteArray,off:Int,len:Int) {out.write(b,off,len)}
                override fun close() {flush()}
            }))
            zip.use {
                it.putNextEntry(ZipEntry("manifest.json"));it.write(manifest);it.closeEntry()
                files.forEachIndexed {index,material->
                    progress("正在保存附件 ${index+1}/${files.size}")
                    val source=File(if(material.path.startsWith("assets/")) location.assets else location.materials,material.id)
                    if(!source.isFile || source.length()!=material.size) throw AgendaFailure.FileUnavailable()
                    val digest=MessageDigest.getInstance("SHA-256")
                    it.putNextEntry(ZipEntry(material.path))
                    val size=source.inputStream().use {input->copy(input,it,material.size,digest,checkSpace=true)}
                    if(size!=material.size || digest.hex()!=material.sha256) throw AgendaFailure.FileUnavailable()
                    it.closeEntry()
                }
            }
            raw.fd.sync()
        }
    }
    private suspend fun readArchive(file:File,destination:File?,progress:(String)->Unit):BackupContents {
        try {ZipFile(file).use {zip->
            val entries=zip.entries().toList()
            if(entries.size>limits.recordCount+1) throw BackupException(BackupProblem.LIMIT)
            if(entries.any {it.isDirectory} || entries.map {it.name}.distinct().size!=entries.size) throw BackupException(BackupProblem.INVALID)
            val manifest=entries.singleOrNull {it.name=="manifest.json"} ?: throw BackupException(BackupProblem.INVALID)
            val bytes=ByteArrayOutputStream();val crc=CRC32()
            zip.getInputStream(manifest).use {copy(it,bytes,limits.manifestBytes,crc=crc,overflow=BackupProblem.LIMIT)}
            if(crc.value!=manifest.crc) throw BackupException(BackupProblem.INVALID)
            val contents=BackupCodec.decode(bytes.toByteArray(),limits)
            val files=contents.files();val expected=files.map {it.path}.toSet()+"manifest.json"
            if(entries.map {it.name}.toSet()!=expected) throw BackupException(BackupProblem.INVALID)
            if(destination!=null) ensureSpace(contents.info().fileBytes+limits.manifestBytes)
            files.forEachIndexed {index,material->
                progress("正在校验附件 ${index+1}/${files.size}")
                val entry=zip.getEntry(material.path)
                if(entry.size!=material.size) throw BackupException(BackupProblem.INVALID)
                val digest=MessageDigest.getInstance("SHA-256");val fileCrc=CRC32()
                val output=destination?.let {File(it,material.path).also {f->f.parentFile?.mkdirs()}}
                val size=zip.getInputStream(entry).use {input->
                    if(output==null) copy(input,object:OutputStream() {override fun write(b:Int) {};override fun write(b:ByteArray,off:Int,len:Int) {}},material.size,digest,fileCrc)
                    else FileOutputStream(output).use {stream->copy(input,stream,material.size,digest,fileCrc,checkSpace=true).also {stream.fd.sync()}}
                }
                if(size!=material.size || digest.hex()!=material.sha256 || fileCrc.value!=entry.crc) throw BackupException(BackupProblem.INVALID)
            }
            if(destination!=null) {
                contents.records.assets.forEach {validateImage(File(destination,"assets/${it.id}"))}
                WorkspaceManager.writeSynced(File(destination,"manifest.json"),bytes.toByteArray())
            }
            return contents
        }} catch(c:CancellationException) {throw c}
        catch(e:BackupException) {throw e}
        catch(e:Exception) {throw BackupException(BackupProblem.INVALID,e)}
    }
    suspend fun export(uri:Uri,progress:(String)->Unit={})=exportTo({context.contentResolver.openOutputStream(uri,"wt") ?: throw IOException("Destination unavailable")},progress)
    internal suspend fun exportTo(destination:()->OutputStream,progress:(String)->Unit={})=withContext(Dispatchers.IO) {
        val stage=newStage()
        try {
            val archive=File(stage,"export.agenda.zip")
            gate.run {progress("正在生成一致备份");writeArchive(archive,currentContents(),progress)}
            destination().use {output->archive.inputStream().use {copy(it,output,limits.archiveBytes)};output.flush()}
        } finally {WorkspaceManager.safeRemove(manager.stagingRoot,stage)}
    }
    suspend fun prepare(uri:Uri,progress:(String)->Unit={}):PreparedBackup=prepareSource({context.contentResolver.openInputStream(uri) ?: throw IOException("Source unavailable")},progress)
    internal suspend fun prepareSource(source:()->InputStream,progress:(String)->Unit={}):PreparedBackup=withContext(Dispatchers.IO) {
        val stage=newStage();var keep=false
        try {
            progress("正在读取备份");ensureSpace(limits.manifestBytes)
            val archive=File(stage,"incoming.zip")
            source().use {input->FileOutputStream(archive).use {output->copy(input,output,limits.archiveBytes,checkSpace=true,overflow=BackupProblem.LIMIT);output.fd.sync()}}
            val contents=readArchive(archive,stage,progress)
            keep=true;PreparedBackup(stage.name,contents.info())
        } finally {if(!keep) WorkspaceManager.safeRemove(manager.stagingRoot,stage)}
    }
    suspend fun discard(prepared:PreparedBackup)=withContext(Dispatchers.IO) {
        if(WorkspaceManager.validUuid(prepared.handle)) WorkspaceManager.safeRemove(manager.stagingRoot,File(manager.stagingRoot,prepared.handle))
    }
    private suspend fun verifyStage(stage:File,progress:(String)->Unit):BackupContents {
        val manifest=File(stage,"manifest.json")
        if(!manifest.isFile || manifest.length()>limits.manifestBytes) throw BackupException(BackupProblem.STALE)
        val contents=BackupCodec.decode(manifest.readBytes(),limits)
        val files=contents.files()
        files.forEachIndexed {index,material->
            progress("正在复核附件 ${index+1}/${files.size}")
            val file=File(stage,material.path)
            if(!file.isFile || file.length()!=material.size) throw BackupException(BackupProblem.INVALID)
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->copy(input,object:OutputStream() {override fun write(b:Int) {};override fun write(b:ByteArray,off:Int,len:Int) {}},material.size,digest)}
            if(digest.hex()!=material.sha256) throw BackupException(BackupProblem.INVALID)
        }
        contents.records.assets.forEach {validateImage(File(stage,"assets/${it.id}"))}
        return contents
    }
    suspend fun restore(prepared:PreparedBackup,progress:(String)->Unit={}):String=withContext(Dispatchers.IO) {gate.run {
        if(!WorkspaceManager.validUuid(prepared.handle)) throw BackupException(BackupProblem.STALE)
        val stage=File(manager.stagingRoot,prepared.handle)
        if(!stage.isDirectory) throw BackupException(BackupProblem.STALE)
        val contents=verifyStage(stage,progress);restoreHook(RestoreStep.VALIDATED)
        val current=currentContents() // Reject active undo before creating or switching anything.
        ensureSpace(current.info().fileBytes+BackupCodec.encode(current).size+BackupCodec.encode(contents).size*3L)
        progress("正在保存恢复前快照")
        val snapshotId=UUID.randomUUID().toString();manager.snapshotsRoot.mkdirs()
        val temporarySnapshot=File(manager.snapshotsRoot,"$snapshotId.part")
        val snapshot=File(manager.snapshotsRoot,"$snapshotId.agenda.zip")
        try {
            writeArchive(temporarySnapshot,current,progress);readArchive(temporarySnapshot,null,progress)
            if(!temporarySnapshot.renameTo(snapshot)) throw IOException("Snapshot commit failed")
            WorkspaceManager.syncDirectory(manager.snapshotsRoot)
        } catch(c:CancellationException) {temporarySnapshot.delete();throw c}
        catch(e:Exception) {temporarySnapshot.delete();throw BackupException(BackupProblem.SNAPSHOT_FAILED,e)}
        manager.trimSnapshots();restoreHook(RestoreStep.SNAPSHOT_SAVED)
        progress("正在准备新的本机数据")
        val building=File(stage,"dataset");if(!building.mkdirs()) throw IOException("Dataset staging unavailable")
        val db=AgendaDatabase.open(context,File(building,"agenda.db").absolutePath)
        try {
            db.withTransaction {
                val target=db.agenda();val r=contents.records
                r.objects.forEach {target.insertObject(it)};r.tasks.forEach {target.insertTask(it)}
                r.notes.forEach {target.insertNote(it)};r.materials.forEach {target.insertMaterial(it)}
                r.taskMaterials.forEach {target.insertTaskMaterial(it)}
                r.schedule.forEach {target.insertSchedule(it)};r.weekly.forEach {target.insertWeeklyPlan(it)}
                r.layouts.forEach {target.saveLayout(it)};r.leisure.forEach {target.insertLeisure(it)}
                r.assets.forEach {target.insertAsset(it)};r.appearance.forEach {target.saveAppearance(it)}
                r.reminders.forEach {target.insertReminderCheck(it)};target.changed()
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {require(it.moveToFirst() && it.getInt(0)==0)}
        } finally {db.close()}
        for(folder in listOf("materials","assets")) {
            val sourceFiles=File(stage,folder)
            if(sourceFiles.isDirectory) {if(!sourceFiles.renameTo(File(building,folder))) throw IOException("File staging failed")}
            else if(!File(building,folder).mkdirs()) throw IOException("Directory unavailable")
        }
        WorkspaceManager.writeSynced(File(building,"preferences-seed.json"),BackupCodec.preferencesJson(contents.preferences).toString().toByteArray())
        WorkspaceManager.writeSynced(File(building,"READY"),"1".toByteArray())
        WorkspaceManager.syncDirectory(building)
        val generation=UUID.randomUUID().toString();manager.workspacesRoot.mkdirs()
        val destination=File(manager.workspacesRoot,generation)
        if(!building.renameTo(destination)) throw IOException("Dataset commit failed")
        WorkspaceManager.syncDirectory(manager.workspacesRoot)
        manager.validateReady(generation);restoreHook(RestoreStep.NEW_READY)
        progress("正在切换数据")
        withContext(NonCancellable) {
            try {
                manager.publish(generation,location.id);restoreHook(RestoreStep.POINTER_PUBLISHED)
                if(manager.resolve().id!=generation) throw BackupException(BackupProblem.SWITCH_FAILED)
                gate.retire()
            } catch(e:Exception) {
                try {manager.rollbackTo(location.id)} catch(rollback:Exception) {gate.retire()}
                throw BackupException(BackupProblem.SWITCH_FAILED,e)
            }
        }
        WorkspaceManager.safeRemove(manager.stagingRoot,stage)
        generation
    }}
    suspend fun snapshots():List<RecoverySnapshot> =withContext(Dispatchers.IO) {manager.snapshotFiles().map {RecoverySnapshot(it.name.removeSuffix(".agenda.zip"),it.lastModified(),it.length())}}
    private fun snapshotFile(id:String):File {
        if(!WorkspaceManager.validUuid(id)) throw BackupException(BackupProblem.STALE)
        return File(manager.snapshotsRoot,"$id.agenda.zip").also {if(!it.isFile) throw BackupException(BackupProblem.STALE)}
    }
    suspend fun prepareSnapshot(id:String,progress:(String)->Unit={}):PreparedBackup=prepareSource({snapshotFile(id).inputStream()},progress)
    suspend fun exportSnapshot(id:String,uri:Uri,progress:(String)->Unit={})=withContext(Dispatchers.IO) {
        val file=snapshotFile(id);readArchive(file,null,progress)
        context.contentResolver.openOutputStream(uri,"wt")?.use {output->file.inputStream().use {copy(it,output,limits.archiveBytes)};output.flush()} ?: throw IOException("Destination unavailable")
    }
    private fun MessageDigest.hex()=digest().joinToString("") {"%02x".format(it)}
    private fun validateImage(file:File) {
        try {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) {decoder,info,_->
                val ratio=minOf(1.0,128.0/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()));decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }.recycle()
        } catch(e:Exception) {throw BackupException(BackupProblem.INVALID,e)}
    }
}
