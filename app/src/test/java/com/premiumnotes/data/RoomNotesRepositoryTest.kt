package com.premiumnotes.data

import android.content.Context
import androidx.room.Room
import com.premiumnotes.data.db.AppDatabase
import com.premiumnotes.model.NoteType
import com.premiumnotes.model.PageContent
import com.premiumnotes.model.PenStyle
import com.premiumnotes.model.Stroke
import com.premiumnotes.model.TranscriptSegment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomNotesRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RoomNotesRepository

    @Before
    fun setup() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomNotesRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun createNotebookEmitsToFlow() = runBlocking {
        repo.createNotebook("Math")
        val list = repo.notebooks.first()
        assertEquals(1, list.size)
        assertEquals("Math", list[0].title)
        assertEquals(0, list[0].pageCount)
    }

    @Test
    fun createNotebookDefaultsToNormalType() = runBlocking {
        val id = repo.createNotebook("Notes")
        assertEquals(NoteType.NORMAL, repo.getNotebook(id)?.type)
    }

    @Test
    fun createClassroomNotebookRoundTripsType() = runBlocking {
        val id = repo.createNotebook("Biology", NoteType.CLASSROOM)
        val nb = repo.getNotebook(id)
        assertEquals(NoteType.CLASSROOM, nb?.type)
        // The type shows up in the catalog flow too.
        assertEquals(NoteType.CLASSROOM, repo.notebooks.first().firstOrNull { it.id == id }?.type)
    }

    @Test
    fun duplicateNotebookPreservesType() = runBlocking {
        val id = repo.createNotebook("Lecture", NoteType.CLASSROOM)
        val copyId = repo.duplicateNotebook(id)
        assertTrue(copyId > 0)
        assertEquals(NoteType.CLASSROOM, repo.getNotebook(copyId)?.type)
    }

    @Test
    fun pageContentRoundTrip() = runBlocking {
        val nb = repo.createNotebook("Math")
        val pageId = repo.createPage(nb)
        val content = PageContent(
            strokes = listOf(
                Stroke(id = 1L, style = PenStyle(), pointsPacked = floatArrayOf(0f, 0f, 10f, 10f))
            )
        )
        repo.savePageContent(pageId, content)
        val loaded = repo.loadPageContent(pageId)
        assertEquals(1, loaded?.strokes?.size)
        assertEquals(4, loaded?.strokes?.get(0)?.pointsPacked?.size)
    }

    @Test
    fun duplicatePageIsIndependent() = runBlocking {
        val nb = repo.createNotebook("Notes")
        val p1 = repo.createPage(nb)
        repo.savePageContent(p1, PageContent(strokes = listOf(Stroke(7L, PenStyle(), floatArrayOf(1f, 2f)))))
        val p2 = repo.duplicatePage(p1)
        assertTrue(p2 > 0)
        val c2 = repo.loadPageContent(p2)!!
        assertEquals(1, c2.strokes.size)
        assertEquals(-1L, c2.strokes[0].id)
    }

    @Test
    fun transcriptRoundTrips() = runBlocking {
        val nb = repo.createNotebook("Class")
        val pageId = repo.createPage(nb)
        repo.savePageContent(
            pageId,
            PageContent(
                transcript = listOf(
                    TranscriptSegment(id = 1L, startMs = 0L, endMs = 1500L, text = "Hello class"),
                    TranscriptSegment(id = 2L, startMs = 1500L, endMs = 3200L, text = "today we cover algebra"),
                ),
                summary = "Algebra lecture",
            )
        )
        val loaded = repo.loadPageContent(pageId)!!
        assertEquals(2, loaded.transcript.size)
        assertEquals("Hello class", loaded.transcript[0].text)
        assertEquals(3200L, loaded.transcript[1].endMs)
        assertEquals("Algebra lecture", loaded.summary)
    }

    @Test
    fun duplicatePageClearsTranscriptAndSummary() = runBlocking {
        val nb = repo.createNotebook("Class")
        val p1 = repo.createPage(nb)
        repo.savePageContent(
            p1,
            PageContent(
                transcript = listOf(TranscriptSegment(3L, 0L, 1000L, "recorded words")),
                summary = "some summary",
            )
        )
        val p2 = repo.duplicatePage(p1)
        assertTrue(p2 > 0)
        val c2 = repo.loadPageContent(p2)!!
        assertEquals(0, c2.transcript.size)
        assertEquals(null, c2.summary)
    }

    @Test
    fun deleteNotebookCascadesPages() = runBlocking {
        val nb = repo.createNotebook("Temp")
        val pageId = repo.createPage(nb)
        repo.savePageContent(pageId, PageContent())
        repo.deleteNotebook(nb)
        assertEquals(0, repo.notebooks.first().size)
        assertTrue(repo.loadPageContent(pageId) == null)
    }
}