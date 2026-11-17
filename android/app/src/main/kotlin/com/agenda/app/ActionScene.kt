package com.agenda.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.*
import java.time.LocalDate

private val blueCardTints=listOf(Color(0xFFDDEDF3),Color(0xFFE1EDF2),Color(0xFFD6E9F1),Color(0xFFDDE7F2))
private val greenCardTints=listOf(Color(0xFFE3F3EB),Color(0xFFFAF0D9),Color(0xFFDEEFFB),Color(0xFFE8E4FC))
private val cardTints get()=if(InterfaceSettings.blue) blueCardTints else greenCardTints
private val cardArt=listOf(R.drawable.meadow_notes,R.drawable.meadow_coffee,R.drawable.meadow_laptop,R.drawable.meadow_camp)

@Composable
internal fun ActionScene(data:AgendaSnapshot,repository:AgendaViewModel,today:LocalDate,onOpen:(String)->Unit,
    onLeisure:()->Unit,onCreate:()->Unit,onThings:()->Unit) {
    var expanded by rememberSaveable {mutableStateOf<String?>(null)}
    BackHandler(expanded!=null) {expanded=null}
    val labels=Effort.entries.map {it.name}+"leisure"
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardHeight=((maxWidth-32.dp)/2*.85f).coerceAtLeast(150.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp).padding(top=16.dp,bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            MeadowHeading(modifier=Modifier.padding(bottom=2.dp))
            labels.chunked(2).forEach {pair->
                ExpandablePair(pair,expanded,{expanded=if(expanded==it) null else it},label={tr("行动卡片：{0}",if(it=="leisure") tr("休闲活动") else tr(Effort.valueOf(it).label))},
                    baseHeight=cardHeight,cornerRadius=24.dp,
                    cardModifier={Modifier.border(2.dp,Color.White.copy(alpha=.8f),RoundedCornerShape(24.dp))},
                    decoration={label->
                        val i=labels.indexOf(label)
                        Canvas(Modifier.matchParentSize()) {
                            drawRect(Brush.verticalGradient(listOf(cardTints[i].copy(alpha=.20f),Color.White.copy(alpha=.2f))))
                            if(expanded!=label) {
                                val w=size.width;val h=size.height
                                val wave=Path().apply {moveTo(0f,h*.97f);cubicTo(w*.3f,h,w*.45f,h*.67f,w,h*.76f);lineTo(w,h);lineTo(0f,h);close()}
                                drawPath(wave,cardTints[i].copy(alpha=.8f))
                            }
                        }
                        if(expanded!=label) Image(painterResource(cardArt[i]),null,contentScale=ContentScale.Fit,
                            modifier=Modifier.align(Alignment.BottomEnd).padding(end=10.dp,bottom=7.dp).width(78.dp).height(70.dp))
                    },
                    header={label->
                        val i=labels.indexOf(label)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            MeadowText(if(label=="leisure") tr("休闲活动") else tr(Effort.valueOf(label).label),size=18,bold=true,title=true,modifier=Modifier.weight(1f))
                            MeadowSymbol(7,Meadow.Muted,Modifier.size(16.dp))
                        }
                        val count=data.tasks.count {it.effort?.name==label && !it.completed}
                        MeadowText(if(i==3) tr("{0} 件喜欢的事",data.leisure.size) else tr("{0} 项待做",count),size=13,color=Meadow.Muted,modifier=Modifier.padding(top=4.dp))
                    },body={label,interactive->
                        Column {
                            val tasks=data.tasks.filter {it.effort?.name==label && !it.completed}
                            if(label=="leisure") {
                                data.leisure.forEach {MeadowText(it.text,modifier=Modifier.padding(vertical=6.dp))}
                                MeadowText(tr("也可以，什么都不做。"),size=13,color=Meadow.Muted)
                                TextButton(onLeisure,enabled=interactive) {MeadowText(tr("管理休闲活动"),size=12,color=Meadow.Green)}
                            } else if(tasks.isEmpty()) {
                                val allDone=data.tasks.any {it.effort?.name==label}
                                MeadowText(if(allDone) tr("这一类任务都完成了") else tr("还没有这类任务"),size=13,color=Meadow.Muted)
                                TextButton(if(allDone) onThings else onCreate,enabled=interactive) {
                                    MeadowText(if(allDone) tr("查看事项") else tr("新建任务"),size=12,color=Meadow.Green)
                                }
                            } else tasks.forEach {TaskLine(it,repository,onOpen,enabled=interactive)}
                        }
                    })
            }
            val urgent=urgentTasks(data.tasks,today)
            Column(Modifier.padding(top=2.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFFFFDFD)).border(2.dp,Color.White,RoundedCornerShape(24.dp))) {
                Row(Modifier.fillMaxWidth().background(Color(0xFFE5F0FB)).padding(horizontal=22.dp,vertical=14.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    MeadowText(tr("尽快做"),size=19,bold=true,title=true)
                }
                Column(Modifier.padding(horizontal=22.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                        AgendaIcon(Mark.CALENDAR,Meadow.Muted)
                        MeadowText(tr("一周内 · 按截止日期"),color=if(InterfaceSettings.blue) Meadow.Text else Color(0xFF53616A),modifier=Modifier.weight(1f).padding(start=12.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0EEEE)))
                    if(urgent.isEmpty()) Row(Modifier.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                        MeadowSymbol(5,Meadow.Muted)
                        MeadowText(tr("一周内暂无待完成任务"),color=Meadow.Muted,modifier=Modifier.padding(start=12.dp))
                    }
                    urgent.forEach {TaskLine(it,repository,onOpen,date=true)}
                }
            }
        }
    }
}
