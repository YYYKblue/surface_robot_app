package cn.surface.operator

import cn.surface.operator.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class MapCodecTest {
    private fun fixture()=wireJson.parseToJsonElement(javaClass.getResource("/compressed-map.json")!!.readText()).jsonObject.obj("map")
    private fun encode(bytes:ByteArray):String {
        val out=ByteArrayOutputStream()
        DeflaterOutputStream(out).use {it.write(bytes)}
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
    private fun rejects(raw:JsonObject) {
        try {GridMap.parse(raw);fail("expected invalid map")} catch(_:IllegalArgumentException) {}
    }
    @Test fun realGatewayCompressedPaletteMatchesLegacyOccupancyAndGeometry() {
        val compressed=fixture();val decoded=GridMap.parse(compressed)
        assertArrayEquals(byteArrayOf(-1,0,0,100,100,-1),decoded.cells)
        assertEquals(Point(2.0,-1.0),decoded.origin);assertEquals(9L,decoded.revision)
        assertEquals(.05,decoded.resolution,0.0);assertEquals("map",decoded.frame)
        val legacy=JsonObject(compressed-filterSet+packet("data" to JsonArray(listOf(-1,0,50,51,100,-1).map(::JsonPrimitive))))
        assertEquals(GridMap.parse(legacy).cells.map {if(it<0) -1 else if(it>50) 100 else 0},decoded.cells.map {it.toInt()})
    }
    private val filterSet=setOf("encoding","data_zlib_base64")
    @Test fun truncatedTrailingOverlongAndInvalidIndexPayloadsAreRejected() {
        val raw=fixture();val encoded=raw.str("data_zlib_base64");val compressed=Base64.getDecoder().decode(encoded)
        for(bytes in listOf(compressed.copyOf(compressed.size-1),compressed+byteArrayOf(0),byteArrayOf(1,2,3)))
            rejects(JsonObject(raw+packet("data_zlib_base64" to Base64.getEncoder().encodeToString(bytes))))
        for(pixels in listOf(byteArrayOf(0,1,2,0,1),byteArrayOf(0,1,2,0,1,2,0),byteArrayOf(0,1,2,0,1,3),ByteArray(1000000)))
            rejects(JsonObject(raw+packet("data_zlib_base64" to encode(pixels))))
        rejects(JsonObject(raw+packet("data_zlib_base64" to "not!base64")))
    }
    @Test fun dimensionsAndGeometryAreValidatedBeforeDecompression() {
        val raw=fixture()
        rejects(JsonObject(raw+packet("width" to 4000001,"height" to 1)))
        rejects(JsonObject(raw+packet("width" to 0)))
        rejects(JsonObject(raw+packet("resolution" to 0)))
        rejects(JsonObject(raw+packet("origin" to packet("x" to 0,"y" to 0))))
    }
}
