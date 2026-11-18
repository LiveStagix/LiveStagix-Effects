package com.livestagix.renderer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererManagerContentSessionTest {
    @Test
    fun managedContentSessionForwardsContentAndMotionCalls() {
        val delegate = RecordingContentSession()
        val session = RendererManagedContentSession(delegate, emptyList())
        val listener = RendererEventListener { }

        session.setScene(RendererScene.EMPTY)
        session.setScene(RendererScene.EMPTY, null)
        session.updateMotion(RendererMotion.NEUTRAL)
        session.updateSettings(RendererSettings.DEFAULT)
        session.setPlaybackPaused(true)
        session.setEventListener(listener)

        assertEquals(2, delegate.sceneUpdates)
        assertSame(RendererMotion.NEUTRAL, delegate.motion)
        assertSame(RendererSettings.DEFAULT, delegate.settings)
        assertEquals(true, delegate.pausedState)
        assertSame(listener, delegate.listenerState)
    }

    @Test
    fun managedContentSessionStopsForwardingAfterClose() {
        val delegate = RecordingContentSession()
        val session = RendererManagedContentSession(delegate, emptyList())

        session.close()
        session.setScene(RendererScene.EMPTY)
        session.updateMotion(RendererMotion.NEUTRAL)
        session.updateSettings(RendererSettings.DEFAULT)

        assertEquals(1, delegate.closeCount)
        assertEquals(0, delegate.sceneUpdates)
        assertTrue(delegate.motion == null)
        assertTrue(delegate.settings == null)
    }

    private class RecordingContentSession : RendererContentSession {
        override val backendType = RendererType.OPENGL_ES_2
        override val capabilities = RendererCapabilities(backendType)
        var sceneUpdates = 0
        var motion: RendererMotion? = null
        var settings: RendererSettings? = null
        var pausedState = false
        var listenerState: RendererEventListener? = null
        var closeCount = 0

        override fun resize(width: Int, height: Int) = Unit
        override fun setPaused(paused: Boolean) {
            this.pausedState = paused
        }
        override fun setFailureListener(listener: RendererFailureListener?) = Unit
        override fun setScene(scene: RendererScene) {
            sceneUpdates++
        }
        override fun setScene(
            scene: RendererScene,
            previousMediaReleased: RendererMediaReleaseListener?
        ) {
            sceneUpdates++
        }
        override fun updateMotion(motion: RendererMotion) {
            this.motion = motion
        }
        override fun updateSettings(settings: RendererSettings) {
            this.settings = settings
        }
        override fun setPlaybackPaused(paused: Boolean) {
            this.paused = paused
        }
        override fun setEventListener(listener: RendererEventListener?) {
            listenerState = listener
        }
        override fun close() {
            closeCount++
        }
    }
}
