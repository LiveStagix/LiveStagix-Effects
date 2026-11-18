package com.livestagix.renderer.effects

import com.livestagix.renderer.api.RendererType

/** Stable, API-neutral identity for a standalone or overlay visual effect. */
data class RendererEffectDescriptor(
    val id: String,
    val displayName: String,
    val supportsGyro: Boolean,
    val requiresMedia: Boolean,
    val defaultParameters: Map<String, Float> = emptyMap(),
    /** Empty until a backend has a real, tested implementation of this effect. */
    val implementedBackends: Set<RendererType> = emptySet()
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(defaultParameters.keys.all(String::isNotBlank))
        require(defaultParameters.values.all(Float::isFinite))
    }
}

/** Shared built-in IDs copied from LiveWP. GPU resources and shaders stay backend-specific. */
object RendererEffectCatalog {
    const val PULSE_HALO = "pulse_halo"
    const val REACTOR_CORE = "reactor_core"

    val builtIn: List<RendererEffectDescriptor> = listOf(
        RendererEffectDescriptor(
            id = PULSE_HALO,
            displayName = "Pulse Halo",
            supportsGyro = true,
            requiresMedia = false
        ),
        RendererEffectDescriptor(
            id = REACTOR_CORE,
            displayName = "Reactor Core",
            supportsGyro = true,
            requiresMedia = false
        )
    )

    fun find(effectId: String?): RendererEffectDescriptor? {
        val normalized = effectId?.trim()?.lowercase() ?: return null
        return builtIn.firstOrNull { it.id == normalized }
    }

    fun normalize(effectId: String?): String = find(effectId)?.id.orEmpty()
}
