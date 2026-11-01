package com.oculus.camerademo.model3d

import org.junit.Assert.*
import org.junit.Test

class HumanPersonaCatalogTest {
    private val removed = listOf(HumanPersona.GOLDEN_SCULPTURE,
        HumanPersona.CYBER_SYNTH, HumanPersona.HOLO_HUMAN)

    @Test fun passthroughCatalogIncludesSunglassesAndKeepsRemovedFacesHidden() {
        assertEquals(listOf(HumanPersona.UNICORN_MEMOJI, HumanPersona.LION_MEMOJI,
            HumanPersona.DRAGON_MEMOJI, HumanPersona.MESSI_3D, HumanPersona.DIGITAL_TWIN,
            HumanPersona.SUNGLASSES), HumanPersona.available)
        assertTrue(removed.none { it in HumanPersona.available })
    }

    @Test fun sunglassesAreSavedIndependentlyAndExcludedFromMirrorFaces() {
        assertEquals(HumanPersona.SUNGLASSES, HumanPersona.restore("SUNGLASSES"))
        assertTrue(HumanPersona.SUNGLASSES.isPassthroughOnly)
        assertEquals(HumanPersona.available.filterNot { it.isPassthroughOnly }, HumanPersona.mirrorAvailable)
        assertFalse(HumanPersona.SUNGLASSES in HumanPersona.mirrorAvailable)
        assertEquals(HumanPersona.DIGITAL_TWIN, HumanPersona.restoreMirror("SUNGLASSES"))
        assertEquals(HumanPersona.UNICORN_MEMOJI,
            HumanPersona.restoreMirror("SUNGLASSES", HumanPersona.UNICORN_MEMOJI))
        for (persona in HumanPersona.mirrorAvailable) assertEquals(persona, HumanPersona.restoreMirror(persona.name))
    }

    @Test fun savedRemovedFacesMigrateToRainInEitherView() {
        for (persona in removed) {
            assertEquals(HumanPersona.DIGITAL_TWIN, HumanPersona.restore(persona.name))
            assertEquals(HumanPersona.DIGITAL_TWIN,
                HumanPersona.restore(persona.name, HumanPersona.UNICORN_MEMOJI))
        }
    }

    @Test fun cyclingVisitsEachRemainingFaceAndWrapsWithoutLegacyOrdinals() {
        var current = HumanPersona.available.first()
        val visited = mutableListOf<HumanPersona>()
        repeat(HumanPersona.available.size) {
            visited += current
            current = HumanPersona.next(current)
        }
        assertEquals(HumanPersona.available, visited)
        assertEquals(HumanPersona.available.first(), current)
    }

    @Test fun validSavedChoicesAndViewDefaultsRemainStable() {
        for (persona in HumanPersona.available) assertEquals(persona, HumanPersona.restore(persona.name))
        assertEquals(HumanPersona.UNICORN_MEMOJI, HumanPersona.restore(null, HumanPersona.UNICORN_MEMOJI))
        assertEquals(HumanPersona.DIGITAL_TWIN, HumanPersona.restore("unknown"))
        assertEquals(HumanPersona.DIGITAL_TWIN, HumanPersona.restore(null, HumanPersona.GOLDEN_SCULPTURE))
    }
}
