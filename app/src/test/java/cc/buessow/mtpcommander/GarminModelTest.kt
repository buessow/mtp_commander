package cc.buessow.mtpcommander

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarminModelTest {
    @Test
    fun readsProductFromEdgeDeviceFit() {
        val fit = javaClass.getResourceAsStream("/edge1030plus-Device.fit")!!.readBytes()
        assertEquals(3570, GarminModel.productId(fit))
    }

    @Test
    fun ignoresDataThatIsNotFit() {
        assertNull(GarminModel.productId(ByteArray(64)))
    }
}
