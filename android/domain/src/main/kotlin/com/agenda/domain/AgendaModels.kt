package com.agenda.domain

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

enum class Effort(val label: String) { QUICK("随手做"), LIGHT("轻量做"), FOCUS("专注做") }
enum class Period(val label: String) { MORNING("上午"), NOON("中午"), AFTERNOON("下午"), EVENING("晚上") }
data class AgendaNote(val id:String,val title:String,val body:String)
data class AgendaMaterial(val id:String,val name:String,val mimeType:String="application/octet-stream",val sizeBytes:Long=0,
    val title:String="",val description:String="") {
    val displayTitle:String get()=title.ifBlank {name}
    val isImage:Boolean get()=mimeType.startsWith("image/",ignoreCase=true) || name.substringAfterLast('.',"").lowercase() in
        setOf("jpg","jpeg","png","webp","gif","heic","heif","bmp","avif")
}
data class AgendaObject(val id: String, val name: String, val materials: List<AgendaMaterial>, val notes: List<AgendaNote>)
data class Task(val id: String, val name: String, val objectId: String? = null,
    val effort: Effort? = null, val due: LocalDate? = null, val completed: Boolean = false,
    val wants: Boolean = false, val should: Boolean = false,val description:String="",val materialIds:List<String> = emptyList())
data class TaskDraft(val name: String, val effort: Effort? = null, val objectId: String? = null,
    val due: LocalDate? = null, val wants: Boolean = false, val should: Boolean = false)
data class ScheduleEntry(val id: String, val date: LocalDate, val period: Period,
    val time: LocalTime? = null, val taskId: String? = null, val title: String = "",
    val completed: Boolean = false, val displayName: String? = null) {
    fun name(tasks: List<Task>): String = displayName?.takeIf { it.isNotBlank() }
        ?: taskId?.let { id -> tasks.find { it.id == id }?.name } ?: title
    fun isCompleted(tasks: List<Task>): Boolean = taskId?.let { id -> tasks.find { it.id == id }?.completed } ?: completed
}
data class WeeklyPlanItem(val id:String,val weekStart:LocalDate,val text:String,val completed:Boolean=false)
fun weekStart(date:LocalDate):LocalDate=date.minusDays((date.dayOfWeek.value-1).toLong())
data class AgendaSnapshot(val objects: List<AgendaObject>, val tasks: List<Task>, val schedule: List<ScheduleEntry>,
    val weeklyPlans:List<WeeklyPlanItem> = emptyList(),val placements:List<BoardPlacement> = emptyList(),
    val leisure:List<LeisureActivity> = emptyList(),val appearance:ScheduleAppearance=ScheduleAppearance(),
    val assets:List<VisualAsset> = emptyList(),val reminderChecks:List<ReminderCheck> = emptyList())
val EmptyAgenda=AgendaSnapshot(emptyList(),emptyList(),emptyList())
data class ScheduleDraft(val date:LocalDate,val period:Period,val time:LocalTime?=null,
    val taskId:String?=null,val title:String="",val displayName:String?=null)
data class UserPreferences(val page:Int=0,val board:Boolean=false,val fullDays:Boolean=false)
interface PreferencesRepository {
    val preferences:Flow<UserPreferences>
    suspend fun save(preferences:UserPreferences)
}
sealed class AgendaFailure:Exception() {
    class NotFound:AgendaFailure()
    class InvalidInput:AgendaFailure()
    class DuplicateSchedule:AgendaFailure()
    class FileUnavailable:AgendaFailure()
    class DeleteChanged:AgendaFailure()
    class UndoExpired:AgendaFailure()
    class UndoConflict:AgendaFailure()
    class WorkspaceChanged:AgendaFailure()
}
/** The -1 sentinel makes absent local time participate in the unique reference key. */
fun scheduleMinuteKey(time:LocalTime?):Int=time?.let {it.hour*60+it.minute} ?: -1
fun validateSchedule(draft:ScheduleDraft) {
    if(draft.taskId==null && draft.title.isBlank()) throw AgendaFailure.InvalidInput()
    if(draft.time?.second?.let {it!=0}==true || draft.time?.nano?.let {it!=0}==true) throw AgendaFailure.InvalidInput()
}
interface AgendaRepository {
    val snapshot: Flow<AgendaSnapshot>
    suspend fun setCompleted(taskId: String, completed: Boolean)
    suspend fun setScheduleCompleted(entryId: String, completed: Boolean)
    suspend fun createTask(name: String, effort: Effort?): String = createTask(TaskDraft(name,effort))
    suspend fun createTask(draft: TaskDraft): String
    suspend fun createObject(name: String): String
    suspend fun addNote(objectId:String,title:String,body:String):String
    suspend fun createSchedule(draft:ScheduleDraft):String
    suspend fun addWeeklyPlan(week:LocalDate,text:String):String
    suspend fun setWeeklyPlanCompleted(id:String,completed:Boolean)
    suspend fun updateMaterial(id:String,title:String,description:String)
    suspend fun updateTask(id:String,draft:TaskDraft)
    suspend fun renameObject(id:String,name:String)
    suspend fun updateNote(id:String,title:String,body:String)
    suspend fun updateSchedule(id:String,draft:ScheduleDraft)
    suspend fun updateWeeklyPlan(id:String,text:String)
}

data class GraphTransform(val x: Float, val y: Float, val scale: Float)

/** Shared board morph: neighbour area is conserved at rest, with bounded spring overshoot. */
fun boardCardHeight(baseWidth:Float,width:Float,collapsed:Float,contentHeight:Float,
    progress:Float,neighbourProgress:Float):Float {
    require(baseWidth>0 && width>0 && collapsed>0)
    val open=progress.coerceIn(-.08f,1.12f)
    val neighbour=neighbourProgress.coerceIn(0f,1f)
    val expanded=maxOf(contentHeight,collapsed*1.55f)
    val own=collapsed+(expanded-collapsed)*open
    val squeezed=collapsed*(baseWidth/width).coerceIn(1f,2.6f)
    return (own*(1f-neighbour)+squeezed*neighbour).coerceAtLeast(collapsed*.85f)
}

/** Pointer-up centroids are unspecified (NaN). Never publish them into render state. */
fun applyGraphGesture(old: GraphTransform, cx: Float, cy: Float, dx: Float, dy: Float, factor: Float): GraphTransform {
    if (!listOf(cx, cy, dx, dy, factor).all { it.isFinite() } || factor <= 0f) return old
    val scale = (old.scale * factor).coerceIn(.35f, 1.7f)
    val ratio = scale / old.scale
    val x = cx - (cx - old.x) * ratio + dx
    val y = cy - (cy - old.y) * ratio + dy
    return if (x.isFinite() && y.isFinite()) GraphTransform(x, y, scale) else old
}

/** Confirmed 2026-10-29: today through today + 6, no overdue. See product §3.4. */
fun urgentTasks(tasks: List<Task>, today: LocalDate, limit: Int = 3): List<Task> {
    require(limit >= 0)
    return tasks.filter { !it.completed && it.due != null && !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(6)) }
        .sortedWith(compareBy<Task> { it.due }.thenBy { it.id }).take(limit)
}

/** Geometry independent of Android; resist overscroll progressively rather than applying a constant gain. */
fun dampedPosition(value: Float, min: Float, max: Float, resistance: Float = 120f): Float {
    require(min <= max && resistance > 0)
    if (value in min..max) return value
    val edge = if (value < min) min else max
    val distance = kotlin.math.abs(value - edge)
    return edge + (if (value < min) -1 else 1) * resistance * distance / (resistance + distance)
}
