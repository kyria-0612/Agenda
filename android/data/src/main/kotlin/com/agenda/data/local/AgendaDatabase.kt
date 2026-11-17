package com.agenda.data.local

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName="agenda_objects")
data class ObjectRow(@PrimaryKey val id:String,val name:String,val createdAt:Long)

@Entity(tableName="agenda_tasks",foreignKeys=[ForeignKey(entity=ObjectRow::class,parentColumns=["id"],childColumns=["objectId"],onDelete=ForeignKey.RESTRICT)],
    indices=[Index("objectId"),Index(value=["completed","dueDay"])])
data class TaskRow(@PrimaryKey val id:String,val name:String,val objectId:String?,val effort:String?,val dueDay:Long?,
    val completed:Boolean,val wants:Boolean,val should:Boolean,val createdAt:Long,@ColumnInfo(defaultValue="''") val description:String="")

@Entity(tableName="agenda_task_materials",primaryKeys=["taskId","materialId"],foreignKeys=[
    ForeignKey(entity=TaskRow::class,parentColumns=["id"],childColumns=["taskId"],onDelete=ForeignKey.RESTRICT),
    ForeignKey(entity=MaterialRow::class,parentColumns=["id"],childColumns=["materialId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("materialId")])
data class TaskMaterialRow(val taskId:String,val materialId:String)

@Entity(tableName="agenda_notes",foreignKeys=[ForeignKey(entity=ObjectRow::class,parentColumns=["id"],childColumns=["objectId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("objectId")])
data class NoteRow(@PrimaryKey val id:String,val objectId:String,val title:String,val body:String,val createdAt:Long)

@Entity(tableName="agenda_materials",foreignKeys=[ForeignKey(entity=ObjectRow::class,parentColumns=["id"],childColumns=["objectId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("objectId"),Index(value=["fileKey"],unique=true)])
data class MaterialRow(@PrimaryKey val id:String,val objectId:String,val name:String,val mimeType:String,val fileKey:String,
    val sizeBytes:Long,val sha256:String,val createdAt:Long,
    @ColumnInfo(defaultValue="''") val title:String="",@ColumnInfo(defaultValue="''") val description:String="")

@Entity(tableName="agenda_schedule",foreignKeys=[ForeignKey(entity=TaskRow::class,parentColumns=["id"],childColumns=["taskId"],onDelete=ForeignKey.RESTRICT)],
    indices=[Index("taskId"),Index("dateDay"),Index(value=["taskId","dateDay","period","minuteKey"],unique=true)])
data class ScheduleRow(@PrimaryKey val id:String,val dateDay:Long,val period:String,val minuteKey:Int,val taskId:String?,
    val title:String,val displayName:String?,val completed:Boolean,val createdAt:Long)

@Entity(tableName="agenda_revision")
data class RevisionRow(@PrimaryKey val id:Int=0,val value:Long=0)

@Entity(tableName="agenda_weekly_plans",indices=[Index("weekStartDay")])
data class WeeklyPlanRow(@PrimaryKey val id:String,val weekStartDay:Long,val text:String,val completed:Boolean,val createdAt:Long)

@Entity(tableName="agenda_delete_undo")
data class DeleteUndoRow(@PrimaryKey val token:String,val name:String,val payload:String,val createdAt:Long,val expiresAt:Long)

@Entity(tableName="agenda_board_layout")
data class BoardLayoutRow(@PrimaryKey val cardId:String,val column:Int,val row:Int)
@Entity(tableName="agenda_leisure")
data class LeisureRow(@PrimaryKey val id:String,val text:String,val createdAt:Long)
@Entity(tableName="agenda_visual_assets")
data class VisualAssetRow(@PrimaryKey val id:String,val mimeType:String,val sizeBytes:Long,val sha256:String,val createdAt:Long)
@Entity(tableName="agenda_appearance",foreignKeys=[ForeignKey(entity=VisualAssetRow::class,parentColumns=["id"],childColumns=["assetId"],onDelete=ForeignKey.RESTRICT)],indices=[Index("assetId")])
data class AppearanceRow(@PrimaryKey val id:Int=0,val color:Long=0xFFEEEEEE,val text:String="留些空白，也留些期待。",val assetId:String?=null)
@Entity(tableName="agenda_reminder_checks")
data class ReminderCheckRow(@PrimaryKey val dateDay:Long,val checkedAt:Long)

@Dao
interface AgendaDao {
    @Query("SELECT * FROM agenda_task_materials ORDER BY taskId,materialId") suspend fun taskMaterials():List<TaskMaterialRow>
    @Insert suspend fun insertTaskMaterial(row:TaskMaterialRow)
    @Query("DELETE FROM agenda_task_materials WHERE taskId IN (:ids)") suspend fun deleteTaskMaterials(ids:List<String>)
    @Query("DELETE FROM agenda_task_materials WHERE materialId IN (:ids)") suspend fun deleteMaterialLinks(ids:List<String>)
    @Query("UPDATE agenda_tasks SET description=:description WHERE id=:id") suspend fun updateTaskDescription(id:String,description:String):Int
    @Query("SELECT * FROM agenda_board_layout ORDER BY row,`column`,cardId") suspend fun layouts():List<BoardLayoutRow>
    @Query("SELECT * FROM agenda_leisure ORDER BY createdAt,id") suspend fun leisure():List<LeisureRow>
    @Query("SELECT * FROM agenda_visual_assets ORDER BY id") suspend fun assets():List<VisualAssetRow>
    @Query("SELECT * FROM agenda_appearance WHERE id=0") suspend fun appearance():AppearanceRow?
    @Query("SELECT * FROM agenda_reminder_checks ORDER BY dateDay") suspend fun reminderChecks():List<ReminderCheckRow>
    @Query("SELECT EXISTS(SELECT 1 FROM agenda_reminder_checks WHERE dateDay=:day)") suspend fun reminderChecked(day:Long):Boolean
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveLayout(row:BoardLayoutRow)
    @Insert suspend fun insertLeisure(row:LeisureRow)
    @Query("UPDATE agenda_leisure SET text=:text WHERE id=:id") suspend fun updateLeisure(id:String,text:String):Int
    @Insert suspend fun insertAsset(row:VisualAssetRow)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveAppearance(row:AppearanceRow)
    @Insert suspend fun insertReminderCheck(row:ReminderCheckRow)
    @Query("DELETE FROM agenda_board_layout WHERE cardId IN (:ids)") suspend fun deleteLayouts(ids:List<String>)
    @Query("DELETE FROM agenda_leisure WHERE id IN (:ids)") suspend fun deleteLeisure(ids:List<String>)
    @Query("DELETE FROM agenda_visual_assets WHERE id!=:keep") suspend fun removeOtherAssets(keep:String)
    @Query("SELECT value FROM agenda_revision WHERE id=0") suspend fun revision():Long
    @Query("SELECT * FROM agenda_delete_undo ORDER BY createdAt,token") fun observeDeletions():Flow<List<DeleteUndoRow>>
    @Query("SELECT * FROM agenda_delete_undo") suspend fun deletions():List<DeleteUndoRow>
    @Query("SELECT * FROM agenda_delete_undo WHERE token=:token") suspend fun deletion(token:String):DeleteUndoRow?
    @Insert suspend fun insertDeletion(row:DeleteUndoRow)
    @Query("DELETE FROM agenda_delete_undo WHERE token=:token") suspend fun removeDeletion(token:String)
    @Query("DELETE FROM agenda_delete_undo WHERE expiresAt<=:now OR createdAt>:now") suspend fun expireDeletions(now:Long)
    @Query("DELETE FROM agenda_schedule WHERE id IN (:ids)") suspend fun deleteSchedule(ids:List<String>)
    @Query("DELETE FROM agenda_tasks WHERE id IN (:ids)") suspend fun deleteTasks(ids:List<String>)
    @Query("DELETE FROM agenda_notes WHERE id IN (:ids)") suspend fun deleteNotes(ids:List<String>)
    @Query("DELETE FROM agenda_materials WHERE id IN (:ids)") suspend fun deleteMaterials(ids:List<String>)
    @Query("DELETE FROM agenda_objects WHERE id IN (:ids)") suspend fun deleteObjects(ids:List<String>)
    @Query("DELETE FROM agenda_weekly_plans WHERE id IN (:ids)") suspend fun deleteWeeklyPlans(ids:List<String>)
    @Query("SELECT value FROM agenda_revision WHERE id=0") fun revisions():Flow<Long>
    @Query("UPDATE agenda_revision SET value=value+1 WHERE id=0") suspend fun changed()
    @Query("SELECT * FROM agenda_objects ORDER BY createdAt,id") suspend fun objects():List<ObjectRow>
    @Query("SELECT * FROM agenda_tasks ORDER BY createdAt,id") suspend fun tasks():List<TaskRow>
    @Query("SELECT * FROM agenda_notes ORDER BY createdAt,id") suspend fun notes():List<NoteRow>
    @Query("SELECT * FROM agenda_materials ORDER BY createdAt,id") suspend fun materials():List<MaterialRow>
    @Query("SELECT * FROM agenda_schedule ORDER BY dateDay,period,minuteKey,createdAt,id") suspend fun schedule():List<ScheduleRow>
    @Query("SELECT * FROM agenda_tasks WHERE id=:id") suspend fun task(id:String):TaskRow?
    @Query("SELECT * FROM agenda_schedule WHERE id=:id") suspend fun entry(id:String):ScheduleRow?
    @Query("SELECT * FROM agenda_materials WHERE id=:id") suspend fun material(id:String):MaterialRow?
    @Query("SELECT EXISTS(SELECT 1 FROM agenda_objects WHERE id=:id)") suspend fun hasObject(id:String):Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM agenda_schedule WHERE taskId=:taskId AND dateDay=:date AND period=:period AND minuteKey=:minute AND id!=:excludingId)")
    suspend fun hasReference(taskId:String,date:Long,period:String,minute:Int,excludingId:String=""):Boolean
    @Update suspend fun updateTask(row:TaskRow):Int
    @Update suspend fun updateSchedule(row:ScheduleRow):Int
    @Query("UPDATE agenda_objects SET name=:name WHERE id=:id") suspend fun renameObject(id:String,name:String):Int
    @Query("UPDATE agenda_notes SET title=:title,body=:body WHERE id=:id") suspend fun updateNote(id:String,title:String,body:String):Int
    @Query("UPDATE agenda_weekly_plans SET text=:text WHERE id=:id") suspend fun updateWeeklyPlan(id:String,text:String):Int
    @Insert suspend fun insertObject(row:ObjectRow)
    @Insert suspend fun insertTask(row:TaskRow)
    @Insert suspend fun insertNote(row:NoteRow)
    @Insert suspend fun insertMaterial(row:MaterialRow)
    @Insert suspend fun insertSchedule(row:ScheduleRow)
    @Query("SELECT * FROM agenda_weekly_plans ORDER BY weekStartDay,createdAt,id") suspend fun weeklyPlans():List<WeeklyPlanRow>
    @Insert suspend fun insertWeeklyPlan(row:WeeklyPlanRow)
    @Query("UPDATE agenda_weekly_plans SET completed=:completed WHERE id=:id") suspend fun completeWeeklyPlan(id:String,completed:Boolean):Int
    @Query("UPDATE agenda_materials SET title=:title,description=:description WHERE id=:id") suspend fun updateMaterial(id:String,title:String,description:String):Int
    @Query("UPDATE agenda_tasks SET completed=:completed WHERE id=:id") suspend fun completeTask(id:String,completed:Boolean):Int
    @Query("UPDATE agenda_schedule SET completed=:completed WHERE id=:id AND taskId IS NULL") suspend fun completeTemporary(id:String,completed:Boolean):Int
}

@Database(entities=[ObjectRow::class,TaskRow::class,NoteRow::class,MaterialRow::class,ScheduleRow::class,RevisionRow::class,WeeklyPlanRow::class,DeleteUndoRow::class,
    BoardLayoutRow::class,LeisureRow::class,VisualAssetRow::class,AppearanceRow::class,ReminderCheckRow::class,TaskMaterialRow::class],version=6,exportSchema=true)
abstract class AgendaDatabase:RoomDatabase() {
    abstract fun agenda():AgendaDao
    companion object {
        val MIGRATION_5_6=object:Migration(5,6) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agenda_tasks ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_task_materials (taskId TEXT NOT NULL, materialId TEXT NOT NULL, PRIMARY KEY(taskId,materialId), FOREIGN KEY(taskId) REFERENCES agenda_tasks(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(materialId) REFERENCES agenda_materials(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agenda_task_materials_materialId ON agenda_task_materials (materialId)")
            }
        }
        val MIGRATION_4_5=object:Migration(4,5) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_board_layout (cardId TEXT NOT NULL, `column` INTEGER NOT NULL, row INTEGER NOT NULL, PRIMARY KEY(cardId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_leisure (id TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_visual_assets (id TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_appearance (id INTEGER NOT NULL, color INTEGER NOT NULL, text TEXT NOT NULL, assetId TEXT, PRIMARY KEY(id), FOREIGN KEY(assetId) REFERENCES agenda_visual_assets(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agenda_appearance_assetId ON agenda_appearance (assetId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_reminder_checks (dateDay INTEGER NOT NULL, checkedAt INTEGER NOT NULL, PRIMARY KEY(dateDay))")
            }
        }
        val MIGRATION_3_4=object:Migration(3,4) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_delete_undo (token TEXT NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, PRIMARY KEY(token))")
            }
        }
        val MIGRATION_2_3=object:Migration(2,3) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agenda_materials ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE agenda_materials ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS agenda_weekly_plans (id TEXT NOT NULL, weekStartDay INTEGER NOT NULL, text TEXT NOT NULL, completed INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agenda_weekly_plans_weekStartDay ON agenda_weekly_plans (weekStartDay)")
            }
        }
        fun open(context:Context,name:String="agenda.db"):AgendaDatabase =
            Room.databaseBuilder(context.applicationContext,AgendaDatabase::class.java,name)
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6)
                .addCallback(object:Callback() {
                    override fun onCreate(db:SupportSQLiteDatabase) {db.execSQL("INSERT INTO agenda_revision(id,value) VALUES(0,0)")}
                }).build()
        // No destructive migration fallback: schema changes must ship an explicit migration.
    }
}

/** Keep Room lifecycle calls inside data; app does not depend on Room internals. */
fun closeLocalDatabase(database:AgendaDatabase) {database.close()}
