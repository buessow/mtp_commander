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

    @Test
    fun namesModelFromGarminDeviceXml() {
        // The <Model> part of an inReach Mini 2's GarminDevice.xml.
        val xml = """<?xml version="1.0" encoding="UTF-8"?><Device xmlns="http://www.garmin.com/xmlschemas/GarminDevice/v2">""" +
            "<Model><PartNumber>006-B3968-00</PartNumber><SoftwareVersion>504</SoftwareVersion>" +
            "<Description>inReach Mini 2</Description></Model><Id>1</Id></Device>"
        assertEquals("Garmin inReach Mini 2", GarminModel.fromDeviceXml(xml))
        assertEquals("Garmin Edge 830", GarminModel.fromDeviceXml("<Model><Description>Garmin Edge 830</Description></Model>"))
        assertNull(GarminModel.fromDeviceXml("<Device><Model><Description></Description></Model></Device>"))
        assertNull(GarminModel.fromDeviceXml("not xml"))
    }
}
