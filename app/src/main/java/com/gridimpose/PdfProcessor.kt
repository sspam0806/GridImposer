package com.gridimpose

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.InputStream
import java.io.OutputStream

enum class RuleStyle(val label: String, val defaultSpacingMm: Float) {
    /** Horizontal ruled lines only. */
    LINES("Lines", 9.5f),

    /** Square grid (vertical + horizontal lines). */
    GRID("Grid", 5f),
}

/**
 * Same result as the original Python scripts:
 *
 *   for every source page  ->  one output sheet, twice as wide,
 *                              source page on the left, ruled page on the right,
 *                              12 pt apart, each with a 1 pt grey outline.
 *
 * A4 portrait pages therefore end up on sheets a little over A3 landscape. The original page is
 * embedded as a vector form XObject, so text stays sharp and searchable.
 */
object PdfProcessor {

    const val MIN_SPACING_MM = 1f
    const val MAX_SPACING_MM = 50f

    private const val PT_PER_MM = 72f / 25.4f

    // Same light blue and hairline width as the scripts.
    private const val RULE_R = 0.792f
    private const val RULE_G = 0.922f
    private const val RULE_B = 0.992f
    private const val RULE_WIDTH = 0.3f

    // Outline around each page, and the gap between the two pages (all in points).
    private const val BORDER_WIDTH = 1f
    private const val BORDER_GRAY = 0.8f
    private const val PAGE_GAP = 12f

    fun process(
        context: Context,
        input: InputStream,
        output: OutputStream,
        style: RuleStyle,
        spacingMm: Float,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val spacingPt = spacingMm * PT_PER_MM

        // Temp-file storage keeps big PDFs out of the Java heap.
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { src ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { out ->
                val layers = LayerUtility(out)
                val total = src.numberOfPages
                onProgress(0, total)
                for (i in 0 until total) {
                    addSheet(src, out, layers, src.getPage(i), style, spacingPt)
                    onProgress(i + 1, total)
                }
                out.save(output)
            }
        }
    }

    private fun addSheet(
        src: PDDocument,
        out: PDDocument,
        layers: LayerUtility,
        page: PDPage,
        style: RuleStyle,
        spacingPt: Float,
    ) {
        // PDFBox's own rotation handling in importPageAsForm() squashes non-square pages,
        // so import the page unrotated and apply /Rotate ourselves below.
        val rotation = ((page.rotation % 360) + 360) % 360
        page.rotation = 0
        val form = layers.importPageAsForm(src, page)

        // Find where the form really paints: its BBox pushed through its matrix.
        val bbox = form.getBBox()
        val m = form.getMatrix()
        val xs = floatArrayOf(bbox.lowerLeftX, bbox.upperRightX, bbox.lowerLeftX, bbox.upperRightX)
        val ys = floatArrayOf(bbox.lowerLeftY, bbox.lowerLeftY, bbox.upperRightY, bbox.upperRightY)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (k in 0..3) {
            val x = m.scaleX * xs[k] + m.shearX * ys[k] + m.translateX
            val y = m.shearY * xs[k] + m.scaleY * ys[k] + m.translateY
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val w = maxX - minX
        val h = maxY - minY

        // Displayed size and the matrix that moves the form to the origin, rotated upright.
        val pageW: Float
        val pageH: Float
        val placement: Matrix
        when (rotation) {
            90 -> {
                pageW = h; pageH = w
                placement = Matrix(0f, -1f, 1f, 0f, -minY, w + minX)
            }
            180 -> {
                pageW = w; pageH = h
                placement = Matrix(-1f, 0f, 0f, -1f, w + minX, h + minY)
            }
            270 -> {
                pageW = h; pageH = w
                placement = Matrix(0f, 1f, -1f, 0f, h + minY, -minX)
            }
            else -> {
                pageW = w; pageH = h
                placement = Matrix(1f, 0f, 0f, 1f, -minX, -minY)
            }
        }

        val sheet = PDPage(PDRectangle(pageW * 2f + PAGE_GAP, pageH))
        out.addPage(sheet)

        PDPageContentStream(out, sheet).use { cs ->
            // Left half: the original page.
            cs.saveGraphicsState()
            cs.transform(placement)
            cs.drawForm(form)
            cs.restoreGraphicsState()

            // Right half: the ruled page, after the gap.
            val rightX = pageW + PAGE_GAP
            drawRules(cs, x0 = rightX, width = pageW, height = pageH, style = style, spacingPt = spacingPt)

            // Outline around both pages, drawn last so it sits on top.
            drawBorder(cs, x0 = 0f, width = pageW, height = pageH)
            drawBorder(cs, x0 = rightX, width = pageW, height = pageH)
        }
    }

    private fun drawBorder(cs: PDPageContentStream, x0: Float, width: Float, height: Float) {
        cs.setStrokingColor(BORDER_GRAY, BORDER_GRAY, BORDER_GRAY)
        cs.setLineWidth(BORDER_WIDTH)
        // A stroke is centred on its path, so inset by half the width to keep the whole
        // line inside the page (otherwise half of it is cut off at the sheet edge).
        val inset = BORDER_WIDTH / 2f
        cs.addRect(x0 + inset, inset, width - BORDER_WIDTH, height - BORDER_WIDTH)
        cs.stroke()
    }

    private fun drawRules(
        cs: PDPageContentStream,
        x0: Float,
        width: Float,
        height: Float,
        style: RuleStyle,
        spacingPt: Float,
    ) {
        cs.setStrokingColor(RULE_R, RULE_G, RULE_B)
        cs.setLineWidth(RULE_WIDTH)

        if (style == RuleStyle.GRID) {
            var k = 0
            while (k * spacingPt <= width + 0.01f) {
                val x = x0 + k * spacingPt
                cs.moveTo(x, 0f)
                cs.lineTo(x, height)
                k++
            }
        }

        var k = 0
        while (k * spacingPt <= height + 0.01f) {
            val y = k * spacingPt
            cs.moveTo(x0, y)
            cs.lineTo(x0 + width, y)
            k++
        }
        cs.stroke()
    }
}
