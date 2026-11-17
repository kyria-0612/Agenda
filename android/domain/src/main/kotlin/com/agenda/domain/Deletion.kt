package com.agenda.domain

import kotlinx.coroutines.flow.Flow

enum class DeleteKind(val label:String) { OBJECT("对象"),TASK("任务"),MATERIAL("材料"),NOTE("说明"),SCHEDULE("日程"),WEEKLY("周计划"),LEISURE("休闲活动") }
data class DeleteTarget(val kind:DeleteKind,val id:String)
data class DeletePreview(val target:DeleteTarget,val name:String,val revision:Long,
    val objects:Int=0,val tasks:Int=0,val materials:Int=0,val notes:Int=0,val schedule:Int=0,val weekly:Int=0,
    val undoMillis:Long=DELETE_UNDO_MILLIS,val leisure:Int=0,val materialLinks:Int=0)
data class PendingDeletion(val token:String,val name:String,val createdAt:Long,val expiresAt:Long)
const val DELETE_UNDO_MILLIS=8_000L
interface DeletionRepository {
    val pendingDeletions:Flow<List<PendingDeletion>>
    suspend fun previewDelete(target:DeleteTarget):DeletePreview
    suspend fun delete(preview:DeletePreview)
    suspend fun undoDelete(token:String)
    suspend fun expireDeletions()
}
