package com.agenda.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.agenda.app.design.*
import com.agenda.domain.*
import java.time.LocalDate
import kotlin.math.roundToInt

/** Page-local surfaces: no Dialog window, default dim, or whole-screen blur. */
@Composable
fun GlassOverlay(kind:String, data:AgendaSnapshot, repository:AgendaViewModel,
    glass:GlassSnapshot, onClose:()->Unit, onNavigate:(String)->Unit) {
    val dailyNotice by repository.dailyReminder.collectAsState()
    val editingTask=remember(kind) {data.tasks.find {kind=="edit-task:${it.id}"}}
    val editingObject=remember(kind) {data.objects.find {kind=="edit-object:${it.id}"}}
    val editingNote=remember(kind) {data.objects.flatMap {it.notes}.find {kind=="edit-note:${it.id}"}}
    val editing=editingTask!=null || editingObject!=null || editingNote!=null
    val initialValue=editingTask?.name ?: editingObject?.name ?: editingNote?.body.orEmpty()
    var value by rememberSaveable(kind) {mutableStateOf(initialValue)}
    var noteTitle by rememberSaveable(kind) {mutableStateOf(editingNote?.title.orEmpty())}
    var effort by rememberSaveable(kind) {mutableStateOf(editingTask?.effort)}
    var parent by rememberSaveable(kind) {mutableStateOf(editingTask?.objectId)}
    var dateText by rememberSaveable(kind) {mutableStateOf(editingTask?.due?.toString().orEmpty())}
    var wants by rememberSaveable(kind) {mutableStateOf(editingTask?.wants ?: false)}
    var should by rememberSaveable(kind) {mutableStateOf(editingTask?.should ?: false)}
    var more by rememberSaveable(kind) {mutableStateOf(false)}
    var selectedWeek by rememberSaveable(kind) {mutableStateOf(weekStart(LocalDate.now()))}
    var editingWeekId by rememberSaveable(kind) {mutableStateOf<String?>(null)}
    val weeklyInputFocus=remember {FocusRequester()}
    var tab by rememberSaveable(kind) {mutableIntStateOf(0)}
    var error by rememberSaveable(kind) {mutableStateOf<String?>(null)}
    var saving by remember(kind) {mutableStateOf(false)}
    var scheduleDirty by remember(kind) {mutableStateOf(false)}
    var scheduleSaving by remember(kind) {mutableStateOf(false)}
    var discard by rememberSaveable(kind) {mutableStateOf(false)}
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var importing by remember {mutableStateOf(false)}
    val collection=data.objects.find {it.id==kind.substringAfter(':',"")}
    val materialList=kind.startsWith("materials:")
    val noteList=kind.startsWith("notes:")
    val noteCreate=kind.startsWith("create-note:") || editingNote!=null
    val pickMaterial=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null && collection!=null) scope.launch {
            importing=true;error=null
            try {
                val added=repository.importMaterial(collection.id,uri)
                onNavigate("material-edit:${added.id}")
            } catch(c:kotlinx.coroutines.CancellationException) {throw c}
            catch(e:Exception) {error=tr("材料未保存，请检查可用空间或文件权限后重试")}
            finally {importing=false}
        }
    }
    val focusManager=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val objectMode=kind=="create-object" || editingObject!=null
    val creating=kind=="create" || objectMode || editingTask!=null
    val scheduling=kind.startsWith("schedule:") || kind.startsWith("schedule-for:") || kind.startsWith("edit-schedule:")
    val editedFields=value!=initialValue || noteTitle!=editingNote?.title.orEmpty() || effort!=editingTask?.effort || parent!=editingTask?.objectId ||
        dateText!=editingTask?.due?.toString().orEmpty() || wants!=(editingTask?.wants ?: false) || should!=(editingTask?.should ?: false)
    val dirty=scheduleDirty || (kind=="week" && more && value!=(data.weeklyPlans.find {it.id==editingWeekId}?.text ?: "")) ||
        (editing && editedFields) || (!editing && (creating || noteCreate) && (value.isNotBlank() || noteTitle.isNotBlank() || effort!=null || parent!=null || dateText.isNotEmpty() || wants || should))
    fun requestClose() {if(saving || scheduleSaving || importing || repository.backupBusy.value) return;if(dirty) discard=true else onClose()}
    BackHandler {requestClose()}
    val task=if(kind.startsWith("task:")) data.tasks.find {it.id==kind.removePrefix("task:")} else null
    val material=data.objects.flatMap {it.materials}.find {kind=="material:${it.id}" || kind=="material-edit:${it.id}"}
    val note=data.objects.flatMap {it.notes}.find {kind=="note:${it.id}"}
    val taskDetails=data.tasks.find {kind=="task-details:${it.id}"}
    val title=when {
        kind=="backup"->tr("备份与恢复")
        kind=="leisure"->tr("休闲活动")
        kind=="appearance"->tr("背景与文字")
        kind=="daily-reminder"->tr("近三天的截止提醒")
        kind.startsWith("delete:")->tr("删除确认")
        kind.startsWith("edit-schedule:")->tr("编辑日程")
        scheduling->tr("添加日程")
        taskDetails!=null->tr("任务说明与材料")
        editingNote!=null->tr("编辑说明")
        note!=null->note.title
        noteCreate->tr("添加说明")
        materialList->tr("{0} · 材料",collection?.name.orEmpty())
        noteList->tr("{0} · 说明",collection?.name.orEmpty())
        creating->if(editing) {if(objectMode) tr("编辑对象") else tr("编辑任务")} else if(objectMode) tr("新对象") else tr("新任务")
        kind=="search"->tr("搜索")
        kind=="week"->tr("周计划")
        task!=null->task.name
        material!=null->material.displayTitle
        else->tr("说明")
    }
    val ime=WindowInsets.ime.getBottom(LocalDensity.current)>0
    // The caller prepares the backdrop before mounting this surface, so the
    // first visible frame already has exactly the final glass material.
    val reveal=remember(kind) {Animatable(.94f)}
    LaunchedEffect(kind) {reveal.animateTo(1f,spring(dampingRatio=.82f,stiffness=420f))}
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha=.025f))
            .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,
                onClick={requestClose()}).semantics {contentDescription=tr("关闭浮层")})
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val top=when(kind) {"week"->54.dp;"search"->56.dp;else->12.dp}
            val bottom=if(ime) 12.dp else 78.dp
            val alignment=when(kind) {"week"->Alignment.TopEnd;"search"->Alignment.TopCenter;else->Alignment.BottomCenter}
            val maxPanelHeight=(maxHeight-top-(if(kind=="week" || kind=="search") 24.dp else bottom)).coerceAtLeast(120.dp)
            Box(modifier=Modifier.align(alignment).padding(start=12.dp,end=12.dp,
                    top=if(kind=="week" || kind=="search") top else 12.dp,
                    bottom=if(kind=="week" || kind=="search") 12.dp else bottom).graphicsLayer {
                    scaleX=reveal.value;scaleY=reveal.value
                    transformOrigin=when(kind) {"week"->TransformOrigin(1f,0f);"search"->TransformOrigin(.5f,0f);else->TransformOrigin(.5f,1f)}
                }) {
                GlassSurface(glass,Modifier.then(if(kind=="week") Modifier.widthIn(max=320.dp).fillMaxWidth(.90f) else Modifier.fillMaxWidth())
                    .heightIn(max=maxPanelHeight).semantics {paneTitle=title}) {
                    Column(Modifier.verticalScroll(rememberScrollState()).animateContentSize(spring(dampingRatio=.8f,stiffness=400f)).padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            AgendaText(title,title=!creating && material==null,size=if(creating) 13 else 17,
                                color=if(creating || material!=null) Ink.Muted else Ink.Primary,
                                maxLines=2,modifier=Modifier.weight(1f))
                            if(creating && !editing) TextButton({onNavigate(if(objectMode) "create" else "create-object")},enabled=!saving) {
                                AgendaText(if(objectMode) tr("创建任务") else tr("创建对象"),size=12,color=Ink.Muted)
                            }
                            MarkButton(Mark.CLOSE,tr("关闭"),{requestClose()})
                        }
                        when {
                            kind=="backup" -> BackupPanel(repository,{scheduleSaving=it})
                            kind=="leisure" -> LeisurePanel(data,repository,onNavigate,{scheduleDirty=it},{scheduleSaving=it})
                            kind=="appearance" -> AppearancePanel(data,repository,onClose,{scheduleDirty=it},{scheduleSaving=it})
                            kind=="daily-reminder" -> {
                                val notice=dailyNotice
                                val items=if(notice==null) emptyList() else reminderTasks(data.tasks,notice.first).filter {it.id in notice.second}
                                AgendaText(tr("明天起三个自然日内，留意这些未完成任务。"),size=13,color=Ink.Muted,modifier=Modifier.padding(vertical=12.dp))
                                items.forEach {TaskLine(it,repository,{id->onNavigate("task:$id")},date=true)}
                                if(items.isEmpty()) AgendaText(tr("目前没有待提醒的任务"),size=13,color=Ink.Muted)
                            }
                            kind.startsWith("delete:") -> DeleteConfirmation(kind,repository,onClose,{scheduleSaving=it})
                            taskDetails!=null -> TaskDetailsPanel(taskDetails,data,repository,{onNavigate("task:${taskDetails.id}")},{scheduleDirty=it},{scheduleSaving=it})
                            scheduling -> ScheduleComposer(kind,data,repository,onClose,{scheduleDirty=it},{scheduleSaving=it},{onNavigate("delete:schedule:$it")})
                            materialList || noteList -> {
                                TextButton({onNavigate("edit-object:${collection?.id}")}) {AgendaText(tr("编辑对象"),size=13,color=Ink.Primary)}
                                TextButton({onNavigate("delete:object:${collection?.id}")}) {AgendaText(tr("删除对象"),size=13,color=Ink.Danger)}
                                if(materialList) {
                                    collection?.materials?.forEach {item->
                                        AgendaText(item.displayTitle,size=14,modifier=Modifier.fillMaxWidth().clickable {onNavigate("material:${item.id}")}.padding(vertical=14.dp))
                                    }
                                    Button(onClick={pickMaterial.launch(arrayOf("*/*"))},enabled=!importing,shape=RoundedCornerShape(11.dp),modifier=Modifier.padding(top=12.dp)) {
                                        AgendaIcon(Mark.PLUS,Ink.Surface);Spacer(Modifier.width(8.dp))
                                        AgendaText(if(importing) tr("正在复制…") else tr("添加材料"),color=Ink.Surface)
                                    }
                                    AgendaText(tr("选择文件后保存一份副本。"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=10.dp))
                                } else {
                                    collection?.notes?.forEach {item->AgendaText(item.title,size=14,
                                        modifier=Modifier.fillMaxWidth().clickable {onNavigate("note:${item.id}")}.padding(vertical=14.dp))}
                                    Button(onClick={onNavigate("create-note:${collection?.id}")},shape=RoundedCornerShape(11.dp),modifier=Modifier.padding(top=12.dp)) {
                                        AgendaIcon(Mark.PLUS,Ink.Surface);Spacer(Modifier.width(8.dp));AgendaText(tr("添加说明"),color=Ink.Surface)
                                    }
                                }
                                error?.let {AgendaText(it,size=12,color=Ink.Danger,modifier=Modifier.padding(top=10.dp))}
                            }
                            note!=null -> {
                                AgendaText(note.body,size=14,modifier=Modifier.padding(vertical=16.dp))
                                TextButton({onNavigate("edit-note:${note.id}")}) {AgendaText(tr("编辑说明"),size=13,color=Ink.Primary)}
                                TextButton({onNavigate("delete:note:${note.id}")}) {AgendaText(tr("删除说明"),size=13,color=Ink.Danger)}
                            }
                            noteCreate -> {
                                AgendaText(tr("标题"),size=12,color=Ink.Muted,modifier=Modifier.padding(bottom=6.dp))
                                AgendaOutlinedTextField(noteTitle,{noteTitle=it;error=null},placeholder={AgendaText(tr("给这份说明起个名字"),color=Ink.Muted)},
                                    modifier=Modifier.fillMaxWidth().semantics {contentDescription=tr("说明标题")},
                                    shape=RoundedCornerShape(14.dp),singleLine=true)
                                AgendaText(tr("正文"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=16.dp,bottom=6.dp))
                                AgendaOutlinedTextField(value,{value=it},placeholder={AgendaText(tr("记下说明…"),color=Ink.Muted)},
                                    modifier=Modifier.fillMaxWidth().heightIn(min=140.dp).semantics {contentDescription=tr("说明内容")},
                                    shape=RoundedCornerShape(14.dp),minLines=3)
                                Button(onClick={
                                    if(noteTitle.isBlank()) error=tr("请先填写说明标题")
                                    else if(value.isBlank()) error=tr("请先填写说明正文")
                                    else if(collection!=null || editingNote!=null) {
                                        val ownerId=collection?.id;val titleToSave=noteTitle;val bodyToSave=value
                                        saving=true
                                        scope.launch {
                                            try {
                                                if(editingNote!=null) {repository.updateNote(editingNote.id,titleToSave,bodyToSave);onClose()}
                                                else if(ownerId!=null) {repository.addNote(ownerId,titleToSave,bodyToSave);onNavigate("notes:$ownerId")}
                                            }
                                            catch(c:kotlinx.coroutines.CancellationException) {throw c}
                                            catch(e:Exception) {error=saveError(e)}
                                            finally {saving=false}
                                        }
                                    }
                                },enabled=!saving,shape=RoundedCornerShape(11.dp),modifier=Modifier.align(Alignment.End).padding(top=14.dp)) {AgendaText(if(saving) tr("保存中…") else tr("保存说明"),color=Ink.Surface)}
                                error?.let {AgendaText(it,size=12,color=Ink.Danger)}
                            }
                            creating -> {
                                AgendaInput(value,{value=it;error=null},tr("名称"),if(objectMode) tr("记下一个对象…") else tr("记下一件事…"),size=21)
                                if(objectMode) {
                                    AgendaText(tr("课程、项目，以及你想放在一起的事情。"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=16.dp))
                                } else {
                                    Row(Modifier.fillMaxWidth().padding(top=16.dp),verticalAlignment=Alignment.CenterVertically) {
                                        AgendaText(tr("适合怎么做"),size=12,color=Ink.Muted,modifier=Modifier.weight(1f))
                                        AgendaText(tr("可跳过"),size=11,color=Ink.Muted)
                                    }
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                        Effort.entries.forEach {item->
                                            OptionChip(tr(item.label),effort==item,{effort=if(effort==item) null else item},Modifier.weight(1f))
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth().padding(top=14.dp),verticalAlignment=Alignment.CenterVertically) {
                                    if(!objectMode) TextButton({
                                        focusManager.clearFocus();keyboard?.hide();more=!more
                                    },contentPadding=PaddingValues(horizontal=0.dp)) {
                                        AgendaIcon(if(more) Mark.CLOSE else Mark.PLUS,Ink.Muted,Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        AgendaText(tr("归属、日期、意愿"),size=12,color=Ink.Muted)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Button(onClick={
                                        if(value.isBlank()) error=tr("请先填写名称")
                                        else {
                                            val due=if(dateText.isBlank()) null else runCatching {LocalDate.parse(dateText)}.getOrNull()
                                            if(!objectMode && dateText.isNotBlank() && due==null) {
                                                error=tr("请填写有效日期，例如 2026-11-03"); more=true;tab=1
                                            } else {
                                                val objectName=value
                                                val draft=TaskDraft(value,effort,parent,due,wants,should)
                                                saving=true
                                                scope.launch {
                                                    try {
                                                        if(editingObject!=null) repository.renameObject(editingObject.id,objectName)
                                                        else if(editingTask!=null) repository.updateTask(editingTask.id,draft)
                                                        else if(objectMode) repository.createObject(objectName)
                                                        else repository.createTask(draft)
                                                        onClose()
                                                    } catch(c:kotlinx.coroutines.CancellationException) {throw c}
                                                    catch(e:Exception) {error=saveError(e)}
                                                    finally {saving=false}
                                                }
                                            }
                                        }
                                    },enabled=!saving,shape=RoundedCornerShape(11.dp),contentPadding=PaddingValues(horizontal=14.dp,vertical=11.dp)) {
                                        AgendaText(if(saving) tr("保存中…") else if(editing) tr("保存修改") else tr("创建"),color=Ink.Surface,size=13)
                                        Spacer(Modifier.width(8.dp)); AgendaIcon(if(editing) Mark.CHECK else Mark.PLUS,Ink.Surface,Modifier.size(17.dp))
                                    }
                                }
                                val summary=listOfNotNull(data.objects.find {it.id==parent}?.name,
                                    dateText.takeIf {it.isNotBlank()}?.let {tr("截止 {0}",it)},
                                    if(wants) tr("想做") else null,if(should) tr("该做") else null).joinToString(" · ")
                                if(!objectMode && summary.isNotEmpty()) AgendaText(summary,size=12,color=Ink.Muted,modifier=Modifier.padding(top=10.dp))
                                if(more && !objectMode) {
                                    Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                        listOf(tr("归属"),tr("日期"),tr("意愿")).forEachIndexed {index,label->
                                            OptionChip(label,tab==index,{tab=index},Modifier.weight(1f))
                                        }
                                    }
                                    when(tab) {
                                        0->Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                            OptionChip(tr("独立任务"),parent==null,{parent=null})
                                            data.objects.forEach {obj->OptionChip(obj.name,parent==obj.id,{parent=obj.id})}
                                        }
                                        1->{
                                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                                listOf(tr("今天") to 0L,tr("明天") to 1L,tr("三天后") to 3L).forEach {(label,offset)->
                                                    val date=LocalDate.now().plusDays(offset).toString()
                                                    OptionChip(label,dateText==date,{dateText=date;error=null},Modifier.weight(1f))
                                                }
                                            }
                                            DeadlineCalendar(dateText,{dateText=it;error=null})
                                        }
                                        2->{
                                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                                OptionChip(tr("想做"),wants,{wants=!wants},Modifier.weight(1f))
                                                OptionChip(tr("该做"),should,{should=!should},Modifier.weight(1f))
                                            }
                                            AgendaText(tr("可以同时选择，也可以都不选。"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=8.dp))
                                        }
                                    }
                                }
                                error?.let {AgendaText(it,size=12,color=Ink.Danger,modifier=Modifier.padding(top=10.dp).semantics {liveRegion=LiveRegionMode.Polite})}
                            }
                            kind=="search" -> {
                                AgendaInput(value,{value=it},tr("搜索内容"),tr("对象、任务、材料、说明"),size=15)
                                Spacer(Modifier.height(16.dp))
                                if(value.isBlank()) AgendaText(tr("输入关键词，让内容浮现"),size=13,color=Ink.Muted)
                                else {
                                    val matches=buildList<Pair<String,String>> {
                                        data.tasks.filter {it.name.contains(value,true) || it.description.contains(value,true)}.forEach {add(it.name to "task:${it.id}")}
                                        data.objects.forEach {obj->
                                            if(obj.name.contains(value,true)) add(obj.name to "focus-object:${obj.id}")
                                            obj.materials.filter {it.name.contains(value,true) || it.title.contains(value,true) || it.description.contains(value,true)}.forEach {add(it.displayTitle to "material:${it.id}")}
                                            obj.notes.filter {it.title.contains(value,true) || it.body.contains(value,true)}.forEach {add(it.title to "note:${it.id}")}
                                        }
                                    }
                                    if(matches.isEmpty()) AgendaText(tr("没有找到相关内容"),size=13,color=Ink.Muted)
                                    matches.forEach {(label,target)->AgendaText(label,modifier=Modifier.fillMaxWidth().clickable {onNavigate(target)}.padding(vertical=14.dp))}
                                }
                            }
                            task!=null -> {
                                if(task.description.isNotBlank()) AgendaText(task.description,size=14,modifier=Modifier.padding(vertical=12.dp))
                                val linked=data.objects.flatMap {it.materials}.filter {it.id in task.materialIds}
                                if(linked.isNotEmpty()) {
                                    AgendaText(tr("关联材料"),size=12,color=Ink.Muted,modifier=Modifier.padding(top=12.dp))
                                    linked.forEach {item->TextButton({onNavigate("material:${item.id}")}) {AgendaText(item.displayTitle,size=14,color=Ink.Primary)}}
                                }
                                TextButton({onNavigate("task-details:${task.id}")}) {AgendaText(tr("编辑说明与关联材料"),size=13,color=Ink.Primary)}
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(task.completed,{repository.setCompleted(task.id,it)},colors=CheckboxDefaults.colors(
                                        checkedColor=Ink.Primary,uncheckedColor=Ink.Control),modifier=Modifier.semantics {contentDescription=tr("完成：{0}",task.name)})
                                    AgendaText(if(task.completed) tr("已完成") else tr("标记为完成"),size=13,color=Ink.Muted)
                                }
                                task.due?.let {AgendaText(tr("截止日期：{0} 年 {1} 月 {2} 日",it.year,it.monthValue,it.dayOfMonth),size=13,modifier=Modifier.padding(vertical=8.dp))}
                                AgendaText(task.effort?.label?.let {tr(it)} ?: tr("未分类"),size=13,color=Ink.Muted)
                                data.objects.find {it.id==task.objectId}?.let {AgendaText(tr("归属：{0}",it.name),size=13,color=Ink.Muted,modifier=Modifier.padding(top=8.dp))}
                                val intentions=listOfNotNull(if(task.wants) tr("想做") else null,if(task.should) tr("该做") else null).joinToString(" · ")
                                if(intentions.isNotEmpty()) AgendaText(intentions,size=13,color=Ink.Muted,modifier=Modifier.padding(top=8.dp))
                                Button({onNavigate("schedule-for:${task.id}")},shape=RoundedCornerShape(11.dp),modifier=Modifier.padding(top=16.dp)) {AgendaText(tr("安排日程"),color=Ink.Surface)}
                                TextButton({onNavigate("edit-task:${task.id}")}) {AgendaText(tr("编辑任务"),size=13,color=Ink.Primary)}
                                TextButton({onNavigate("delete:task:${task.id}")}) {AgendaText(tr("删除任务"),size=13,color=Ink.Danger)}
                            }
                            material!=null -> {
                                MaterialDetails(material,repository,kind.startsWith("material-edit:"),{scheduleDirty=it},{scheduleSaving=it},
                                    onDelete={onNavigate("delete:material:${material.id}")}) {
                                    if(material.isImage) onNavigate("image:${material.id}")
                                    else scope.launch {
                                    try {
                                        val document=repository.materialDocument(material.id)
                                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                            .setDataAndType(document.uri,document.mimeType).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    } catch(c:kotlinx.coroutines.CancellationException) {throw c}
                                    catch(e:android.content.ActivityNotFoundException) {error=tr("手机上没有可打开此文件的应用")}
                                    catch(e:Exception) {error=tr("材料副本暂时无法打开，请稍后重试")}
                                    }
                                }
                                error?.let {AgendaText(it,size=12,color=Ink.Danger,modifier=Modifier.padding(top=10.dp))}
                            }
                            kind=="week" -> {
                                val end=selectedWeek.plusDays(6)
                                AgendaText(tr("{0} 年 {1} 月 {2} 日 — {3} 月 {4} 日",selectedWeek.year,selectedWeek.monthValue,selectedWeek.dayOfMonth,end.monthValue,end.dayOfMonth),
                                    size=12,color=Ink.Muted,modifier=Modifier.padding(top=8.dp))
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                    TextButton({selectedWeek=selectedWeek.minusWeeks(1)},enabled=!more && !saving) {AgendaText(tr("上一周"),size=12)}
                                    TextButton({selectedWeek=weekStart(LocalDate.now())},enabled=!more && !saving) {AgendaText(tr("本周"),size=12)}
                                    TextButton({selectedWeek=selectedWeek.plusWeeks(1)},enabled=!more && !saving) {AgendaText(tr("下一周"),size=12)}
                                }
                                val items=data.weeklyPlans.filter {it.weekStart==selectedWeek}
                                if(items.isEmpty()) AgendaText(tr("这一周还没有计划"),size=13,color=Ink.Muted,modifier=Modifier.padding(vertical=16.dp))
                                items.forEach {item->
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                        Checkbox(item.completed,{repository.setWeeklyPlanCompleted(item.id,it)},enabled=!more && !saving,
                                            colors=CheckboxDefaults.colors(checkedColor=Ink.Primary,uncheckedColor=Ink.Control),
                                            modifier=Modifier.semantics {contentDescription=tr("完成周计划：{0}",item.text)})
                                        AgendaText(item.text,size=14,color=if(item.completed) Ink.Muted else Ink.Text,modifier=Modifier.weight(1f))
                                        TextButton({editingWeekId=item.id;value=item.text;more=true;error=null},enabled=!more && !saving,
                                            modifier=Modifier.semantics {contentDescription=tr("编辑周计划：{0}",item.text)}) {AgendaText(tr("编辑"),size=12,color=Ink.Primary)}
                                        TextButton({onNavigate("delete:weekly:${item.id}")},enabled=!more && !saving,
                                            modifier=Modifier.semantics {contentDescription=tr("删除周计划：{0}",item.text)}) {AgendaText(tr("删除"),size=12,color=Ink.Danger)}
                                    }
                                }
                                if(!more) {
                                    Button({editingWeekId=null;value="";more=true},shape=RoundedCornerShape(11.dp),modifier=Modifier.padding(top=12.dp)) {
                                        AgendaIcon(Mark.PLUS,Ink.Surface);Spacer(Modifier.width(8.dp));AgendaText(tr("添加计划"),color=Ink.Surface)
                                    }
                                    AgendaText(tr("本周独立清单"),size=11,color=Ink.Muted,modifier=Modifier.padding(top=10.dp))
                                } else {
                                    LaunchedEffect(Unit) {weeklyInputFocus.requestFocus()}
                                    AgendaOutlinedTextField(value,{value=it;error=null},label={AgendaText(tr("计划内容"),size=12)},
                                        placeholder={AgendaText(tr("这一周想做什么…"),size=14,color=Ink.Muted)},
                                        modifier=Modifier.fillMaxWidth().focusRequester(weeklyInputFocus).semantics {contentDescription=tr("周计划内容")},
                                        enabled=!saving,isError=error!=null,shape=RoundedCornerShape(14.dp),minLines=2,maxLines=5)
                                    error?.let {AgendaText(it,size=12,color=Ink.Danger,modifier=Modifier.padding(top=8.dp).semantics {liveRegion=LiveRegionMode.Polite})}
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                                        TextButton({more=false;editingWeekId=null;value="";error=null;keyboard?.hide()},enabled=!saving) {AgendaText(tr("取消"),size=13)}
                                        Button({
                                            if(value.isBlank()) {error=tr("请先填写计划内容");weeklyInputFocus.requestFocus()}
                                            else {
                                                val text=value;val week=selectedWeek;val editId=editingWeekId;saving=true;error=null
                                                scope.launch {
                                                    try {
                                                        if(editId!=null) repository.updateWeeklyPlan(editId,text) else repository.addWeeklyPlan(week,text)
                                                        value="";more=false;editingWeekId=null;keyboard?.hide();focusManager.clearFocus()
                                                    }
                                                    catch(c:kotlinx.coroutines.CancellationException) {throw c}
                                                    catch(e:Exception) {error=saveError(e)}
                                                    finally {saving=false}
                                                }
                                            }
                                        },enabled=!saving,shape=RoundedCornerShape(11.dp)) {AgendaText(if(saving) tr("保存中…") else tr("保存计划"),color=Ink.Surface)}
                                    }
                                }
                            }
                            else -> AgendaText(if(kind.startsWith("info:")) kind.removePrefix("info:") else tr("这项内容已不存在，请关闭后重新选择。"),size=13,color=Ink.Muted,modifier=Modifier.padding(vertical=12.dp))
                        }
                        if(discard) {
                            HorizontalDivider(Modifier.padding(top=16.dp),color=Ink.Divider)
                            AgendaText(tr("还有未保存的内容，要放弃吗？"),size=13,modifier=Modifier.padding(top=12.dp))
                            Row {
                                TextButton({discard=false}) {AgendaText(tr("继续编辑"),size=13,color=Ink.Primary)}
                                TextButton(onClose) {AgendaText(tr("放弃编辑"),size=13,color=Ink.Primary)}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassSurface(glass:GlassSnapshot,modifier:Modifier=Modifier,content:@Composable ()->Unit) {
    var origin by remember {mutableStateOf(Offset.Zero)}
    val firstFrame=remember {java.util.concurrent.atomic.AtomicBoolean(true)}
    val shape=RoundedCornerShape(22.dp)
    Box(modifier.onGloballyPositioned {origin=it.positionInRoot()}
        .shadow(20.dp,shape,ambientColor=Ink.Primary.copy(alpha=.25f),spotColor=Ink.Primary.copy(alpha=.35f))
        .border(1.dp,Color.White.copy(alpha=.88f),shape).clip(shape)
        .clickable(interactionSource=remember {MutableInteractionSource()},indication=null,onClick={})) {
        Canvas(Modifier.matchParentSize()) {
            if(BuildConfig.DEBUG && firstFrame.compareAndSet(true,false)) {
                android.util.Log.d("AgendaGlassFirstFrame","${android.os.SystemClock.elapsedRealtime()}|prepared=true")
            }
            glass.let {
                drawImage(it.image,dstOffset=IntOffset(-origin.x.roundToInt(),-origin.y.roundToInt()),
                    dstSize=IntSize(it.sourceWidth,it.sourceHeight),filterQuality=FilterQuality.Medium,
                    colorFilter=ColorFilter.colorMatrix(ColorMatrix().apply {setToSaturation(1.15f)}))
            }
        }
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(
            Ink.Surface.copy(alpha=.32f),
            Ink.Surface.copy(alpha=.16f)))))
        content()
    }
}

@Composable
internal fun OptionChip(label:String,selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    Box(modifier.heightIn(min=48.dp).padding(vertical=4.dp).clip(RoundedCornerShape(12.dp))
        .background(if(selected) (if(InterfaceSettings.blue) Meadow.Green else Ink.Primary) else Ink.Surface.copy(alpha=.62f))
        .clickable(onClick=onClick).semantics {this.selected=selected},contentAlignment=Alignment.Center) {
        AgendaText(label,size=13,color=if(selected) (if(InterfaceSettings.blue) Meadow.Text else Ink.Surface) else Ink.Muted,
            modifier=Modifier.padding(horizontal=8.dp,vertical=10.dp))
    }
}

private fun Modifier.borderBottom(color:Color):Modifier = this.drawBehind {
    drawLine(color,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())
}

@Composable
internal fun AgendaInput(value:String,onChange:(String)->Unit,label:String,placeholder:String,size:Int=16) {
    BasicTextField(value,onChange,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)
        .borderBottom(Ink.Divider).padding(vertical=10.dp).semantics {contentDescription=label},
        textStyle=TextStyle(fontFamily=BodyFont,fontSize=size.sp,color=Ink.Text,lineHeight=(size*1.45f).sp,fontFeatureSettings="tnum",fontSynthesis=androidx.compose.ui.text.font.FontSynthesis.None),
        cursorBrush=SolidColor(Ink.Primary),singleLine=true,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
        visualTransformation=agendaInputTransformation(),
        decorationBox={inner->Box {if(value.isEmpty()) AgendaText(placeholder,size=size,color=Ink.Muted);inner()}})
}

