package com.agenda.domain

import java.time.LocalDate

data class BoardPlacement(val cardId:String,val column:Int,val row:Int)
data class LeisureActivity(val id:String,val text:String)
data class ScheduleAppearance(val color:Long=0xFFEEEEEE,val text:String="留些空白，也留些期待。",val assetId:String?=null)
data class VisualAsset(val id:String,val mimeType:String,val sizeBytes:Long)
data class ReminderCheck(val date:LocalDate,val checkedAt:Long)
fun reminderTasks(tasks:List<Task>,today:LocalDate):List<Task> =tasks.filter {
    !it.completed && it.due?.let {date->date>today && date<=today.plusDays(3)}==true
}.sortedWith(compareBy<Task> {it.due}.thenBy {it.id})

/** Stable two-column spatial slots, independent of task priority or creation order. */
fun boardPlacements(ids:List<String>,saved:List<BoardPlacement>):List<BoardPlacement> {
    val occupied=mutableSetOf<Pair<Int,Int>>();val result=mutableListOf<BoardPlacement>()
    ids.forEach {id->
        val existing=saved.find {it.cardId==id && it.column in 0..1 && it.row in 0..100000}
        if(existing!=null && occupied.add(existing.column to existing.row)) result+=existing
    }
    ids.filter {id->result.none {it.cardId==id}}.forEach {id->
        val index=(0..(occupied.size*2+2)).first {(it%2 to it/2) !in occupied}
        val placement=BoardPlacement(id,index%2,index/2);occupied.add(placement.column to placement.row);result+=placement
    }
    return result
}
