package cc.buessow.mtpcommander

import com.garmin.fit.BufferEncoder
import com.garmin.fit.DeveloperDataIdMesg
import com.garmin.fit.DeveloperField
import com.garmin.fit.FieldDescriptionMesg
import com.garmin.fit.Fit
import com.garmin.fit.SessionMesg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContentsTest {
    @Test
    fun showsFitHeaderAndOneTablePerMessageType() {
        val view = javaClass.getResourceAsStream("/edge1030plus-Device.fit")!!.use { FileContents.view("Device.fit", it) }
        assertNull(view.lines)
        assertEquals("File header", view.sections[0].title)
        val fileId = view.sections[1]
        assertTrue(fileId.title, fileId.title.startsWith("file_id"))
        val row = fileId.rows.single()
        assertEquals("garmin", row[fileId.columns.indexOf("manufacturer")])
        assertEquals("3570", row[fileId.columns.indexOf("product")])
        assertEquals(1, view.sections.count { it.title.startsWith("software") })
    }

    @Test
    fun showsDeveloperFieldsWithoutUnits() {
        // Connect IQ fields like CIQ_device_info come without units.
        val id = DeveloperDataIdMesg().apply { setDeveloperDataIndex(0); setApplicationId(0, 1) }
        val description = FieldDescriptionMesg().apply {
            setDeveloperDataIndex(0); setFieldDefinitionNumber(0); setFitBaseTypeId(Fit.BASE_TYPE_UINT8.toShort())
            setFieldName(0, "CIQ_device_info")
        }
        val session = SessionMesg().apply {
            setTotalElapsedTime(60f)
            addDeveloperField(DeveloperField(description, id).apply { value = 7 })
        }
        val fit = BufferEncoder(Fit.ProtocolVersion.V2_0).apply { write(listOf(id, description, session)) }.close()
        val view = FileContents.view("activity.fit", fit, truncated = false)
        assertNull(view.sections[0].rows.firstOrNull { it[0] == "Decoding stopped" })
        val table = view.sections.single { it.title.startsWith("session") }
        assertEquals("7", table.rows.single()[table.columns.indexOf("CIQ_device_info")])
    }

    @Test
    fun showsTextLineByLine() {
        val view = FileContents.view("notes.txt", "one\ntwo\tcolumns\n".byteInputStream())
        assertEquals(listOf("one", "two\tcolumns", ""), view.lines)
        assertNull(view.message)
    }

    @Test
    fun showsOnlyTheStartOfLongText() {
        val view = FileContents.view("log.txt", "x".repeat(FileContents.TEXT_LIMIT + 1).byteInputStream())
        assertEquals(FileContents.TEXT_LIMIT, view.lines!!.single().length)
        assertNotNull(view.message)
    }

    @Test
    fun refusesBinaryFiles() {
        val view = FileContents.view("photo.jpg", byteArrayOf(-1, -40, -1, 0, 16, 74).inputStream())
        assertNull(view.lines)
        assertTrue(view.sections.isEmpty())
        assertNotNull(view.message)
    }
}
