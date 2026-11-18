package com.livestagix.renderer.api

/**
 * Latest filtered motion sample consumed by the renderer.
 *
 * Roll and pitch use degrees, matching the existing LiveWP motion model.
 * Depth preserves the host's calibrated LiveWP depth channel; it is not a distance
 * expressed in metres. Rotation is the accumulated Z-axis angle in degrees.
 *
 * The host remains responsible for registering Android sensors and supplying samples.
 * Rendering backends consume this state at their normal frame cadence.
 */
data class RendererMotion(
    val rollDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val depth: Float = 0f,
    val rotationDegrees: Float = 0f,
    val timestampNanos: Long = 0L,
    val configuration: RendererMotionConfiguration = RendererMotionConfiguration()
) {
    init {
        require(rollDegrees.isFinite()) { "Roll must be finite" }
        require(pitchDegrees.isFinite()) { "Pitch must be finite" }
        require(depth.isFinite()) { "Depth must be finite" }
        require(rotationDegrees.isFinite()) { "Rotation must be finite" }
        require(timestampNanos >= 0L) { "Motion timestamp must not be negative" }
    }

    companion object {
        val NEUTRAL = RendererMotion()
    }
}

/**
 * Motion controls shared by photo and effect renderers.
 *
 * Sensitivity and automatic-stabilization values are percentages. Rate values retain
 * the limits used by LiveWP's existing motion implementation.
 */
data class RendererMotionConfiguration(
    val enabled: Boolean = false,
    val twoDimensionalEnabled: Boolean = false,
    val threeDimensionalEnabled: Boolean = false,
    val rotationEnabled: Boolean = false,
    val twoDimensionalInverted: Boolean = false,
    val threeDimensionalInverted: Boolean = false,
    val rotationInverted: Boolean = false,
    val sensitivity2dX: Int = 50,
    val sensitivity2dY: Int = 50,
    val sensitivity3dX: Int = 50,
    val sensitivity3dY: Int = 50,
    val depthSensitivity: Int = 50,
    val rotationSensitivity: Int = 50,
    val autoStabilize2dX: Int = 0,
    val autoStabilize2dY: Int = 0,
    val autoStabilize3dX: Int = 0,
    val autoStabilize3dY: Int = 0,
    val autoStabilizeZoom: Int = 0,
    val autoStabilizeRotation: Int = 0,
    val zoomResponseFactor: Float = 1f,
    val edgeSafetyEnabled: Boolean = true,
    val translationRate: Float = 0.05f,
    val rotationRateDegreesPerSecond: Float = 2f,
    val depthRate: Float = 1f,
    val rotationRateZDegreesPerSecond: Float = 2f
) {
    init {
        requirePercentages(
            sensitivity2dX,
            sensitivity2dY,
            sensitivity3dX,
            sensitivity3dY,
            depthSensitivity,
            rotationSensitivity,
            autoStabilize2dX,
            autoStabilize2dY,
            autoStabilize3dX,
            autoStabilize3dY,
            autoStabilizeZoom,
            autoStabilizeRotation
        )
        require(zoomResponseFactor.isFinite() && zoomResponseFactor in 0f..1f) {
            "Zoom response factor must be between 0 and 1"
        }
        require(translationRate.isFinite() && translationRate in 0.01f..0.15f) {
            "Translation rate must be between 0.01 and 0.15 normalized screen units per second"
        }
        require(
            rotationRateDegreesPerSecond.isFinite() &&
                rotationRateDegreesPerSecond in 0.5f..8f
        ) {
            "Rotation rate must be between 0.5 and 8 degrees per second"
        }
        require(depthRate.isFinite() && depthRate in 0.25f..4f) {
            "Depth rate must be between 0.25 and 4"
        }
        require(
            rotationRateZDegreesPerSecond.isFinite() &&
                rotationRateZDegreesPerSecond in 0f..15f
        ) {
            "Z rotation rate must be between 0 and 15 degrees per second"
        }
    }
}

private fun requirePercentages(vararg values: Int) {
    require(values.all { it in 0..100 }) {
        "Motion sensitivities and stabilization values must be between 0 and 100"
    }
}
