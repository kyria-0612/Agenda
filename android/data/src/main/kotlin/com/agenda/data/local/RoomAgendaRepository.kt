package com.agenda.data.local

import androidx.room.withTransaction
import com.agenda.domain.*
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

internal fun Effort.code()=when(this) {Effort.QUICK->"quick";Effort.LIGHT->"light";Effort.FOCUS->"focus"}
internal fun effortFrom(code:String)=when(code) {"quick"->Effort.QUICK;"light"->Effort.LIGHT;"focus"->Effort.FOCUS;else->error("Unknown effort code")}
internal fun Period.code()=when(this) {Period.MORNING->"morning";Period.NOON->"noon";Period.AFTERNOON->"afternoon";Period.EVENING->"evening"}
internal fun periodFrom(code:String)=when(code) {"morning"->Period.MORNING;"noon"->Period.NOON;"afternoon"->Period.AFTERNOON;"evening"->Period.EVENING;else->error("Unknown period code")}
internal fun MaterialRow.model()=AgendaMaterial(id,name,mimeType,sizeBytes,title,description)

class RoomAgendaRepository(private val database:AgendaDatabase,private val gate:LocalDataGate=LocalDataGate()):AgendaRepository {
    private val dao=database.agenda()
    // Every write increments revision inside the same transaction. A reader then
    // loads all tables in one read transaction rather than combining torn flows.
    override val snapshot=dao.revisions().map {database.withTransaction {
        val notes=dao.notes().groupBy {it.objectId}
        val materials=dao.materials().groupBy {it.objectId}
        val links=dao.taskMaterials().groupBy {it.taskId}
        AgendaSnapshot(
            dao.objects().map {o->AgendaObject(o.id,o.name,materials[o.id].orEmpty().map {it.model()},
                notes[o.id].orEmpty().map {AgendaNote(it.id,it.title,it.body)})},
            dao.tasks().map {Task(it.id,it.name,it.objectId,it.effort?.let(::effortFrom),it.dueDay?.let(LocalDate::ofEpochDay),it.completed,it.wants,it.should,it.description,links[it.id].orEmpty().map {link->link.materialId})},
            dao.schedule().map {ScheduleEntry(it.id,LocalDate.ofEpochDay(it.dateDay),periodFrom(it.period),
                if(it.minuteKey<0) null else LocalTime.of(it.minuteKey/60,it.minuteKey%60),it.taskId,it.title,it.completed,it.displayName)},
            dao.weeklyPlans().map {WeeklyPlanItem(it.id,LocalDate.ofEpochDay(it.weekStartDay),it.text,it.completed)},
            dao.layouts().map {BoardPlacement(it.cardId,it.column,it.row)},
            dao.leisure().map {LeisureActivity(it.id,it.text)},
            dao.appearance()?.let {ScheduleAppearance(it.color,it.text,it.assetId)} ?: ScheduleAppearance(),
            dao.assets().map {VisualAsset(it.id,it.mimeType,it.sizeBytes)},
            dao.reminderChecks().map {ReminderCheck(LocalDate.ofEpochDay(it.dateDay),it.checkedAt)}
        )
    }}
    private suspend fun <T> write(action:suspend ()->T):T=gate.run {database.withTransaction {
        val result=action();synchronizeBoardLayouts(dao);dao.changed();result
    }}
    override suspend fun updateTask(id:String,draft:TaskDraft) {write {
        if(draft.name.isBlank()) throw AgendaFailure.InvalidInput()
        val old=dao.task(id) ?: throw AgendaFailure.NotFound()
        val parent=draft.objectId
        if(parent!=null && !dao.hasObject(parent)) throw AgendaFailure.NotFound()
        dao.updateTask(old.copy(name=draft.name.trim(),objectId=parent,effort=draft.effort?.code(),dueDay=draft.due?.toEpochDay(),wants=draft.wants,should=draft.should))
    }}
    override suspend fun renameObject(id:String,name:String) {write {
        if(name.isBlank()) throw AgendaFailure.InvalidInput()
        if(dao.renameObject(id,name.trim())!=1) throw AgendaFailure.NotFound()
    }}
    override suspend fun updateNote(id:String,title:String,body:String) {write {
        if(title.isBlank() || body.isBlank()) throw AgendaFailure.InvalidInput()
        if(dao.updateNote(id,title.trim(),body.trim())!=1) throw AgendaFailure.NotFound()
    }}
    override suspend fun updateWeeklyPlan(id:String,text:String) {write {
        if(text.isBlank()) throw AgendaFailure.InvalidInput()
        if(dao.updateWeeklyPlan(id,text.trim())!=1) throw AgendaFailure.NotFound()
    }}
    override suspend fun updateSchedule(id:String,draft:ScheduleDraft) {write {
        validateSchedule(draft)
        val old=dao.entry(id) ?: throw AgendaFailure.NotFound()
        val target=draft.taskId
        if(target!=null) {
            if(dao.task(target)==null) throw AgendaFailure.NotFound()
            if(dao.hasReference(target,draft.date.toEpochDay(),draft.period.code(),scheduleMinuteKey(draft.time),id)) throw AgendaFailure.DuplicateSchedule()
        }
        val completion=if(target==null) old.taskId?.let {dao.task(it)?.completed ?: throw AgendaFailure.NotFound()} ?: old.completed else false
        dao.updateSchedule(old.copy(dateDay=draft.date.toEpochDay(),period=draft.period.code(),minuteKey=scheduleMinuteKey(draft.time),
            taskId=target,title=if(target==null) draft.title.trim() else "",displayName=if(target==null) null else draft.displayName?.trim()?.takeIf {it.isNotEmpty()},completed=completion))
    }}
    override suspend fun addWeeklyPlan(week:LocalDate,text:String):String=write {
        if(text.isBlank()) throw AgendaFailure.InvalidInput()
        val id=UUID.randomUUID().toString()
        dao.insertWeeklyPlan(WeeklyPlanRow(id,weekStart(week).toEpochDay(),text.trim(),false,System.currentTimeMillis()));id
    }
    override suspend fun setWeeklyPlanCompleted(id:String,completed:Boolean) {write {
        if(dao.completeWeeklyPlan(id,completed)!=1) throw AgendaFailure.NotFound()
    }}
    override suspend fun updateMaterial(id:String,title:String,description:String) {write {
        if(dao.updateMaterial(id,title.trim(),description.trim())!=1) throw AgendaFailure.NotFound()
    }}
    override suspend fun createObject(name:String):String=write {
        if(name.isBlank()) throw AgendaFailure.InvalidInput()
        val id=UUID.randomUUID().toString()
        dao.insertObject(ObjectRow(id,name.trim(),System.currentTimeMillis()));id
    }
    override suspend fun createTask(draft:TaskDraft):String=write {
        if(draft.name.isBlank()) throw AgendaFailure.InvalidInput()
        val objectId=draft.objectId
        if(objectId!=null && !dao.hasObject(objectId)) throw AgendaFailure.NotFound()
        val id=UUID.randomUUID().toString()
        dao.insertTask(TaskRow(id,draft.name.trim(),draft.objectId,draft.effort?.code(),draft.due?.toEpochDay(),false,
            draft.wants,draft.should,System.currentTimeMillis()));id
    }
    override suspend fun addNote(objectId:String,title:String,body:String):String=write {
        if(title.isBlank() || body.isBlank()) throw AgendaFailure.InvalidInput()
        if(!dao.hasObject(objectId)) throw AgendaFailure.NotFound()
        val id=UUID.randomUUID().toString()
        dao.insertNote(NoteRow(id,objectId,title.trim(),body.trim(),System.currentTimeMillis()));id
    }
    override suspend fun setCompleted(taskId:String,completed:Boolean) {write {
        if(dao.completeTask(taskId,completed)!=1) throw AgendaFailure.NotFound()
    }}
    override suspend fun setScheduleCompleted(entryId:String,completed:Boolean) {write {
        val entry=dao.entry(entryId) ?: throw AgendaFailure.NotFound()
        if(entry.taskId!=null) {
            if(dao.completeTask(entry.taskId,completed)!=1) throw AgendaFailure.NotFound()
        } else if(dao.completeTemporary(entryId,completed)!=1) throw AgendaFailure.NotFound()
    }}
    override suspend fun createSchedule(draft:ScheduleDraft):String=write {
        validateSchedule(draft)
        val taskId=draft.taskId
        if(taskId!=null) {
            if(dao.task(taskId)==null) throw AgendaFailure.NotFound()
            if(dao.hasReference(taskId,draft.date.toEpochDay(),draft.period.code(),scheduleMinuteKey(draft.time))) throw AgendaFailure.DuplicateSchedule()
        }
        val id=UUID.randomUUID().toString()
        dao.insertSchedule(ScheduleRow(id,draft.date.toEpochDay(),draft.period.code(),scheduleMinuteKey(draft.time),draft.taskId,
            if(draft.taskId==null) draft.title.trim() else "",if(draft.taskId==null) null else draft.displayName?.trim()?.takeIf {it.isNotEmpty()},false,System.currentTimeMillis()));id
    }
}
