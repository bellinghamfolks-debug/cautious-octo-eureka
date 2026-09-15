import com.abdullah.visionbridge.data.vision.TextGroundingGate
import java.util.Base64

/** Local stress probe of the production gate, not a production OCR/cloud replay.
 * TSV stdin: opaque key, Base64 model claim, Base64 optical text, detected-box count.
 * Both confidences are deliberately maximized: rejection must come from optical disagreement
 * or NO_TEXT, rather than a low model/optical confidence shortcut. Keep evidence inputs local.
 * Stdout contains only the key and decision, never the supplied text. */
fun main() {
    val decoder=Base64.getDecoder()
    generateSequence(::readLine).forEach { line ->
        val columns=line.split('\t')
        require(columns.size==4)
        val claim=decoder.decode(columns[1]).toString(Charsets.UTF_8)
        val optical=decoder.decode(columns[2]).toString(Charsets.UTF_8)
        val decision=TextGroundingGate.evaluate(claim,100,true,false,
            TextGroundingGate.Evidence(optical,1f,columns[3].toInt()))
        println(listOf(columns[0],decision.accepted,decision.retry,decision.reason).joinToString("\t"))
    }
}
