package com.agenda.app.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Shared 156 × 122 coordinate system: all leaves join the stem, with a level baseline.
internal const val CalendarBranchPath = "M 17 102 L 138 102 M 58 102 C 60 96 62 92 64 88 C 66 83 69 79 71 75 C 73 71 75 68 77 65 C 79 61 81 57 83 54 M 64 88 C 54 91 47 85 43 79 C 53 77 61 80 64 88 Z M 71 75 C 60 75 53 69 54 61 C 64 61 70 66 71 75 Z M 77 65 C 88 70 97 65 102 58 C 91 56 83 58 77 65 Z M 83 54 C 76 49 79 44 82 39 C 85 44 90 50 83 54 Z"

/** Native line drawing; no bitmap, rotation, or independent X/Y scaling. */
@Composable
fun CalendarBranch(modifier:Modifier=Modifier) {
    val branch=remember {PathParser().parsePathString(CalendarBranchPath).toPath()}
    Canvas(modifier.size(width=156.dp,height=122.dp)) {
        val factor=minOf(size.width/156f,size.height/122f)
        val ink=if(InterfaceSettings.blue) Meadow.Text else Color(0xFF4A6578)
        val outline=Stroke(width=1.4f,cap=StrokeCap.Round,join=StrokeJoin.Round)
        withTransform({
            translate((size.width-156f*factor)/2f,(size.height-122f*factor)/2f)
            scale(factor,factor,Offset.Zero)
        }) {
            drawPath(branch,ink,style=outline)
            drawCircle(ink,radius=8f,center=Offset(115f,51f),style=outline)
        }
    }
}
