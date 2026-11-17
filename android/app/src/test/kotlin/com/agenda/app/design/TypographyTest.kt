package com.agenda.app.design

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TypographyTest {
    @After fun resetTypeface() {InterfaceSettings.selectBodyTypeface("times")}

    @Test fun digitsAndAsciiPunctuationKeepManropeInBothEnglishModes() {
        val characters="0123456789!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        characters.forEach {c->
            assertEquals(c.toString(),FontRoute.NUMBERS,fontRoute(c.code))
            assertEquals(c.toString(),FontRoute.NUMBERS,fontRoute(c.code,title=true))
            InterfaceSettings.selectBodyTypeface("lato")
            assertEquals(AgendaNumbers,routeFamily(fontRoute(c.code)))
            InterfaceSettings.selectBodyTypeface("times")
            assertEquals(AgendaNumbers,routeFamily(fontRoute(c.code)))
        }
    }
    @Test fun chineseTitlesAndBodyHaveDifferentFamilies() {
        "任务，。！？年月".forEach {c->
            assertEquals(FontRoute.CHINESE_TITLE,fontRoute(c.code,title=true))
            assertEquals(FontRoute.CHINESE_BODY,fontRoute(c.code))
        }
        assertNotEquals(AgendaSerif,AgendaSans)
    }
    @Test fun fontChoiceAppliesToAllEnglishButKeepsBrandAndNumbers() {
        InterfaceSettings.selectBodyTypeface("times")
        "Title and body".forEach {c->
            assertEquals(AgendaTimes,routeFamily(fontRoute(c.code,title=true)))
            assertEquals(AgendaTimes,routeFamily(fontRoute(c.code)))
        }
        InterfaceSettings.selectBodyTypeface("lato")
        "Title and body".forEach {c->
            assertEquals(AgendaLato,routeFamily(fontRoute(c.code,title=true)))
            assertEquals(AgendaLato,routeFamily(fontRoute(c.code)))
        }
        assertEquals(AgendaBrandFont,routeFamily(FontRoute.BRAND))
        assertEquals(AgendaNumbers,routeFamily(FontRoute.NUMBERS))
    }
    @Test fun typographyChangesNeverChangeTheInputValue() {
        val text="任务 Focus 2026/11/14，Cafe\u0301 \uD840\uDC00"
        assertEquals(text,agendaAnnotated(text).text)
        assertTrue(agendaAnnotated(text).spanStyles.all {it.item.fontFeatureSettings=="tnum"})
        val supplementaryStart=text.indexOf('\uD840')
        assertTrue(agendaAnnotated(text).spanStyles.none {it.start==supplementaryStart+1 || it.end==supplementaryStart+1})
    }
    @Test fun contiguousLettersKeepShapingAndKerningTogether() {
        val mixed=agendaAnnotated("Focus 2026")
        assertEquals("Focus ",mixed.text.substring(mixed.spanStyles.first().start,mixed.spanStyles.first().end))
        assertEquals(AgendaTimes,mixed.spanStyles.first().item.fontFamily)
        assertEquals(AgendaNumbers,mixed.spanStyles.last().item.fontFamily)
    }
    @Test fun brandHasASeparateFontResourceFamily() {
        val brand=agendaAnnotated("Agenda",brand=true)
        assertEquals(1,brand.spanStyles.size)
        assertEquals(AgendaBrandFont,brand.spanStyles.single().item.fontFamily)
    }
}
