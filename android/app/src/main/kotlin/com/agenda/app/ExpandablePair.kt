package com.agenda.app

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agenda.app.design.*
import com.agenda.domain.boardCardHeight
import kotlin.math.roundToInt

/** One measured container transition shared by the two card grids. Content stays
 * at a stable reading width while its frame grows; collapsed hit areas cover the
 * whole card so clipped/disabled children cannot swallow the first tap. */
@Composable
fun ExpandablePair(ids:List<String>,expanded:String?,onSelect:(String)->Unit,
    label:(String)->String,baseHeight:Dp=148.dp,
    cardModifier:(String)->Modifier={Modifier},
    cornerRadius:Dp=12.dp,
    decoration:@Composable BoxScope.(String)->Unit={},
    header:@Composable (String)->Unit,body:@Composable (String,Boolean)->Unit) {
    val left=animateFloatAsState(if(expanded==ids[0]) 1f else 0f,AgendaMotion.container(),label="pair-left")
    val right=animateFloatAsState(if(ids.size>1 && expanded==ids[1]) 1f else 0f,AgendaMotion.container(),label="pair-right")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val baseWidth=with(LocalDensity.current) {((maxWidth-12.dp)/2).toPx()}
    val readingWidth=(baseWidth*1.28f).roundToInt()
    Layout(modifier=Modifier.fillMaxWidth(),content={
        ids.forEachIndexed {index,id->
            if(id.isEmpty()) {Spacer(Modifier);return@forEachIndexed}
            val own=if(index==0) left else right
            val neighbour=if(index==0) right else left
            val interactive by remember(own,expanded,id) {derivedStateOf {expanded==id && own.value>.96f}}
            Box(cardModifier(id).clip(RoundedCornerShape(cornerRadius)).background(Ink.Surface)
                .clickable {onSelect(id)}.semantics {contentDescription=label(id)}
                .onSizeChanged {if(BuildConfig.DEBUG) Log.d("AgendaBoardMotion","${SystemClock.elapsedRealtime()}|$id|${it.width}|${it.height}")}) {
                decoration(id)
                Layout(content={
                    Column(Modifier.padding(16.dp).graphicsLayer {alpha=(1f-neighbour.value*.9f).coerceIn(.12f,1f)}) {
                        // Compact titles must wrap inside the compact card too.
                        // Keep that reading width stable through the transition.
                        Box(Modifier.width(with(LocalDensity.current) {baseWidth.toDp()}-32.dp)) {
                            Column {header(id)}
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.graphicsLayer {alpha=((own.value-.12f)/.7f).coerceIn(0f,1f)}
                            .then(if(!interactive) Modifier.clearAndSetSemantics {} else Modifier)) {body(id,interactive)}
                    }
                }) {measurables,constraints->
                    val content=measurables.single().measure(Constraints.fixedWidth(readingWidth))
                    val height=boardCardHeight(baseWidth,constraints.maxWidth.toFloat(),baseHeight.toPx(),
                        content.height.toFloat(),own.value,neighbour.value).roundToInt()
                    layout(constraints.maxWidth,height) {content.placeRelative(0,0)}
                }
                if(expanded!=id) Box(Modifier.matchParentSize().clickable {onSelect(id)})
            }
        }
        if(ids.size==1) Spacer(Modifier)
    }) {measurables,constraints->
        val gap=12.dp.roundToPx()
        val available=constraints.maxWidth-gap
        val fraction=(.5f+.14f*(left.value-right.value)).coerceIn(.34f,.66f)
        val first=(available*fraction).roundToInt()
        val children=measurables.mapIndexed {index,m->m.measure(Constraints.fixedWidth(if(index==0) first else available-first))}
        layout(constraints.maxWidth,children.maxOf {it.height}) {
            children[0].placeRelative(0,0);children[1].placeRelative(first+gap,0)
        }
    }
    }
}
