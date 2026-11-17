package com.agenda.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalDensity
import com.agenda.app.design.*
import com.agenda.domain.*
import java.time.LocalDate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged

private fun weekday(date:LocalDate) = listOf(tr("周一"),tr("周二"),tr("周三"),tr("周四"),tr("周五"),tr("周六"),tr("周日"))[date.dayOfWeek.value-1]

@Composable
fun ScheduleScene(data:AgendaSnapshot,repository:AgendaViewModel,today:LocalDate,full:Boolean,onInfo:(String)->Unit) {
    var backgroundFailed by remember(data.appearance.assetId) {mutableStateOf(false)}
    val background by produceState<ImageBitmap?>(null,data.appearance.assetId) {
        value=null;backgroundFailed=false
        data.appearance.assetId?.let {id->
            try {value=repository.appearanceImage(id).asImageBitmap()}
            catch(c:kotlinx.coroutines.CancellationException) {throw c}
            catch(e:Exception) {backgroundFailed=true}
        }
    }
    var selected by rememberSaveable(today) {mutableStateOf<LocalDate?>(null)}
    val dates=remember(today) {(-3L..14L).map {today.plusDays(it)}}
    val stripDensity=LocalDensity.current
    val stripState=rememberScrollState(initial=with(stripDensity) {82.dp.roundToPx()})
    var focusedDay by rememberSaveable {mutableStateOf<LocalDate?>(null)}
    val fullListState=rememberLazyListState(initialFirstVisibleItemIndex=3)
    BackHandler(selected!=null && !full) {selected=null}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Base colour and waves come from the shared app background, including both bars.
        background?.let {Image(it,contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.matchParentSize().graphicsLayer {alpha=.20f})}
        val fullWidth=maxWidth-32.dp
        val density=LocalDensity.current
        val expandedWidth=fullWidth*.87f
        LaunchedEffect(selected,full) {
            val day=selected ?: focusedDay
            if(day!=null && !full) {
                val index=dates.indexOf(day)
                if(index>=0) {
                    val target=with(density) {
                        (20.dp+82.dp*index+(if(selected==day) expandedWidth else 70.dp)/2-maxWidth/2).roundToPx()
                    }
                    // One coordinated movement. No delayed second centring pass.
                    stripState.animateScrollTo(target.coerceAtLeast(0),AgendaMotion.container())
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(top=8.dp)) {
            Box(Modifier.fillMaxWidth()) {
                MeadowHeading(calendar=true,month=tr("{0} 年 {1} 月",today.year,today.monthValue))
                // The header itself opens the existing appearance editor, keeping the reference uncluttered.
                Box(Modifier.matchParentSize().clickable {onInfo("appearance")}.semantics {contentDescription=tr("背景与文字")})
            }
            if(backgroundFailed) AgendaText(tr("背景图片暂不可用，可重新选择"),size=12,color=Ink.Danger)
            Spacer(Modifier.height(10.dp))
            if(full) {
                LazyRow(state=fullListState,flingBehavior=rememberSnapFlingBehavior(fullListState),
                    contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f)) {
                    items(dates,key={it.toString()}) {day->
                        Column(Modifier.width(fullWidth).fillMaxHeight().padding(bottom=12.dp)
                            .clip(RoundedCornerShape(16.dp)).border(1.dp,Ink.Divider.copy(alpha=.65f),RoundedCornerShape(16.dp))
                            .background(Ink.Surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            DayHeading(day,today,data)
                            HorizontalLine()
                            DayContent(day,data,repository,onInfo)
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val dateAreaHeight=maxHeight

                    // A finite strip keeps compact dates composed. Lazy composition
                    // during shrinking used to insert several expensive detail trees.
                    val viewportWidth=with(density) {this@BoxWithConstraints.maxWidth.roundToPx()}
                    Layout(modifier=Modifier.fillMaxSize().horizontalScroll(stripState).padding(horizontal=20.dp),content={
                        dates.forEach {day->
                            key(day) {
                                HangingDateCard(day,today,data,repository,selected==day,expandedWidth,
                                    dateAreaHeight,
                                    {focusedDay=day;selected=if(selected==day) null else day},onInfo)
                            }
                        }
                    }) {measurables,constraints->
                        val gap=12.dp.roundToPx()
                        val cards=measurables.map {it.measure(Constraints(maxHeight=constraints.maxHeight))}
                        layout(cards.sumOf {it.width}+gap*(cards.size-1),constraints.maxHeight) {
                            // Keep cheap compact trees warm, but never traverse or draw
                            // the sixteen offscreen dates on each animation frame.
                            val first=stripState.value-20.dp.roundToPx()
                            val last=first+viewportWidth
                            var x=0
                            cards.forEach {card->
                                if(x+card.width>=first-gap && x<=last+gap) card.placeRelative(x,0)
                                x+=card.width+gap
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(selected==null,enter=fadeIn(tween(120,delayMillis=320)),exit=fadeOut(tween(70)),
                        modifier=Modifier.align(Alignment.Center).padding(top=90.dp,start=40.dp,end=40.dp)) {
                        Box(Modifier.fillMaxWidth()
                            .clickable {onInfo("appearance")},contentAlignment=Alignment.Center) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                CalendarBranch()
                                if(data.appearance.text.isNotBlank()) MeadowText(if(data.appearance.text==ScheduleAppearance().text) tr("留些空白，也留些期待。") else data.appearance.text,size=15,color=Meadow.Muted,
                                    bold=true,title=true,modifier=Modifier.padding(top=16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HangingDateCard(day:LocalDate,today:LocalDate,data:AgendaSnapshot,repository:AgendaViewModel,
    open:Boolean,expandedWidth:Dp,maxHeight:Dp,onToggle:()->Unit,onInfo:(String)->Unit) {
    val progress=animateFloatAsState(if(open) 1f else 0f,AgendaMotion.container(),label="hanging-container")
    val interactive by remember(open) {derivedStateOf {open && progress.value>.96f}}
    val retainDetails by remember(open) {derivedStateOf {open || progress.value>.001f}}
    Column(horizontalAlignment=Alignment.CenterHorizontally) {

        Box(Modifier.clip(RoundedCornerShape(17.dp)).drawBehind {
                drawRect(if(day==today) androidx.compose.ui.graphics.lerp(Meadow.Teal,Meadow.White,progress.value.coerceIn(0f,1f)) else Meadow.White.copy(alpha=.88f))
            }.onSizeChanged {
                if(BuildConfig.DEBUG) android.util.Log.d("AgendaDateMotion","${android.os.SystemClock.elapsedRealtime()}|$day|${it.width}|${it.height}")
            }.clickable(onClick=onToggle).semantics {contentDescription=tr("{0}日期：{1}",if(open) tr("收起") else tr("展开"),day)}) {
            Layout(content={
                Column(Modifier.graphicsLayer {alpha=(1f-progress.value*3).coerceIn(0f,1f)}
                    .clearAndSetSemantics {}.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    val compactColor=if(day==today && !InterfaceSettings.blue) Color.White else Meadow.Text
                    val mutedColor=if(day==today && !InterfaceSettings.blue) Color.White.copy(alpha=.9f) else Meadow.Muted
                    MeadowText(weekday(day),size=11,color=mutedColor)
                    MeadowText("${day.dayOfMonth}",size=24,color=compactColor,modifier=Modifier.padding(vertical=3.dp))
                    MeadowText(tr("{0} 月",day.monthValue),size=10,color=mutedColor)
                    Row(Modifier.padding(top=10.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                        repeat(data.schedule.count {it.date==day}.coerceIn(1,4)) {
                            Box(Modifier.size(3.dp).clip(CircleShape).background(if(day==today) Color.White else Color(0xFFCCD7D7)))
                        }
                    }
                }
                if(retainDetails) Column(Modifier.heightIn(max=maxHeight).verticalScroll(rememberScrollState())
                    .graphicsLayer {alpha=((progress.value-.2f)/.6f).coerceIn(0f,1f)}
                    .then(if(!interactive) Modifier.clearAndSetSemantics {} else Modifier).padding(16.dp)) {
                    DisposableEffect(day) {
                        if(BuildConfig.DEBUG) android.util.Log.d("AgendaDateDetails","${android.os.SystemClock.elapsedRealtime()}|$day|composed")
                        onDispose {}
                    }
                    DayHeading(day,today,data);HorizontalLine()
                    DayContent(day,data,repository,onInfo,enabled=interactive)
                }
            }) {measurables,_->
                val compact=measurables[0].measure(Constraints.fixedWidth(70.dp.roundToPx()))
                val full=measurables.getOrNull(1)?.measure(Constraints.fixedWidth(expandedWidth.roundToPx()))
                val v=progress.value.coerceIn(0f,1.015f)
                val w=(compact.width+(expandedWidth.roundToPx()-compact.width)*v).toInt()
                val h=(compact.height+((full?.height ?: compact.height)-compact.height)*v).toInt()
                layout(w,h) {compact.placeRelative(0,0);full?.placeRelative(0,0)}
            }
            if(!interactive) Box(Modifier.matchParentSize().clickable(onClick=onToggle))
        }
    }
}

@Composable
private fun DayHeading(day:LocalDate,today:LocalDate,data:AgendaSnapshot) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        AgendaText("${day.dayOfMonth}",size=38,color=Ink.Primary)
        Column(Modifier.weight(1f).padding(start=8.dp)) {
            AgendaText(tr("{0} 月 / {1}",day.monthValue,weekday(day)),size=12,color=Ink.Muted)
            if(day==today) AgendaText(tr("今天"),size=12,color=Ink.Control)
        }
        AgendaText(tr("{0} 项",data.schedule.count {it.date==day}),size=12,color=Ink.Muted)
    }
}

@Composable
private fun DayContent(day:LocalDate,data:AgendaSnapshot,repository:AgendaViewModel,onInfo:(String)->Unit,enabled:Boolean=true) {
    Period.entries.forEach {period->
        Column(Modifier.fillMaxWidth().padding(bottom=12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                AgendaText(tr(period.label),size=13,color=Ink.Muted,modifier=Modifier.weight(1f))
                MarkButton(Mark.PLUS,tr("{0}月{1}日{2}添加日程",day.monthValue,day.dayOfMonth,tr(period.label)),{
                    onInfo("schedule:$day:${period.name}")
                })
            }
            val entries=data.schedule.filter {it.date==day && it.period==period}
            if(entries.isEmpty()) AgendaText(tr("留白"),size=13,color=Ink.Muted,modifier=Modifier.padding(start=8.dp,bottom=8.dp))
            entries.forEach {entry->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(entry.isCompleted(data.tasks),if(enabled) ({done:Boolean->repository.setScheduleCompleted(entry.id,done)}) else null,
                        modifier=Modifier.semantics { contentDescription=tr("完成：{0}",entry.name(data.tasks)) },
                        colors=CheckboxDefaults.colors(checkedColor=Ink.Primary,uncheckedColor=Ink.Control))
                    Column(Modifier.weight(1f).clickable(enabled=enabled) {onInfo("edit-schedule:${entry.id}")}
                        .semantics {contentDescription=tr("编辑日程：{0}",entry.name(data.tasks))}.padding(vertical=8.dp)) {
                        AgendaText(entry.name(data.tasks),color=if(entry.isCompleted(data.tasks)) Ink.Muted else Ink.Text)
                        entry.time?.let {AgendaText(it.toString(),size=12,color=Ink.Muted)}
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalLine() { Box(Modifier.fillMaxWidth().padding(vertical=12.dp).height(1.dp).background(Ink.Divider)) }

