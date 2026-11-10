package fr.spotifeur.presentation.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import fr.spotifeur.R
import fr.spotifeur.SpotifeurModel
import fr.spotifeur.data.youtube.cookieValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Google's sign-in page, then music.youtube.com: the cookies it leaves open the account's playlists. */
private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
    "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
private const val MUSIC_ORIGIN = "https://music.youtube.com"
/** The web client's configuration holds the anonymous identity and, for a brand account, its page id. */
private const val READ_CONFIG = "(function(){try{return {v:ytcfg.get('VISITOR_DATA')||'',d:ytcfg.get('DATASYNC_ID')||''}}catch(e){return {v:'',d:''}}})()"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GoogleSignInScreen(model: SpotifeurModel, navController: NavHostController) {
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.google_signin_failed)
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler { webView?.takeIf { it.canGoBack() && !checking }?.goBack() ?: navController.popBackStack() }

    fun finish(view: WebView, cookie: String) {
        if (checking) return
        checking = true
        error = null
        view.evaluateJavascript(READ_CONFIG) { result ->
            val config = runCatching { JSONObject(result) }.getOrNull()
            scope.launch {
                try {
                    model.connectGoogle(cookie, config?.optString("v"), config?.optString("d"))
                    navController.popBackStack()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    error = failed
                    checking = false
                    fr.spotifeur.data.diagnostics.DiagnosticLog.record("youtube-account", "sign-in ${failure.javaClass.simpleName} \"${failure.message}\"")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.google_signin_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_cancel))
                    }
                },
            )
        },
    ) { inner ->
        val hasSong by model.ui.derivedHasSong.collectAsStateWithLifecycle()
        Box(Modifier.padding(inner).padding(bottom = if (hasSong) fr.spotifeur.presentation.components.MiniPlayerHeight else 0.dp).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (url?.startsWith(MUSIC_ORIGIN) != true) return
                                val cookie = CookieManager.getInstance().getCookie(MUSIC_ORIGIN) ?: return
                                if (cookieValue(cookie, "SAPISID") == null && cookieValue(cookie, "__Secure-3PAPISID") == null) return
                                finish(view, cookie)
                            }
                        }
                        // A clean start: no other account's session is reused.
                        CookieManager.getInstance().removeAllCookies { loadUrl(SIGN_IN_URL) }
                        webView = this
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
            if (checking) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator()
                    Text(stringResource(R.string.google_signin_checking), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            error?.let { message ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { error = null; webView?.loadUrl(SIGN_IN_URL) }) { Text(stringResource(R.string.google_signin_retry)) }
                    }
                }
            }
        }
    }
}
