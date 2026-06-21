package com.droidagentkit.visuals

import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.sqrt

class PngDiffEngine {
    fun compare(
        baseline: Path,
        candidate: Path,
        diffOutput: Path,
        tolerance: VisualTolerance,
    ): PngDiffResult {
        val base = ImageIO.read(baseline.toFile())
        val next = ImageIO.read(candidate.toFile())
        require(base.width == next.width && base.height == next.height) {
            "Image dimensions differ: ${base.width}x${base.height} vs ${next.width}x${next.height}"
        }
        val diff = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
        var changed = 0
        var maxDistance = 0.0
        for (y in 0 until base.height) {
            for (x in 0 until base.width) {
                val baseColor = Color(base.getRGB(x, y), true)
                val nextColor = Color(next.getRGB(x, y), true)
                val distance = colorDistance(baseColor, nextColor)
                if (baseColor.rgb != nextColor.rgb) {
                    changed++
                    maxDistance = maxOf(maxDistance, distance)
                    diff.setRGB(x, y, Color.RED.rgb)
                } else {
                    diff.setRGB(x, y, Color(0, 0, 0, 0).rgb)
                }
            }
        }
        ImageIO.write(diff, "png", diffOutput.toFile())
        val total = base.width * base.height
        val percent = changed.toDouble() / total.toDouble() * 100.0
        return PngDiffResult(
            changed,
            total,
            percent,
            percent <= tolerance.maxChangedPixelPercent && maxDistance <= tolerance.maxColorDistance,
        )
    }

    private fun colorDistance(a: Color, b: Color): Double =
        sqrt(
            (a.red - b.red).toDouble().pow(2) +
                (a.green - b.green).toDouble().pow(2) +
                (a.blue - b.blue).toDouble().pow(2) +
                (a.alpha - b.alpha).toDouble().pow(2),
        )
}
