package com.creepybubble.markeditor

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.OutputStream

/**
 * Gera um PDF renderizando o HTML num WebView (fora de tela) e desenhando o conteúdo
 * em páginas de um PdfDocument. Assim o PDF sai com o layout do HTML — títulos, tabelas,
 * blocos de código, links e imagens embutidas — sem depender do motor de impressão
 * (cujos callbacks não podem ser estendidos fora do pacote android.print).
 *
 * Deve ser chamado na thread principal. [out] NÃO é fechado aqui: o chamador fecha no [onDone].
 */
object HtmlToPdf {
    // A4 a ~96 dpi.
    private const val PAGE_WIDTH = 794
    private const val PAGE_HEIGHT = 1123

    // Mantém os WebViews vivos até terminarem (evita coleta pelo GC durante o carregamento).
    private val alive = mutableListOf<WebView>()

    fun render(context: Context, html: String, out: OutputStream, onDone: (Boolean) -> Unit) {
        val webView = WebView(context)
        alive.add(webView)
        webView.settings.javaScriptEnabled = false
        // Sem isto o conteúdo (acelerado por hardware) não é desenhado no canvas de software do PDF.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                // Pequeno atraso para imagens (ex.: remotas) assentarem antes de desenhar.
                view.postDelayed({
                    val ok = try {
                        drawToPdf(view, out)
                        true
                    } catch (e: Exception) {
                        false
                    }
                    alive.remove(view)
                    onDone(ok)
                }, 400)
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun drawToPdf(view: WebView, out: OutputStream) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(PAGE_WIDTH, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        val totalHeight = view.measuredHeight.coerceAtLeast(1)
        view.layout(0, 0, PAGE_WIDTH, totalHeight)

        val pdf = PdfDocument()
        var top = 0
        var pageNum = 1
        while (top < totalHeight) {
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
            val page = pdf.startPage(info)
            val canvas = page.canvas
            canvas.save()
            canvas.translate(0f, -top.toFloat())
            view.draw(canvas)
            canvas.restore()
            pdf.finishPage(page)
            top += PAGE_HEIGHT
            pageNum++
        }
        pdf.writeTo(out)
        pdf.close()
    }
}
