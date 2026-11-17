package com.agenda.data.local

import com.agenda.domain.*
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

enum class BackupProblem { INVALID,UNSUPPORTED,LIMIT,NO_SPACE,PENDING_UNDO,STALE,SNAPSHOT_FAILED,SWITCH_FAILED }
class BackupException(val problem:BackupProblem,cause:Throwable?=null):IOException(problem.name,cause)
data class BackupInfo(val createdAt:Long,val objects:Int,val tasks:Int,val materials:Int,val notes:Int,val schedule:Int,val weekly:Int,val fileBytes:Long,val leisure:Int=0,val backgrounds:Int=0,val materialLinks:Int=0)
data class PreparedBackup(val handle:String,val info:BackupInfo)
data class RecoverySnapshot(val id:String,val createdAt:Long,val sizeBytes:Long)
internal data class BackupContents(val records:DeletedBundle,val preferences:UserPreferences,val createdAt:Long) {
    fun info()=BackupInfo(createdAt,records.objects.size,records.tasks.size,records.materials.size,records.notes.size,records.schedule.size,records.weekly.size,files().sumOf {it.size},records.leisure.size,records.assets.size,records.taskMaterials.size)
    fun files():List<BackupFile> = records.materials.map {BackupFile("materials/${it.id}",it.id,it.sizeBytes,it.sha256)}+records.assets.map {BackupFile("assets/${it.id}",it.id,it.sizeBytes,it.sha256)}
}
internal data class BackupFile(val path:String,val id:String,val size:Long,val sha256:String)
data class BackupLimits(val manifestBytes:Long=8L*1024*1024,val fileBytes:Long=2L*1024*1024*1024,
    val archiveBytes:Long=2L*1024*1024*1024+32L*1024*1024,val recordCount:Int=100_000)

internal object BackupCodec {
    fun preferences(json:JSONObject):UserPreferences {
        val page=json.getInt("page");require(page in 0..2)
        require(json.get("board") is Boolean && json.get("fullDays") is Boolean)
        return UserPreferences(page,json.getBoolean("board"),json.getBoolean("fullDays"))
    }
    fun preferencesJson(value:UserPreferences)=JSONObject().put("page",value.page).put("board",value.board).put("fullDays",value.fullDays)
    fun encode(contents:BackupContents):ByteArray=JSONObject().put("formatVersion",3).put("createdAt",contents.createdAt)
        .put("records",JSONObject(contents.records.encode())).put("preferences",preferencesJson(contents.preferences))
        .toString().toByteArray(Charsets.UTF_8)
    fun decode(bytes:ByteArray,limits:BackupLimits):BackupContents {
        try {
            val json=JSONObject(String(bytes,Charsets.UTF_8))
            if(json.getInt("formatVersion") !in 1..3) throw BackupException(BackupProblem.UNSUPPORTED)
            val result=BackupContents(DeletedBundle.decode(json.getJSONObject("records").toString()),preferences(json.getJSONObject("preferences")),json.getLong("createdAt"))
            validate(result,limits);return result
        } catch(e:BackupException) {throw e}
        catch(e:Exception) {throw BackupException(BackupProblem.INVALID,e)}
    }
    fun validate(contents:BackupContents,limits:BackupLimits) {
        val r=contents.records
        require(contents.createdAt>=0)
        if(r.objects.size.toLong()+r.tasks.size+r.materials.size+r.notes.size+r.schedule.size+r.weekly.size+r.layouts.size+r.leisure.size+r.assets.size+r.reminders.size+r.taskMaterials.size>limits.recordCount) throw BackupException(BackupProblem.LIMIT)
        fun ids(values:List<String>):Set<String> {require(values.all {WorkspaceManager.validUuid(it)});require(values.distinct().size==values.size);return values.toSet()}
        val objects=ids(r.objects.map {it.id});val tasks=ids(r.tasks.map {it.id})
        require(objects.intersect(tasks).isEmpty())
        ids(r.materials.map {it.id});ids(r.notes.map {it.id});ids(r.schedule.map {it.id});ids(r.weekly.map {it.id})
        val materials=r.materials.map {it.id}.toSet()
        require(r.taskMaterials.distinct().size==r.taskMaterials.size)
        r.taskMaterials.forEach {require(it.taskId in tasks && it.materialId in materials)}
        r.objects.forEach {require(it.name.isNotBlank() && it.createdAt>=0)}
        r.tasks.forEach {
            require(it.name.isNotBlank() && (it.objectId==null || it.objectId in objects) && it.createdAt>=0)
            it.effort?.let(::effortFrom);it.dueDay?.let(LocalDate::ofEpochDay)
        }
        var total=0L
        r.materials.forEach {
            require(it.objectId in objects && it.fileKey==it.id && it.name.isNotBlank() && it.mimeType.isNotBlank() && it.createdAt>=0)
            require(it.sizeBytes>=0 && Regex("[0-9a-f]{64}").matches(it.sha256))
            total=Math.addExact(total,it.sizeBytes)
            if(it.sizeBytes>limits.fileBytes || total>limits.fileBytes) throw BackupException(BackupProblem.LIMIT)
        }
        r.notes.forEach {require(it.objectId in objects && it.title.isNotBlank() && it.body.isNotBlank() && it.createdAt>=0)}
        val references=mutableSetOf<List<Any>>()
        r.schedule.forEach {
            LocalDate.ofEpochDay(it.dateDay);periodFrom(it.period);require(it.minuteKey in -1..1439 && it.createdAt>=0)
            if(it.taskId==null) require(it.title.isNotBlank()) else {
                require(it.taskId in tasks)
                require(references.add(listOf(it.taskId,it.dateDay,it.period,it.minuteKey)))
            }
        }
        r.weekly.forEach {require(LocalDate.ofEpochDay(it.weekStartDay).dayOfWeek.value==1 && it.text.isNotBlank() && it.createdAt>=0)}
        ids(r.leisure.map {it.id});r.leisure.forEach {require(it.text.isNotBlank() && it.createdAt>=0)}
        ids(r.layouts.map {it.cardId});val slots=mutableSetOf<Pair<Int,Int>>()
        val visible=objects+r.tasks.filter {it.objectId==null}.map {it.id}
        r.layouts.forEach {require(it.cardId in objects || it.cardId in tasks);require(it.column in 0..1 && it.row in 0..100000);if(it.cardId in visible) require(slots.add(it.column to it.row))}
        val assets=ids(r.assets.map {it.id})
        r.assets.forEach {
            require(it.mimeType.startsWith("image/") && it.sizeBytes>=0 && Regex("[0-9a-f]{64}").matches(it.sha256) && it.createdAt>=0)
            total=Math.addExact(total,it.sizeBytes);if(it.sizeBytes>50L*1024*1024 || total>limits.fileBytes) throw BackupException(BackupProblem.LIMIT)
        }
        require(r.appearance.size<=1)
        r.appearance.forEach {require(it.id==0 && it.color in 0..0xFFFFFFFFL && (it.color ushr 24)==255L && it.text.length<=200 && it.text.lines().size<=2 && (it.assetId==null || it.assetId in assets))}
        require(assets==r.appearance.mapNotNull {it.assetId}.toSet())
        require(r.reminders.map {it.dateDay}.distinct().size==r.reminders.size)
        r.reminders.forEach {LocalDate.ofEpochDay(it.dateDay);require(it.checkedAt>=0)}
        require(contents.preferences.page in 0..2)
    }
}
