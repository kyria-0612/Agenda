package com.agenda.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TaskDetailsPanel(task:Task,data:AgendaSnapshot,repository:AgendaViewModel,onSaved:()->Unit,onDirty:(Boolean)->Unit,onBusy:(Boolean)->Unit) {
    var description by rememberSaveable(task.id) {mutableStateOf(task.description)}
    var selected by rememberSaveable(task.id) {mutableStateOf(task.materialIds)}
    var saving by remember {mutableStateOf(false)}
    var error by rememberSaveable {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val available=data.objects.flatMap {it.materials}.map {it.id}.toSet()
    LaunchedEffect(available) {
        if(selected.any {it !in available}) {selected=selected.filter {it in available};error=tr("有材料已删除，已移除对应选择，请核对后保存")}
    }
    SideEffect {onDirty(description!=task.description || selected.toSet()!=task.materialIds.toSet());onBusy(saving)}
    AgendaText(task.name,size=16,color=Ink.Primary,modifier=Modifier.padding(vertical=12.dp))
    AgendaOutlinedTextField(description,{description=it;error=null},label={AgendaText(tr("任务说明（可选）"),size=12)},
        modifier=Modifier.fillMaxWidth().semantics {contentDescription=tr("任务说明")},enabled=!saving,
        minLines=3,maxLines=8,shape=RoundedCornerShape(14.dp))
    AgendaText(tr("关联已有材料"),size=14,color=Ink.Primary,modifier=Modifier.padding(top=20.dp,bottom=8.dp))
    AgendaText(tr("可以选择任意对象已保存的材料；关联共用一份副本，移动任务后继续保留。"),size=12,color=Ink.Muted)
    if(available.isEmpty()) AgendaText(tr("还没有材料，请先在对象的材料分支添加。"),size=13,color=Ink.Muted,modifier=Modifier.padding(vertical=16.dp))
    else Column(Modifier.fillMaxWidth().heightIn(max=260.dp).verticalScroll(rememberScrollState())) {
        data.objects.filter {it.materials.isNotEmpty()}.forEach {obj->
            AgendaText(obj.name,size=12,color=Ink.Muted,modifier=Modifier.padding(top=12.dp))
            obj.materials.forEach {item->
                fun toggle(checked:Boolean) {selected=if(checked) (selected+item.id).distinct() else selected-item.id;error=null}
                Row(Modifier.fillMaxWidth().clickable(enabled=!saving) {toggle(item.id !in selected)}
                    .semantics {contentDescription=tr("关联材料：{0}",item.displayTitle);this.selected=item.id in selected},verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(item.id in selected,{toggle(it)},enabled=!saving,colors=CheckboxDefaults.colors(checkedColor=Ink.Primary))
                    AgendaText(item.displayTitle,size=14,modifier=Modifier.weight(1f).padding(vertical=8.dp))
                }
            }
        }
    }
    error?.let {AgendaText(it,size=12,color=Ink.Danger,modifier=Modifier.padding(top=12.dp).semantics {liveRegion=LiveRegionMode.Polite})}
    Button({val text=description;val links=selected;saving=true;error=null;scope.launch {
        try {repository.saveTaskDetails(task.id,text,links);onSaved()}
        catch(c:CancellationException) {throw c}
        catch(e:Exception) {error=saveError(e)}
        finally {saving=false}
    }},enabled=!saving,shape=RoundedCornerShape(11.dp),modifier=Modifier.padding(top=16.dp)) {
        AgendaText(if(saving) tr("保存中…") else tr("保存说明与关联"),color=Ink.Surface)
    }
}
