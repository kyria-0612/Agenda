package com.agenda.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import com.agenda.domain.TaskDraft
import com.agenda.domain.Effort

class DemoAgendaRepositoryTest {
    @Test fun attachmentsAndNotesRemainWithTheirChosenObject():Unit = runBlocking {
        val repository=DemoAgendaRepository()
        repository.addMaterial("course","my-report.pdf")
        val noteId=repository.addNote("course","  新说明  ","  具体说明内容  ")
        val objects=repository.snapshot.value.objects
        assertTrue(objects.first {it.id=="course"}.materials.any {it.name=="my-report.pdf"})
        val saved=objects.first {it.id=="course"}.notes.first {it.id==noteId}
        assertEquals("新说明",saved.title)
        assertEquals("具体说明内容",saved.body)
        assertFalse(objects.first {it.id=="agenda"}.materials.any {it.name=="my-report.pdf"})
        assertFalse(objects.first {it.id=="agenda"}.notes.any {it.id==noteId})
    }
    @Test fun attachmentToMissingObjectIsRejected():Unit = runBlocking {
        val repository=DemoAgendaRepository()
        assertThrows(IllegalArgumentException::class.java) {repository.addMaterial("missing","a.pdf")}
        assertThrows(IllegalArgumentException::class.java) {runBlocking {repository.addNote("course"," ","内容")}}
        assertThrows(IllegalArgumentException::class.java) {runBlocking {repository.addNote("course","标题"," ")}}
    }
    @Test fun duplicateNoteTitlesKeepIndependentBodiesAndIds():Unit = runBlocking {
        val repository=DemoAgendaRepository()
        val first=repository.addNote("course","说明","第一份正文")
        val second=repository.addNote("course","说明","第二份正文")
        val notes=repository.snapshot.value.objects.first {it.id=="course"}.notes
        assertNotEquals(first,second)
        assertEquals("第一份正文",notes.first {it.id==first}.body)
        assertEquals("第二份正文",notes.first {it.id==second}.body)
    }
    @Test fun richTaskDraftKeepsDateObjectAndIndependentIntents():Unit = runBlocking {
        val repository=DemoAgendaRepository()
        val due=LocalDate.of(2026,11,3)
        val id=repository.createTask(TaskDraft("报告",Effort.FOCUS,"course",due,wants=true,should=true))
        val saved=repository.snapshot.value.tasks.first {it.id==id}
        assertEquals(due,saved.due)
        assertEquals("course",saved.objectId)
        assertTrue(saved.wants && saved.should)
    }
    @Test fun referencedCompletionSharesTaskButTemporaryEntryStaysIndependent():Unit = runBlocking {
        val repository=DemoAgendaRepository(LocalDate.of(2026,10,20))
        repository.setScheduleCompleted("s-report",true)
        var snapshot=repository.snapshot.value
        assertTrue(snapshot.tasks.first {it.id=="report"}.completed)
        assertTrue(snapshot.schedule.first {it.id=="s-report"}.isCompleted(snapshot.tasks))
        assertFalse(snapshot.schedule.first {it.id=="s-walk"}.isCompleted(snapshot.tasks))
        repository.setCompleted("report",false)
        repository.setScheduleCompleted("s-walk",true)
        snapshot=repository.snapshot.value
        assertFalse(snapshot.schedule.first {it.id=="s-report"}.isCompleted(snapshot.tasks))
        assertTrue(snapshot.schedule.first {it.id=="s-walk"}.isCompleted(snapshot.tasks))
    }
    @Test fun demoSessionIsResetAndNewTaskHasStableIdentityWithinSession():Unit = runBlocking {
        val repository=DemoAgendaRepository()
        val id=repository.createTask("  新任务  ",null)
        assertEquals("新任务",repository.snapshot.value.tasks.first {it.id==id}.name)
        assertFalse(DemoAgendaRepository().snapshot.value.tasks.any {it.id==id})
    }
}
