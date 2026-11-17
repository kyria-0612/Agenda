package com.agenda.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.agenda.domain.*
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomDeletionRepository(private val database:AgendaDatabase,private val clock:()->Long=System::currentTimeMillis,
    private val undoDuration:()->Long={DELETE_UNDO_MILLIS},private val gate:LocalDataGate=LocalDataGate()):DeletionRepository {
    private val dao=database.agenda()
    override val pendingDeletions=dao.observeDeletions().map {rows->rows.map {PendingDeletion(it.token,it.name,it.createdAt,it.expiresAt)}}
    private suspend fun bundle(target:DeleteTarget):DeletedBundle {
        val objects=dao.objects();val tasks=dao.tasks();val materials=dao.materials();val notes=dao.notes();val schedule=dao.schedule();val weekly=dao.weeklyPlans()
        when(target.kind) {
            DeleteKind.OBJECT-> {
                val obj=objects.find {it.id==target.id} ?: throw AgendaFailure.NotFound()
                val owned=tasks.filter {it.objectId==obj.id};val ids=owned.map {it.id}.toSet()
                val removedMaterials=materials.filter {it.objectId==obj.id}
                return DeletedBundle(listOf(obj),owned,removedMaterials,notes.filter {it.objectId==obj.id},schedule.filter {it.taskId in ids},layouts=dao.layouts().filter {it.cardId==obj.id || it.cardId in ids},
                    taskMaterials=dao.taskMaterials().filter {it.taskId in ids || it.materialId in removedMaterials.map {m->m.id}})
            }
            DeleteKind.TASK-> {
                val task=tasks.find {it.id==target.id} ?: throw AgendaFailure.NotFound()
                return DeletedBundle(tasks=listOf(task),schedule=schedule.filter {it.taskId==task.id},layouts=dao.layouts().filter {it.cardId==task.id},taskMaterials=dao.taskMaterials().filter {it.taskId==task.id})
            }
            DeleteKind.MATERIAL->return DeletedBundle(materials=listOf(materials.find {it.id==target.id} ?: throw AgendaFailure.NotFound()),taskMaterials=dao.taskMaterials().filter {it.materialId==target.id})
            DeleteKind.NOTE->return DeletedBundle(notes=listOf(notes.find {it.id==target.id} ?: throw AgendaFailure.NotFound()))
            DeleteKind.SCHEDULE->return DeletedBundle(schedule=listOf(schedule.find {it.id==target.id} ?: throw AgendaFailure.NotFound()))
            DeleteKind.WEEKLY->return DeletedBundle(weekly=listOf(weekly.find {it.id==target.id} ?: throw AgendaFailure.NotFound()))
            DeleteKind.LEISURE->return DeletedBundle(leisure=listOf(dao.leisure().find {it.id==target.id} ?: throw AgendaFailure.NotFound()))
        }
    }
    private suspend fun makePreview(bundle:DeletedBundle,target:DeleteTarget):DeletePreview {
        val result=bundle.preview(target,dao.revision()).copy(undoMillis=undoDuration().coerceAtLeast(DELETE_UNDO_MILLIS))
        if(target.kind!=DeleteKind.SCHEDULE) return result
        val entry=bundle.schedule.single()
        return result.copy(name=entry.displayName?.takeIf {it.isNotBlank()} ?: entry.taskId?.let {dao.task(it)?.name} ?: entry.title)
    }
    override suspend fun previewDelete(target:DeleteTarget):DeletePreview=database.withTransaction {makePreview(bundle(target),target)}
    override suspend fun delete(preview:DeletePreview) {gate.run {database.withTransaction {
        if(dao.revision()!=preview.revision) throw AgendaFailure.DeleteChanged()
        val bundle=bundle(preview.target)
        if(makePreview(bundle,preview.target)!=preview) throw AgendaFailure.DeleteChanged()
        val now=clock()
        dao.insertDeletion(DeleteUndoRow(UUID.randomUUID().toString(),preview.name,bundle.encode(),now,now+preview.undoMillis))
        if(bundle.tasks.isNotEmpty()) dao.deleteTaskMaterials(bundle.tasks.map {it.id})
        if(bundle.materials.isNotEmpty()) dao.deleteMaterialLinks(bundle.materials.map {it.id})
        if(bundle.schedule.isNotEmpty()) dao.deleteSchedule(bundle.schedule.map {it.id})
        if(bundle.tasks.isNotEmpty()) dao.deleteTasks(bundle.tasks.map {it.id})
        if(bundle.materials.isNotEmpty()) dao.deleteMaterials(bundle.materials.map {it.id})
        if(bundle.notes.isNotEmpty()) dao.deleteNotes(bundle.notes.map {it.id})
        if(bundle.weekly.isNotEmpty()) dao.deleteWeeklyPlans(bundle.weekly.map {it.id})
        if(bundle.objects.isNotEmpty()) dao.deleteObjects(bundle.objects.map {it.id})
        if(bundle.layouts.isNotEmpty()) dao.deleteLayouts(bundle.layouts.map {it.cardId})
        if(bundle.leisure.isNotEmpty()) dao.deleteLeisure(bundle.leisure.map {it.id})
        dao.changed()
    }}}
    override suspend fun undoDelete(token:String) {
        try {gate.run {database.withTransaction {
            val row=dao.deletion(token) ?: throw AgendaFailure.UndoExpired()
            if(clock() !in row.createdAt until row.expiresAt) throw AgendaFailure.UndoExpired()
            val bundle=DeletedBundle.decode(row.payload)
            bundle.objects.forEach {dao.insertObject(it)}
            bundle.tasks.forEach {dao.insertTask(it)}
            bundle.notes.forEach {dao.insertNote(it)}
            bundle.materials.forEach {dao.insertMaterial(it)}
            bundle.taskMaterials.forEach {dao.insertTaskMaterial(it)}
            bundle.schedule.forEach {dao.insertSchedule(it)}
            bundle.weekly.forEach {dao.insertWeeklyPlan(it)}
            bundle.layouts.forEach {dao.saveLayout(it)}
            bundle.leisure.forEach {dao.insertLeisure(it)}
            if(bundle.layouts.isNotEmpty()) synchronizeBoardLayouts(dao)
            dao.removeDeletion(token);dao.changed()
        }}} catch(e:SQLiteConstraintException) {throw AgendaFailure.UndoConflict()}
    }
    override suspend fun expireDeletions() {gate.run {database.withTransaction {dao.expireDeletions(clock())}}}
}
