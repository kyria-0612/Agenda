package com.agenda.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.room.withTransaction
import com.agenda.domain.*
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID

class VisualAssetStore(private val context:Context,private val database:AgendaDatabase,
    private val gate:LocalDataGate=LocalDataGate(),private val directory:File=File(context.filesDir,"assets")) {
    private val dao=database.agenda()
    private fun clean(referenced:Set<String>) {
        directory.listFiles().orEmpty().forEach {file->
            if(file.isFile && WorkspaceManager.validUuid(file.name.removeSuffix(".part")) && file.name !in referenced) file.delete()
        }
    }
    suspend fun recoverOrphans()=withContext(Dispatchers.IO) {gate.run {clean(dao.assets().map {it.id}.toSet())}}
    suspend fun save(color:Long,text:String,useImage:Boolean,uri:Uri?)=withContext(Dispatchers.IO) {gate.run {
        if(color !in 0..0xFFFFFFFFL || color ushr 24!=255L || text.length>200 || text.lines().size>2) throw AgendaFailure.InvalidInput()
        var added:VisualAssetRow?=null;var published=false
        var temporary:File?=null;var destination:File?=null
        try {
            if(useImage && uri!=null) {
                if(!directory.isDirectory && !directory.mkdirs()) throw IOException("Background directory unavailable")
                val id=UUID.randomUUID().toString();temporary=File(directory,"$id.part");destination=File(directory,id)
                val digest=MessageDigest.getInstance("SHA-256");var count=0L
                (context.contentResolver.openInputStream(uri) ?: throw AgendaFailure.FileUnavailable()).use {input->FileOutputStream(temporary).use {output->
                    val buffer=ByteArray(64*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0) break
                        count+=n;if(count>50L*1024*1024) throw AgendaFailure.InvalidInput()
                        output.write(buffer,0,n);digest.update(buffer,0,n)
                    };output.fd.sync()
                }}
                var mime="image/*"
                val preview=decode(temporary,512) {mime=it};preview.recycle()
                currentCoroutineContext().ensureActive()
                if(!temporary.renameTo(destination)) throw IOException("Background commit failed")
                added=VisualAssetRow(id,mime,count,digest.digest().joinToString("") {"%02x".format(it)},System.currentTimeMillis())
            }
            withContext(NonCancellable) {database.withTransaction {
                val asset=if(useImage) added?.id ?: dao.appearance()?.assetId else null
                if(useImage && asset==null) throw AgendaFailure.InvalidInput()
                added?.let {dao.insertAsset(it)}
                dao.saveAppearance(AppearanceRow(color=color,text=text.trim(),assetId=asset))
                dao.removeOtherAssets(asset ?: "");dao.changed();published=true
            }}
            clean(dao.assets().map {it.id}.toSet())
        } finally {temporary?.delete();if(!published) destination?.delete()}
    }}
    private fun decode(file:File,maxEdge:Int,onMime:(String)->Unit={}):Bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) {decoder,info,_->
        onMime(info.mimeType);val ratio=minOf(1.0,maxEdge.toDouble()/maxOf(info.size.width,info.size.height))
        decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
    }
    suspend fun image(id:String):Bitmap=withContext(Dispatchers.IO) {
        if(!WorkspaceManager.validUuid(id) || dao.assets().none {it.id==id}) throw AgendaFailure.FileUnavailable()
        decode(File(directory,id),2048)
    }
}
