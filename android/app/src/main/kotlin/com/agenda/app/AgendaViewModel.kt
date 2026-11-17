package com.agenda.app

import com.agenda.app.design.tr

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agenda.data.local.LocalMaterialStore
import com.agenda.data.local.MaterialDocument
import com.agenda.data.local.*
import com.agenda.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun saveError(error:Throwable):String=when(error) {
    is AgendaFailure.DuplicateSchedule->tr("这条任务已安排在同一日期、时段和时间，请调整后重试")
    is AgendaFailure.NotFound->tr("这项内容已不存在，请重新选择")
    is AgendaFailure.InvalidInput,is IllegalArgumentException->tr("请检查填写的内容")
    is AgendaFailure.FileUnavailable->tr("材料副本暂时无法读取，请检查文件后重试")
    is AgendaFailure.UndoExpired->tr("撤销时间已结束")
    is AgendaFailure.UndoConflict->tr("关联内容已变化，暂时无法撤销；可先撤销相关删除后重试")
    is AgendaFailure.WorkspaceChanged->tr("数据集已切换，请重新打开应用")
    is AgendaFailure.DeleteChanged->tr("内容已变化，请重新核对删除范围")
    else->tr("保存失败，内容已保留，请重试")
}

class AgendaViewModel(private val repository:AgendaRepository,private val preferencesRepository:PreferencesRepository,
    private val materialStore:LocalMaterialStore,private val deletionRepository:DeletionRepository,
    private val backupStore:LocalBackupStore,private val features:DailyFeatureRepository,
    private val visualAssets:VisualAssetStore):ViewModel() {
    private val foregroundState=MutableStateFlow(false)
    val foreground=foregroundState.asStateFlow()
    private val reminderState=MutableStateFlow<Pair<java.time.LocalDate,List<String>>?>(null)
    val dailyReminder=reminderState.asStateFlow()
    private val reminderSessionDates=mutableSetOf<java.time.LocalDate>()
    private val working=MutableStateFlow(false)
    val backupBusy=working.asStateFlow()
    private val progress=MutableStateFlow("")
    val backupProgress=progress.asStateFlow()
    private val backupFailure=MutableStateFlow<String?>(null)
    val backupError=backupFailure.asStateFlow()
    private val backupNotice=MutableStateFlow<String?>(null)
    val backupStatus=backupNotice.asStateFlow()
    private val prepared=MutableStateFlow<PreparedBackup?>(null)
    val preparedBackup=prepared.asStateFlow()
    private val recovery=MutableStateFlow<List<RecoverySnapshot>>(emptyList())
    val recoverySnapshots=recovery.asStateFlow()
    private val switched=MutableStateFlow(false)
    val restoreApplied=switched.asStateFlow()
    private val data=MutableStateFlow(EmptyAgenda)
    val snapshot=data.asStateFlow()
    private val prefs=MutableStateFlow(UserPreferences())
    val preferences=prefs.asStateFlow()
    private val loaded=MutableStateFlow(false)
    val ready=loaded.asStateFlow()
    private val failure=MutableStateFlow<String?>(null)
    val loadError=failure.asStateFlow()
    private val messages=MutableSharedFlow<String>(extraBufferCapacity=4)
    val errors=messages.asSharedFlow()
    private val pending=MutableStateFlow<List<PendingDeletion>>(emptyList())
    val pendingDeletions=pending.asStateFlow()
    private val undoInFlight=mutableSetOf<String>()
    private var observer:Job?=null
    init {
        reload()
        viewModelScope.launch {
            while(isActive) {
                delay(250)
                val now=System.currentTimeMillis()
                if(pending.value.any {now<it.createdAt || now>=it.expiresAt}) {
                    try {deletionRepository.expireDeletions();materialStore.recoverOrphans()}
                    catch(c:CancellationException) {throw c}
                    catch(e:Exception) {delay(1_000)}
                }
            }
        }
    }
    fun reload() {
        observer?.cancel();failure.value=null
        observer=viewModelScope.launch {
            try {
                features.initialize()
                deletionRepository.expireDeletions()
                materialStore.recoverOrphans()
                visualAssets.recoverOrphans()
                combine(repository.snapshot,preferencesRepository.preferences,deletionRepository.pendingDeletions) {s,p,d->Triple(s,p,d)}.collect {(s,p,d)->
                    data.value=s;prefs.value=p;pending.value=d;loaded.value=true
                }
            } catch(c:CancellationException) {throw c}
            catch(e:Exception) {failure.value=tr("暂时无法读取本机数据，请重试");loaded.value=false}
        }
    }
    private fun command(action:suspend ()->Unit) {viewModelScope.launch {
        try {action()} catch(c:CancellationException) {throw c} catch(e:Exception) {messages.emit(saveError(e))}
    }}
    fun setCompleted(id:String,completed:Boolean)=command {repository.setCompleted(id,completed)}
    fun setWeeklyPlanCompleted(id:String,completed:Boolean)=command {repository.setWeeklyPlanCompleted(id,completed)}
    suspend fun addWeeklyPlan(week:java.time.LocalDate,text:String)=commit {repository.addWeeklyPlan(week,text)}
    fun setScheduleCompleted(id:String,completed:Boolean)=command {repository.setScheduleCompleted(id,completed)}
    // Writes belong to the ViewModel. Activity recreation can cancel an awaiting
    // form without interrupting a committed transaction or submitting a second one.
    private suspend fun <T> commit(action:suspend ()->T):T=viewModelScope.async {action()}.await()
    suspend fun createTask(draft:TaskDraft)=commit {repository.createTask(draft)}
    suspend fun createObject(name:String)=commit {repository.createObject(name)}
    suspend fun addNote(objectId:String,title:String,body:String)=commit {repository.addNote(objectId,title,body)}
    suspend fun createSchedule(draft:ScheduleDraft)=commit {repository.createSchedule(draft)}
    suspend fun importMaterial(objectId:String,uri:Uri)=commit {materialStore.import(objectId,uri)}
    suspend fun materialDocument(id:String):MaterialDocument=materialStore.document(id)
    suspend fun materialImage(id:String)=materialStore.image(id)
    suspend fun updateMaterial(id:String,title:String,description:String)=commit {repository.updateMaterial(id,title,description)}
    suspend fun updateTask(id:String,draft:TaskDraft)=commit {repository.updateTask(id,draft)}
    suspend fun renameObject(id:String,name:String)=commit {repository.renameObject(id,name)}
    suspend fun updateNote(id:String,title:String,body:String)=commit {repository.updateNote(id,title,body)}
    suspend fun updateSchedule(id:String,draft:ScheduleDraft)=commit {repository.updateSchedule(id,draft)}
    suspend fun updateWeeklyPlan(id:String,text:String)=commit {repository.updateWeeklyPlan(id,text)}
    fun moveCard(id:String,column:Int,row:Int)=command {features.moveCard(id,column,row)}
    suspend fun saveTaskDetails(id:String,description:String,materialIds:List<String>)=commit {features.saveTaskDetails(id,description,materialIds)}
    suspend fun saveLeisure(id:String?,text:String)=commit {features.saveLeisure(id,text)}
    suspend fun saveAppearance(color:Long,text:String,useImage:Boolean,uri:Uri?)=commit {visualAssets.save(color,text,useImage,uri)}
    suspend fun appearanceImage(id:String)=visualAssets.image(id)
    fun onForeground(value:Boolean) {foregroundState.value=value}
    fun dismissDailyReminder() {reminderState.value=null}
    fun checkDailyReminder(today:java.time.LocalDate) {
        if(today!=java.time.LocalDate.now() || !foregroundState.value || working.value || !reminderSessionDates.add(today)) return
        viewModelScope.launch {
            try {features.claimReminder(today)?.takeIf {it.isNotEmpty()}?.let {reminderState.value=today to it}}
            catch(c:CancellationException) {throw c}
            catch(e:Exception) {
                if(e !is AgendaFailure.WorkspaceChanged) {
                    val ids=reminderTasks(data.value.tasks,today).map {it.id}
                    if(ids.isNotEmpty()) reminderState.value=today to ids
                    messages.emit(tr("今日提醒记录暂未保存，本次使用仍只提醒一次"))
                }
            }
        }
    }
    suspend fun previewDelete(target:DeleteTarget)=deletionRepository.previewDelete(target)
    suspend fun delete(preview:DeletePreview)=commit {deletionRepository.delete(preview)}
    private fun backupAction(action:suspend ()->Unit) {
        if(working.value) return
        working.value=true;backupFailure.value=null;backupNotice.value=null
        viewModelScope.launch {
            try {action()}
            catch(c:CancellationException) {throw c}
            catch(e:Exception) {backupFailure.value=backupErrorMessage(e)}
            finally {
                runCatching {recovery.value=backupStore.snapshots()}
                working.value=false;progress.value=""
            }
        }
    }
    fun refreshRecoverySnapshots() {viewModelScope.launch {runCatching {recovery.value=backupStore.snapshots()}}}
    fun exportBackup(uri:Uri,snapshotId:String?=null)=backupAction {
        if(snapshotId==null) backupStore.export(uri) {progress.value=it}
        else backupStore.exportSnapshot(snapshotId,uri) {progress.value=it}
        backupNotice.value=tr("备份已导出")
    }
    fun prepareBackup(uri:Uri)=backupAction {
        prepared.value?.let {backupStore.discard(it)};prepared.value=null
        prepared.value=backupStore.prepare(uri) {progress.value=it}
    }
    fun prepareRecoverySnapshot(id:String)=backupAction {
        prepared.value?.let {backupStore.discard(it)};prepared.value=null
        prepared.value=backupStore.prepareSnapshot(id) {progress.value=it}
    }
    fun cancelPreparedBackup()=backupAction {prepared.value?.let {backupStore.discard(it)};prepared.value=null}
    fun restoreBackup() {
        val value=prepared.value ?: return
        backupAction {
            try {
                backupStore.restore(value) {progress.value=it}
                prepared.value=null;switched.value=true
            } catch(e:Exception) {
                prepared.value=null;backupStore.discard(value);throw e
            }
        }
    }
    fun undoDelete(token:String) {
        if(!undoInFlight.add(token)) return
        viewModelScope.launch {
            try {deletionRepository.undoDelete(token)}
            catch(c:CancellationException) {throw c}
            catch(e:Exception) {messages.emit(saveError(e))}
            finally {undoInFlight.remove(token)}
        }
    }
    suspend fun savePreferences(preferences:UserPreferences) {
        try {preferencesRepository.save(preferences)}
        catch(c:CancellationException) {throw c}
        catch(e:Exception) {messages.emit(tr("视图设置未保存，请稍后重试"))}
    }
}

private fun backupErrorMessage(error:Throwable):String=when(error) {
    is BackupException->when(error.problem) {
        BackupProblem.INVALID->tr("备份损坏、内容不完整或校验失败，当前数据未改变")
        BackupProblem.UNSUPPORTED->tr("暂不支持这个备份版本，当前数据未改变")
        BackupProblem.LIMIT->tr("备份超过本版大小或条目上限：附件合计 2GB、元数据 8MB、记录 10 万条")
        BackupProblem.NO_SPACE->tr("本机空间不足，请释放空间后重试")
        BackupProblem.PENDING_UNDO->tr("还有可撤销的删除，请先撤销或等待提示结束后重试")
        BackupProblem.STALE->tr("这份备份的暂存已失效，请重新选择")
        BackupProblem.SNAPSHOT_FAILED->tr("恢复前快照未能保存，恢复已停止，当前数据保留")
        BackupProblem.SWITCH_FAILED->tr("数据切换未完成，原数据集与恢复前快照已保留，请重新打开应用")
    }
    is AgendaFailure.FileUnavailable->tr("材料副本无法读取或校验不一致，操作未完成")
    else->tr("操作未完成，请检查文件权限和可用空间后重试")
}
