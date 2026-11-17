package com.agenda.data.local

import androidx.room.withTransaction
import com.agenda.domain.*
import java.time.LocalDate
import java.util.UUID

internal suspend fun synchronizeBoardLayouts(dao:AgendaDao):Boolean {
    val ids=dao.objects().map {it.id}+dao.tasks().filter {it.objectId==null}.map {it.id}
    val saved=dao.layouts();var changed=false
    boardPlacements(ids,saved.map {BoardPlacement(it.cardId,it.column,it.row)}).forEach {position->
        val row=BoardLayoutRow(position.cardId,position.column,position.row)
        if(saved.find {it.cardId==row.cardId}!=row) {dao.saveLayout(row);changed=true}
    }
    return changed
}

class DailyFeatureRepository(private val database:AgendaDatabase,private val gate:LocalDataGate=LocalDataGate()) {
    private val dao=database.agenda()
    suspend fun saveTaskDetails(id:String,description:String,materialIds:List<String>) {gate.run {database.withTransaction {
        if(dao.task(id)==null) throw AgendaFailure.NotFound()
        val available=dao.materials().map {it.id}.toSet()
        if(materialIds.any {it !in available}) throw AgendaFailure.NotFound()
        dao.updateTaskDescription(id,description.trim())
        dao.deleteTaskMaterials(listOf(id))
        materialIds.distinct().sorted().forEach {dao.insertTaskMaterial(TaskMaterialRow(id,it))}
        dao.changed()
    }}}
    suspend fun initialize() {gate.run {database.withTransaction {
        var changed=synchronizeBoardLayouts(dao)
        if(dao.appearance()==null) {dao.saveAppearance(AppearanceRow());changed=true}
        if(changed) dao.changed()
    }}}
    suspend fun moveCard(id:String,column:Int,row:Int) {gate.run {database.withTransaction {
        if(column !in 0..1 || row !in 0..100000) throw AgendaFailure.InvalidInput()
        val ids=dao.objects().map {it.id}+dao.tasks().filter {it.objectId==null}.map {it.id}
        if(id !in ids) throw AgendaFailure.NotFound()
        synchronizeBoardLayouts(dao)
        val layouts=dao.layouts().filter {it.cardId in ids};val old=layouts.single {it.cardId==id}
        val occupied=layouts.find {it.cardId!=id && it.column==column && it.row==row}
        if(occupied!=null) dao.saveLayout(occupied.copy(column=old.column,row=old.row))
        dao.saveLayout(old.copy(column=column,row=row));dao.changed()
    }}}
    suspend fun saveLeisure(id:String?,text:String):String=gate.run {database.withTransaction {
        if(text.isBlank()) throw AgendaFailure.InvalidInput()
        val result=id ?: UUID.randomUUID().toString()
        if(id==null) dao.insertLeisure(LeisureRow(result,text.trim(),System.currentTimeMillis()))
        else if(dao.updateLeisure(id,text.trim())!=1) throw AgendaFailure.NotFound()
        dao.changed();result
    }}
    suspend fun claimReminder(today:LocalDate):List<String>?=gate.run {database.withTransaction {
        if(dao.reminderChecked(today.toEpochDay())) return@withTransaction null
        val candidates=dao.tasks().filter {!it.completed && it.dueDay?.let {day->day>today.toEpochDay() && day<=today.plusDays(3).toEpochDay()}==true}
            .sortedWith(compareBy<TaskRow> {it.dueDay}.thenBy {it.id}).map {it.id}
        dao.insertReminderCheck(ReminderCheckRow(today.toEpochDay(),System.currentTimeMillis()));dao.changed();candidates
    }}
}
