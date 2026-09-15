import com.abdullah.visionbridge.capture.vision.ImagePlane
import com.abdullah.visionbridge.capture.vision.ViewportSessionResolver
import com.abdullah.visionbridge.domain.model.AnalysisMode
import com.abdullah.visionbridge.domain.model.ViewportMode
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** Local JVM probe using production viewport logic. Desktop JPEG decoding/resampling is not
 * Android device execution. TSV stdin: source width, source height, opaque key, local JPEG path.
 * TSV stdout: opaque key, strategy, normalized bounds. No image or recognized text is emitted. */
fun main() {
    val resolver = ViewportSessionResolver()
    generateSequence(::readLine).forEach { line ->
        val columns = line.split('\t', limit=4)
        require(columns.size==4)
        val width=columns[0].toInt();val height=columns[1].toInt()
        val source=checkNotNull(ImageIO.read(File(columns[3])))
        require(kotlin.math.abs(source.width.toDouble()/source.height-width.toDouble()/height)<.01) {
            "Probe requires full capture evidence with matching aspect ratio"
        }
        val scale=256.0/maxOf(source.width,source.height)
        val w=(source.width*scale).roundToInt().coerceAtLeast(1)
        val h=(source.height*scale).roundToInt().coerceAtLeast(1)
        val sampled=BufferedImage(w,h,BufferedImage.TYPE_INT_RGB)
        sampled.createGraphics().let { graphics ->
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(source,0,0,w,h,null)
            } finally { graphics.dispose() }
        }
        val plane=ImagePlane.fromArgb(w,h,sampled.getRGB(0,0,w,h,null,0,w))
        val r=resolver.resolve(plane,width,height,ViewportMode.ESIGHT_TEXT_SAFE,AnalysisMode.TEXT_READING)
        println(listOf(columns[2],r.strategy,r.rect.left,r.rect.top,r.rect.right,r.rect.bottom).joinToString("\t"))
        source.flush();sampled.flush()
    }
}
