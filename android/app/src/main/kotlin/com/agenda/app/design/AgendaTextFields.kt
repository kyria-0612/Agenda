package com.agenda.app.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun agendaInputTransformation():VisualTransformation = remember(InterfaceSettings.bodyTypeface) {
    VisualTransformation {TransformedText(agendaAnnotated(it.text),OffsetMapping.Identity)}
}

/** The editable value is unchanged; only its glyph runs receive the mixed font families. */
@Composable
fun AgendaOutlinedTextField(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,
    enabled:Boolean=true,label:(@Composable ()->Unit)?=null,placeholder:(@Composable ()->Unit)?=null,
    isError:Boolean=false,singleLine:Boolean=false,minLines:Int=1,maxLines:Int=Int.MAX_VALUE,
    shape:Shape=RoundedCornerShape(14.dp)) {
    OutlinedTextField(value,onValueChange,modifier=modifier,enabled=enabled,label=label,placeholder=placeholder,
        isError=isError,singleLine=singleLine,minLines=minLines,maxLines=maxLines,shape=shape,
        textStyle=TextStyle(fontFamily=BodyFont,fontSize=16.sp,lineHeight=24.sp,color=Ink.Text,
            fontFeatureSettings="tnum",fontSynthesis=FontSynthesis.None),visualTransformation=agendaInputTransformation())
}
