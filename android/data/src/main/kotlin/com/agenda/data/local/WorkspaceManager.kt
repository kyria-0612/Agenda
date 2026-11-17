package com.agenda.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import com.agenda.domain.UserPreferences
import org.json.JSONObject
import java.io.*
import java.util.UUID

data class WorkspaceLocation(val id:String,val root:File?,val databaseName:String,val materials:File,
    val preferencesFile:File?,val seed:UserPreferences?,val assets:File=File(materials.parentFile,"assets"))

/** Atomic pointer publishes an already validated, complete dataset. Legacy data stays intact. */
class WorkspaceManager(private val context:Context) {
    internal val stagingRoot=File(context.filesDir,"backup-staging")
    internal val workspacesRoot=File(context.filesDir,"workspaces")
    internal val snapshotsRoot=File(context.filesDir,"recovery-snapshots")
    private val pointer=AtomicFile(File(context.filesDir,"agenda-workspace.json"))
    private fun readPointer():JSONObject=try {JSONObject(String(pointer.readFully(),Charsets.UTF_8))}
        catch(e:FileNotFoundException) {JSONObject().put("active","legacy").put("previous","legacy").put("pending",false)}
    private fun writePointer(json:JSONObject) {
        val stream=pointer.startWrite()
        try {stream.write(json.toString().toByteArray(Charsets.UTF_8));pointer.finishWrite(stream)}
        catch(e:Exception) {pointer.failWrite(stream);throw e}
    }
    internal fun location(id:String):WorkspaceLocation {
        if(id=="legacy") return WorkspaceLocation(id,null,context.getDatabasePath("agenda.db").absolutePath,File(context.filesDir,"materials"),null,null)
        require(validUuid(id))
        val root=File(workspacesRoot,id)
        val seed=BackupCodec.preferences(JSONObject(File(root,"preferences-seed.json").readText()))
        return WorkspaceLocation(id,root,File(root,"agenda.db").absolutePath,File(root,"materials"),File(root,"settings.preferences_pb"),seed)
    }
    @Synchronized fun resolve():WorkspaceLocation {
        val record=readPointer();val id=record.getString("active")
        if(record.getBoolean("pending")) {
            val previous=record.getString("previous")
            try {
                validateReady(id)
                writePointer(JSONObject().put("active",id).put("previous",previous).put("pending",false))
            } catch(e:Exception) {
                // An interrupted, uncommitted switch must never hide the old dataset.
                location(previous)
                writePointer(JSONObject().put("active",previous).put("previous",previous).put("pending",false))
                return location(previous)
            }
        }
        return location(id)
    }
    internal fun validateReady(id:String) {
        require(validUuid(id));val location=location(id)
        require(File(location.root,"READY").isFile)
        SQLiteDatabase.openDatabase(location.databaseName,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("PRAGMA quick_check",null).use {require(it.moveToFirst() && it.getString(0)=="ok")}
            db.rawQuery("PRAGMA foreign_key_check",null).use {require(!it.moveToFirst())}
            db.rawQuery("SELECT id,fileKey,sizeBytes FROM agenda_materials",null).use {rows->
                while(rows.moveToNext()) {
                    val id=rows.getString(0);require(validUuid(id) && rows.getString(1)==id)
                    val file=File(location.materials,id);require(file.isFile && file.length()==rows.getLong(2))
                }
            }
            db.rawQuery("SELECT id,sizeBytes FROM agenda_visual_assets",null).use {rows->
                while(rows.moveToNext()) {
                    val id=rows.getString(0);require(validUuid(id))
                    val file=File(location.assets,id);require(file.isFile && file.length()==rows.getLong(1))
                }
            }
        }
    }
    @Synchronized internal fun publish(id:String,previous:String) {
        require(resolve().id==previous)
        validateReady(id)
        writePointer(JSONObject().put("active",id).put("previous",previous).put("pending",true))
    }
    @Synchronized internal fun rollbackTo(id:String) {
        location(id)
        writePointer(JSONObject().put("active",id).put("previous",id).put("pending",false))
    }
    /** Called only at cold startup, before services or imports are created. */
    @Synchronized fun cleanStartupStaging() {
        stagingRoot.listFiles().orEmpty().filter {validUuid(it.name)}.forEach {safeRemove(stagingRoot,it)}
        val record=readPointer();val keep=setOf(record.getString("active"),record.getString("previous"))
        workspacesRoot.listFiles().orEmpty().filter {validUuid(it.name) && it.name !in keep}.forEach {safeRemove(workspacesRoot,it)}
        snapshotsRoot.listFiles().orEmpty().filter {it.isFile && it.name.endsWith(".part") && validUuid(it.name.removeSuffix(".part"))}.forEach {it.delete()}
    }
    internal fun trimSnapshots() {
        snapshotFiles().drop(3).forEach {it.delete()}
    }
    internal fun snapshotFiles():List<File> = snapshotsRoot.listFiles().orEmpty()
        .filter {it.isFile && it.name.endsWith(".agenda.zip") && validUuid(it.name.removeSuffix(".agenda.zip"))}
        .sortedWith(compareByDescending<File> {it.lastModified()}.thenBy {it.name})
    companion object {
        internal fun validUuid(id:String)=runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)
        internal fun safeRemove(parent:File,target:File) {
            require(target.canonicalFile.parentFile==parent.canonicalFile && validUuid(target.name))
            target.deleteRecursively()
        }
        internal fun writeSynced(file:File,bytes:ByteArray) {
            file.parentFile?.let {if(!it.isDirectory && !it.mkdirs()) throw IOException("Directory unavailable")}
            FileOutputStream(file).use {it.write(bytes);it.fd.sync()}
        }
        internal fun syncDirectory(directory:File) {
            require(directory.isDirectory)
            val fd=android.system.Os.open(directory.absolutePath,android.system.OsConstants.O_RDONLY,0)
            try {android.system.Os.fsync(fd)} finally {android.system.Os.close(fd)}
        }
    }
}
