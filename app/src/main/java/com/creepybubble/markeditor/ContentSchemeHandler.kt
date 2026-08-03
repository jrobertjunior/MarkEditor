package com.creepybubble.markeditor

import android.content.Context
import android.net.Uri
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler

/**
 * Permite ao Markwon carregar imagens locais escolhidas pelo usuário (content://),
 * lendo os bytes via ContentResolver. Sem isso, o renderizador só saberia lidar com
 * http(s), file e data.
 */
class ContentSchemeHandler(private val context: Context) : SchemeHandler() {

    override fun handle(raw: String, uri: Uri): ImageItem {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Não foi possível abrir a imagem: $raw")
        val contentType = context.contentResolver.getType(uri)
        return ImageItem.withDecodingNeeded(contentType, stream)
    }

    override fun supportedSchemes(): Collection<String> = listOf("content")
}
