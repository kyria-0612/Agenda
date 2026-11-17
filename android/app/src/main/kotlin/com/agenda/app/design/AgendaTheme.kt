package com.agenda.app.design

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.agenda.app.R

object Ink {
    val Canvas get()=Color(if(InterfaceSettings.blue) 0xFFE9EFF3 else 0xFFEEEEEE)
    val Surface get()=Color(if(InterfaceSettings.blue) 0xFFFFFFFF else 0xFFFAFAFA)
    val Text get()=Color(if(InterfaceSettings.blue) 0xFF43515F else 0xFF222831)
    val Muted get()=Color(if(InterfaceSettings.blue) 0xFF657582 else 0xFF59616B)
    val Primary get()=Color(if(InterfaceSettings.blue) 0xFF43515F else 0xFF3B4F62)
    val Teal get()=Color(if(InterfaceSettings.blue) 0xFF5BA9B1 else 0xFF00ADB5)
    val Control get()=Color(if(InterfaceSettings.blue) 0xFF3D8490 else 0xFF00767C)
    val Divider get()=Color(if(InterfaceSettings.blue) 0xFFCBD6DD else 0xFFCDD2D6)
    val Done get()=Color(if(InterfaceSettings.blue) 0xFF3D7984 else 0xFF376C60)
    val ObjectFill get()=Color(if(InterfaceSettings.blue) 0xFFDCE6EC else 0xFFDFE4E7)
    val Danger = Color(0xFFA13E36)
}
val AgendaSans = FontFamily(Font(R.font.notosanssc),Font(R.font.notosanssc_semibold,FontWeight.SemiBold))
val AgendaSerif = FontFamily(Font(R.font.notoserifsc),Font(R.font.notoserifsc_semibold,FontWeight.SemiBold))
val BodyFont = AgendaSans
val AgendaBrandFont = FontFamily(Font(R.font.spacegrotesk_semibold,FontWeight.SemiBold))
val AgendaNumbers = FontFamily(Font(R.font.manrope),Font(R.font.manrope_semibold,FontWeight.SemiBold))
val AgendaTimes = FontFamily(Font(R.font.times_new_roman),Font(R.font.times_new_roman_bold,FontWeight.Bold))
val AgendaLato = FontFamily(Font(R.font.lato),Font(R.font.lato_bold,FontWeight.Bold))
val SelectedEnglishFont get()=if(InterfaceSettings.bodyTypeface=="lato") AgendaLato else AgendaTimes

enum class FontRoute { BRAND, INTERFACE, NUMBERS, CHINESE_TITLE, CHINESE_BODY }

/** English headings and body share the selected face; existing numeral and CJK routes stay fixed. */
fun fontRoute(codePoint:Int,title:Boolean=false,brand:Boolean=false):FontRoute=when {
    codePoint in 0x30..0x39 || codePoint in 0x21..0x2F || codePoint in 0x3A..0x40 ||
        codePoint in 0x5B..0x60 || codePoint in 0x7B..0x7E -> FontRoute.NUMBERS
    codePoint==0x20 || Character.UnicodeScript.of(codePoint)==Character.UnicodeScript.LATIN ->
        if(brand) FontRoute.BRAND else FontRoute.INTERFACE
    else -> if(title) FontRoute.CHINESE_TITLE else FontRoute.CHINESE_BODY
}

fun routeFamily(route:FontRoute):FontFamily=when(route) {
    FontRoute.BRAND->AgendaBrandFont
    FontRoute.INTERFACE->SelectedEnglishFont
    FontRoute.NUMBERS->AgendaNumbers
    FontRoute.CHINESE_TITLE->AgendaSerif
    FontRoute.CHINESE_BODY->AgendaSans
}

@Composable
fun AgendaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink.Primary, onPrimary = Ink.Surface,
        surface = Ink.Surface, onSurface = Ink.Text, background = Ink.Canvas,
        onBackground = Ink.Text, outline = Ink.Divider, secondary = Ink.Teal), content = content)
}

@Composable
fun AgendaText(text: String, modifier: Modifier = Modifier, title: Boolean = false,
    size: Int = 14, color: Color = Ink.Text, maxLines: Int = Int.MAX_VALUE,
    weight: FontWeight = FontWeight.Normal, decoration: TextDecoration? = null, brand:Boolean=false) {
    // English headings and body use the same face; numerals and CJK retain their original families.
    val mixed = agendaAnnotated(text,title,brand)
    Text(mixed, modifier, color = color, fontSize = size.sp, lineHeight = (size * 1.5).sp,
        fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFeatureSettings = "tnum", fontSynthesis=FontSynthesis.None,textDecoration=decoration))
}

fun agendaAnnotated(text:String, title:Boolean=false,brand:Boolean=false): AnnotatedString = buildAnnotatedString {
    var index=0
    while(index<text.length) {
        val start=index
        val route=fontRoute(text.codePointAt(index),title,brand)
        index+=Character.charCount(text.codePointAt(index))
        while(index<text.length) {
            val cp=text.codePointAt(index)
            // Keep an accent with its preceding letter and never split surrogate pairs.
            if(Character.getType(cp)!=Character.NON_SPACING_MARK.toInt() && fontRoute(cp,title,brand)!=route) break
            index+=Character.charCount(cp)
        }
        withStyle(SpanStyle(fontFamily=routeFamily(route),fontFeatureSettings="tnum",fontSynthesis=FontSynthesis.None)) {
            append(text.substring(start,index))
        }
    }
}
