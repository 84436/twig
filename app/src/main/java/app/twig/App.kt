package app.twig

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import app.twig.data.Store

class App : Application() {
    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // On since Compose 1.13: a focused text field waits for the window's focus before it takes the
        // keyboard. With it, Enter (the cursor moves to the new bullet) dropped the keyboard.
        ComposeFoundationFlags.isTextFieldWaitWindowFocusForInputSessionEnabled = false
        // Kick off the database load right away; the activity waits for it (usually already done).
        Store.init(this)
    }
}
