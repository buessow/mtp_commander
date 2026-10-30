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

    @Test
    fun namesProductsFromTheSdk() {
        assertEquals("Garmin Edge 1030 Plus", GarminModel.name(3570))
        assertEquals("Garmin Forerunner 955", GarminModel.name(4024))
        assertEquals("Garmin device 65000", GarminModel.name(65000))
    }
}
