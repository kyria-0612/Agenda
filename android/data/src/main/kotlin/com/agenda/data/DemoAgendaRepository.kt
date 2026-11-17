package com.agenda.data

import com.agenda.domain.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Motion prototype only. Never writes to disk or imports user data. */
class DemoAgendaRepository(today: LocalDate = LocalDate.now()) : AgendaRepository {
    private val state = MutableStateFlow(AgendaSnapshot(
        objects = listOf(
            AgendaObject("course", "光电课程", listOf(AgendaMaterial("lecture","课程讲义.pdf"),AgendaMaterial("experiment","实验数据.png")), listOf(AgendaNote("report-note","报告要求","说明测量过程与结果"))),
            AgendaObject("agenda", "日程软件", listOf(AgendaMaterial("architecture","页面架构.png")), listOf(AgendaNote("design-note","交互原则","让事情自然地展开")))),
        tasks = listOf(
            Task("report", "完成实验报告", "course", Effort.FOCUS, today.plusDays(1)),
            Task("review", "复习第三章", "course", Effort.LIGHT, today.plusDays(3)),
            Task("ui", "探索事项 UI", "agenda", Effort.FOCUS, today.plusDays(5)),
            Task("rules", "整理交互规则", "agenda", Effort.LIGHT),
            Task("records", "整理实验记录", effort = Effort.QUICK, due = today),
            Task("paper", "读一篇论文，记下值得继续研究的问题", effort = Effort.FOCUS),
            Task("mail", "回复课程邮件", effort = Effort.QUICK, due = today.plusDays(2))),
        schedule = listOf(
            ScheduleEntry("s-report", today, Period.MORNING, LocalTime.of(9, 0), "report"),
            ScheduleEntry("s-record", today, Period.AFTERNOON, taskId = "records"),
            ScheduleEntry("s-walk", today, Period.EVENING, title = "散步"),
            ScheduleEntry("s-review", today.plusDays(1), Period.AFTERNOON, taskId = "review"))
    ))
    override val snapshot = state.asStateFlow()
    override suspend fun updateTask(id:String,draft:TaskDraft) {
        if(draft.name.isBlank()) throw AgendaFailure.InvalidInput()
        if(state.value.tasks.none {it.id==id} || (draft.objectId!=null && state.value.objects.none {it.id==draft.objectId})) throw AgendaFailure.NotFound()
        state.update {s->s.copy(tasks=s.tasks.map {if(it.id==id) it.copy(name=draft.name.trim(),objectId=draft.objectId,effort=draft.effort,due=draft.due,wants=draft.wants,should=draft.should) else it})}
    }
    override suspend fun renameObject(id:String,name:String) {
        if(name.isBlank()) throw AgendaFailure.InvalidInput()
        if(state.value.objects.none {it.id==id}) throw AgendaFailure.NotFound()
        state.update {s->s.copy(objects=s.objects.map {if(it.id==id) it.copy(name=name.trim()) else it})}
    }
    override suspend fun updateNote(id:String,title:String,body:String) {
        if(title.isBlank() || body.isBlank()) throw AgendaFailure.InvalidInput()
        if(state.value.objects.none {obj->obj.notes.any {it.id==id}}) throw AgendaFailure.NotFound()
        state.update {s->s.copy(objects=s.objects.map {obj->obj.copy(notes=obj.notes.map {if(it.id==id) it.copy(title=title.trim(),body=body.trim()) else it})})}
    }
    override suspend fun updateWeeklyPlan(id:String,text:String) {
        if(text.isBlank()) throw AgendaFailure.InvalidInput()
        if(state.value.weeklyPlans.none {it.id==id}) throw AgendaFailure.NotFound()
        state.update {s->s.copy(weeklyPlans=s.weeklyPlans.map {if(it.id==id) it.copy(text=text.trim()) else it})}
    }
    override suspend fun updateSchedule(id:String,draft:ScheduleDraft) {
        validateSchedule(draft)
        val snapshot=state.value;val old=snapshot.schedule.find {it.id==id} ?: throw AgendaFailure.NotFound()
        if(draft.taskId!=null && snapshot.tasks.none {it.id==draft.taskId}) throw AgendaFailure.NotFound()
        if(draft.taskId!=null && snapshot.schedule.any {it.id!=id && it.taskId==draft.taskId && it.date==draft.date && it.period==draft.period && it.time==draft.time}) throw AgendaFailure.DuplicateSchedule()
        val updated=old.copy(date=draft.date,period=draft.period,time=draft.time,taskId=draft.taskId,
            title=if(draft.taskId==null) draft.title.trim() else "",displayName=if(draft.taskId==null) null else draft.displayName?.trim()?.takeIf {it.isNotEmpty()},
            completed=if(draft.taskId==null) old.isCompleted(snapshot.tasks) else false)
        state.update {s->s.copy(schedule=s.schedule.map {if(it.id==id) updated else it})}
    }
    override suspend fun updateMaterial(id:String,title:String,description:String) {
        if(state.value.objects.none {obj->obj.materials.any {it.id==id}}) throw AgendaFailure.NotFound()
        state.update {s->s.copy(objects=s.objects.map {obj->obj.copy(materials=obj.materials.map {
            if(it.id==id) it.copy(title=title.trim(),description=description.trim()) else it
        })})}
    }
    override suspend fun addWeeklyPlan(week:LocalDate,text:String):String {
        require(text.isNotBlank())
        val id=UUID.randomUUID().toString()
        state.update {it.copy(weeklyPlans=it.weeklyPlans+WeeklyPlanItem(id,weekStart(week),text.trim()))}
        return id
    }
    override suspend fun setWeeklyPlanCompleted(id:String,completed:Boolean) {
        state.update {s->s.copy(weeklyPlans=s.weeklyPlans.map {if(it.id==id) it.copy(completed=completed) else it})}
    }
    override suspend fun setCompleted(taskId: String, completed: Boolean) {
        state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) it.copy(completed = completed) else it }) }
    }
    override suspend fun setScheduleCompleted(entryId: String, completed: Boolean) {
        state.update { s ->
            val entry = s.schedule.find { it.id == entryId } ?: return@update s
            if (entry.taskId != null) s.copy(tasks = s.tasks.map { if (it.id == entry.taskId) it.copy(completed = completed) else it })
            else s.copy(schedule = s.schedule.map { if (it.id == entryId) it.copy(completed = completed) else it })
        }
    }
    override suspend fun createTask(name: String, effort: Effort?): String = createTask(TaskDraft(name, effort))
    override suspend fun createTask(draft: TaskDraft): String {
        require(draft.name.isNotBlank())
        require(draft.objectId == null || state.value.objects.any {it.id == draft.objectId})
        val id = UUID.randomUUID().toString()
        state.update { it.copy(tasks = it.tasks + Task(id, draft.name.trim(), draft.objectId,
            draft.effort, draft.due, wants=draft.wants, should=draft.should)) }
        return id
    }
    override suspend fun createObject(name: String): String {
        require(name.isNotBlank())
        val id = UUID.randomUUID().toString()
        state.update {it.copy(objects=it.objects + AgendaObject(id,name.trim(),emptyList(),emptyList()))}
        return id
    }
    fun addMaterial(objectId:String,name:String) {
        require(name.isNotBlank())
        require(state.value.objects.any {it.id==objectId})
        state.update {s->s.copy(objects=s.objects.map {if(it.id==objectId) it.copy(materials=it.materials+AgendaMaterial(UUID.randomUUID().toString(),name.trim())) else it})}
    }
    override suspend fun addNote(objectId:String,title:String,body:String):String {
        require(title.isNotBlank() && body.isNotBlank())
        require(state.value.objects.any {it.id==objectId})
        val note=AgendaNote(UUID.randomUUID().toString(),title.trim(),body.trim())
        state.update {s->s.copy(objects=s.objects.map {if(it.id==objectId) it.copy(notes=it.notes+note) else it})}
        return note.id
    }
    override suspend fun createSchedule(draft:ScheduleDraft):String {
        validateSchedule(draft)
        if(draft.taskId!=null && state.value.tasks.none {it.id==draft.taskId}) throw AgendaFailure.NotFound()
        if(draft.taskId!=null && state.value.schedule.any {it.taskId==draft.taskId && it.date==draft.date && it.period==draft.period && scheduleMinuteKey(it.time)==scheduleMinuteKey(draft.time)}) throw AgendaFailure.DuplicateSchedule()
        val id=UUID.randomUUID().toString()
        state.update {it.copy(schedule=it.schedule+ScheduleEntry(id,draft.date,draft.period,draft.time,draft.taskId,draft.title,displayName=draft.displayName))}
        return id
    }

}
