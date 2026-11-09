// Ported from Resona (https://github.com/code-saksham-hash/Resona, MIT, Copyright (c) 2026 Code.Saksham).
// The signature and n-parameter transforms come from yt-dlp-android (MIT, Copyright (c) 2026 Hamzeh Lahwani).
package fr.spotifeur.data.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Only needed when every direct client fails and the web client returns a signature-ciphered format.
 * The transform functions live in YouTube's player script and run in a throwaway WebView on
 * about:blank, with network loads blocked.
 */
internal class YouTubeDecipher(private val context: Context) {
    private val signatureFunctions = HashMap<Int, String>(2)
    private val nFunctions = HashMap<Int, String>(4)

    fun playableUrl(format: YouTubeFormat, playerJs: String?): String {
        val direct = format.url
        // Direct addresses from the Android, iOS and TV clients already carry the CDN's own `n` value.
        if (direct != null) return direct
        val cipher = format.signatureCipher ?: throw YouTubeFailure("Format ${format.itag} has no address.")
        val js = playerJs ?: throw YouTubeFailure("Format ${format.itag} needs the player script.")
        val params = cipher.split('&').associate { part ->
            val separator = part.indexOf('=')
            if (separator < 0) part to "" else part.substring(0, separator) to part.substring(separator + 1)
        }
        val encrypted = params["s"]?.let { URLDecoder.decode(it, "UTF-8") } ?: throw YouTubeFailure("Missing signature.")
        val parameter = params["sp"] ?: "sig"
        val base = params["url"]?.let { URLDecoder.decode(it, "UTF-8") } ?: throw YouTubeFailure("Missing address.")
        val function = synchronized(signatureFunctions) { signatureFunctions.getOrPut(js.hashCode()) { signatureFunction(js) } }
        val signature = execute(function, encrypted) ?: throw YouTubeFailure("The signature could not be computed.")
        return transformN("$base&$parameter=$signature", js)
    }

    private fun transformN(url: String, js: String): String {
        val value = N_PARAM.find(url)?.groupValues?.get(2) ?: return url
        val function = synchronized(nFunctions) { nFunctions.getOrPut(js.hashCode()) { nFunction(js) } }
        val transformed = if (function == FULL_PLAYER) evaluate("$js\n;${discoveryScript(value)}") else execute(function, value)
        return url.replace(N_PARAM) { it.groupValues[1] + (transformed ?: value) }
    }

    private fun signatureFunction(js: String): String {
        val name = SIGNATURE_PATTERNS.firstNotNullOfOrNull { it.find(js)?.groupValues?.get(1) }
            ?: throw YouTubeFailure("Signature function not found.")
        val main = JsSource.functionBody(js, name) ?: throw YouTubeFailure("Signature function unreadable.")
        val helperName = Regex("""([a-zA-Z0-9$]{2,4})\.[a-zA-Z0-9$]+\(""").find(main)?.groupValues?.get(1)
        val helper = helperName?.let { JsSource.objectLiteral(js, it) }
        return if (helper != null) "(function(){var $helperName=$helper;\nreturn $main;})()" else main
    }

    private fun nFunction(js: String): String {
        val raw = N_NAME_PATTERNS.firstNotNullOfOrNull { it.find(js)?.groupValues?.get(1) }
        val name = if (raw != null && '[' in raw) {
            val array = raw.substringBefore('[')
            val index = raw.substringAfter('[').substringBefore(']').toIntOrNull() ?: 0
            Regex("""var\s+${Regex.escape(array)}\s*=\s*\[([^\]]+)]""").find(js)?.groupValues?.get(1)?.split(',')?.getOrNull(index)?.trim()
        } else raw
        val body = name?.let { JsSource.functionBody(js, it) }
        return if (body != null && plausibleN(body)) body else FULL_PLAYER
    }

    private fun plausibleN(function: String): Boolean {
        val first = execute(function, "abcdefghijklmnopq") ?: return false
        val second = execute(function, "qponmlkjihgfedcba") ?: return false
        return looksTransformed(first, "abcdefghijklmnopq") && looksTransformed(second, "qponmlkjihgfedcba") && first != second
    }

    private fun looksTransformed(result: String, input: String) = result != input && result != "undefined" && result != "null" &&
        result.length in 5..(input.length + 2) && Regex("[A-Za-z0-9_-]+").matches(result) && !Regex("[0-9a-f]+").matches(result)

    private fun execute(function: String, argument: String): String? =
        evaluate("(function(){var fn=$function;return fn(${JSONObject.quote(argument)});})();")

    /** Runs on the main thread; callers are loader or IO threads and wait for the result. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun evaluate(script: String): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Script evaluation must not block the main thread." }
        val result = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val holder = AtomicReference<WebView?>(null)
        main.post {
            try {
                val view = WebView(context.applicationContext)
                holder.set(view)
                view.settings.javaScriptEnabled = true
                view.settings.blockNetworkLoads = true
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(page: WebView, url: String?) {
                        page.evaluateJavascript(script) { raw -> result.set(decode(raw)); page.destroy(); done.countDown() }
                    }
                }
                view.loadUrl("about:blank")
            } catch (_: Exception) { done.countDown() }
        }
        if (!done.await(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) main.post { holder.get()?.destroy() }
        return result.get()
    }

    private fun decode(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        return runCatching { if (raw.startsWith("\"")) JSONTokener(raw).nextValue() as? String else raw }.getOrNull()
    }

    private fun discoveryScript(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            (function(){
              var nVal="$escaped",ti="abcdefghijklmnopq",ti2="qponmlkjihgfedcbaZ",res=nVal;
              function ok(r,i){return typeof r==='string'&&r!==i&&r!=='undefined'&&r!=='null'&&r.indexOf('=')<0&&r.indexOf('+')<0&&r.indexOf('/')<0&&r.indexOf(':')<0&&r.length>=5&&r.length<=i.length+2&&/^[A-Za-z0-9_-]+${'$'}/.test(r)&&!/^[0-9a-f]+${'$'}/.test(r);}
              function real(fn){try{var a=fn(ti),b=fn(ti2);return ok(a,ti)&&ok(b,ti2)&&a!==b;}catch(e){return false;}}
              try{var g=globalThis._yt_player||{};var keys=Object.keys(g).filter(function(k){return k.length>=2&&k.length<=4&&typeof g[k]==='function';});
                for(var i=0;i<keys.length;i++){if(real(g[keys[i]])){res=g[keys[i]](nVal);break;}}}catch(e){}
              return res;
            })()
        """.trimIndent()
    }

    private companion object {
        const val FULL_PLAYER = "__FULL_PLAYER_JS__"
        const val SCRIPT_TIMEOUT_SECONDS = 20L
        val N_PARAM = Regex("""([?&]n=)([^&]+)""")
        val SIGNATURE_PATTERNS = listOf(
            Regex("""\.sig\|\|([a-zA-Z0-9$]+)\("""),
            Regex("""\.signature\s*=\s*([a-zA-Z0-9$]+)\("""),
            Regex(""""signature"\s*,\s*([a-zA-Z0-9$]+)\("""),
        )
        val N_NAME_PATTERNS = listOf(
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9_$]+=([a-zA-Z0-9_\[\]$]{2,40})\("""),
            Regex("""[a-zA-Z0-9_$]+&&\([a-zA-Z0-9_$]+=([a-zA-Z0-9_\[\]$]{2,40})\([a-zA-Z0-9_$]"""),
            Regex("""\.get\("n"\)\)&&\([a-zA-Z_$]=([a-zA-Z0-9_$]{2,4})\["""),
            Regex("""([a-zA-Z0-9_$]{3,})\s*=\s*function\s*\([a-zA-Z_$]\)\s*\{var\s+[a-zA-Z_$]\s*=\s*[a-zA-Z_$]\.split\(""\)"""),
        )
    }
}

/** Pulls a function or an object literal out of the player script by matching braces. */
internal object JsSource {
    fun functionBody(js: String, name: String): String? {
        val escaped = Regex.escape(name)
        val start = Regex("""(?:$escaped\s*=\s*function|function\s+$escaped)\s*\([^)]*\)\s*\{""").find(js) ?: return null
        val open = js.indexOf('{', start.range.first).takeIf { it >= 0 } ?: return null
        val close = matchingBrace(js, open) ?: return null
        return js.substring(start.range.first, close + 1)
    }

    fun objectLiteral(js: String, name: String): String? {
        val start = Regex("""var\s+${Regex.escape(name)}\s*=\s*\{""").find(js) ?: return null
        val open = js.indexOf('{', start.range.first).takeIf { it >= 0 } ?: return null
        val close = matchingBrace(js, open) ?: return null
        return js.substring(open, close + 1)
    }

    fun matchingBrace(js: String, open: Int): Int? {
        var depth = 0
        var quote: Char? = null
        for (index in open until js.length) {
            val character = js[index]
            if (quote != null) {
                if (character == quote && js[index - 1] != '\\') quote = null
                continue
            }
            when (character) {
                '"', '\'', '`' -> quote = character
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return index }
            }
        }
        return null
    }
}
