package com.agenda.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.*
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ScheduleComposer(kind:String,data:AgendaSnapshot,repository:AgendaViewModel,onSaved:()->Unit,
    onDirty:(Boolean)->Unit,onBusy:(Boolean)->Unit,onDelete:((String)->Unit)?=null) {
    val parts=remember(kind) {kind.split(':')}
    val original=remember(kind) {data.schedule.find {parts[0]=="edit-schedule" && it.id==parts.getOrNull(1)}}
    if(parts[0]=="edit-schedule" && original==null) {
        SideEffect {onDirty(false);onBusy(false)}
        AgendaText(tr("这项日程已不存在，请关闭后重新选择。"),color=Ink.Muted)
        return
    }
    val initialDate=remember(kind) {original?.date ?: if(parts[0]=="schedule") LocalDate.parse(parts[1]) else LocalDate.now()}
    val initialPeriod=remember(kind) {original?.period ?: if(parts[0]=="schedule") Period.valueOf(parts[2]) else Period.MORNING}
    val initialTask=remember(kind) {original?.taskId ?: parts.getOrNull(1)?.takeIf {parts[0]=="schedule-for"}}
    val availableTasks=data.tasks.filter {!it.completed || it.id==original?.taskId}
    val initialReference=remember(kind) {if(original!=null) original.taskId!=null else initialTask!=null || availableTasks.isNotEmpty()}
    var reference by rememberSaveable(kind) {mutableStateOf(initialReference)}
    var date by rememberSaveable(kind) {mutableStateOf(initialDate)}
    var period by rememberSaveable(kind) {mutableStateOf(initialPeriod)}
    var selectedTask by rememberSaveable(kind) {mutableStateOf(initialTask)}
    var title by rememberSaveable(kind) {mutableStateOf(original?.title.orEmpty())}
    var alias by rememberSaveable(kind) {mutableStateOf(original?.displayName.orEmpty())}
    var time by rememberSaveable(kind) {mutableStateOf(original?.time?.toString().orEmpty())}
    var query by rememberSaveable(kind) {mutableStateOf("")}
    var calendar by rememberSaveable(kind) {mutableStateOf(false)}
    var saving by remember(kind) {mutableStateOf(false)}
    var error by rememberSaveable(kind) {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val dirty=reference!=initialReference || date!=initialDate || period!=initialPeriod || selectedTask!=initialTask ||
        title!=original?.title.orEmpty() || alias!=original?.displayName.orEmpty() || time!=original?.time?.toString().orEmpty()
    SideEffect {onDirty(dirty);onBusy(saving)}
    Column {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AgendaText(tr("{0} 月 {1} 日",date.monthValue,date.dayOfMonth),title=true,size=20,color=Ink.Primary)
                AgendaText(tr("{0} 年",date.year),size=12,color=Ink.Muted)
            }
            TextButton({calendar=!calendar},enabled=!saving) {AgendaText(if(calendar) tr("收起日期") else tr("更改日期"),size=13,color=Ink.Primary)}
        }
        if(calendar) DeadlineCalendar(date.toString(),{value->
            runCatching {LocalDate.parse(value)}.getOrNull()?.let {date=it;calendar=false;error=null}
        },label=tr("日程日期"),allowClear=false)
        Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            OptionChip(tr("引用任务"),reference,{if(!saving) {reference=true;error=null}},Modifier.weight(1f))
            OptionChip(tr("临时安排"),!reference,{if(!saving) {reference=false;error=null}},Modifier.weight(1f))
        }
        if(reference) {
            if(availableTasks.isEmpty()) AgendaText(tr("没有可引用的未完成任务，可以创建临时安排"),size=13,color=Ink.Muted,modifier=Modifier.padding(vertical=16.dp))
            else {
                if(data.tasks.size>6) AgendaInput(query,{query=it},tr("查找任务"),tr("查找任务"),size=14)
                Column(Modifier.fillMaxWidth().heightIn(max=200.dp).verticalScroll(rememberScrollState()).padding(vertical=8.dp)) {
                    availableTasks.filter {it.name.contains(query,true)}.sortedBy {it.completed}.forEach {task->
                        val chosen=task.id==selectedTask
                        Row(Modifier.fillMaxWidth().padding(vertical=3.dp).clip(RoundedCornerShape(12.dp))
                            .background(if(chosen) Ink.ObjectFill else Ink.Surface.copy(alpha=.45f))
                            .clickable(enabled=!saving) {selectedTask=task.id;error=null}
                            .semantics {selected=chosen;contentDescription=tr("引用任务：{0}",task.name)}
                            .padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            AgendaIcon(if(chosen) Mark.CHECK else Mark.GRAPH,Ink.Primary,Modifier.size(18.dp))
                            Column(Modifier.weight(1f).padding(start=10.dp)) {
                                AgendaText(task.name,size=14,color=Ink.Text)
                                if(task.completed) AgendaText(tr("已完成"),size=11,color=Ink.Muted)
                            }
                        }
                    }
                }
            }
            AgendaText(tr("显示名称（可选）"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=8.dp))
            AgendaInput(alias,{alias=it;error=null},tr("日程显示名称"),tr("默认跟随任务名称"),size=14)
        } else AgendaInput(title,{title=it;error=null},tr("临时安排内容"),tr("记下一项安排…"),size=18)
        AgendaText(tr("时段"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=16.dp,bottom=4.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Period.entries.forEach {item->OptionChip(tr(item.label),period==item,{if(!saving) {period=item;error=null}},Modifier.weight(1f))}
        }
        AgendaOutlinedTextField(time,{time=it.trim();error=null},label={AgendaText(tr("具体时间（可选）"),size=12,color=Ink.Muted)},
            placeholder={AgendaText(tr("例如 09:30"),size=14,color=Ink.Muted)},singleLine=true,enabled=!saving,
            modifier=Modifier.fillMaxWidth().padding(top=10.dp).semantics {contentDescription=tr("具体时间")},shape=RoundedCornerShape(12.dp))
        if(error!=null) AgendaText(error!!,size=12,color=Ink.Danger,modifier=Modifier.padding(top=12.dp).semantics {liveRegion=LiveRegionMode.Polite})
        Button(onClick={
            val localTime=if(time.isBlank()) null else runCatching {LocalTime.parse(time)}.getOrNull()
            when {
                reference && selectedTask==null->error=tr("请选择要引用的任务")
                !reference && title.isBlank()->error=tr("请填写安排内容")
                time.isNotBlank() && (localTime==null || !Regex("\\d{2}:\\d{2}").matches(time))->error=tr("请填写有效钟点，例如 09:30")
                else->{
                    val draft=ScheduleDraft(date,period,localTime,if(reference) selectedTask else null,
                        title,if(reference) alias.takeIf {it.isNotBlank()} else null)
                    saving=true;onBusy(true);error=null
                    scope.launch {
                        try {
                            if(original!=null) repository.updateSchedule(original.id,draft) else repository.createSchedule(draft)
                            onSaved()
                        } catch(c:CancellationException) {throw c}
                        catch(e:Exception) {error=saveError(e)}
                        finally {saving=false;onBusy(false)}
                    }
                }
            }
        },enabled=!saving && (!reference || availableTasks.any {it.id==selectedTask}),shape=RoundedCornerShape(11.dp),modifier=Modifier.align(Alignment.End).padding(top=16.dp).semantics {contentDescription=tr("保存日程")}) {
            AgendaText(if(saving) tr("保存中…") else if(original!=null) tr("保存修改") else tr("添加日程"),color=Ink.Surface)
            Spacer(Modifier.width(8.dp));AgendaIcon(if(original!=null) Mark.CHECK else Mark.PLUS,Ink.Surface,Modifier.size(17.dp))
        }
        if(original!=null && onDelete!=null) {
            TextButton({onDelete(original.id)},enabled=!saving && !dirty) {AgendaText(tr("删除日程"),color=if(dirty) Ink.Muted else Ink.Danger,size=13)}
            if(dirty) AgendaText(tr("保存修改或放弃编辑后，可以删除日程。"),size=12,color=Ink.Muted)
        }
    }
}

