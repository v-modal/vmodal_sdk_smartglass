package com.vmodal.smartglass.simulator

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses

class MainActivity : Activity() {
    private val kit by lazy { MockDeviceKit.getInstance(applicationContext) }
    private var glasses: MockGlasses? = null
    private lateinit var status: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
        }
        body.addView(TextView(this).apply {
            text = "Meta Smart Glasses simulator"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 17f
            setPadding(0, px(20), 0, px(20))
        }
        body.addView(status)
        body.addButton("Enable and pair") { simulatorEnable() }
        body.addButton("Power on, unfold, and don") { simulatorWear() }
        body.addButton("Doff, fold, and power off") { simulatorSleep() }
        body.addButton("Reset mock device") { simulatorReset() }
        setContentView(ScrollView(this).apply { addView(body) })
        simulatorEnable()
    }

    override fun onDestroy() {
        kit.disable()
        super.onDestroy()
    }

    private fun simulatorEnable() = simulatorAction {
        if (glasses == null) {
            kit.enable()
            glasses = kit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
            "Mock Device Kit enabled; Ray-Ban Meta paired."
        } else {
            "Ray-Ban Meta is already paired."
        }
    }

    private fun simulatorWear() = simulatorAction {
        val ownGlasses = glasses ?: error("Pair the mock glasses first.")
        ownGlasses.powerOn()
        ownGlasses.unfold()
        ownGlasses.don()
        "Mock glasses are powered on, unfolded, and donned."
    }

    private fun simulatorSleep() = simulatorAction {
        val ownGlasses = glasses ?: error("Pair the mock glasses first.")
        ownGlasses.doff()
        ownGlasses.fold()
        ownGlasses.powerOff()
        "Mock glasses are doffed, folded, and powered off."
    }

    private fun simulatorReset() = simulatorAction {
        kit.disable()
        glasses = null
        "Mock Device Kit disabled. Tap Enable and pair to restart."
    }

    private fun simulatorAction(action: () -> String) {
        status.text = runCatching(action).getOrElse { err ->
            "Simulator error: ${err.message ?: err.javaClass.simpleName}"
        }
    }

    private fun LinearLayout.addButton(label: String, action: () -> Unit) {
        addView(Button(context).apply {
            text = label
            setOnClickListener { action() }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
