package com.agenda.app

import android.util.Log
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.agenda.app.design.*
import com.agenda.domain.*
import kotlin.math.roundToInt

@Composable
fun TaskBoard(data:AgendaSnapshot,repository:AgendaViewModel,onTask:(String)->Unit,onInfo:(String)->Unit) {
    val ids=data.objects.map {it.id}+data.tasks.filter {it.objectId==null}.map {it.id}
    val positions=remember(ids,data.placements) {boardPlacements(ids,data.placements)}
    var expanded by rememberSaveable {mutableStateOf<String?>(null)}
    var dragged by remember {mutableStateOf<String?>(null)}
    var delta by remember {mutableStateOf(Offset.Zero)}
    val density=LocalDensity.current.density
    BackHandler(expanded!=null) {expanded=null}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        AgendaText(tr("事项"),title=true,size=20,color=Ink.Primary)
        var previousRow=-1
        positions.groupBy {it.row}.toSortedMap().forEach {(rowIndex,cards)->
            var gaps=rowIndex-previousRow-1
            while(gaps>0) {val chunk=minOf(gaps,50);Spacer(Modifier.height((162*chunk).dp));gaps-=chunk}
            previousRow=rowIndex
            val row=listOf(cards.find {it.column==0}?.cardId.orEmpty(),cards.find {it.column==1}?.cardId.orEmpty())
            ExpandablePair(row,expanded,{expanded=if(expanded==it) null else it},
                label={id->tr("事项卡片：")+(data.objects.find {it.id==id}?.name ?: data.tasks.find {it.id==id}?.name.orEmpty())},
                cardModifier={id->Modifier.zIndex(if(dragged==id) 1f else 0f).graphicsLayer {
                    if(dragged==id) {translationX=delta.x;translationY=delta.y;scaleX=1.025f;scaleY=1.025f}
                }.pointerInput(id,positions) {
                    detectDragGesturesAfterLongPress(onDragStart={dragged=id;delta=Offset.Zero},
                        onDrag={change,amount->change.consume();delta+=amount},
                        onDragCancel={dragged=null;delta=Offset.Zero},onDragEnd={
                            val from=positions.single {it.cardId==id}
                            val vertical=(delta.y/(162f*density)).roundToInt()
                            val horizontal=when {delta.x>65f*density->1;delta.x< -65f*density-> -1;else->0}
                            val column=(from.column+horizontal).coerceIn(0,1)
                            val targetRow=(from.row+vertical).coerceIn(0,((positions.maxOfOrNull {it.row} ?: 0)+1).coerceAtMost(100000))
                            repository.moveCard(id,column,targetRow)
                            dragged=null;delta=Offset.Zero
                        })
                }},
                header={id->
                    val obj=data.objects.find {it.id==id}
                    val task=data.tasks.find {it.id==id}
                    AgendaText(obj?.name ?: task?.name.orEmpty(),title=obj!=null,size=if(obj!=null) 18 else 14,
                        color=if(obj!=null) Ink.Primary else Ink.Text,maxLines=3)
                    Spacer(Modifier.height(10.dp))
                    AgendaText(if(obj!=null) tr("{0} 项待做",data.tasks.count {it.objectId==id && !it.completed}) else task?.effort?.label?.let {tr(it)} ?: tr("未分类"),size=12,color=Ink.Muted)
                },body={id,interactive->
                    val obj=data.objects.find {it.id==id}
                    if(obj!=null) {
                        Column {
                            TextButton({onInfo("edit-object:${obj.id}")},enabled=interactive) {AgendaText(tr("编辑对象"),size=12,color=Ink.Primary)}
                            TextButton({onInfo("delete:object:${obj.id}")},enabled=interactive) {AgendaText(tr("删除对象"),size=12,color=Ink.Danger)}
                            data.tasks.filter {it.objectId==id}.forEach {TaskLine(it,repository,onTask,enabled=interactive)}
                            obj.materials.forEach {file->AgendaText(file.displayTitle,size=13,
                                modifier=Modifier.fillMaxWidth().then(if(interactive) Modifier.clickable {onInfo("material:${file.id}")} else Modifier).padding(vertical=12.dp))}
                            obj.notes.forEach {note->AgendaText(note.title,size=13,color=Ink.Muted,
                                modifier=Modifier.fillMaxWidth().then(if(interactive) Modifier.clickable {onInfo("note:${note.id}")} else Modifier).padding(vertical=12.dp))}
                        }
                    } else data.tasks.find {it.id==id}?.let {TaskLine(it,repository,onTask,date=true,enabled=interactive)}
                })
        }
        AgendaText(tr("轻点展开 · 长按移动 · 上下滑动"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=8.dp,bottom=50.dp))
    }
}

