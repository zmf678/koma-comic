package com.arkreader.comic.engine

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class WebDavEntry(
    val href: String,
    val name: String,
    val isCollection: Boolean,
    val contentLength: Long = 0
)

class WebDavClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = ""
) {
    private fun authHeader(): String? {
        if (username.isBlank() && password.isBlank()) return null
        val creds = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(creds, Base64.NO_WRAP)
    }

    fun normalizeUrl(raw: String): String {
        return raw.trimEnd('/')
    }

    fun listDirectory(dirUrl: String): List<WebDavEntry> {
        val url = URL(if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PROPFIND"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Depth", "1")
            setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            authHeader()?.let { setRequestProperty("Authorization", it) }
            doOutput = true
        }
        val body = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getcontentlength/><D:displayname/></D:prop></D:propfind>"""
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("WebDAV HTTP $code")
        }
        return conn.inputStream.use { parseMultiStatus(url, it) }
    }

    fun downloadToFile(fileUrl: String, target: File) {
        val url = URL(fileUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            authHeader()?.let { setRequestProperty("Authorization", it) }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("WebDAV GET HTTP $code")
        }
        val tmp = File(target.parentFile, target.name + ".part")
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                input.copyTo(out, bufferSize = 64 * 1024)
            }
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private fun parseMultiStatus(requestUrl: URL, input: InputStream): List<WebDavEntry> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        val entries = mutableListOf<WebDavEntry>()
        var inResponse = false
        var currentHref = ""
        var currentDisplay = ""
        var currentLen = 0L
        var currentCollection = false
        var currentTag = ""

        val reqPath = requestUrl.path.trimEnd('/')
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    currentTag = tag
                    if (tag == "response") {
                        inResponse = true
                        currentHref = ""
                        currentDisplay = ""
                        currentLen = 0L
                        currentCollection = false
                    } else if (inResponse && tag == "collection") {
                        currentCollection = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inResponse) {
                        val txt = parser.text?.trim().orEmpty()
                        if (txt.isNotEmpty()) {
                            when (currentTag) {
                                "href" -> currentHref = txt
                                "displayname" -> currentDisplay = txt
                                "getcontentlength" -> currentLen = txt.toLongOrNull() ?: 0L
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    if (tag == "response" && inResponse) {
                        inResponse = false
                        val resolved = URL(requestUrl, currentHref)
                        val resolvedPath = resolved.path.trimEnd('/')
                        if (resolvedPath != reqPath) {
                            val rawName = currentDisplay.ifBlank {
                                val seg = resolvedPath.substringAfterLast('/')
                                runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
                            }
                            if (rawName.isNotBlank()) {
                                entries.add(
                                    WebDavEntry(
                                        href = resolved.toString(),
                                        name = rawName.trimEnd('/'),
                                        isCollection = currentCollection || currentHref.endsWith("/"),
                                        contentLength = currentLen
                                    )
                                )
                            }
                        }
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return entries
    }
}
