package com.livestagix.renderer.api

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Selects one backend for one surface and performs explicit fallback.
 * A failed candidate is closed before the next candidate is attempted.
 */
class RendererManager(backends: Collection<RendererBackend>) {
    private val backendsByType: Map<RendererType, RendererBackend>

    init {
        require(backends.isNotEmpty()) { "At least one renderer backend is required" }
        val mapped = backends.associateBy { it.type }
        require(mapped.size == backends.size) { "Renderer backend types must be unique" }
        backendsByType = mapped
    }

    fun createSession(
        context: Context,
        request: RendererCreateRequest,
        policy: RendererSelectionPolicy = RendererSelectionPolicy()
    ): RendererManagedSession =
        createSessionInternal(context, request, policy, requireContentSession = false)

    private fun createSessionInternal(
        context: Context,
        request: RendererCreateRequest,
        policy: RendererSelectionPolicy,
        requireContentSession: Boolean
    ): RendererManagedSession {
        val attempts = mutableListOf<RendererBackendAttempt>()

        for (type in policy.orderedBackends()) {
            val backend = backendsByType[type]
            if (backend == null) {
                attempts += RendererBackendAttempt(
                    backendType = type,
                    failure = RendererFailure(
                        RendererFailureCategory.BACKEND_UNAVAILABLE,
                        "Backend " + type + " was not registered",
                        "RENDERER_BACKEND_NOT_REGISTERED"
                    )
                )
                continue
            }

            val support = try {
                backend.inspectSupport(context)
            } catch (_: Exception) {
                RendererSupportResult.NotChecked
            }

            if (support is RendererSupportResult.Unsupported) {
                attempts += RendererBackendAttempt(
                    backendType = type,
                    supportResult = support,
                    failure = support.failure
                )
                continue
            }

            var session: RendererSession? = null
            try {
                session = backend.createSession(request)
                if (session.backendType != backend.type) {
                    throw RendererException(
                        RendererFailure(
                            RendererFailureCategory.INITIALIZATION_FAILED,
                            "Backend " + backend.type + " created a " + session.backendType + " session",
                            "RENDERER_BACKEND_TYPE_MISMATCH"
                        )
                    )
                }

                if (requireContentSession && session !is RendererContentSession) {
                    session.close()
                    session = null
                    attempts += RendererBackendAttempt(
                        backendType = type,
                        supportResult = support,
                        failure = RendererFailure(
                            RendererFailureCategory.MISSING_REQUIRED_FEATURE,
                            "Backend " + type + " does not expose a content session",
                            "RENDERER_CONTENT_SESSION_UNAVAILABLE"
                        )
                    )
                    continue
                }

                val availableFeatures = session.capabilities?.contentFeatures.orEmpty()
                val missingFeatures = policy.requiredContentFeatures - availableFeatures
                if (missingFeatures.isNotEmpty()) {
                    session.close()
                    session = null
                    attempts += RendererBackendAttempt(
                        backendType = type,
                        supportResult = support,
                        failure = RendererFailure(
                            RendererFailureCategory.MISSING_REQUIRED_FEATURE,
                            "Backend " + type + " does not implement required content features: " +
                                missingFeatures.joinToString(),
                            "RENDERER_CONTENT_FEATURES_UNAVAILABLE"
                        )
                    )
                    continue
                }

                return if (session is RendererContentSession) {
                    RendererManagedContentSession(session, attempts.toList())
                } else {
                    RendererManagedSession(session, attempts.toList())
                }
            } catch (error: Exception) {
                runCatching { session?.close() }
                val failure = (error as? RendererException)?.failure
                    ?: RendererFailure(
                        RendererFailureCategory.UNKNOWN,
                        error.message ?: "Unknown renderer initialization failure",
                        "RENDERER_BACKEND_CREATE_FAILED"
                    )
                attempts += RendererBackendAttempt(
                    backendType = type,
                    supportResult = support,
                    failure = failure
                )
            }
        }

        throw RendererManagerException(attempts.toList())
    }

    fun createContentSession(
        context: Context,
        request: RendererCreateRequest,
        policy: RendererSelectionPolicy = RendererSelectionPolicy()
    ): RendererManagedContentSession =
        createSessionInternal(context, request, policy, requireContentSession = true)
            as RendererManagedContentSession

}

/** Explicit selection and fallback policy. */
data class RendererSelectionPolicy(
    val preferredBackend: RendererType? = null,
    val allowFallback: Boolean = true,
    val fallbackOrder: List<RendererType> = DEFAULT_RENDERER_BACKEND_ORDER,
    val requiredContentFeatures: Set<RendererContentFeature> = emptySet()
) {
    init {
        require(fallbackOrder.isNotEmpty()) { "At least one backend candidate is required" }
        require(fallbackOrder.distinct().size == fallbackOrder.size) {
            "Fallback backend order must not contain duplicates"
        }
    }

    internal fun orderedBackends(): List<RendererType> {
        val ordered = buildList {
            preferredBackend?.let { add(it) }
            fallbackOrder.filterNot { it == preferredBackend }.forEach { add(it) }
        }
        return if (allowFallback) ordered else ordered.take(1)
    }
}

data class RendererBackendAttempt(
    val backendType: RendererType,
    val supportResult: RendererSupportResult? = null,
    val failure: RendererFailure
)

class RendererManagerException(
    val attempts: List<RendererBackendAttempt>
) : RuntimeException(
    attempts.joinToString(
        prefix = "No renderer backend could be started",
        separator = "; "
    ) {
        it.backendType.toString() + ": " + it.failure.category + " - " + it.failure.message
    }
)

/** A managed session, retaining the failures that led to the selected backend. */
open class RendererManagedSession internal constructor(
    private val delegate: RendererSession,
    val fallbackAttempts: List<RendererBackendAttempt>
) : RendererSession {
    private val closed = AtomicBoolean(false)

    override val backendType: RendererType
        get() = delegate.backendType

    override val capabilities: RendererCapabilities?
        get() = delegate.capabilities

    override fun resize(width: Int, height: Int) {
        if (!closed.get()) delegate.resize(width, height)
    }

    override fun setPaused(paused: Boolean) {
        if (!closed.get()) delegate.setPaused(paused)
    }

    override fun setFailureListener(listener: RendererFailureListener?) {
        if (!closed.get()) delegate.setFailureListener(listener)
    }

    protected fun isOpen(): Boolean = !closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }
}

/** Content-aware wrapper that preserves scene and motion access after backend selection. */
class RendererManagedContentSession internal constructor(
    private val contentDelegate: RendererContentSession,
    fallbackAttempts: List<RendererBackendAttempt>
) : RendererManagedSession(contentDelegate, fallbackAttempts), RendererContentSession {
    override fun setScene(scene: RendererScene) {
        if (isOpen()) contentDelegate.setScene(scene.snapshot())
    }

    override fun setScene(
        scene: RendererScene,
        previousMediaReleased: RendererMediaReleaseListener?
    ) {
        if (isOpen()) contentDelegate.setScene(scene.snapshot(), previousMediaReleased)
    }

    override fun updateMotion(motion: RendererMotion) {
        if (isOpen()) contentDelegate.updateMotion(motion)
    }

    override fun updateSettings(settings: RendererSettings) {
        if (isOpen()) contentDelegate.updateSettings(settings)
    }

    override fun setPlaybackPaused(paused: Boolean) {
        if (isOpen()) contentDelegate.setPlaybackPaused(paused)
    }

    override fun setEventListener(listener: RendererEventListener?) {
        if (isOpen()) contentDelegate.setEventListener(listener)
    }
}

/** Default order for devices that allow automatic renderer selection. */
val DEFAULT_RENDERER_BACKEND_ORDER: List<RendererType> = listOf(
    RendererType.VULKAN,
    RendererType.OPENGL_ES_3,
    RendererType.OPENGL_ES_2
)
