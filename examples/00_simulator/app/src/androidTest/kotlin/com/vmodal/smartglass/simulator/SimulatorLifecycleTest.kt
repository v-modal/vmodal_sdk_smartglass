package com.vmodal.smartglass.simulator

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimulatorLifecycleTest {
    @Test
    fun fullLifecycleCanRestartAndReset() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                fun status(text: String) {
                    assertNotNull("Expected simulator status: $text", findText(root, text))
                }
                fun click(text: String) {
                    val button = findText(root, text) as? Button
                    assertNotNull("Missing simulator button: $text", button)
                    button!!.performClick()
                }
                fun cycle() {
                    click("Power on, unfold, and don")
                    status("Mock glasses are powered on, unfolded, and donned.")
                    click("Doff, fold, and power off")
                    status("Mock glasses are doffed, folded, and powered off.")
                }

                status("Mock Device Kit enabled; Ray-Ban Meta paired.")
                cycle()
                cycle()
                click("Reset mock device")
                status("Mock Device Kit disabled. Tap Enable and pair to restart.")
                click("Enable and pair")
                status("Mock Device Kit enabled; Ray-Ban Meta paired.")
                cycle()
            }
        }
    }

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
