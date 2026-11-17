package com.agenda.app.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared styling for all three main views. */
object Meadow {
    val Canvas get()=Color(if(InterfaceSettings.blue) 0xFFE9EFF3 else 0xFFF4F8F5)
    val ObjectFill get()=Color(if(InterfaceSettings.blue) 0xFF43515F else 0xFF456A5D)
    val GraphConnector get()=Color(if(InterfaceSettings.blue) 0xFF587F94 else 0xFF6B8D7D)
    val Text get()=Color(if(InterfaceSettings.blue) 0xFF43515F else 0xFF09292A)
    val Muted get()=Color(if(InterfaceSettings.blue) 0xFF657582 else 0xFF75818B)
    val Green get()=Color(if(InterfaceSettings.blue) 0xFF5BA9B1 else 0xFF27824F)
    val Teal get()=Color(if(InterfaceSettings.blue) 0xFF5BA9B1 else 0xFF72A4A0)
    val White get()=Color(if(InterfaceSettings.blue) 0xFFFFFFFF else 0xFFFCFDFD)
}

fun agendaCanvas():Color=Meadow.Canvas

@Composable
fun MeadowText(text:String,modifier:Modifier=Modifier,size:Int=14,color:Color=Meadow.Text,bold:Boolean=false,title:Boolean=false) {
    AgendaText(text,modifier,title=title,size=size,color=color,
        weight=if(bold) FontWeight.SemiBold else FontWeight.Normal)
}

@Composable
fun MeadowBackground(modifier:Modifier=Modifier,baseColor:Color=Meadow.Canvas) {
    Canvas(modifier) {
        val w=size.width; val h=size.height
        drawRect(Brush.linearGradient(listOf(lerp(baseColor,Color.White,.25f),baseColor,lerp(baseColor,Color.White,.15f)),
            start=Offset.Zero,end=Offset(w,h)))
        val mist=if(baseColor==Meadow.Canvas) Color(if(InterfaceSettings.blue) 0xFFD2E1E9 else 0xFFDAE8DF) else lerp(baseColor,Color.Black,.06f)
        val upper=Path().apply {
            moveTo(0f,h*.38f)
            cubicTo(w*.25f,h*.39f,w*.30f,h*.15f,w*.64f,h*.115f)
            cubicTo(w*.80f,h*.09f,w*.94f,h*.08f,w,h*.10f)
            lineTo(w,h*.47f)
            cubicTo(w*.60f,h*.45f,w*.29f,h*.47f,0f,h*.55f);close()
        }
        drawPath(upper,Brush.verticalGradient(listOf(mist.copy(alpha=.52f),mist.copy(alpha=0f)),startY=h*.10f,endY=h*.55f))
        val lower=Path().apply {
            moveTo(0f,h*.94f)
            cubicTo(w*.35f,h*.98f,w*.70f,h*.72f,w,h*.78f)
            lineTo(w,h);lineTo(0f,h);close()
        }
        drawPath(lower,Brush.verticalGradient(listOf(mist.copy(alpha=.48f),mist.copy(alpha=.10f)),startY=h*.78f,endY=h))
    }
}

@Composable
fun MeadowHeading(calendar:Boolean=false,month:String="",modifier:Modifier=Modifier) {
    Box(modifier.fillMaxWidth().height(if(calendar) 140.dp else 104.dp)) {
        Column(Modifier.align(Alignment.CenterStart).padding(start=16.dp,bottom=if(calendar) 8.dp else 0.dp)) {
            if(calendar) MeadowText(month,size=13,color=if(InterfaceSettings.blue) Meadow.Muted else Color(0xFF6B8A80),bold=true,modifier=Modifier.padding(bottom=10.dp))
            MeadowText(if(calendar) tr("今天，留一点余地。") else tr("现在，适合做什么？"),size=if(calendar) 20 else 22,bold=true,title=true)
            if(calendar) Box(Modifier.padding(top=10.dp).width(36.dp).height(3.dp).clip(RoundedCornerShape(3.dp)).background(if(InterfaceSettings.blue) Meadow.Green else Color(0xFF79A787)))
        }
    }
}

@Composable
fun MeadowSymbol(kind:Int,color:Color,modifier:Modifier=Modifier) {
    Canvas(modifier.size(24.dp)) {
        val u=size.width/24f
        fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(color,Offset(x*u,y*u),Offset(a*u,b*u),1.8f*u,StrokeCap.Round)
        fun path(vararg points:Pair<Float,Float>,close:Boolean=false) {
            val p=Path().apply {points.forEachIndexed {i,(x,y)->if(i==0) moveTo(x*u,y*u) else lineTo(x*u,y*u)};if(close) close()}
            drawPath(p,color,style=Stroke(1.8f*u,cap=StrokeCap.Round,join=StrokeJoin.Round))
        }
        when(kind) {
            0->{path(9f to 3f,3f to 3f,3f to 21f,21f to 21f,21f to 12f);path(10f to 14f,11f to 9f,20f to 1f,23f to 4f,14f to 13f,10f to 14f)}
            1->path(5f to 2f,20f to 2f,20f to 22f,12.5f to 17f,5f to 22f,close=true)
            2->{drawCircle(color,10*u,style=Stroke(1.8f*u));drawCircle(color,6*u,style=Stroke(1.8f*u));drawCircle(color,2*u);line(12f,12f,21f,3f)}
            3->{val p=Path().apply {moveTo(4*u,19*u);cubicTo(0f,7*u,10*u,2*u,22*u,2*u);cubicTo(22*u,16*u,13*u,23*u,4*u,19*u)};drawPath(p,color,style=Stroke(1.8f*u));line(2f,23f,15f,9f)}
            4->path(11f to 2f,5f to 13f,11f to 13f,9f to 23f,20f to 9f,14f to 9f,17f to 2f,close=true)
            5->{drawCircle(color,10*u,style=Stroke(1.8f*u));line(12f,5f,12f,12f);line(12f,12f,17f,15f)}
            6->{val p=Path().apply {moveTo(3*u,10*u);lineTo(12*u,2*u);lineTo(21*u,10*u);lineTo(21*u,22*u);lineTo(3*u,22*u);close()};drawPath(p,color.copy(alpha=.6f));drawPath(p,color,style=Stroke(1.8f*u));path(9f to 22f,9f to 15f,15f to 15f,15f to 22f)}
            else->{line(9f,6f,15f,12f);line(15f,12f,9f,18f)}
        }
    }
}
