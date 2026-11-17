package com.agenda.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.agenda.app.design.*
import com.agenda.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ThingsScene(data: AgendaSnapshot, repository: AgendaViewModel, board: Boolean,
    onTask: (String)->Unit, onInfo: (String)->Unit,focusObject:String?=null) {
    if(data.objects.isEmpty() && data.tasks.isEmpty()) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                AgendaText(tr("还没有事项"),title=true,size=20,color=Ink.Primary)
                AgendaText(tr("点击右下角加号，创建任务或对象"),size=13,color=Ink.Muted,modifier=Modifier.padding(top=12.dp))
            }
        }
        return
    }
    if(board) TaskBoard(data,repository,onTask,onInfo) else RelationshipGraph(data,onTask,onInfo,focusObject)
}

private data class GraphNode(val id:String,val text:String,val x:Float,val y:Float,val parent:String?=null,val kind:String="task")
private fun GraphNode.nodeWidth()=when(kind) {"branch"->if(InterfaceSettings.english) 96f else 64f;"object"->124f;else->140f}

@Composable
private fun RelationshipGraph(data:AgendaSnapshot,onTask:(String)->Unit,onInfo:(String)->Unit,focusObject:String?) {
    var collapsed by rememberSaveable {mutableStateOf(emptySet<String>())}
    val english=InterfaceSettings.english
    val rawNodes=remember(data,english) {
        buildList {
            var groupY=170f
            data.objects.forEach {obj->
                val cy=groupY
                val tasks=data.tasks.filter {it.objectId==obj.id}
                val lastMaterialY=cy-130+obj.materials.lastIndex.coerceAtLeast(0)*54f
                val noteY=maxOf(cy+140,lastMaterialY+68f)
                add(GraphNode(obj.id,obj.name,280f,cy,kind="object"))
                // Leave room for the connectors instead of hiding them under adjacent boxes.
                add(GraphNode("${obj.id}:tasks",tr("任务"),if(english) 140f else 160f,cy-15,obj.id,"branch"))
                add(GraphNode("${obj.id}:materials",tr("材料"),if(english) 415f else 395f,cy-15,obj.id,"branch"))
                add(GraphNode("${obj.id}:notes",tr("说明"),if(english) 415f else 395f,noteY-65,obj.id,"branch"))
                tasks.forEachIndexed {i,t->add(GraphNode(t.id,t.name,if(english) 20f else 50f,cy-130+i*55f,"${obj.id}:tasks"))}
                obj.materials.forEachIndexed {i,m->add(GraphNode(m.id,m.displayTitle,if(english) 540f else 500f,cy-130+i*54f,"${obj.id}:materials","file"))}
                obj.notes.forEachIndexed {i,n->add(GraphNode(n.id,n.title,if(english) 540f else 500f,noteY+i*54f,"${obj.id}:notes","note"))}
                val bottom=maxOf(cy+75,cy-130+tasks.lastIndex.coerceAtLeast(0)*55f,
                    if(obj.notes.isEmpty()) noteY-65 else noteY+obj.notes.lastIndex*54f)
                groupY=cy+maxOf(330f,bottom-cy+190f)
            }
            data.tasks.filter {it.objectId==null}.forEachIndexed {i,t->
                add(GraphNode(t.id,t.name,if(i%2==0) 150f else 430f, groupY-70f+(i/2)*90f))
            }
        }
    }
    val allNodes=remember(rawNodes) {
        val left=rawNodes.minOfOrNull {it.x-it.nodeWidth()/2} ?: 0f
        val top=rawNodes.minOfOrNull {it.y-24f} ?: 0f
        rawNodes.map {it.copy(x=it.x-left,y=it.y-top)}
    }
    val nodes=allNodes.filter {n->
        var parent=n.parent
        var visible=true
        while(parent!=null) {
            if(parent in collapsed) {visible=false;break}
            parent=allNodes.find {it.id==parent}?.parent
        }
        visible
    }
    val worldHeight=(allNodes.maxOfOrNull {it.y} ?: 650f)+40f
    val worldWidth=allNodes.maxOfOrNull {it.x+it.nodeWidth()/2} ?: 550f
    val density=LocalDensity.current.density
    val scope=rememberCoroutineScope()
    var pan by rememberSaveable(stateSaver=Saver<Offset,List<Float>>(save={listOf(it.x,it.y)},restore={Offset(it[0],it[1])})) {mutableStateOf(Offset.Zero)}
    var zoom by rememberSaveable {mutableFloatStateOf(.65f)}
    var fitted by rememberSaveable {mutableStateOf(false)}
    var lastFocus by rememberSaveable {mutableStateOf<String?>(null)}
    var settle by remember {mutableStateOf<Job?>(null)}
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
        val viewportW=constraints.maxWidth.toFloat()
        val viewportH=constraints.maxHeight.toFloat()
        fun limits(scale:Float):Pair<Offset,Offset> {
            val w=worldWidth*density*scale
            val h=worldHeight*density*scale
            val minX=if(w<viewportW) (viewportW-w)/2 else viewportW-w-24f*density
            val maxX=if(w<viewportW) minX else 24f*density
            val minY=if(h<viewportH) (viewportH-h)/2 else viewportH-h-24f*density
            val maxY=if(h<viewportH) minY else 24f*density
            return Offset(minX,minY) to Offset(maxX,maxY)
        }
        fun overview() {
            settle?.cancel()
            zoom=minOf(viewportW/(worldWidth*density),viewportH/(worldHeight*density),.9f)*.92f
            val bounds=limits(zoom)
            pan=bounds.first
        }
        LaunchedEffect(viewportW,viewportH) {
            if(!fitted) {overview();fitted=true}
            else {
                val (min,max)=limits(zoom)
                pan=Offset(pan.x.coerceIn(min.x,max.x),pan.y.coerceIn(min.y,max.y))
            }
        }
        LaunchedEffect(focusObject) {
            if(focusObject==null) {lastFocus=null;return@LaunchedEffect}
            if(lastFocus==focusObject) return@LaunchedEffect
            val node=allNodes.find {it.id==focusObject} ?: return@LaunchedEffect
            lastFocus=focusObject
            collapsed=collapsed-node.id
            zoom=maxOf(zoom,.85f)
            val (min,max)=limits(zoom)
            pan=Offset((viewportW/2-node.x*density*zoom).coerceIn(min.x,max.x),
                (viewportH/2-node.y*density*zoom).coerceIn(min.y,max.y))
        }
        LaunchedEffect(worldWidth,worldHeight) {
            // Content changes adjust bounds without resetting the user's zoom.
            val (min,max)=limits(zoom)
            pan=Offset(pan.x.coerceIn(min.x,max.x),pan.y.coerceIn(min.y,max.y))
        }
        Box(Modifier.fillMaxSize().pointerInput(viewportW,viewportH,worldHeight) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false)
                settle?.cancel()
                var moved=false
                var total=0f
                var rawPan=pan
                do {
                    val event=awaitPointerEvent()
                    // An UP event has no active centroid. Applying it produces NaN
                    // translation and makes the entire layer disappear.
                    if(event.changes.none {it.pressed}) break
                    val changePan=event.calculatePan()
                    val factor=event.calculateZoom()
                    total+=changePan.getDistance()
                    if(total>viewConfiguration.touchSlop || factor!=1f) moved=true
                    if(moved) {
                        val center=event.calculateCentroid(useCurrent=false)
                        val next=applyGraphGesture(GraphTransform(rawPan.x,rawPan.y,zoom),center.x,center.y,changePan.x,changePan.y,factor)
                        val raw=Offset(next.x,next.y)
                        rawPan=raw
                        zoom=next.scale
                        val (min,max)=limits(zoom)
                        pan=Offset(dampedPosition(raw.x,min.x,max.x,100f*density),dampedPosition(raw.y,min.y,max.y,100f*density))
                        event.changes.forEach {if(it.positionChanged()) it.consume()}
                    }
                } while(event.changes.any {it.pressed})
                val (min,max)=limits(zoom)
                val destination=Offset(pan.x.coerceIn(min.x,max.x),pan.y.coerceIn(min.y,max.y))
                if(destination!=pan) {
                    val start=pan
                    settle=scope.launch {animate(0f,1f,animationSpec=spring(dampingRatio=.85f,stiffness=450f)) {v,_->pan=start+(destination-start)*v}}
                }
            }
        }) {
            Layout(content={
            Box(Modifier.graphicsLayer {
                transformOrigin=TransformOrigin(0f,0f);scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y
            }) {
                Canvas(Modifier.matchParentSize()) {
                    // All branches share the main connector style at every zoom level.
                    val connectorWidth=2f*density/zoom.coerceIn(.2f,1f)
                    nodes.forEach {n->
                        val parent=nodes.find {it.id==n.parent} ?: return@forEach
                        drawLine(Meadow.GraphConnector,
                            Offset(parent.x*density,parent.y*density),Offset(n.x*density,n.y*density),
                            connectorWidth,
                            cap=StrokeCap.Round)
                    }
                }
                nodes.forEach {node->
                    val completed=data.tasks.find {it.id==node.id}?.completed==true
                    val width=node.nodeWidth()
                    Box(Modifier.offset((node.x-width/2).dp,(node.y-24).dp).width(width.dp).heightIn(min=48.dp)
                        .shadow(if(node.kind=="task" || node.kind=="file") 2.dp else 0.dp,
                            RoundedCornerShape(if(node.kind=="object") 24.dp else 10.dp),ambientColor=Ink.Text.copy(alpha=.08f),spotColor=Ink.Text.copy(alpha=.08f))
                        .clip(RoundedCornerShape(if(node.kind=="object") 24.dp else 10.dp))
                        .background(if(node.kind=="object") Meadow.ObjectFill else Color.White)
                        .then(if(node.kind=="task" || node.kind=="file" || node.kind=="note")
                            Modifier.border(1.dp,Ink.Divider.copy(alpha=.75f),RoundedCornerShape(10.dp)) else Modifier)
                        .clickable {
                            when(node.kind) {
                                "object" -> collapsed=if(node.id in collapsed) collapsed-node.id else collapsed+node.id
                                "branch" -> when {
                                    node.id.endsWith(":materials")->onInfo("materials:${node.parent}")
                                    node.id.endsWith(":notes")->onInfo("notes:${node.parent}")
                                    else->collapsed=if(node.id in collapsed) collapsed-node.id else collapsed+node.id
                                }
                                "task" -> onTask(node.id)
                                "note" -> onInfo("note:${node.id}")
                                else -> onInfo(if(node.kind=="file") "material:${node.id}" else node.text)
                            }
                        }.padding(8.dp),contentAlignment=Alignment.Center) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            // Only the three object branches get icons; task nodes remain text-only.
                            if(node.kind=="branch") AgendaIcon(when {
                                node.id.endsWith(":materials")->Mark.LAYERS
                                node.id.endsWith(":notes")->Mark.INFO
                                else->Mark.TASKS
                            },color=Meadow.ObjectFill,modifier=Modifier.size(14.dp))
                            AgendaText(node.text,title=node.kind=="object",size=if(node.kind=="object") 18 else 14,
                                color=when {node.kind=="object"->Color.White;completed->Ink.Muted;else->Meadow.Text},maxLines=2,
                                decoration=if(completed) TextDecoration.LineThrough else null)
                        }
                    }
                }
            }
            }) {measurables,constraints->
                // Measure the world independently and place its origin explicitly.
                // requiredSize in a constrained Box silently recentres oversized
                // children before applying our pan, causing a double offset.
                val world=measurables.single().measure(Constraints.fixed((worldWidth*density).toInt(),(worldHeight*density).toInt()))
                layout(constraints.maxWidth,constraints.maxHeight) {world.place(0,0)}
            }
        }
        MarkButton(Mark.OVERVIEW,tr("全局概览"),{overview()},modifier=Modifier.align(Alignment.BottomStart).padding(16.dp))
    }
}
