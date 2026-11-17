package com.agenda.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DeleteConfirmation(kind:String,repository:AgendaViewModel,onClose:()->Unit,onBusy:(Boolean)->Unit) {
    val target=remember(kind) {runCatching {val parts=kind.split(':');DeleteTarget(DeleteKind.valueOf(parts[1].uppercase()),parts[2])}.getOrNull()}
    var preview by remember(kind) {mutableStateOf<DeletePreview?>(null)}
    var error by remember(kind) {mutableStateOf<String?>(null)}
    var loading by remember(kind) {mutableStateOf(true)}
    var saving by remember(kind) {mutableStateOf(false)}
    var retry by remember(kind) {mutableIntStateOf(0)}
    val scope=rememberCoroutineScope()
    SideEffect {onBusy(saving)}
    LaunchedEffect(target,retry) {
        loading=true;preview=null;error=null
        try {if(target!=null) preview=repository.previewDelete(target) else error=tr("无法识别要删除的内容")}
        catch(c:CancellationException) {throw c}
        catch(e:Exception) {error=if(e is AgendaFailure.NotFound) tr("这项内容已不存在") else tr("暂时无法读取删除范围，请重试")}
        finally {loading=false}
    }
    if(loading) AgendaText(tr("正在核对影响范围…"),color=Ink.Muted,modifier=Modifier.padding(vertical=16.dp))
    val plan=preview
    if(plan!=null) {
        AgendaText(tr("删除「{0}」？",plan.name),size=18,color=Ink.Primary,modifier=Modifier.padding(top=12.dp,bottom=16.dp))
        val counts=listOf(tr("对象") to plan.objects,tr("任务") to plan.tasks,tr("材料") to plan.materials,tr("说明") to plan.notes,
            (if(plan.target.kind==DeleteKind.SCHEDULE) tr("日程") else tr("关联日程")) to plan.schedule,tr("周计划") to plan.weekly,tr("休闲活动") to plan.leisure).filter {it.second>0}
        counts.forEach {(label,count)->Row(Modifier.fillMaxWidth().padding(vertical=5.dp)) {
            AgendaText(label,size=14,color=Ink.Muted,modifier=Modifier.weight(1f));AgendaText(tr("{0} 项",count),size=14)
        }}
        if(plan.materials>0) AgendaText(tr("只处理应用保存的副本，手机上的原始文件保留。"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=12.dp))
        if(plan.materialLinks>0) AgendaText(tr("同时解除 {0} 条任务与材料的关联；删除任务不会删除所关联的材料。",plan.materialLinks),size=12,color=Ink.Muted,modifier=Modifier.padding(top=12.dp))
        AgendaText(tr("删除后有 {0} 秒可以撤销，之后无法在应用内恢复。",(plan.undoMillis+999)/1000),size=12,color=Ink.Muted,modifier=Modifier.padding(top=12.dp))
    }
    error?.let {AgendaText(it,size=13,color=Ink.Danger,modifier=Modifier.padding(top=12.dp).semantics {liveRegion=LiveRegionMode.Polite})}
    Row(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.End) {
        TextButton(onClose,enabled=!saving) {AgendaText(tr("取消"),color=Ink.Primary)}
        if(plan==null && !loading) TextButton({retry++}) {AgendaText(tr("重试"),color=Ink.Primary)}
        if(plan!=null) Button({
            saving=true;error=null
            scope.launch {
                try {repository.delete(plan);onClose()}
                catch(c:CancellationException) {throw c}
                catch(e:Exception) {
                    error=when(e) {
                        is AgendaFailure.DeleteChanged->tr("内容已变化，已刷新删除范围，请重新核对后确认")
                        is AgendaFailure.NotFound->tr("这项内容已不存在，请关闭后重新选择")
                        else->tr("删除未完成，请重试")
                    }
                    if(e is AgendaFailure.DeleteChanged) {
                        try {preview=repository.previewDelete(plan.target)}
                        catch(c:CancellationException) {throw c}
                        catch(next:Exception) {preview=null;error=tr("内容已变化，请关闭后重新选择")}
                    }
                } finally {saving=false}
            }
        },enabled=!saving,colors=ButtonDefaults.buttonColors(containerColor=Ink.Danger),shape=RoundedCornerShape(11.dp)) {
            AgendaText(if(saving) tr("删除中…") else tr("确认删除"),color=Ink.Surface)
        }
    }
}

@Composable
internal fun UndoDeletionNotices(repository:AgendaViewModel,modifier:Modifier=Modifier) {
    val pending by repository.pendingDeletions.collectAsState()
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(pending) {while(pending.isNotEmpty()) {now=System.currentTimeMillis();delay(100)}}
    val visible=pending.filter {now in it.createdAt until it.expiresAt}
    if(visible.isNotEmpty()) Column(modifier.heightIn(max=240.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        visible.forEach {item->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Primary).padding(start=14.dp,end=6.dp),
            verticalAlignment=Alignment.CenterVertically) {
            AgendaText(tr("已删除：{0}",item.name),size=13,color=Ink.Surface,maxLines=2,modifier=Modifier.weight(1f))
            TextButton({repository.undoDelete(item.token)},modifier=Modifier.semantics {contentDescription=tr("撤销删除：{0}",item.name)}) {
                AgendaText(tr("撤销"),size=13,color=Ink.Surface)
            }
        }}
    }
}
