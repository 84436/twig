package app.twig

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.twig.data.Gist
import app.twig.data.Store
import app.twig.ui.MainScreen
import app.twig.ui.TwigTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.awaitLoaded()
        Store.ensureSeeded()
        setContent { TwigTheme { MainScreen() } }
    }

    // uiMode changes are handled in-process (no restart), so refresh the system bar icon colours.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        Gist.start()
    }

    override fun onStop() {
        super.onStop()
        Store.flush()
        Gist.start() // what was typed goes up now
    }
}
