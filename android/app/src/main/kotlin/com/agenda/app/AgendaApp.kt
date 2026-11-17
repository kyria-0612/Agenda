package com.agenda.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.*
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

@Composable
fun AgendaApp(repository: AgendaViewModel,onDatasetChanged:()->Unit={}) {
    val data by repository.snapshot.collectAsState()
    val preferences by repository.preferences.collectAsState()
    val pendingDeletes by repository.pendingDeletions.collectAsState()
    val ready by repository.ready.collectAsState()
    val loadError by repository.loadError.collectAsState()
    val restored by repository.restoreApplied.collectAsState()
    val foreground by repository.foreground.collectAsState()
    val reminder by repository.dailyReminder.collectAsState()
    val backupWorking by repository.backupBusy.collectAsState()
    LaunchedEffect(restored) {if(restored) onDatasetChanged()}
    if(!ready) {
        Box(Modifier.fillMaxSize().background(Ink.Canvas).statusBarsPadding(),contentAlignment=Alignment.Center) {
            if(loadError==null) CircularProgressIndicator(color=Ink.Primary)
            else Column(horizontalAlignment=Alignment.CenterHorizontally) {
                AgendaText(loadError!!,color=Ink.Muted)
                TextButton({repository.reload()}) {AgendaText(tr("重试"),color=Ink.Primary)}
            }
        }
        return
    }
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(repository) {repository.errors.collect {snackbar.showSnackbar(it)}}
    var page by rememberSaveable { mutableIntStateOf(preferences.page) }
    var board by rememberSaveable { mutableStateOf(preferences.board) }
    var fullDays by rememberSaveable { mutableStateOf(preferences.fullDays) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    var focusObject by remember {mutableStateOf<String?>(null)}
    val pageStates=rememberSaveableStateHolder()
    LaunchedEffect(page,board,fullDays) {repository.savePreferences(UserPreferences(page,board,fullDays))}
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while(true) { today = LocalDate.now(); delay(15_000) } }
    BackHandler(page != 0 && overlay == null) { page = 0 }
    val backdrop = rememberGraphicsLayer()
    // Restore the route and its saved form only after a fresh backdrop is ready.
    var pendingOverlay by remember {mutableStateOf(overlay)}
    var preparedGlass by remember {mutableStateOf<GlassSnapshot?>(null)}
    val blurRadius=with(androidx.compose.ui.platform.LocalDensity.current) {9.dp.toPx()}
    fun showOverlay(target:String) {
        if(target.startsWith("focus-object:")) {
            focusObject=target.removePrefix("focus-object:");page=0;board=false
            overlay=null;pendingOverlay=null;preparedGlass=null;return
        }
        if(target=="search") focusObject=null
        if(overlay=="daily-reminder" && target!="daily-reminder") repository.dismissDailyReminder()
        if(overlay!=null && preparedGlass!=null) overlay=target
        else pendingOverlay=target
    }
    LaunchedEffect(foreground) {if(foreground) today=LocalDate.now()}
    LaunchedEffect(today,foreground,overlay,pendingOverlay,backupWorking) {
        if(foreground && overlay==null && pendingOverlay==null && !backupWorking) repository.checkDailyReminder(today)
    }
    LaunchedEffect(reminder,today,foreground,overlay,pendingOverlay,backupWorking,data.tasks) {
        val notice=reminder
        if(notice!=null && notice.first==today && foreground && overlay==null && pendingOverlay==null && !backupWorking) {
            if(reminderTasks(data.tasks,today).any {it.id in notice.second}) showOverlay("daily-reminder")
            else repository.dismissDailyReminder()
        }
    }
    BackHandler(pendingOverlay!=null) {pendingOverlay=null}
    LaunchedEffect(pendingOverlay) {
        val target=pendingOverlay ?: return@LaunchedEffect
        val started=android.os.SystemClock.elapsedRealtime()
        repeat(2) {withFrameNanos {}}
        val image=try {blurGlassSnapshot(backdrop.toImageBitmap(),blurRadius)}
        catch(c:kotlinx.coroutines.CancellationException) {throw c}
        catch(e:Exception) {
            android.util.Log.w("AgendaGlass","Backdrop preparation failed",e)
            GlassSnapshot(androidx.compose.ui.graphics.ImageBitmap(1,1),1,1)
        }
        preparedGlass=image
        overlay=target
        pendingOverlay=null
        if(BuildConfig.DEBUG) android.util.Log.d("AgendaGlassReady","${android.os.SystemClock.elapsedRealtime()}|$target|${android.os.SystemClock.elapsedRealtime()-started}")
    }
    val pageCanvas=agendaCanvas()
    Box(Modifier.fillMaxSize().background(pageCanvas).drawWithContent {
            if(pendingOverlay!=null) {
                backdrop.record {
                    // The background modifier itself is outside drawContent().
                    // Seed an opaque canvas before capturing for raster blur.
                    drawRect(pageCanvas)
                    this@drawWithContent.drawContent()
                }
                drawLayer(backdrop)
            } else drawContent()
        }) {
        MeadowBackground(Modifier.matchParentSize(),baseColor=pageCanvas)
        Column(Modifier.fillMaxSize().then(if(overlay!=null) Modifier.clearAndSetSemantics {} else Modifier)
            .statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal=12.dp)) {
                if(page != 1) MarkButton(Mark.FLIP, if(page==0) tr("切换事项视图") else tr("切换日程视图"), {
                    if(page==0) board = !board else fullDays = !fullDays
                },color=Meadow.Text,modifier=Modifier.align(Alignment.CenterStart))
                if(page==1) MarkButton(Mark.BACKUP,tr("备份与恢复"),{showOverlay("backup")},color=Meadow.Text,modifier=Modifier.align(Alignment.CenterStart))
                AgendaText("Agenda", size=18,color=Meadow.Text,weight=FontWeight.SemiBold,brand=true,
                    modifier=Modifier.align(Alignment.Center).semantics {contentDescription=tr("Agenda品牌")})
                Row(Modifier.align(Alignment.CenterEnd)) {
                    if(page==2) MarkButton(Mark.WEEK, tr("周计划"), {showOverlay("week")},color=Meadow.Text)
                    MarkButton(Mark.SEARCH,tr("搜索"), {showOverlay("search")},color=Meadow.Text)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                pageStates.SaveableStateProvider(page) { when(page) {
                    0 -> ThingsScene(data, repository, board, {showOverlay("task:$it")}, {showOverlay(if(listOf("material:","materials:","notes:","note:","schedule:","schedule-for:","edit-","delete:").any {prefix->it.startsWith(prefix)}) it else "info:$it")},focusObject)
                    1 -> ActionScene(data, repository, today, {showOverlay("task:$it")},{showOverlay("leisure")},{showOverlay("create")},{page=0})
                    else -> ScheduleScene(data, repository, today, fullDays, {showOverlay(if(it=="appearance" || it.startsWith("schedule:") || it.startsWith("edit-schedule:")) it else "info:$it")})
                } }
                if(page==0) MarkButton(Mark.PLUS, tr("新建任务"), {showOverlay("create")},
                    modifier=Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
            Row(Modifier.fillMaxWidth().height(72.dp)
                .clip(RoundedCornerShape(topStart=32.dp,topEnd=32.dp)),
                horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                listOf(1 to tr("首页"),0 to tr("事项"),2 to tr("日历")).forEach { (index,label) ->
                    val color=if(page==index) (if(index==2) Meadow.Teal else Meadow.Green) else Meadow.Muted
                    Column(Modifier.width(80.dp).height(56.dp).clickable {page=index}
                        .semantics {contentDescription=label;selected=page==index},horizontalAlignment=Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        if(index==1) MeadowSymbol(6,color,Modifier.size(24.dp))
                        else AgendaIcon(if(index==0) Mark.GRAPH else Mark.CALENDAR,color,Modifier.size(24.dp))
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if(pendingOverlay!=null) Box(Modifier.matchParentSize().clickable(
            interactionSource=remember {androidx.compose.foundation.interaction.MutableInteractionSource()},indication=null,onClick={}))
        if(overlay?.startsWith("image:")==true) {
            val imageId=overlay!!.removePrefix("image:")
            MaterialImageViewer(data.objects.flatMap {it.materials}.find {it.id==imageId},repository) {overlay="material:$imageId"}
        } else if(overlay != null && preparedGlass!=null) key(overlay) { GlassOverlay(overlay!!, data, repository, preparedGlass!!,
            {if(overlay=="daily-reminder") repository.dismissDailyReminder();overlay=null;preparedGlass=null}, {showOverlay(it)}) }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(horizontal=16.dp).padding(bottom=(80+pendingDeletes.size.coerceAtMost(3)*60).dp)) {notice->
            Snackbar(containerColor=Ink.Primary,contentColor=Ink.Surface) {
                AgendaText(notice.visuals.message,color=Ink.Surface)
            }
        }
        UndoDeletionNotices(repository,Modifier.align(Alignment.BottomCenter).imePadding().padding(horizontal=12.dp).padding(bottom=82.dp))
    }
}

@Composable
fun TaskLine(task: Task, repository: AgendaViewModel, onOpen: (String)->Unit, date: Boolean = false, enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Checkbox(task.completed, if(enabled) ({done:Boolean->repository.setCompleted(task.id,done)}) else null, modifier=Modifier.semantics {
            contentDescription = tr("{0}：{1}",if(task.completed) tr("恢复未完成") else tr("完成"),task.name)
        }, colors=CheckboxDefaults.colors(checkedColor=Ink.Primary, uncheckedColor=Ink.Control))
        Column(Modifier.weight(1f).clickable(enabled=enabled) {onOpen(task.id)}.padding(vertical=10.dp)) {
            AgendaText(task.name, color=if(task.completed) Ink.Muted else Ink.Text)
            val due=task.due
            if(date && due!=null) AgendaText(tr("{0} 月 {1} 日截止",due.monthValue,due.dayOfMonth), size=12, color=Ink.Muted)
        }
    }
}

