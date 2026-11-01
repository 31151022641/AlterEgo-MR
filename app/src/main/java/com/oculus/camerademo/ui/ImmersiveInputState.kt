package com.oculus.camerademo.ui

/** Merge this SDK frame's existing hand/controller buttons without sampling
 * physical hand joints again. A hand's Menu edge remains meaningful when its
 * pointing ray is suppressed; an inactive physical controller is ignored. */
class ImmersiveInputState(
    private val menu: MenuPressGate,
    private val back: MenuPressGate,
    private val mirror: MenuPressGate,
) {
    var menuDown = false; private set
    private var freshMenu = false
    private var handMenu = false
    private var backDown = false
    private var mirrorDown = false

    fun beginFrame() {
        menuDown = false; freshMenu = false; handMenu = false
        backDown = false; mirrorDown = false
    }

    fun sample(hand: Boolean, active: Boolean, menuDown: Boolean, menuPressed: Boolean,
               backDown: Boolean = false, mirrorDown: Boolean = false) {
        if (!hand && !active) return
        this.menuDown = this.menuDown || menuDown
        freshMenu = freshMenu || menuPressed
        handMenu = handMenu || hand && (menuDown || menuPressed)
        if (!hand && active) {
            this.backDown = this.backDown || backDown
            this.mirrorDown = this.mirrorDown || mirrorDown
        }
    }

    fun finishFrame(focused: Boolean, nowMs: Long): Int {
        val open = if (focused && freshMenu) menu.controllerPress(true, nowMs)
            else menu.controller(menuDown, focused, nowMs)
        var actions = if (open) MENU or (if (handMenu) HAND else 0) else 0
        if (back.controller(backDown, focused, nowMs)) actions = actions or BACK
        if (mirror.controller(mirrorDown, focused, nowMs)) actions = actions or MIRROR
        return actions
    }

    companion object {
        const val MENU = 1
        const val BACK = 2
        const val MIRROR = 4
        const val HAND = 8
    }
}
