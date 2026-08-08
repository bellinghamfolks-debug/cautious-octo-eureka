package com.abdullah.visionbridge.capture.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The registration stack, checked against transforms whose answer is known exactly.
 *
 * These are the techniques that replace the 24×24 grayscale difference: pyramidal Lucas-Kanade for
 * an affine warp, FAST corners with oriented BRIEF and a RANSAC homography for the cases it cannot
 * reach, and structural similarity in place of a pixel count.
 */
class RegistrationTest {

    private val base = VisionScenes.page()
    private val basePyramid = VisionScenes.pyramid(base)
    private val centre = VisionScenes.SIZE / 2.0

    // region projective algebra

    @Test
    fun `a transform composed with its inverse is the identity`() {
        val warp = Warp.similarity(centre, centre, 12.0, 1.2, 5.0, -3.0)
        val product = (warp * warp.inverse()!!).m
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (index in 0..8) {
            assertEquals(identity[index], product[index] / product[8], 1e-9)
        }
    }

    @Test
    fun `a transform reports the motion it encodes`() {
        val warp = Warp.similarity(centre, centre, 12.0, 1.2, 5.0, -3.0)
        val description = warp.describe(VisionScenes.SIZE, VisionScenes.SIZE)
        assertEquals(12.0, description.rotationDegrees, 1e-3)
        assertEquals(1.2, description.scale, 1e-3)
        assertEquals(5.0, description.translationX, 1e-9)
        assertEquals(-3.0, description.translationY, 1e-9)
    }

    @Test
    fun `a degenerate transform is rejected before it can be believed`() {
        assertTrue(Warp.identity().isPlausible())
        // A collapse to a line, a fold-over, and a value that is not a number.
        assertTrue(!Warp(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)).isPlausible())
        assertTrue(!Warp(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.5, 0.5, 1.0)).isPlausible())
        assertTrue(!Warp(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)).isPlausible())
        // Ten times the area is not a view of the same subject.
        assertTrue(!Warp.similarity(0.0, 0.0, 0.0, 3.5, 0.0, 0.0).isPlausible())
    }

    // endregion

    // region Lucas-Kanade

    @Test
    fun `lucas kanade recovers a translation`() {
        for (shift in listOf(1.0, 3.0, 6.0, 10.0)) {
            val moved = VisionScenes.transform(base, Warp.translation(shift, -shift / 2))
            val alignment = LucasKanade.align(basePyramid, VisionScenes.pyramid(moved))
            assertNotNull("no alignment for a shift of $shift", alignment)
            val motion = alignment!!.warp.describe(VisionScenes.SIZE, VisionScenes.SIZE)
            assertEquals("x for shift $shift", shift, motion.translationX, 0.6)
            assertEquals("y for shift $shift", -shift / 2, motion.translationY, 0.6)
        }
    }

    /** Turning a bottle to read its label. Translation-only compensation cannot follow this. */
    @Test
    fun `lucas kanade recovers a rotation`() {
        for (degrees in listOf(3.0, 7.0, 12.0, -9.0)) {
            val rotated = VisionScenes.transform(base, Warp.similarity(centre, centre, degrees, 1.0, 0.0, 0.0))
            val alignment = LucasKanade.align(basePyramid, VisionScenes.pyramid(rotated))
            assertNotNull("no alignment at $degrees degrees", alignment)
            val motion = alignment!!.warp.describe(VisionScenes.SIZE, VisionScenes.SIZE)
            assertEquals("rotation $degrees", degrees, motion.rotationDegrees, 1.5)
        }
    }

    /** Bringing an object closer to read it. Also invisible to a translation-only model. */
    @Test
    fun `lucas kanade recovers a scale change`() {
        for (scale in listOf(0.85, 0.92, 1.1, 1.25)) {
            val zoomed = VisionScenes.transform(base, Warp.similarity(centre, centre, 0.0, scale, 0.0, 0.0))
            val alignment = LucasKanade.align(basePyramid, VisionScenes.pyramid(zoomed))
            assertNotNull("no alignment at scale $scale", alignment)
            val motion = alignment!!.warp.describe(VisionScenes.SIZE, VisionScenes.SIZE)
            assertEquals("scale $scale", scale, motion.scale, 0.05)
        }
    }

    @Test
    fun `lucas kanade recovers rotation, scale and translation together`() {
        val warped = VisionScenes.transform(base, Warp.similarity(centre, centre, 8.0, 1.12, 6.0, -4.0))
        val alignment = LucasKanade.align(basePyramid, VisionScenes.pyramid(warped))
        assertNotNull(alignment)
        val motion = alignment!!.warp.describe(VisionScenes.SIZE, VisionScenes.SIZE)
        assertEquals(8.0, motion.rotationDegrees, 1.5)
        assertEquals(1.12, motion.scale, 0.05)
        assertEquals(6.0, motion.translationX, 1.5)
        assertEquals(-4.0, motion.translationY, 1.5)
    }

    @Test
    fun `lucas kanade declines to guess at a textureless frame`() {
        val blank = VisionScenes.pyramid(VisionScenes.flat(90))
        assertNull(LucasKanade.align(blank, blank))
    }

    // endregion

    // region features and homography

    @Test
    fun `corners are found and described across a page`() {
        val features = Features.detect(base)
        assertTrue("expected corners, found ${features.size}", features.size > 40)
        assertTrue(features.all { it.descriptor.size == 4 })
        // Descriptors must not be constant: a pattern of dead bits matches everything.
        val first = features.first().descriptor
        assertTrue(features.any { candidate -> candidate.descriptor.toList() != first.toList() })
    }

    @Test
    fun `features match the same page after a large rotation and zoom`() {
        val moved = VisionScenes.transform(base, Warp.similarity(centre, centre, 15.0, 1.15, 8.0, -6.0))
        val matches = Features.match(Features.detect(base), Features.detect(moved))
        assertTrue("only ${matches.size} matches survived", matches.size >= Homography.MINIMUM_MATCHES)

        val estimate = Homography.estimate(matches)
        assertNotNull("no homography from ${matches.size} matches", estimate)
        val motion = estimate!!.warp.describe(VisionScenes.SIZE, VisionScenes.SIZE)
        assertEquals(15.0, motion.rotationDegrees, 3.0)
        assertEquals(1.15, motion.scale, 0.08)
        assertTrue("inlier ratio ${estimate.inlierRatio}", estimate.inlierRatio > 0.5)
    }

    @Test
    fun `the direct linear transform recovers a known projective transform exactly`() {
        val truth = Warp(
            doubleArrayOf(1.05, 0.08, 4.0, -0.06, 0.98, -3.0, 0.0004, 0.0002, 1.0),
        )
        val fromX = doubleArrayOf(10.0, 100.0, 20.0, 90.0, 55.0, 30.0)
        val fromY = doubleArrayOf(15.0, 25.0, 95.0, 105.0, 60.0, 80.0)
        val toX = DoubleArray(6)
        val toY = DoubleArray(6)
        for (index in 0 until 6) {
            val mapped = truth.apply(fromX[index], fromY[index])!!
            toX[index] = mapped[0]
            toY[index] = mapped[1]
        }

        val fit = Homography.fit(fromX, fromY, toX, toY)
        assertNotNull(fit)
        for (index in 0 until 6) {
            val mapped = fit!!.apply(fromX[index], fromY[index])!!
            assertEquals(toX[index], mapped[0], 1e-6)
            assertEquals(toY[index], mapped[1], 1e-6)
        }
    }

    @Test
    fun `a homography is not fitted from too few correspondences`() {
        assertNull(Homography.fit(DoubleArray(3), DoubleArray(3), DoubleArray(3), DoubleArray(3)))
        assertNull(Homography.estimate(emptyList()))
    }

    // endregion

    // region structural residual

    @Test
    fun `the same content after motion is structurally similar`() {
        val moved = VisionScenes.transform(base, Warp.translation(4.0, 2.0))
        val alignment = LucasKanade.align(basePyramid, VisionScenes.pyramid(moved))!!
        val residual = StructuralResidual.measure(base, moved, alignment.warp)
        assertTrue("dissimilarity ${residual.dissimilarity}", residual.dissimilarity < 0.10)
        assertTrue(residual.usable)
    }

    @Test
    fun `a different page is structurally dissimilar`() {
        val other = VisionScenes.page(seed = 99)
        val alignment = LucasKanade.align(basePyramid, VisionScenes.pyramid(other))
        val warp = alignment?.warp ?: Warp.identity()
        val residual = StructuralResidual.measure(base, other, warp)
        assertTrue("dissimilarity ${residual.dissimilarity}", residual.dissimilarity > 0.30)
    }

    /**
     * The measurement a pixel difference gets wrong in the most damaging direction: a page that
     * moved under a changing light is the same page.
     */
    @Test
    fun `a lighting change is not a content change`() {
        val relit = VisionScenes.relit(base, gain = 0.75f, offset = 50f)
        val residual = StructuralResidual.measure(base, relit, Warp.identity())
        assertTrue("dissimilarity ${residual.dissimilarity}", residual.dissimilarity < 0.15)
    }

    /** What grayscale could never see: the same layout in a different colour. */
    @Test
    fun `a colour change is visible in the chroma term`() {
        val recoloured = VisionScenes.page(colourShift = 0x00004000)
        val residual = StructuralResidual.measure(base, recoloured, Warp.identity())
        assertTrue("chroma ${residual.chromaDifference}", residual.chromaDifference > 1.0)
    }

    @Test
    fun `a warp that pushes the reference out of frame is not usable`() {
        val residual = StructuralResidual.measure(base, base, Warp.translation(500.0, 500.0))
        assertTrue(!residual.usable)
    }

    // endregion

    // region solvers

    @Test
    fun `the linear solver returns null for a singular system`() {
        val singular = arrayOf(
            doubleArrayOf(1.0, 2.0),
            doubleArrayOf(2.0, 4.0),
        )
        assertNull(LinearAlgebra.solve(singular, doubleArrayOf(1.0, 2.0)))
    }

    @Test
    fun `the linear solver solves a well conditioned system`() {
        val a = arrayOf(
            doubleArrayOf(4.0, 1.0, 0.0),
            doubleArrayOf(1.0, 3.0, 1.0),
            doubleArrayOf(0.0, 1.0, 2.0),
        )
        val x = LinearAlgebra.solve(a, doubleArrayOf(5.0, 5.0, 3.0))!!
        for (row in 0 until 3) {
            var sum = 0.0
            for (column in 0 until 3) sum += a[row][column] * x[column]
            assertEquals(doubleArrayOf(5.0, 5.0, 3.0)[row], sum, 1e-9)
        }
    }

    @Test
    fun `the decomposition finds the smallest singular vector`() {
        // Diagonal, so the answer is the axis with the smallest entry.
        val a = Array(4) { DoubleArray(4) }
        a[0][0] = 9.0
        a[1][1] = 4.0
        a[2][2] = 1e-6
        a[3][3] = 7.0
        val v = LinearAlgebra.smallestSingularVector(a)!!
        assertTrue("expected the third axis, got ${v.toList()}", abs(v[2]) > 0.99)
    }

    /**
     * The reason inverse iteration on the normal equations was replaced. This matrix is conditioned
     * at about 1e7, which `MᵀM` would square to 1e14 — past what double precision carries — while
     * the Jacobi decomposition never forms it and answers exactly.
     */
    @Test
    fun `an ill conditioned system is still solved`() {
        val scale = 1e7
        val rows = arrayOf(
            doubleArrayOf(scale, 0.0, 0.0),
            doubleArrayOf(0.0, scale, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        val v = LinearAlgebra.smallestSingularVector(rows)!!
        assertTrue("expected the third axis, got ${v.toList()}", abs(v[2]) > 0.999)
    }

    /** A genuine null vector of a rectangular system, which is the shape a homography fit hands it. */
    @Test
    fun `a tall system yields its exact null vector`() {
        // Every row is orthogonal to (1, 1, 1)/sqrt(3).
        val rows = arrayOf(
            doubleArrayOf(1.0, -1.0, 0.0),
            doubleArrayOf(0.0, 1.0, -1.0),
            doubleArrayOf(2.0, -1.0, -1.0),
            doubleArrayOf(1.0, 0.0, -1.0),
        )
        val v = LinearAlgebra.smallestSingularVector(rows)!!
        val expected = 1.0 / kotlin.math.sqrt(3.0)
        for (index in 0 until 3) {
            assertEquals("component $index of ${v.toList()}", expected, abs(v[index]), 1e-9)
        }
    }

    @Test
    fun `a ragged or empty system is declined rather than guessed`() {
        assertTrue(LinearAlgebra.smallestSingularVector(emptyArray()) == null)
        assertTrue(
            LinearAlgebra.smallestSingularVector(
                arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0)),
            ) == null,
        )
    }

    // endregion
}
