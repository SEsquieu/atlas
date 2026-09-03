package com.grinningfrog.atlas.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.grinningfrog.atlas.model.ContextStability
import com.grinningfrog.atlas.model.MotionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

class MotionMonitor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val samples = ArrayDeque<Float>()
    private val mutableState = MutableStateFlow(MotionState.UNKNOWN)
    val state: StateFlow<MotionState> = mutableState

    fun start() {
        accelerometer?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = manager?.unregisterListener(this)

    fun stability(): ContextStability = when (mutableState.value) {
        MotionState.STATIONARY, MotionState.HANDHELD_STABLE -> ContextStability.STABLE
        MotionState.TURNING, MotionState.WALKING, MotionState.VEHICLE -> ContextStability.TRANSITIONING
        MotionState.UNKNOWN -> ContextStability.UNKNOWN
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        samples.addLast(abs(magnitude - SensorManager.GRAVITY_EARTH))
        while (samples.size > 12) samples.removeFirst()
        if (samples.size < 6) return
        val mean = samples.average()
        mutableState.value = when {
            mean < 0.18 -> MotionState.STATIONARY
            mean < 0.75 -> MotionState.HANDHELD_STABLE
            mean < 2.5 -> MotionState.WALKING
            else -> MotionState.TURNING
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
