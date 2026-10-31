package cc.buessow.mtpcommander

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WahooModelTest {
    @Test
    fun namesRoamFromSystemInfo() {
        assertEquals("Wahoo ELEMNT ROAM", WahooModel.name("WD08-13007\n74,3967,1\n"))
        assertEquals("Wahoo ELEMNT ROAM", WahooModel.name("WD14-18001\n"))
        assertEquals("Wahoo ELEMNT ROAM", WahooModel.name("WR24-17068\n"))
    }

    @Test
    fun namesUnknownPrefixes() {
        assertEquals("Wahoo device (WX)", WahooModel.name("WX12-1\n"))
    }

    @Test
    fun ignoresOtherFiles() {
        assertNull(WahooModel.name("hello world"))
        assertNull(WahooModel.name(""))
    }
}
