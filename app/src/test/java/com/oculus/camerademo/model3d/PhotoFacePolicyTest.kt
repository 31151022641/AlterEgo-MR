package com.oculus.camerademo.model3d
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class PhotoFacePolicyTest {
    @Test fun onlyVerifiedImageTypesAreSupported(){assertTrue(PhotoFacePolicy.supported("image/png"));assertTrue(PhotoFacePolicy.supported("image/jpeg"));assertFalse(PhotoFacePolicy.supported("image/svg+xml"));assertFalse(PhotoFacePolicy.supported(null))}
    @Test fun zeroAndNegativeDimensionsFail(){assertFalse(PhotoFacePolicy.dimensions(0,10));assertFalse(PhotoFacePolicy.dimensions(10,-1))}
    @Test fun decodedPixelBudgetCannotOverflow(){assertFalse(PhotoFacePolicy.dimensions(Int.MAX_VALUE,Int.MAX_VALUE));assertFalse(PhotoFacePolicy.dimensions(8000,8000));assertTrue(PhotoFacePolicy.dimensions(4000,4000))}
    @Test fun veryLongEdgesFail(){assertFalse(PhotoFacePolicy.dimensions(20000,10))}
    @Test fun ordinaryBytesAreReadExactly(){val bytes=byteArrayOf(1,2,3,4);assertArrayEquals(bytes,PhotoFacePolicy.read(ByteArrayInputStream(bytes)))}
    @Test(expected=IllegalArgumentException::class) fun emptyFileFails(){PhotoFacePolicy.read(ByteArrayInputStream(byteArrayOf()))}
    @Test(expected=IllegalArgumentException::class) fun oversizedFileIsRejected(){PhotoFacePolicy.read(ByteArrayInputStream(ByteArray(PhotoFacePolicy.MAX_BYTES+1)))}
    @Test fun metadataCannotEscapePrivateFolder(){assertTrue(PhotoFacePolicy.validId("12345678-1234-1234-1234-123456789abc"));assertFalse(PhotoFacePolicy.validId("../secret"));assertFalse(PhotoFacePolicy.validId("C:/photo.png"))}
    @Test fun safeDisplayNameHasBoundedLength(){assertEquals("A face",PhotoFacePolicy.name(" \u0000A face\n "));assertEquals(40,PhotoFacePolicy.name("x".repeat(100)).length);assertEquals("My photo face",PhotoFacePolicy.name("\n"))}
    @Test fun zeroBulkReadStillMakesProgress(){val stream=object:java.io.InputStream(){var remaining=3;override fun read(b:ByteArray,off:Int,len:Int)=0;override fun read()=if(remaining-->0)7 else -1};assertArrayEquals(byteArrayOf(7,7,7),PhotoFacePolicy.read(stream))}
}
