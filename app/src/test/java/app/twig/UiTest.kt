@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.twig

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.twig.data.Node
import app.twig.data.Store
import app.twig.share.ShareActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws every window (activity, menus, dialogs) in software; PixelCopy isn't available here. */
@Suppress("UNCHECKED_CAST")
private fun drawWindows(name: String) {
    val wmg = Class.forName("android.view.WindowManagerGlobal")
    val inst = wmg.getMethod("getInstance").invoke(null)
    val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
    val views = (f.get(inst) as List<View>).filter { it.width > 0 && it.height > 0 }
    val main = views.first()
    val bmp = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    for ((i, v) in views.withIndex()) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (i > 0) c.drawColor(0x55000000)
        c.save()
        c.translate(loc[0].toFloat(), loc[1].toFloat())
        v.draw(c)
        c.restore()
    }
    val dir = File("build/shots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private fun SemanticsNodeInteractionsProvider.shot(name: String) {
    if (this is androidx.compose.ui.test.junit4.ComposeTestRule) waitForIdle()
    drawWindows(name)
}

private fun byText(t: String): Node = Store.allNodes().first { it.plain == t }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun clipboard(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()

    private fun foldWelcome() {
        rule.runOnIdle { Store.toggleCollapsed(byText("Welcome to Twig")) }
        rule.waitForIdle()
    }

    private fun longPressRelease(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput {
            down(center)
            advanceEventTime(450)
            moveBy(Offset(1f, 0f))
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun home() {
        rule.onNodeWithText("Welcome to Twig").assertExists()
        rule.onNodeWithTag("searchBar").assertExists()
        rule.onNodeWithText("Twig").assertDoesNotExist() // no title bar any more
        rule.onNodeWithTag("fab").assertDoesNotExist()
        rule.onNodeWithText("Add bullet").assertExists()
        rule.shot("01-home")
    }

    @Test
    fun addBulletHintAndNotTheSpaceBelowIt() {
        foldWelcome()
        val before = Store.size
        // the empty space under the hint does nothing
        rule.onNodeWithTag("footer").performClick()
        rule.waitForIdle()
        assertEquals(before, Store.size)
        // the hint adds a bullet at the end and starts editing it
        rule.onNodeWithTag("addBullet").performClick()
        rule.waitForIdle()
        assertEquals(before + 1, Store.size)
        assertSame(Store.root.children.last().id, Store.focusedId)
    }

    @Test
    fun typeEnterAndTab() {
        rule.onNodeWithTag("addBullet").performClick()
        rule.waitForIdle()
        rule.onNode(isFocused()).performTextInput("Buy milk")
        rule.onNode(isFocused()).performTextInput("\n")
        rule.waitForIdle()
        rule.onNode(isFocused()).performTextInput("Eggs")
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        val milk = byText("Buy milk")
        val eggs = byText("Eggs")
        assertSame(milk, eggs.parent)
        assertSame(Store.root, milk.parent)
        rule.shot("02-typing")
    }

    @Test
    fun longPressDragReorders() {
        val bucket = byText("Bucket list")
        foldWelcome()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("bullet-${bucket.id}").performTouchInput {
            down(center)
            advanceEventTime(450)
            repeat(25) {
                moveBy(Offset(0f, -40f))
                advanceEventTime(16)
            }
        }
        rule.mainClock.advanceTimeBy(400)
        drawWindows("03-dragging")
        rule.onNodeWithTag("bullet-${bucket.id}").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertSame(bucket, Store.root.children.first())
    }

    @Test
    fun dragStillWorksAfterZoomingInAndOut() {
        val welcome = byText("Welcome to Twig")
        val bucket = byText("Bucket list")
        rule.onNodeWithTag("bullet-${welcome.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("searchBack").performClick()
        rule.waitForIdle()
        foldWelcome()
        rule.onNodeWithTag("bullet-${bucket.id}").performTouchInput {
            down(center)
            advanceEventTime(450)
            repeat(25) {
                moveBy(Offset(0f, -40f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        assertSame(bucket, Store.root.children.first())
    }

    @Test
    fun longPressDragSidewaysNests() {
        val pasta = byText("Learn to make fresh pasta")
        val lights = byText("See the northern lights")
        foldWelcome()
        rule.onNodeWithTag("bullet-${pasta.id}").performTouchInput {
            down(center)
            advanceEventTime(450)
            repeat(6) {
                moveBy(Offset(25f, 0f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        assertSame(lights, pasta.parent)
        assertFalse(pasta.done)
    }

    @Test
    fun swipeRightTogglesCompleted() {
        val lights = byText("See the northern lights")
        foldWelcome()
        assertFalse(lights.done)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("text-${lights.id}").performTouchInput {
            down(centerLeft + Offset(20f, 0f))
            repeat(8) {
                moveBy(Offset(32f, 1f))
                advanceEventTime(16)
            }
        }
        rule.mainClock.advanceTimeBy(100)
        drawWindows("04-swiping")
        rule.onNodeWithTag("text-${lights.id}").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(lights.done)
        // starting on the bullet works too, and toggles back
        rule.onNodeWithTag("bullet-${lights.id}").performTouchInput {
            down(center)
            repeat(8) {
                moveBy(Offset(32f, 0f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        assertFalse(lights.done)
    }

    @Test
    fun shortOrVerticalSwipesDoNothing() {
        val lights = byText("See the northern lights")
        foldWelcome()
        rule.onNodeWithTag("text-${lights.id}").performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        // vertical drag starting on the bullet (on a text field Robolectric's Magnifier shim crashes)
        rule.onNodeWithTag("bullet-${lights.id}").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(4f, 40f)) }
            up()
        }
        rule.waitForIdle()
        assertFalse(lights.done)
    }

    @Test
    fun tapBulletZoomsAndBackReturns() {
        val welcome = byText("Welcome to Twig")
        rule.onNodeWithTag("bullet-${welcome.id}").performClick()
        rule.waitForIdle()
        assertSame(welcome, Store.zoom)
        rule.onNodeWithTag("header").assertExists()
        rule.shot("05-zoomed")
        rule.onNodeWithTag("searchBack").performClick()
        rule.waitForIdle()
        assertSame(Store.root, Store.zoom)
        rule.onNodeWithTag("bullet-${welcome.id}").performClick()
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertSame(Store.root, Store.zoom)
    }

    @Test
    fun searchBarFindsAndJumps() {
        rule.onNodeWithTag("searchField").performTextInput("pasta")
        rule.waitForIdle()
        rule.onNodeWithText("1 found").assertExists()
        rule.shot("06-search")
        rule.onNodeWithText("Learn to make fresh pasta").performClick()
        rule.waitForIdle()
        assertSame(byText("Bucket list"), Store.zoom)
        rule.onNodeWithTag("results").assertDoesNotExist()
        rule.onNodeWithText("Search").assertExists() // query cleared, placeholder back
        // clearing the query brings the outline back without an extra list
        rule.onNodeWithTag("searchField").performTextInput("zzz")
        rule.onNodeWithText("Nothing found").assertExists()
        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("outline").assertExists()
    }

    @Test
    fun menuDeleteUndo() {
        val lights = byText("See the northern lights")
        foldWelcome()
        longPressRelease("bullet-${lights.id}")
        rule.onNodeWithText("Move to…").assertExists()
        rule.onNodeWithText("Make to-do").assertDoesNotExist()
        rule.shot("07-menu")
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertFalse(Store.isAlive(lights))
        rule.onNodeWithText("Undo").assertExists()
        rule.shot("08-undo")
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertTrue(Store.isAlive(lights))
    }

    @Test
    fun snackbarSwipesAway() {
        val lights = byText("See the northern lights")
        rule.runOnIdle { Store.delete(lights) }
        rule.waitForIdle()
        rule.onNodeWithTag("snackbar").assertExists()
        rule.onNodeWithTag("snackbar").performTouchInput {
            down(center)
            repeat(10) {
                moveBy(Offset(60f, 0f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("snackbar").assertDoesNotExist()
        assertFalse(Store.isAlive(lights))
        assertNull(Store.notice)
    }

    @Test
    fun linkIsVisibleCopyableAndEditable() {
        lateinit var n: Node
        rule.runOnIdle {
            n = Store.addShared(Store.inbox(), "Never Gonna Give You Up", "https://youtu.be/dQw4w9WgXcQ", "Rick Astley", null)
        }
        rule.waitForIdle()
        rule.onNodeWithText("youtu.be/dQw4w9WgXcQ · Rick Astley").assertExists()
        // long-press the link line copies the URL
        rule.onNodeWithTag("link-${n.id}").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("https://youtu.be/dQw4w9WgXcQ", clipboard())
        // editing the item shows the full, editable URL
        rule.onNodeWithTag("text-${n.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("url-${n.id}").assertExists()
        rule.shot("09-link-edit")
        rule.onNodeWithTag("url-${n.id}").performTextReplacement("https://example.org/x")
        rule.waitForIdle()
        assertEquals("https://example.org/x", n.url)
        rule.runOnIdle { Store.flushBlocking() }
    }

    @Test
    fun addLinkFromMenu() {
        val lights = byText("See the northern lights")
        foldWelcome()
        longPressRelease("bullet-${lights.id}")
        rule.onNodeWithText("Add link").performClick()
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        rule.waitForIdle()
        rule.onNodeWithTag("url-${lights.id}").assertExists().assertIsFocused()
        rule.onNodeWithTag("url-${lights.id}").performTextInput("https://northernlights.example")
        rule.waitForIdle()
        assertEquals("https://northernlights.example", lights.url)
        // leaving with an empty link removes it again
        rule.onNodeWithTag("url-${lights.id}").performTextReplacement("")
        rule.runOnIdle { rule.activity.currentFocus?.clearFocus() }
        rule.onNodeWithTag("searchField").performClick()
        rule.waitForIdle()
        assertNull(lights.url)
    }

    @Test
    fun pastedBareUrlBecomesLink() {
        rule.onNodeWithTag("addBullet").performClick()
        rule.waitForIdle()
        rule.onNode(isFocused()).performTextInput("https://example.com/article?utm_source=feed")
        val n = byText("https://example.com/article?utm_source=feed")
        rule.onNodeWithTag("searchField").performClick() // focus leaves the item
        rule.waitForIdle()
        assertEquals("https://example.com/article", n.url)
    }

    @Test
    fun movePickerQuickTargetsBrowseAndBottom() {
        val pasta = byText("Learn to make fresh pasta")
        val welcome = byText("Welcome to Twig")
        foldWelcome()
        longPressRelease("bullet-${pasta.id}")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pickerField").assertIsFocused()
        rule.onNodeWithText("current parent").assertExists()
        // browse into Welcome, put it at the bottom of it
        rule.onNodeWithTag("place-Bottom").performClick()
        rule.onNodeWithTag("browse-${welcome.id}").performClick()
        rule.waitForIdle()
        rule.shot("11-move-browse")
        rule.onNodeWithText("move here").performClick()
        rule.waitForIdle()
        assertSame(welcome, pasta.parent)
        assertSame(pasta, welcome.children.last())
        // and straight to the top level from the quick list
        longPressRelease("bullet-${welcome.id}")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pickerField").performTextInput("bucket")
        rule.waitForIdle()
        rule.onNodeWithTag("dest-${byText("Bucket list").id}").performClick()
        rule.waitForIdle()
        assertSame(byText("Bucket list"), welcome.parent)
    }

    @Test
    fun hideCompletedSwipeOffersUndo() {
        val lights = byText("See the northern lights")
        foldWelcome()
        rule.runOnIdle { Store.hideCompleted = true }
        rule.onNodeWithTag("bullet-${lights.id}").performTouchInput {
            down(center)
            repeat(8) {
                moveBy(Offset(32f, 0f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        assertTrue(lights.done)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        rule.waitForIdle()
        rule.onNodeWithTag("text-${lights.id}").assertDoesNotExist()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertFalse(lights.done)
        rule.onNodeWithTag("text-${lights.id}").assertExists()
    }

    @Test
    fun infoMessageDoesNotPushUndoAway() {
        val lights = byText("See the northern lights")
        rule.runOnIdle { Store.delete(lights) }
        rule.waitForIdle()
        rule.runOnIdle { Store.notify("Link copied") }
        rule.waitForIdle()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertTrue(Store.isAlive(lights))
    }

    @Test
    fun moveToDialog() {
        val lights = byText("See the northern lights")
        foldWelcome()
        longPressRelease("bullet-${lights.id}")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.shot("10-move")
        rule.onNodeWithText("Top level").assertExists()
        rule.onNodeWithTag("dest-${Store.inbox().id}").performClick()
        rule.waitForIdle()
        assertSame(Store.inbox(), lights.parent)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeDark() {
        rule.onNodeWithText("Welcome to Twig").assertExists()
        rule.shot("11-home-dark")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun shareYoutubeToInbox() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/dQw4w9WgXcQ?si=abc")
            .putExtra(Intent.EXTRA_SUBJECT, "Never Gonna Give You Up")
        ActivityScenario.launch<ShareActivity>(intent).use {
            rule.onNodeWithText("Save to Twig").assertExists()
            rule.shot("12-share")
            rule.onNodeWithTag("dest-inbox").performClick()
            rule.waitForIdle()
            val n = Store.inbox().children.first()
            assertEquals("Never Gonna Give You Up", n.plain)
            assertEquals("https://youtu.be/dQw4w9WgXcQ", n.url)
        }
    }
}
