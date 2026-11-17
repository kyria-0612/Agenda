package com.agenda.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun DeadlineCalendar(value:String,onChange:(String)->Unit,label:String=tr("截止日期"),allowClear:Boolean=true) {
    val selected=runCatching {LocalDate.parse(value)}.getOrNull()
    var month by rememberSaveable {mutableStateOf(YearMonth.from(selected ?: LocalDate.now()))}
    Column(Modifier.fillMaxWidth().padding(top=16.dp).clip(RoundedCornerShape(16.dp))
        .background(Ink.Surface.copy(alpha=.72f)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                AgendaText(label,size=11,color=Ink.Muted)
                AgendaText(selected?.let {tr("{0} 月 {1} 日",it.monthValue,it.dayOfMonth)} ?: tr("未设置"),size=18,color=Ink.Primary)
            }
            if(allowClear) TextButton({onChange("")}) {AgendaText(tr("清除"),size=12,color=Ink.Muted)}
        }
        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            MarkButton(Mark.BACK,tr("上个月"),{month=month.minusMonths(1)})
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {AgendaText(tr("{0} 年 {1} 月",month.year,month.monthValue),size=14,color=Ink.Primary)}
            MarkButton(Mark.BACK,tr("下个月"),{month=month.plusMonths(1)},modifier=Modifier.graphicsLayer {rotationZ=180f})
        }
        Row(Modifier.fillMaxWidth().background(Ink.Canvas.copy(alpha=.75f)).padding(vertical=8.dp)) {
            listOf(tr("一"),tr("二"),tr("三"),tr("四"),tr("五"),tr("六"),tr("日")).forEach {label->
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {AgendaText(label,size=11,color=Ink.Muted)}
            }
        }
        val first=month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value-1).toLong())
        val weeks=((month.lengthOfMonth()+month.atDay(1).dayOfWeek.value-2)/7)+1
        repeat(weeks) {week->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) {column->
                    val date=first.plusDays((week*7+column).toLong())
                    val active=YearMonth.from(date)==month
                    Box(Modifier.weight(1f).height(44.dp).padding(2.dp).clip(RoundedCornerShape(12.dp))
                        .background(if(date==selected) Ink.Primary else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable {month=YearMonth.from(date);onChange(date.toString())}
                        .semantics {contentDescription=tr("{0}：{1}",label,date);this.selected=date==selected},contentAlignment=Alignment.Center) {
                        AgendaText("${date.dayOfMonth}",size=13,color=if(date==selected) Ink.Surface else if(active) Ink.Text else Ink.Muted.copy(alpha=.6f))
                    }
                }
            }
        }
    }
}

