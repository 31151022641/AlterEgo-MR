package com.oculus.camerademo.model3d

enum class PersonaSource(val label: String, val badgeColor: Long) {
    BUILTIN("Built-in", 0xFF00E5FF),
    ONLINE("Online Cloud", 0xFF7C4DFF),
    LOCAL_STORAGE("External / Local", 0xFF00E676)
}

data class DynamicPersona(
    val id: String,
    val displayName: String,
    val description: String,
    val primaryColorHex: Long,
    val accentColorHex: Long,
    val source: PersonaSource = PersonaSource.BUILTIN,
    val builtinPersona: HumanPersona? = null,
    val icon: String = "👤",
    val imageUrlOrPath: String? = null,
    val wireframe: Boolean = false,
    val defaultStickers: Set<Sticker3DType> = emptySet()
) {
    companion object {
        fun fromBuiltin(persona: HumanPersona): DynamicPersona {
            if (!persona.isAvailable) return fromBuiltin(HumanPersona.DIGITAL_TWIN)
            return when (persona) {
                HumanPersona.MESSI_3D -> DynamicPersona(
                    id = "PERSONA_MESSI",
                    displayName = persona.displayName,
                    description = persona.description,
                    primaryColorHex = persona.primaryColor.toLong() and 0xffffffffL,
                    accentColorHex = 0xFF693A1E,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "⚽"
                )
                HumanPersona.CYBER_SYNTH -> DynamicPersona(
                    id = "PERSONA_CYBER_SYNTH",
                    displayName = "Cyber-Synth",
                    description = persona.description,
                    primaryColorHex = 0xFF142332,
                    accentColorHex = 0xFF00F0FF,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "🤖"
                )
                HumanPersona.DIGITAL_TWIN -> DynamicPersona(
                    id = "PERSONA_DIGITAL_TWIN",
                    displayName = persona.displayName,
                    description = persona.description,
                    primaryColorHex = 0xFFE1AF91,
                    accentColorHex = 0xFFB45050,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "👤"
                )
                HumanPersona.GOLDEN_SCULPTURE -> DynamicPersona(
                    id = "PERSONA_GOLDEN_SCULPTURE",
                    displayName = "Golden Bust",
                    description = persona.description,
                    primaryColorHex = 0xFFDCB432,
                    accentColorHex = 0xFFFFEB96,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "👑"
                )
                HumanPersona.HOLO_HUMAN -> DynamicPersona(
                    id = "PERSONA_HOLO_HUMAN",
                    displayName = "Holo Human",
                    description = persona.description,
                    primaryColorHex = 0xFF00FFB4,
                    accentColorHex = 0xFFFFFFFF,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "🌐",
                    wireframe = true
                )
                HumanPersona.UNICORN_MEMOJI -> DynamicPersona(
                    id = "PERSONA_UNICORN_MEMOJI",
                    displayName = "Rainbow Unicorn Memoji",
                    description = persona.description,
                    primaryColorHex = 0xFFFFFFFF,
                    accentColorHex = 0xFFE040FB,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "🦄",
                    defaultStickers = emptySet()
                )
                HumanPersona.LION_MEMOJI -> DynamicPersona(
                    id = "PERSONA_LION_MEMOJI",
                    displayName = "Lion King Memoji",
                    description = persona.description,
                    primaryColorHex = 0xFFFFB300,
                    accentColorHex = 0xFFBF360C,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "🦁"
                )
                HumanPersona.DRAGON_MEMOJI -> DynamicPersona(
                    id = "PERSONA_DRAGON_MEMOJI",
                    displayName = "Dragon King Memoji",
                    description = persona.description,
                    primaryColorHex = 0xFF4CAF50,
                    accentColorHex = 0xFFFFD600,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "🐲"
                )
                HumanPersona.SUNGLASSES -> DynamicPersona(
                    id = "PERSONA_SUNGLASSES",
                    displayName = persona.displayName,
                    description = persona.description,
                    primaryColorHex = persona.primaryColor.toLong() and 0xffffffffL,
                    accentColorHex = persona.accentColor.toLong() and 0xffffffffL,
                    source = PersonaSource.BUILTIN,
                    builtinPersona = persona,
                    icon = "🕶"
                )
            }
        }
    }
}

object OnlineFaceCatalog {
    val curatedOnlineFaces = listOf(
        DynamicPersona(
            id = "ONLINE_CR7",
            displayName = "Cristiano Ronaldo (CR7)",
            description = "Athletic Portuguese football legend with iconic fade & intense focus",
            primaryColorHex = 0xFFD2A374,
            accentColorHex = 0xFFFFD700,
            source = PersonaSource.ONLINE,
            builtinPersona = HumanPersona.MESSI_3D,
            icon = "⚽",
            imageUrlOrPath = "https://images.meta.com/avatars/faces/cr7_gold.jpg",
            defaultStickers = setOf(Sticker3DType.NEON_CROWN)
        ),
        DynamicPersona(
            id = "ONLINE_ANIME_MECHA",
            displayName = "Anime Mecha Hero",
            description = "Vibrant cel-shaded anime protagonist with glowing electric cyber crest",
            primaryColorHex = 0xFFFCE4EC,
            accentColorHex = 0xFF00E5FF,
            source = PersonaSource.ONLINE,
            builtinPersona = HumanPersona.DIGITAL_TWIN,
            icon = "⚡",
            imageUrlOrPath = "https://images.meta.com/avatars/faces/anime_mecha.png",
            defaultStickers = setOf(Sticker3DType.CYBER_VISOR)
        ),
        DynamicPersona(
            id = "ONLINE_CYBER_ONI",
            displayName = "Cyber Oni Mask",
            description = "Futuristic Japanese cyber-demon faceplate with crimson horns & fangs",
            primaryColorHex = 0xFFD50000,
            accentColorHex = 0xFFFFD700,
            source = PersonaSource.ONLINE,
            builtinPersona = HumanPersona.DIGITAL_TWIN,
            icon = "👹",
            imageUrlOrPath = "https://images.meta.com/avatars/faces/cyber_oni.png",
            defaultStickers = setOf(Sticker3DType.NEON_HORNS)
        ),
        DynamicPersona(
            id = "ONLINE_NEBULA_ALIEN",
            displayName = "Nebula Extraterrestrial",
            description = "Iridescent cosmic humanoid with bioluminescent violet skin & almond optics",
            primaryColorHex = 0xFF7B1FA2,
            accentColorHex = 0xFF69F0AE,
            source = PersonaSource.ONLINE,
            builtinPersona = HumanPersona.DIGITAL_TWIN,
            icon = "👽",
            imageUrlOrPath = "https://images.meta.com/avatars/faces/nebula_alien.png",
            wireframe = true,
            defaultStickers = setOf(Sticker3DType.CYBER_STAR)
        ),
        DynamicPersona(
            id = "ONLINE_NEKO_CYBORG",
            displayName = "Neko Cyber Android",
            description = "Sleek pastel cybernetic cat-humanoid with holographic whiskers & sensors",
            primaryColorHex = 0xFFF8BBD0,
            accentColorHex = 0xFF18FFFF,
            source = PersonaSource.ONLINE,
            builtinPersona = HumanPersona.DIGITAL_TWIN,
            icon = "🐱",
            imageUrlOrPath = "https://images.meta.com/avatars/faces/neko_android.png",
            defaultStickers = setOf(Sticker3DType.CAT_EARS)
        )
    )

    fun getDefaultPersonas(): List<DynamicPersona> {
        val list = mutableListOf<DynamicPersona>()
        HumanPersona.available.forEach { list.add(DynamicPersona.fromBuiltin(it)) }
        return list
    }
}
