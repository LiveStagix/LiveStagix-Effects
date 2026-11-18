package com.livestagix.renderer.api

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.InputStream

/**
 * Host-owned media access. The renderer never receives the app Context, ContentResolver,
 * preferences, or storage policy. The host remains responsible for URI permissions and ownership.
 */
interface RendererMediaAccess {
    /** Opens a fresh stream. The caller owns and must close it. */
    fun openInputStream(uri: Uri): InputStream

    /** Descriptor access for native decoders such as MediaExtractor. */
    fun openFileDescriptor(uri: Uri, mode: String = "r"): ParcelFileDescriptor? = null

    /** Optional MIME information supplied by the host. */
    fun getMimeType(uri: Uri): String? = null
}
