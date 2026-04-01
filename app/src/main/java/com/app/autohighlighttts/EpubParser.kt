package com.app.autohighlighttts

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

object EpubParser {

    fun readText(context: Context, uri: Uri): String {
        val chapters = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val lower = entry.name.lowercase()
                    if (!entry.isDirectory && (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) {
                        val rawHtml = BufferedReader(InputStreamReader(zip)).readText()
                        val plainText = rawHtml
                            .replace(Regex("<script[\\s\\S]*?</script>"), " ")
                            .replace(Regex("<style[\\s\\S]*?</style>"), " ")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("&nbsp;"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        if (plainText.isNotBlank()) {
                            chapters += plainText
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return chapters.joinToString("\n\n")
    }
}
