package com.abdullah.visionbridge.capture.vision

import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.ViewportMode
import org.junit.Assert.*
import org.junit.Test

class ViewportResolverTest {
    /** Procedural non-private fixtures. Sensor texture is retained inside a known synthetic image. */
    private fun plane(width: Int, height: Int, rect: Viewport.Rect): ImagePlane {
        val luma = FloatArray(width * height) { index ->
            val x = index % width; val y = index / width
            if (x >= rect.left*width && x < rect.right*width && y >= rect.top*height && y < rect.bottom*height)
                60f + ((x*17 + y*23) % 150) else 0f
        }
        return ImagePlane(width,height,luma,FloatArray(luma.size),FloatArray(luma.size))
    }

    @Test fun portrait1220PreservesTextAcrossWidth() {
        val r = Viewport.Rect(0f,.20f,1f,.80f)
        val result = ViewportResolver.resolve(plane(1220,2712,r),1220,2712,
            ViewportMode.ESIGHT_TEXT_SAFE,AnalysisMode.TEXT_READING)
        assertEquals("PORTRAIT_1220_DYNAMIC",result.strategy)
        assertEquals(0f,result.rect.left,0f); assertEquals(1f,result.rect.right,0f)
        assertEquals(.20f,result.rect.top,.002f); assertEquals(.80f,result.rect.bottom,.002f)
    }

    @Test fun landscapeReferenceExcludesToolsWithoutPrivateScreenshot() {
        val r = EsightViewportCalibration.rect
        val result = ViewportResolver.resolve(plane(1356,610,r),1356,610,
            ViewportMode.ESIGHT_FIXED,AnalysisMode.TEXT_READING)
        assertEquals("ESIGHT_REFERENCE_CONFIRMED",result.strategy)
        assertEquals(r,result.rect)
    }

    @Test fun bothRotationsRequireObservedBoundaryAgreement() {
        val r = EsightViewportCalibration.rect
        for (rotated in listOf(Viewport.Rect(1-r.bottom,r.left,1-r.top,r.right),
            Viewport.Rect(r.top,1-r.right,r.bottom,1-r.left))) {
            val result=ViewportResolver.resolve(plane(610,1356,rotated),610,1356,
                ViewportMode.ESIGHT_FIXED,AnalysisMode.SCENE_DESCRIPTION)
            assertEquals("ESIGHT_ROTATION_CONFIRMED",result.strategy)
            assertEquals(rotated,result.rect)
        }
    }

    @Test fun sameLandscapeAspectDoesNotProveEsightLayout() {
        val r=Viewport.Rect(0f,.1f,1f,.9f)
        val result=ViewportResolver.resolve(plane(1356,610,r),1356,610,
            ViewportMode.ESIGHT_FIXED,AnalysisMode.TEXT_READING)
        assertEquals("DYNAMIC_BOUNDARY",result.strategy)
        assertEquals(1f,result.rect.right,0f)
    }

    @Test fun blackUnresolvedCaptureIsNotFalselyReportedCalibrated() {
        val p=ImagePlane(122,271,FloatArray(122*271),FloatArray(122*271),FloatArray(122*271))
        val result=ViewportResolver.resolve(p,1220,2712,ViewportMode.ESIGHT_FIXED,AnalysisMode.TEXT_READING)
        assertEquals("PORTRAIT_1220_UNRESOLVED_FULL_FRAME",result.strategy)
    }

    @Test fun confirmedPortraitRegionSurvivesBlackCameraButNotChangedControls() {
        val r=Viewport.Rect(0f,.20f,1f,.80f);val resolver=ViewportSessionResolver()
        val original=resolver.resolve(plane(122,271,r),1220,2712,ViewportMode.ESIGHT_TEXT_SAFE,AnalysisMode.TEXT_READING)
        val black=ImagePlane(122,271,FloatArray(122*271),FloatArray(122*271),FloatArray(122*271))
        val retained=resolver.resolve(black,1220,2712,ViewportMode.ESIGHT_TEXT_SAFE,AnalysisMode.TEXT_READING)
        assertEquals("CONFIRMED_EXTERNAL_LAYOUT",retained.strategy)
        assertEquals(original.rect,retained.rect)
        val different=ImagePlane(122,271,FloatArray(122*271) { 220f },FloatArray(122*271),FloatArray(122*271))
        assertNotEquals("CONFIRMED_EXTERNAL_LAYOUT",resolver.resolve(different,1220,2712,
            ViewportMode.ESIGHT_TEXT_SAFE,AnalysisMode.TEXT_READING).strategy)
    }

    @Test fun rotationDoesNotReusePreviousPortraitGeometry() {
        val resolver=ViewportSessionResolver()
        resolver.resolve(plane(122,271,Viewport.Rect(0f,.2f,1f,.8f)),1220,2712,
            ViewportMode.ESIGHT_TEXT_SAFE,AnalysisMode.TEXT_READING)
        val r=EsightViewportCalibration.rect
        val rotated=resolver.resolve(plane(272,122,r),2712,1220,ViewportMode.ESIGHT_TEXT_SAFE,AnalysisMode.TEXT_READING)
        assertNotEquals("CONFIRMED_EXTERNAL_LAYOUT",rotated.strategy)
        assertEquals(r.left,rotated.rect.left,.02f)
    }
}
