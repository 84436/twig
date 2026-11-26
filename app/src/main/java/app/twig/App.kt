package app.twig

import android.app.Application
import app.twig.data.Store

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Kick off the database load right away; the activity waits for it (usually already done).
        Store.init(this)
    }
}
