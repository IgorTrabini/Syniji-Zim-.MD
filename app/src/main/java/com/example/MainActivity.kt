package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import com.example.ui.SynijiApp
import com.example.ui.theme.SynijiZimTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {

    private val incomingZimUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (_: Throwable) {
        }
        incomingZimUri.value = extractZimUri(intent)
        enableEdgeToEdge()
        setContent {
            SynijiZimTheme {
                val vm: MainViewModel = viewModel()
                SynijiApp(
                    viewModel = vm,
                    incomingZimUri = incomingZimUri.value,
                    onConsumeIncomingUri = { incomingZimUri.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractZimUri(intent)?.let { uri ->
            incomingZimUri.value = uri
        }
    }

    private fun extractZimUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
    }
}
