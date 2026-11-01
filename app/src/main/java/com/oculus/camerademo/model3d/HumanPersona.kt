package com.oculus.camerademo.model3d

enum class HumanPersona(
    val displayName: String,
    val description: String,
    val primaryColor: Int,
    val accentColor: Int,
    val wireframe: Boolean,
    val isTextured: Boolean = false
) {
    UNICORN_MEMOJI(
        displayName = "Rainbow Unicorn Memoji",
        description = "Horse-shaped unicorn head with a long muzzle, spiral horn and flowing pastel mane",
        primaryColor = 0xffffffff.toInt(),
        accentColor = 0xffe040fb.toInt(),
        wireframe = false,
        isTextured = true
    ),
    LION_MEMOJI(
        displayName = "Lion King Memoji",
        description = "Golden lion head with a broad feline muzzle, whiskers, rounded ears and a full mane",
        primaryColor = 0xffffb300.toInt(),
        accentColor = 0xffd84315.toInt(),
        wireframe = false,
        isTextured = true
    ),
    DRAGON_MEMOJI(
        displayName = "Mythic Dragon Memoji",
        description = "Emerald dragon head with a broad snout, webbed frills, swept horns and slit pupils",
        primaryColor = 0xff4caf50.toInt(),
        accentColor = 0xffffd600.toInt(),
        wireframe = false,
        isTextured = true
    ),
    MESSI_3D(
        displayName = "Snow Portrait",
        description = "Artist-created Snow portrait with sculpted hair and expressive facial animation",
        primaryColor = 0xffdaa884.toInt(),
        accentColor = 0xff693a1e.toInt(),
        wireframe = false,
        isTextured = true
    ),
    CYBER_SYNTH(
        displayName = "Cyber-Synth Human",
        description = "Futuristic cybernetic persona with glowing synth lines",
        primaryColor = 0xff142332.toInt(),
        accentColor = 0xff00f0ff.toInt(),
        wireframe = false
    ),
    DIGITAL_TWIN(
        displayName = "Rain Portrait",
        description = "Artist-created Rain portrait with sculpted hair and expressive facial animation",
        primaryColor = 0xffe1af91.toInt(),
        accentColor = 0xffb45050.toInt(),
        wireframe = false
    ),
    GOLDEN_SCULPTURE(
        displayName = "Classical Golden Bust",
        description = "Polished bronze/gold human sculpture that animates and speaks",
        primaryColor = 0xffdcb432.toInt(),
        accentColor = 0xffffeb96.toInt(),
        wireframe = false
    ),
    HOLO_HUMAN(
        displayName = "Holographic Matrix Human",
        description = "Rounded holographic cartoon character with luminous accents",
        primaryColor = 0xff00ffb4.toInt(),
        accentColor = 0xffffffff.toInt(),
        wireframe = true
    ),
    SUNGLASSES(
        displayName = "Sunglasses",
        description = "Standalone 3D sunglasses that follow the real face in passthrough",
        primaryColor = 0xff111b25.toInt(),
        accentColor = 0xff527589.toInt(),
        wireframe = false
    );

    // Retain legacy enum values so saved preferences can migrate safely.
    val isAvailable: Boolean
        get() = this != GOLDEN_SCULPTURE && this != CYBER_SYNTH && this != HOLO_HUMAN

    val isPassthroughOnly: Boolean
        get() = this == SUNGLASSES

    companion object {
        val available: List<HumanPersona> = values().filter { it.isAvailable }
        val mirrorAvailable: List<HumanPersona> = available.filterNot { it.isPassthroughOnly }

        fun restore(savedName: String?, fallback: HumanPersona = DIGITAL_TWIN): HumanPersona {
            val saved = values().firstOrNull { it.name == savedName }
            return if (saved != null) {
                if (saved.isAvailable) saved else DIGITAL_TWIN
            } else if (fallback.isAvailable) fallback else DIGITAL_TWIN
        }

        fun restoreMirror(savedName: String?, fallback: HumanPersona = DIGITAL_TWIN): HumanPersona {
            val safeFallback = fallback.takeIf { it.isAvailable && !it.isPassthroughOnly } ?: DIGITAL_TWIN
            return restore(savedName, safeFallback).takeUnless { it.isPassthroughOnly } ?: safeFallback
        }

        fun next(current: HumanPersona): HumanPersona =
            available[(available.indexOf(current) + 1).mod(available.size)]
    }
}
