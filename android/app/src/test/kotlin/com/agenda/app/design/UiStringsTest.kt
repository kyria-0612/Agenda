package com.agenda.app.design

import androidx.compose.ui.graphics.toArgb
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UiStringsTest {
    @After fun resetLanguage() {InterfaceSettings.selectEnglish(false);InterfaceSettings.selectBlue(false)}

    @Test fun placeholdersDoNotRewriteUserContent() {
        val user="任务 {1} · Keep my 中文"
        assertEquals("Complete: $user / 2",formatUiText("Complete: {0} / {1}",user,2))
    }
    @Test fun englishChangesInterfaceCopyButNotNames() {
        InterfaceSettings.selectEnglish(true)
        assertEquals("Quick wins",tr("随手做"))
        assertEquals("Complete: 我的任务",tr("完成：{0}","我的任务"))
        assertEquals("我的任务",tr("我的任务"))
    }
    @Test fun chineseUsesOriginalCopy() {
        InterfaceSettings.selectEnglish(false)
        assertEquals("3 项待做",tr("{0} 项待做",3))
    }
    @Test fun everyEnglishTemplatePreservesItsArguments() {
        val placeholders=Regex("\\{\\d+\\}")
        EnglishCopy.forEach {(key,value)->
            assertEquals(key,placeholders.findAll(key).map {it.value}.toSet(),placeholders.findAll(value).map {it.value}.toSet())
        }
    }
    @Test fun blueAndGreenThemesRetainTheirSeparatePalettes() {
        InterfaceSettings.selectBlue(false)
        val green=Meadow.Canvas
        assertEquals(0xFF456A5D.toInt(),Meadow.ObjectFill.toArgb())
        InterfaceSettings.selectBlue(true)
        assertNotEquals(green,Meadow.Canvas)
        assertEquals(Meadow.Text,Meadow.ObjectFill)
    }
}
