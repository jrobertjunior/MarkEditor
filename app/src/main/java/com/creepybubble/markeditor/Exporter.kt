package com.creepybubble.markeditor

import android.content.Context
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.view.View
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.OutputStream

/** Converte o markdown em um documento HTML completo e estilizado. */
fun renderFullHtml(title: String, markdown: String): String {
    val body = try {
        val parser = Parser.builder().build()
        val renderer = HtmlRenderer.builder().build()
        renderer.render(parser.parse(markdown))
    } catch (e: Exception) {
        "<pre>" + escapeHtml(markdown) + "</pre>"
    }
    val safeTitle = escapeHtml(title)
    return """<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$safeTitle</title>
<style>
  body { font-family: -apple-system, "Segoe UI", Roboto, sans-serif; line-height: 1.6; max-width: 760px; margin: 40px auto; padding: 0 20px; color: #282828; }
  h1, h2, h3, h4 { color: #b5651d; }
  code { background: #f0ede4; padding: 2px 5px; border-radius: 4px; font-family: monospace; }
  pre { background: #282828; color: #ebdbb2; padding: 14px; border-radius: 8px; overflow-x: auto; }
  pre code { background: transparent; color: inherit; padding: 0; }
  blockquote { border-left: 4px solid #d65d0e; margin-left: 0; padding-left: 16px; color: #665c54; }
  table { border-collapse: collapse; }
  th, td { border: 1px solid #ccc; padding: 6px 10px; }
  a { color: #458588; }
  img { max-width: 100%; }
</style>
</head>
<body>
$body
</body>
</html>"""
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Gera um PDF paginado a partir do markdown. Renderiza num TextView com o Markwon e
 * quebra as páginas em limites de linha (sem cortar texto ao meio).
 */
fun writePdf(context: Context, markdown: String, out: OutputStream) {
    // A4 a ~72 dpi.
    val pageWidth = 595
    val pageHeight = 842
    val margin = 36
    val contentWidth = pageWidth - margin * 2
    val contentHeight = pageHeight - margin * 2

    val markwon = Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .build()

    val tv = TextView(context)
    tv.setTextColor(Color.BLACK)
    tv.textSize = 12f
    markwon.setMarkdown(tv, markdown)

    val widthSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    tv.measure(widthSpec, heightSpec)
    tv.layout(0, 0, contentWidth, tv.measuredHeight)

    val layout = tv.layout
    val pdf = PdfDocument()

    if (layout == null || layout.lineCount == 0) {
        val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        pdf.finishPage(pdf.startPage(info))
    } else {
        var startLine = 0
        var pageNum = 1
        while (startLine < layout.lineCount) {
            val pageTop = layout.getLineTop(startLine)
            var endLine = startLine
            while (endLine < layout.lineCount && layout.getLineBottom(endLine) - pageTop <= contentHeight) {
                endLine++
            }
            if (endLine == startLine) endLine = startLine + 1 // linha maior que a página
            val pageBottom = layout.getLineBottom(endLine - 1)

            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            val page = pdf.startPage(info)
            val canvas = page.canvas
            canvas.save()
            canvas.translate(margin.toFloat(), margin.toFloat() - pageTop)
            canvas.clipRect(0, pageTop, contentWidth, pageBottom)
            tv.draw(canvas)
            canvas.restore()
            pdf.finishPage(page)

            startLine = endLine
            pageNum++
        }
    }

    pdf.writeTo(out)
    pdf.close()
}
