package com.oculus.camerademo.ui

enum class QuickMenuPage(val title: String) {
    HOME("Choose an action"), FACES("Faces & Avatars"), LINK("PC Link"),
    IMPORT("Add face"), SETTINGS("Settings")
}

/** Navigation never changes the selected avatar, tracking mode or pairing. */
data class QuickMenuNavigation(val open: Boolean = false, val page: QuickMenuPage = QuickMenuPage.HOME) {
    /** Immersive Back leaves the mode once no visible submenu remains. */
    val backReturnsToMirror: Boolean get() = !open || page == QuickMenuPage.HOME
    fun toggle() = if (open) copy(open = false) else copy(open = true, page = QuickMenuPage.HOME)
    fun show(page: QuickMenuPage = QuickMenuPage.HOME) = copy(open = true, page = page)
    fun close() = copy(open = false)
    /** Reset recovers the window's content; an explicit Menu request uses show(). */
    fun recoverWindow(liveMirror: Boolean) = if (liveMirror) close() else show()
    fun back() = if (page == QuickMenuPage.HOME) close() else copy(page = QuickMenuPage.HOME)
}

/** Combines Android and SDK delivery of one physical press. Release changes
 * cannot toggle, repeats cannot toggle, and focus recovery waits for release. */
class MenuPressGate {
    private var controllerDown = false
    private var keyDown = false
    private var controllerArmed = true
    private var lastAccepted = Long.MIN_VALUE
    private var lastSource = ""

    /** A trusted SDK changed-button DOWN is already a fresh edge, even after
     * focus loss. Held-state polling still requires release before rearming. */
    @Synchronized fun controllerPress(focused: Boolean, nowMs: Long): Boolean {
        if(!focused || (lastSource=="controller" && recentlyAccepted(nowMs)))return false
        controllerDown=true;controllerArmed=true
        // Unlike a polled held bit, a fresh runtime gesture may follow a lost
        // Android key-up. The time window still merges duplicate delivery.
        return accept(nowMs,"controller")
    }

    @Synchronized fun controller(down: Boolean, focused: Boolean, nowMs: Long): Boolean {
        if (!focused) { controllerDown = down; controllerArmed = false; return false }
        if (!down) { controllerDown = false; controllerArmed = true; return false }
        val edge = !controllerDown && controllerArmed
        controllerDown = true
        return edge && !(keyDown && lastSource=="key") && accept(nowMs,"controller")
    }
    @Synchronized fun key(down: Boolean, repeat: Int, focused: Boolean, nowMs: Long): Boolean {
        if (!down) { keyDown = false; return false }
        // Some controller/hand Menu keys lose their UP event when Horizon
        // changes focus. A later fresh DOWN (repeat=0) must not stay latched out.
        val edge = repeat == 0 && (!keyDown || !recentlyAccepted(nowMs))
        keyDown = true
        return focused && edge && !(controllerDown && lastSource=="controller") && accept(nowMs,"key")
    }
    @Synchronized fun recentlyAccepted(nowMs: Long) = lastAccepted != Long.MIN_VALUE &&
        nowMs-lastAccepted in -DUPLICATE_MS until DUPLICATE_MS
    @Synchronized fun focusLost() { controllerArmed = false; keyDown = false }
    private fun accept(nowMs: Long,source: String): Boolean {
        if (nowMs < 0L || lastSource!=source && recentlyAccepted(nowMs)) return false
        lastAccepted = nowMs;lastSource=source;return true
    }
    companion object { const val DUPLICATE_MS = 180L }
}
