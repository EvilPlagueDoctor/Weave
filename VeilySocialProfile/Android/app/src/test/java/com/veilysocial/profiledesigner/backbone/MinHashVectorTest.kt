package com.veilysocial.profiledesigner.backbone

import org.junit.Assert.assertEquals
import org.junit.Test

class MinHashVectorTest {
    @Test
    fun wireVectorMatchesRust() {
        val signature = MinHash.fromFeatures(listOf("woodworking", "joinery", "oak", "furniture"))
        assertEquals("c34a7180275a2834", signature.compactHex())
        assertEquals(
            "zulz7TP60Pr9UF1kRv7iXIDo1LqCpglA2YVJu0B8SfMBM4WsHehsxt5Jom2pU0+IfLdWjzmY8VnQzDnfXY1g1EfCgqqlBX/C6/4QyS5RLuj2OIALzbBQSW9NJEP29U8EV7xOOUlhjDSS2BKsZPc7g/NavxrW5/YdyWBLtI2mXsY=",
            signature.toWireString(),
        )
    }
}
