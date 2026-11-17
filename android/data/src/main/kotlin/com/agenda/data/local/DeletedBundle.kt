package com.agenda.data.local

import com.agenda.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Versioned short-lived journal, not a recycle bin or a backup format. */
internal data class DeletedBundle(
    val objects:List<ObjectRow> = emptyList(),val tasks:List<TaskRow> = emptyList(),
    val materials:List<MaterialRow> = emptyList(),val notes:List<NoteRow> = emptyList(),
    val schedule:List<ScheduleRow> = emptyList(),val weekly:List<WeeklyPlanRow> = emptyList(),
    val layouts:List<BoardLayoutRow> = emptyList(),val leisure:List<LeisureRow> = emptyList(),
    val appearance:List<AppearanceRow> = emptyList(),val assets:List<VisualAssetRow> = emptyList(),val reminders:List<ReminderCheckRow> = emptyList(),
    val taskMaterials:List<TaskMaterialRow> = emptyList()
) {
    fun preview(target:DeleteTarget,revision:Long):DeletePreview {
        val name=when(target.kind) {
            DeleteKind.OBJECT->objects.single().name
            DeleteKind.TASK->tasks.single().name
            DeleteKind.MATERIAL->materials.single().let {it.title.ifBlank {it.name}}
            DeleteKind.NOTE->notes.single().title
            DeleteKind.SCHEDULE->schedule.single().let {it.displayName?.takeIf {s->s.isNotBlank()} ?: it.taskId?.let {id->tasks.find {t->t.id==id}?.name} ?: it.title}
            DeleteKind.WEEKLY->weekly.single().text
            DeleteKind.LEISURE->leisure.single().text
        }
        return DeletePreview(target,name,revision,objects.size,tasks.size,materials.size,notes.size,schedule.size,weekly.size,leisure=leisure.size,materialLinks=taskMaterials.size)
    }
    fun encode():String=JSONObject().put("version",3)
        .put("objects",JSONArray(objects.map {row(it.id,it.name,it.createdAt)}))
        .put("tasks",JSONArray(tasks.map {row(it.id,it.name,it.objectId,it.effort,it.dueDay,it.completed,it.wants,it.should,it.createdAt,it.description)}))
        .put("materials",JSONArray(materials.map {row(it.id,it.objectId,it.name,it.mimeType,it.fileKey,it.sizeBytes,it.sha256,it.createdAt,it.title,it.description)}))
        .put("notes",JSONArray(notes.map {row(it.id,it.objectId,it.title,it.body,it.createdAt)}))
        .put("schedule",JSONArray(schedule.map {row(it.id,it.dateDay,it.period,it.minuteKey,it.taskId,it.title,it.displayName,it.completed,it.createdAt)}))
        .put("weekly",JSONArray(weekly.map {row(it.id,it.weekStartDay,it.text,it.completed,it.createdAt)}))
        .put("layouts",JSONArray(layouts.map {row(it.cardId,it.column,it.row)}))
        .put("leisure",JSONArray(leisure.map {row(it.id,it.text,it.createdAt)}))
        .put("appearance",JSONArray(appearance.map {row(it.id,it.color,it.text,it.assetId)}))
        .put("assets",JSONArray(assets.map {row(it.id,it.mimeType,it.sizeBytes,it.sha256,it.createdAt)}))
        .put("reminders",JSONArray(reminders.map {row(it.dateDay,it.checkedAt)}))
        .put("taskMaterials",JSONArray(taskMaterials.map {row(it.taskId,it.materialId)})).toString()
    companion object {
        private fun row(vararg fields:Any?):JSONArray=JSONArray(fields.map {it ?: JSONObject.NULL})
        private fun JSONArray.stringOrNull(i:Int):String?=if(isNull(i)) null else getString(i)
        private fun JSONObject.rows(key:String):List<JSONArray> = getJSONArray(key).let {list->(0 until list.length()).map {list.getJSONArray(it)}}
        fun decode(payload:String):DeletedBundle {
            val json=JSONObject(payload);val version=json.getInt("version");require(version in 1..3)
            return DeletedBundle(
                json.rows("objects").map {ObjectRow(it.getString(0),it.getString(1),it.getLong(2))},
                json.rows("tasks").map {TaskRow(it.getString(0),it.getString(1),it.stringOrNull(2),it.stringOrNull(3),if(it.isNull(4)) null else it.getLong(4),it.getBoolean(5),it.getBoolean(6),it.getBoolean(7),it.getLong(8),if(version<3) "" else it.getString(9))},
                json.rows("materials").map {MaterialRow(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getString(4),it.getLong(5),it.getString(6),it.getLong(7),it.getString(8),it.getString(9))},
                json.rows("notes").map {NoteRow(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getLong(4))},
                json.rows("schedule").map {ScheduleRow(it.getString(0),it.getLong(1),it.getString(2),it.getInt(3),it.stringOrNull(4),it.getString(5),it.stringOrNull(6),it.getBoolean(7),it.getLong(8))},
                json.rows("weekly").map {WeeklyPlanRow(it.getString(0),it.getLong(1),it.getString(2),it.getBoolean(3),it.getLong(4))},
                if(version==1) emptyList() else json.rows("layouts").map {BoardLayoutRow(it.getString(0),it.getInt(1),it.getInt(2))},
                if(version==1) emptyList() else json.rows("leisure").map {LeisureRow(it.getString(0),it.getString(1),it.getLong(2))},
                if(version==1) emptyList() else json.rows("appearance").map {AppearanceRow(it.getInt(0),it.getLong(1),it.getString(2),it.stringOrNull(3))},
                if(version==1) emptyList() else json.rows("assets").map {VisualAssetRow(it.getString(0),it.getString(1),it.getLong(2),it.getString(3),it.getLong(4))},
                if(version==1) emptyList() else json.rows("reminders").map {ReminderCheckRow(it.getLong(0),it.getLong(1))},
                if(version<3) emptyList() else json.rows("taskMaterials").map {TaskMaterialRow(it.getString(0),it.getString(1))}
            )
        }
    }
}
