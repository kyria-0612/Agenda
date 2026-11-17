package com.agenda.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.AgendaMaterial
import kotlinx.coroutines.CancellationException

/** Local-only image screen; no external viewer or full-resolution decode required. */
@Composable
internal fun MaterialImageViewer(item:AgendaMaterial?,repository:AgendaViewModel,onClose:()->Unit) {
    BackHandler {onClose()}
    var bitmap by remember(item?.id) {mutableStateOf<ImageBitmap?>(null)}
    var failed by remember(item?.id) {mutableStateOf(false)}
    var retry by remember(item?.id) {mutableIntStateOf(0)}
    var zoom by remember(item?.id) {mutableFloatStateOf(1f)}
    var pan by remember(item?.id) {mutableStateOf(Offset.Zero)}
    var viewport by remember {mutableStateOf(IntSize.Zero)}
    fun reset() {zoom=1f;pan=Offset.Zero}
    fun bounded(offset:Offset,scale:Float):Offset {
        val image=bitmap ?: return Offset.Zero
        if(viewport.width==0 || viewport.height==0) return Offset.Zero
        val fit=minOf(viewport.width.toFloat()/image.width,viewport.height.toFloat()/image.height)
        val x=maxOf(0f,(image.width*fit*scale-viewport.width)/2f)
        val y=maxOf(0f,(image.height*fit*scale-viewport.height)/2f)
        return Offset(offset.x.coerceIn(-x,x),offset.y.coerceIn(-y,y))
    }
    LaunchedEffect(item?.id,retry) {
        bitmap=null;failed=false;reset()
        if(item==null) {failed=true;return@LaunchedEffect}
        try {bitmap=repository.materialImage(item.id).asImageBitmap()}
        catch(c:CancellationException) {throw c}
        catch(e:Exception) {failed=true}
    }
    Column(Modifier.fillMaxSize().background(Ink.Canvas).statusBarsPadding().navigationBarsPadding()
        .semantics {paneTitle=tr("应用内图片查看")}) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
            AgendaText(item?.displayTitle ?: tr("图片"),size=16,color=Ink.Primary,maxLines=2,modifier=Modifier.weight(1f))
            TextButton({reset()},enabled=bitmap!=null) {AgendaText(tr("复位"),size=13,color=Ink.Primary)}
            MarkButton(Mark.CLOSE,tr("关闭图片"),onClose)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged {viewport=it;reset()}
            .pointerInput(bitmap,viewport) {
                detectTransformGestures {centroid,delta,scale,_->
                    val next=(zoom*scale).coerceIn(1f,5f)
                    val center=Offset(viewport.width/2f,viewport.height/2f)
                    pan=bounded((pan-(centroid-center))*(next/zoom)+(centroid-center)+delta,next)
                    zoom=next
                }
            }.pointerInput(bitmap,viewport) {
                detectTapGestures(onDoubleTap={point->
                    if(zoom>1.01f) reset()
                    else {zoom=2.5f;pan=bounded((Offset(viewport.width/2f,viewport.height/2f)-point)*(zoom-1f),zoom)}
                })
            }.semantics {
                contentDescription=tr("图片预览，双指缩放，双击放大或复位，缩放 {0}%",(zoom*100).toInt())
            },contentAlignment=Alignment.Center) {
            val loaded=bitmap
            if(loaded!=null) Image(loaded,contentDescription=item?.displayTitle,contentScale=ContentScale.Fit,
                modifier=Modifier.fillMaxSize().graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y})
            else if(failed) Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                AgendaText(tr("图片暂时无法显示"),size=16,color=Ink.Primary)
                AgendaText(tr("文件可能损坏或格式暂不支持，可以重试。"),size=13,color=Ink.Muted,modifier=Modifier.padding(top=8.dp))
                TextButton({retry++}) {AgendaText(tr("重试"),color=Ink.Primary)}
            } else CircularProgressIndicator(color=Ink.Primary)
        }
        AgendaText(tr("双指缩放 · 双击放大 · 放大后拖动"),size=12,color=Ink.Muted,
            modifier=Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
    }
}
