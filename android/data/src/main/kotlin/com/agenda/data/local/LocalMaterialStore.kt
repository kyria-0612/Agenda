package com.agenda.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.agenda.domain.*
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID

data class MaterialDocument(val uri:Uri,val mimeType:String)

/** UUID private files plus Room metadata. Originals are never modified. */
class LocalMaterialStore(private val context:Context,private val database:AgendaDatabase,
    private val gate:LocalDataGate=LocalDataGate(),private val directory:File=File(context.filesDir,"materials")) {
    private val dao=database.agenda()
    suspend fun recoverOrphans()=withContext(Dispatchers.IO) {gate.run {
        // Only after a successful complete metadata read; never clean on DB failure.
        val referenced=database.withTransaction {
            dao.materials().map {it.fileKey}.toSet()+dao.deletions().flatMap {DeletedBundle.decode(it.payload).materials.map {material->material.fileKey}}
        }
        directory.listFiles().orEmpty().forEach {file->
            val key=file.name.removeSuffix(".part")
            val managed=runCatching {UUID.fromString(key).toString()==key}.getOrDefault(false)
            if(managed && file.isFile && file.name !in referenced) file.delete()
        }
    }}
    suspend fun import(objectId:String,uri:Uri):AgendaMaterial = withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val name=runCatching {resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        }}.getOrNull()?.takeIf {it.isNotBlank()} ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf {it.isNotBlank()} ?: "材料"
        val mime=resolver.getType(uri) ?: "application/octet-stream"
        importSource(objectId,name,mime) {resolver.openInputStream(uri) ?: throw AgendaFailure.FileUnavailable()}
    }
    internal suspend fun importSource(objectId:String,name:String,mime:String,source:()->InputStream):AgendaMaterial =
        withContext(Dispatchers.IO) {gate.run {
            if(!dao.hasObject(objectId)) throw AgendaFailure.NotFound()
            if(!directory.isDirectory && !directory.mkdirs()) throw IOException("Material directory unavailable")
            val id=UUID.randomUUID().toString()
            val temp=File(directory,"$id.part")
            val destination=File(directory,id)
            var published=false
            try {
                val digest=MessageDigest.getInstance("SHA-256")
                var size=0L
                source().use {input->FileOutputStream(temp).use {output->
                    val buffer=ByteArray(64*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count=input.read(buffer)
                        if(count<0) break
                        output.write(buffer,0,count);digest.update(buffer,0,count);size+=count
                    }
                    output.fd.sync()
                }}
                currentCoroutineContext().ensureActive()
                if(!temp.renameTo(destination)) throw IOException("Material commit failed")
                val row=MaterialRow(id,objectId,name,mime,id,size,digest.digest().joinToString("") {"%02x".format(it)},System.currentTimeMillis())
                // Once publishing begins, cancellation must not delete a file whose
                // metadata has already committed in SQLite.
                withContext(NonCancellable) {
                    database.withTransaction {
                        if(!dao.hasObject(objectId)) throw AgendaFailure.NotFound()
                        dao.insertMaterial(row);dao.changed()
                    }
                    published=true
                }
                row.model()
            } finally {
                temp.delete()
                if(!published) destination.delete()
            }
        }}
    internal suspend fun copyFor(id:String):File=withContext(Dispatchers.IO) {
        val row=dao.material(id) ?: throw AgendaFailure.NotFound()
        // Never interpret display names as paths. The only current file key is UUID.
        if(row.fileKey!=row.id || runCatching {UUID.fromString(row.fileKey)}.isFailure) throw AgendaFailure.FileUnavailable()
        File(directory,row.fileKey).also {if(!it.isFile || !it.canRead()) throw AgendaFailure.FileUnavailable()}
    }
    suspend fun document(id:String):MaterialDocument {
        val row=dao.material(id) ?: throw AgendaFailure.NotFound()
        val file=copyFor(id)
        return MaterialDocument(FileProvider.getUriForFile(context,"${context.packageName}.materials",file,row.name),row.mimeType)
    }
    suspend fun image(id:String):android.graphics.Bitmap=withContext(Dispatchers.IO) {
        val file=copyFor(id)
        // Decode a bounded preview off the UI thread. ImageDecoder also honours EXIF rotation.
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) {decoder,info,_->
            val ratio=minOf(1.0,2048.0/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()))
            decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}
