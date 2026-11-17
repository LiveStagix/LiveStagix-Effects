package com.livestagix.renderer.api

/** Result of checking whether a backend can run on the current device. */
sealed interface RendererSupportResult {
    data class Supported(
        val capabilities: RendererCapabilities
    ) : RendererSupportResult

    /**
     * Confirmed incompatibility. This result is reserved for permanent device
     * or driver limitations, not transient initialization or surface failures.
     */
    data class Unsupported(
        val failure: RendererFailure
    ) : RendererSupportResult {
        init {
            require(failure.category.isPermanentForDevice) {
                "Unsupported results require a permanent failure category"
            }
        }
    }

    /** The backend has not performed a reliable capability check yet. */
    data object NotChecked : RendererSupportResult
}
