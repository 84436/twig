@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.twig

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.twig.data.Store
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * The keyboard can't be seen in Robolectric, but Compose's calls to it can be counted. Each cursor
 * move between bullets should reconnect the keyboard once and never hide it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w400dp-h860dp-xxhdpi")
class KeyboardTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val calls = mutableListOf<String>()

    private fun find(v: View): View? {
        if (v.javaClass.simpleName == "AndroidComposeView") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Listens in between Compose and Android's keyboard service. */
    private fun listen() = rule.runOnUiThread {
        val view = find(rule.activity.window.decorView)!!
        val service = view.javaClass.getDeclaredField("legacyTextInputServiceAndroid").apply { isAccessible = true }.get(view)
        val imm = service.javaClass.getDeclaredField("inputMethodManager").apply { isAccessible = true }
        val real = imm.get(service)
        imm.set(service, Proxy.newProxyInstance(imm.type.classLoader, arrayOf(imm.type)) { _, m, args ->
            if (m.name in setOf("restartInput", "showSoftInput", "hideSoftInput")) calls += m.name
            m.invoke(real, *(args ?: emptyArray()))
        })
    }

    private fun step(f: () -> Unit): List<String> {
        calls.clear()
        f()
        rule.waitForIdle()
        return calls.toList()
    }

    private val once = listOf("restartInput", "showSoftInput")

    @Test
    fun everyCursorMoveReconnectsTheKeyboardOnceAndNeverHidesIt() {
        rule.waitForIdle()
        listen()
        val bucket = Store.allNodes().first { it.plain == "Bucket list" }
        val lights = Store.allNodes().first { it.plain == "See the northern lights" }
        val pasta = Store.allNodes().first { it.plain == "Learn to make fresh pasta" }
        rule.onNodeWithTag("open-${bucket.id}").performClick()
        rule.waitForIdle()
        assertEquals(once, step { rule.onNodeWithTag("text-${lights.id}").performClick() })
        assertEquals(once, step { rule.onNodeWithTag("text-${pasta.id}").performClick() })
        assertEquals(once, step { rule.onNode(isFocused()).performTextInput("\n") })
        assertEquals(once, step { rule.onNode(isFocused()).performKeyInput { pressKey(Key.Backspace) } })
        assertEquals(pasta.id, Store.focusedId)
    }
}
