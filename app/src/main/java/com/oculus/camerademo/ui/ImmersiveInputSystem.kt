package com.oculus.camerademo.ui

import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.SystemDependencies
import com.meta.spatial.core.SystemDependencyConfig
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.oculus.camerademo.diagnostics.HandPerformanceLog

/** Runs after SDK controller acquisition, before interaction processing and
 * the end-of-frame data reset. ISDK owns hand tracking and prediction. */
class ImmersiveInputSystem(
    private val focused: () -> Boolean,
    menuGate: MenuPressGate,
    backGate: MenuPressGate,
    mirrorGate: MenuPressGate,
    private val onMenu: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onMirror: () -> Unit,
    private val performance: HandPerformanceLog? = null,
) : SystemBase() {
    private val controllers by lazy { Query.where { has(Controller.id) } }
    private val buttons = ImmersiveInputState(menuGate, backGate, mirrorGate)
    private var previousMenuDown = false
    private var previousSourceCounts = -1

    override fun getDependencies() = SystemDependencies(
        mustRunBefore = mutableSetOf(SystemDependencyConfig(IsdkSystem::class, false)))

    override fun execute() {
        val canHandle = focused()
        val now = SystemClock.uptimeMillis()
        buttons.beginFrame()
        if (!canHandle) { buttons.finishFrame(false, now); return }
        var hands = 0
        var activeHands = 0
        var controls = 0
        controllers.eval().forEach { entity ->
            if (!entity.isLocal()) return@forEach
            val controller = entity.getComponent<Controller>()
            val hand = controller.type == ControllerType.HAND
            if (hand) { hands++; if (controller.isActive) activeHands++ }
            else if (controller.type == ControllerType.CONTROLLER) controls++
            else return@forEach
            buttons.sample(hand, controller.isActive,
                controller.isDown(ButtonBits.ButtonMenu), controller.isPressed(ButtonBits.ButtonMenu),
                controller.isDown(ButtonBits.ButtonB), controller.isDown(ButtonBits.ButtonY))
        }
        val counts = (hands shl 16) or controls
        if (counts != previousSourceCounts) {
            previousSourceCounts = counts
            Log.i("AlterEgoMenu", "SDK input sources hands=$hands controllers=$controls")
        }
        if (buttons.menuDown != previousMenuDown) {
            previousMenuDown = buttons.menuDown
            Log.i("AlterEgoMenu", "SDK Menu down=${buttons.menuDown}")
        }
        val actions = buttons.finishFrame(true, now)
        performance?.input(hands, activeHands, controls, actions and ImmersiveInputState.MENU != 0)
        if (actions and ImmersiveInputState.MENU != 0)
            onMenu(if (actions and ImmersiveInputState.HAND != 0) "hand-menu-sdk" else "controller-sdk")
        if (actions and ImmersiveInputState.BACK != 0) onBack()
        if (actions and ImmersiveInputState.MIRROR != 0) onMirror()
    }
}
