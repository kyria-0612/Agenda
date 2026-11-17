package com.agenda.app.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class Mark { GRAPH, ACTION, CALENDAR, FLIP, SEARCH, PLUS, CLOSE, OVERVIEW, WEEK, BACK, CHECK, FILE, BACKUP, TASKS, LAYERS, INFO }

@Composable
fun MarkButton(mark: Mark, label: String, onClick: () -> Unit, color: Color = Ink.Primary,
    modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.size(48.dp).semantics { contentDescription = label }) { AgendaIcon(mark, color) }
}

@Composable
fun AgendaIcon(mark: Mark, color: Color = Ink.Primary, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val unit = size.width / 24f
        val stroke = 1.5f * unit
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x * unit, y * unit), Offset(xx * unit, yy * unit), stroke)
        fun rect(x: Float, y: Float, w: Float, h: Float) = drawRect(color, Offset(x * unit, y * unit), Size(w * unit, h * unit), style = Stroke(stroke))
        when(mark) {
            Mark.TASKS -> {rect(3f,3f,18f,18f);line(7f,11f,11f,15f);line(11f,15f,18f,8f)}
            Mark.LAYERS -> {
                val diamond=Path().apply {moveTo(12f*unit,2f*unit);lineTo(22f*unit,8f*unit);lineTo(12f*unit,14f*unit);lineTo(2f*unit,8f*unit);close()}
                drawPath(diamond,color,style=Stroke(stroke))
                line(2f,12f,12f,18f);line(12f,18f,22f,12f)
                line(2f,16f,12f,22f);line(12f,22f,22f,16f)
            }
            Mark.INFO -> {
                drawCircle(color,10f*unit,Offset(12f*unit,12f*unit),style=Stroke(stroke))
                drawCircle(color,1f*unit,Offset(12f*unit,7f*unit))
                line(12f,11f,12f,17f)
            }
            Mark.BACKUP -> {line(4f,13f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,13f);line(12f,15f,12f,3f);line(7f,8f,12f,3f);line(12f,3f,17f,8f)}
            Mark.PLUS -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            Mark.CLOSE -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            Mark.CHECK -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
            Mark.SEARCH -> { drawCircle(color, 7f*unit, Offset(10f*unit,10f*unit), style=Stroke(stroke)); line(15f,15f,21f,21f) }
            Mark.CALENDAR, Mark.WEEK -> { rect(4f,5f,16f,15f); line(4f,10f,20f,10f); line(8f,3f,8f,7f); line(16f,3f,16f,7f)
                if(mark==Mark.WEEK) { line(9f,10f,9f,20f); line(15f,10f,15f,20f) } }
            Mark.FLIP -> { line(3f,7f,20f,7f); line(16f,3f,20f,7f); line(20f,7f,16f,11f); line(21f,17f,4f,17f); line(8f,13f,4f,17f); line(4f,17f,8f,21f) }
            Mark.GRAPH -> { line(5f,6f,19f,6f); line(5f,6f,12f,19f); line(19f,6f,12f,19f)
                listOf(Offset(5f,6f),Offset(19f,6f),Offset(12f,19f)).forEach { drawCircle(Ink.Surface,2.7f*unit,it*unit); drawCircle(color,2.7f*unit,it*unit,style=Stroke(stroke)) } }
            Mark.ACTION -> { val path=Path().apply { moveTo(8f*unit,4f*unit); lineTo(20f*unit,12f*unit); lineTo(8f*unit,20f*unit); close() }; drawPath(path,color,style=Stroke(stroke)) }
            Mark.OVERVIEW -> { rect(4f,4f,6f,6f); rect(14f,4f,6f,6f); rect(4f,14f,6f,6f); rect(14f,14f,6f,6f) }
            Mark.BACK -> { line(15f,5f,8f,12f); line(8f,12f,15f,19f) }
            Mark.FILE -> { rect(6f,3f,12f,18f); line(9f,11f,15f,11f); line(9f,15f,15f,15f) }
        }
    }
}
