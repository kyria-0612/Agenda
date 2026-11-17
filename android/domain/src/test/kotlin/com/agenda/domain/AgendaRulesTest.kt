package com.agenda.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AgendaRulesTest {
    @Test fun dailyReminderUsesTomorrowThroughThirdDayOnly() {
        val today=LocalDate.of(2026,12,30)
        val tasks=(-1L..4L).map {Task("day$it","任务",due=today.plusDays(it))}+
            Task("done","完成",due=today.plusDays(1),completed=true)+Task("none","无日期")
        assertEquals(listOf("day1","day2","day3"),reminderTasks(tasks,today).map {it.id})
    }
    @Test fun spatialSlotsKeepGapsAndFillOnlyFreePositions() {
        val saved=listOf(BoardPlacement("a",1,3),BoardPlacement("b",0,0))
        val result=boardPlacements(listOf("a","b","new"),saved).associateBy {it.cardId}
        assertEquals(saved[0],result["a"]);assertEquals(saved[1],result["b"])
        assertEquals(BoardPlacement("new",1,0),result["new"])
    }
    @Test fun duplicateSpatialSlotsResolveWithoutLosingCards() {
        val result=boardPlacements(listOf("a","b"),listOf(BoardPlacement("a",0,0),BoardPlacement("b",0,0)))
        assertEquals(2,result.size);assertEquals(2,result.map {it.column to it.row}.distinct().size)
    }
    @Test fun absentTimeIsDifferentFromMidnightAndLocalMinutesAreStable() {
        assertEquals(-1,scheduleMinuteKey(null))
        assertEquals(0,scheduleMinuteKey(java.time.LocalTime.MIDNIGHT))
        assertEquals(9*60+30,scheduleMinuteKey(java.time.LocalTime.of(9,30)))
    }
    @Test(expected=AgendaFailure.InvalidInput::class) fun emptyTemporaryScheduleIsInvalid() {
        validateSchedule(ScheduleDraft(LocalDate.of(2026,10,28),Period.MORNING))
    }
    @Test fun compressedCardConservesAreaAndExpandedCardGrowsVertically() {
        val baseWidth=180f;val collapsed=148f
        assertEquals(collapsed,boardCardHeight(baseWidth,baseWidth,collapsed,360f,0f,0f),.01f)
        val neighbourHeight=boardCardHeight(baseWidth,90f,collapsed,1000f,0f,1f)
        assertEquals(baseWidth*collapsed,90f*neighbourHeight,.01f)
        assertTrue(boardCardHeight(baseWidth,270f,collapsed,120f,1f,0f)>collapsed)
    }
    @Test fun morphHasIntermediateFramesAndBoundedElasticOvershoot() {
        val middle=boardCardHeight(180f,230f,148f,360f,.5f,0f)
        val end=boardCardHeight(180f,270f,148f,360f,1f,0f)
        val overshoot=boardCardHeight(180f,280f,148f,360f,1.1f,0f)
        assertTrue(middle>148f && middle<end)
        assertTrue(overshoot>end && overshoot<end*1.15f)
    }
    @Test fun pointerUpCannotPoisonGraphTransform() {
        val old=GraphTransform(42f,30f,.8f)
        assertEquals(old,applyGraphGesture(old,Float.NaN,Float.NaN,0f,0f,1f))
        assertEquals(old,applyGraphGesture(old,200f,200f,0f,0f,Float.NaN))
        assertEquals(old,applyGraphGesture(old,200f,200f,Float.POSITIVE_INFINITY,0f,1f))
        assertEquals(GraphTransform(62f,20f,.8f),applyGraphGesture(old,100f,100f,20f,-10f,1f))
    }
    @Test fun zoomIsAnchoredAtFingerAndBounded() {
        val old=GraphTransform(10f,20f,1f)
        val next=applyGraphGesture(old,100f,200f,0f,0f,1.5f)
        assertEquals(-35f,next.x,.001f)
        assertEquals(-70f,next.y,.001f)
        assertEquals(1.7f,applyGraphGesture(old,100f,200f,0f,0f,10f).scale,.001f)
    }
    private val today=LocalDate.of(2026,12,29)
    private fun task(id:String,offset:Long?,done:Boolean=false) = Task(id,id,due=offset?.let {today.plusDays(it)},completed=done)
    @Test fun urgentWindowCrossesYearAndExcludesOverdueCompletedAndUndated() {
        val tasks=listOf(task("overdue",-1),task("today",0),task("last-day",6),task("outside",7),task("done",1,true),task("undated",null))
        assertEquals(listOf("today","last-day"),urgentTasks(tasks,today).map {it.id})
    }
    @Test fun threeEarliestWinWithStableTiesAndCompletionRefills() {
        val tasks=listOf(task("d",3),task("b",1),task("a",1),task("c",2))
        assertEquals(listOf("a","b","c"),urgentTasks(tasks,today).map {it.id})
        assertEquals(listOf("b","c","d"),urgentTasks(tasks.map {if(it.id=="a") it.copy(completed=true) else it},today).map {it.id})
    }
    @Test fun rollingDateRecomputesCandidateWindow() {
        val tasks=listOf(task("today",0),task("tomorrow",1),task("outside",7))
        assertEquals(listOf("tomorrow","outside"),urgentTasks(tasks,today.plusDays(1)).map {it.id})
    }
    @Test fun dateWindowDoesNotDependOnCategoryOrObject() {
        assertEquals(1,urgentTasks(listOf(task("unclassified",0)),today).size)
        assertTrue(urgentTasks(emptyList(),today).isEmpty())
    }
    @Test fun resistanceIsBoundedAndProgressivelyStronger() {
        assertEquals(25f,dampedPosition(25f,0f,100f),.001f)
        assertTrue(dampedPosition(10000f,0f,100f)<220f)
        assertTrue(dampedPosition(-10000f,0f,100f)> -120f)
        val near=dampedPosition(120f,0f,100f)-100f
        val far=dampedPosition(140f,0f,100f)-dampedPosition(120f,0f,100f)
        assertTrue(far<near)
    }
}
