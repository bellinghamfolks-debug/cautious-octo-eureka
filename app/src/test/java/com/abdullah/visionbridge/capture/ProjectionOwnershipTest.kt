package com.abdullah.visionbridge.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the one MediaProjection rule that cannot be checked at compile time and costs the whole
 * capture session when it is broken.
 *
 * Android 14 answers a second `createVirtualDisplay()` on a live projection with
 * `SecurityException: Don't take multiple captures by invoking
 * MediaProjection#createVirtualDisplay multiple times on the same instance`, and then stops the
 * projection. The 2026-08-02 device bundle contains three of these, one per capture session, each
 * followed by `PROJECTION_SYSTEM_STOPPED` within 3 ms, and one of them left the user with 216
 * seconds of nothing at all.
 *
 * There is no unit-testable seam for this: the call is on a framework object obtained from a system
 * service through a user consent dialog. Reading the source is the honest way to hold the line, and
 * it fails the moment someone reaches for a second display again.
 */
class ProjectionOwnershipTest {

    private val source: String = readSource("capture/MediaProjectionService.kt")

    @Test
    fun `the projection is asked for exactly one virtual display`() {
        val callSites = Regex("createVirtualDisplay\\s*\\(").findAll(source).count()
        assertEquals(
            "One MediaProjection consent yields one createVirtualDisplay() call for its whole " +
                "life. Replace a surface with VirtualDisplay.setSurface()/resize() on the display " +
                "already held; a second createVirtualDisplay() is a SecurityException on API 34+ " +
                "and kills the capture session.",
            1,
            callSites,
        )
    }

    @Test
    fun `surface recovery re-points the display it already holds`() {
        val recovery = functionBody("refreshCaptureSurfaceAfterBlackFeed")
        assertTrue(
            "Black-feed recovery must attach a new surface to the existing display.",
            recovery.contains("setSurface("),
        )
        assertTrue(
            "Black-feed recovery must never create a second virtual display.",
            !recovery.contains("createVirtualDisplay"),
        )
    }

    /**
     * A repair that fails must leave the capture no worse than it found it. The previous version
     * published the replacement ImageReader into the field before asking the framework to accept
     * it, so a rejected surface left the service listening to a reader with no producer.
     */
    @Test
    fun `the replacement reader is published only after the display accepts it`() {
        val recovery = functionBody("refreshCaptureSurfaceAfterBlackFeed")
        val attach = recovery.indexOf("setSurface(")
        val publish = recovery.indexOf("imageReader = replacement")
        assertTrue("Expected the recovery to attach a surface", attach >= 0)
        assertTrue("Expected the recovery to publish a replacement reader", publish >= 0)
        assertTrue(
            "The replacement reader must be published after setSurface() has succeeded.",
            publish > attach,
        )
    }

    /** A capture the system takes away must be announced in audio, not only on a screen. */
    @Test
    fun `a projection stopped by the system is spoken`() {
        val onStop = functionBody("onStop")
        assertTrue(
            "onStop must speak a notice; a blind user cannot see runtime.stopped().",
            onStop.contains("speakUrgentNotice("),
        )
    }

    /** Returns the body of [name], from its opening brace to the matching close. */
    private fun functionBody(name: String): String {
        val signature = Regex("fun\\s+$name\\s*\\(").find(source)
            ?: run {
                fail("No function named $name in MediaProjectionService.kt")
                error("unreachable")
            }
        val open = source.indexOf('{', signature.range.last)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        fail("Unbalanced braces while reading $name")
        error("unreachable")
    }

    private fun readSource(relative: String): String {
        val roots = listOf(
            File("src/main/java/com/abdullah/visionbridge"),
            File("app/src/main/java/com/abdullah/visionbridge"),
        )
        val file = roots.map { File(it, relative) }.firstOrNull(File::isFile)
        if (file == null) {
            fail("Could not locate $relative from ${File(".").absolutePath}")
            error("unreachable")
        }
        return file.readText()
    }
}
