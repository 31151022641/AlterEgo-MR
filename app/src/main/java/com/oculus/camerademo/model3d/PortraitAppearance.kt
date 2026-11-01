package com.oculus.camerademo.model3d

import android.graphics.Color

/** One palette for the native headset mesh and both software portrait views. */
object PortraitAppearance {
    fun forPersona(persona: HumanPersona, primaryColor: Int = persona.primaryColor): PortraitStyle = when (persona) {
        HumanPersona.DIGITAL_TWIN -> PortraitStyle(
            skin = primaryColor, hair = Color.rgb(28, 23, 23), lips = Color.rgb(180, 93, 114), variant = "classic")
        HumanPersona.MESSI_3D -> PortraitStyle(
            skin = primaryColor, hair = Color.rgb(52, 36, 29), lips = Color.rgb(165, 102, 97), beard = true, variant = "bearded")
        HumanPersona.GOLDEN_SCULPTURE -> PortraitStyle(
            skin = primaryColor, hair = Color.rgb(143, 105, 35), lips = Color.rgb(185, 136, 49), metallic = true, variant = "gold")
        HumanPersona.CYBER_SYNTH -> PortraitStyle(
            skin = Color.rgb(maxOf(Color.red(primaryColor), 61), maxOf(Color.green(primaryColor), 91),
                maxOf(Color.blue(primaryColor), 119)),
            hair = Color.rgb(14, 26, 39), lips = Color.rgb(55, 159, 179), variant = "cyber")
        HumanPersona.HOLO_HUMAN -> PortraitStyle(
            skin = primaryColor, hair = Color.rgb(7, 107, 91), lips = Color.rgb(73, 211, 174), variant = "holo")
        HumanPersona.UNICORN_MEMOJI -> PortraitStyle(
            skin = primaryColor, hair = Color.rgb(122, 76, 142), lips = Color.rgb(185, 112, 145), character = "unicorn")
        HumanPersona.LION_MEMOJI -> PortraitStyle(
            skin = primaryColor, hair = Color.rgb(104, 61, 33), lips = Color.rgb(130, 70, 47), character = "lion")
        HumanPersona.DRAGON_MEMOJI -> PortraitStyle(
            skin = primaryColor, hair = Color.rgb(35, 78, 50), lips = Color.rgb(51, 106, 62), character = "dragon")
        HumanPersona.SUNGLASSES -> PortraitStyle(skin = primaryColor, character = "sunglasses")
    }
}
