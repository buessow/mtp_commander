package cc.buessow.mtpcommander

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String?>): File = temp.newFile("test.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                text?.let { out.write(it.toByteArray()) }
                out.closeEntry()
            }
        }
    }

    @Test
    fun listsFoldersFirstIncludingThoseWithoutTheirOwnEntry() {
        val file = zip("b.txt" to "bee", "Activities/" to null, "Activities/run.fit" to "run",
            "Courses/route.fit" to "route", "a.txt" to "a")
        val top = ZipArchive.list(file, "")
        assertEquals(listOf("Activities", "Courses", "a.txt", "b.txt"), top.map { it.name })
        assertEquals(listOf(true, true, false, false), top.map { it.directory })
        assertEquals(3L, top.single { it.name == "b.txt" }.size)
        assertEquals(listOf("route.fit"), ZipArchive.list(file, "Courses").map { it.name })
        assertEquals("Courses/route.fit", ZipArchive.path(ZipArchive.list(file, "Courses").single()))
    }

    @Test
    fun readsAFile() {
        val file = zip("notes/today.txt" to "hello")
        assertEquals("hello", ZipArchive.read(file, "notes/today.txt") { it.reader().readText() })
    }

    @Test
    fun extractsFilesAndFolders() {
        val file = zip("Garmin/Activities/run.fit" to "run", "Garmin/Activities/ride.fit" to "ride", "top.txt" to "top")
        val single = File(temp.root, "top.txt")
        ZipArchive.extract(file, "top.txt", single)
        assertEquals("top", single.readText())

        val folder = File(temp.root, "Garmin")
        var progress = 0L to 0L
        ZipArchive.extract(file, "Garmin", folder) { copied, total -> progress = copied to total }
        assertEquals("ride", File(folder, "Activities/ride.fit").readText())
        assertEquals("run", File(folder, "Activities/run.fit").readText())
        assertEquals(7L to 7L, progress)
    }

    @Test
    fun refusesEntriesThatLeaveTheTarget() {
        val file = zip("data/../../evil.txt" to "evil")
        assertThrows(IllegalStateException::class.java) { ZipArchive.extract(file, "data", File(temp.root, "out")) }
        assertFalse(File(temp.root, "evil.txt").exists())
    }

    @Test
    fun recognizesZipNames() {
        assertTrue(ZipArchive.isZip("Backup.ZIP"))
        assertFalse(ZipArchive.isZip("zip.fit"))
    }
}
