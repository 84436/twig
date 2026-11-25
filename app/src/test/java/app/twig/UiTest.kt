@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package app.twig

import android.content.ClipData
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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.twig.data.TextFormat
import app.twig.share.ShareActivity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import app.twig.data.Gist
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import app.twig.data.SwipeAction
import java.util.concurrent.CountDownLatch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import androidx.compose.ui.test.assertIsNotFocused
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
@Config(sdk = [37], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun clipboard(): String? = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
    private fun setClipboard(t: String) = ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("t", t))

    private val welcome get() = byText("Welcome to Twig")
    private val bucket get() = byText("Bucket list")
    private val lights get() = byText("See the northern lights")
    private val pasta get() = byText("Learn to make fresh pasta")

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun menu(item: String) {
        click("more")
        rule.onNodeWithText(item).performClick()
        rule.waitForIdle()
    }

    /** Stop editing, the way opening search does. */
    private fun leave() {
        click("openSearch")
        click("closeSearch")
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

    private fun swipe(tag: String, dx: Float, steps: Int = 8, release: Boolean = true) {
        rule.onNodeWithTag(tag).performTouchInput {
            advanceEventTime(1000) // not a double tap on the field's text
            down(if (dx > 0) centerLeft + Offset(20f, 0f) else center)
            repeat(steps) {
                advanceEventTime(16) // a move stamped with the down's time merges into it
                moveBy(Offset(dx, 1f))
            }
            advanceEventTime(300) // stop before lifting: no fling, only the distance counts
            if (release) up()
        }
        rule.waitForIdle()
    }

    private fun openBucket() = click("open-${bucket.id}")

    @Test
    fun homeShowsListsLikeFolders() {
        rule.onNodeWithTag("crumb-0").assertExists()
        rule.onNodeWithTag("open-${bucket.id}").assert(hasText("3"))
        rule.onNodeWithText("Add bullet").assertExists()
        rule.shot("01-home")
    }

    @Test
    fun openFromChevronOrDotAndGoBack() {
        click("open-${welcome.id}")
        assertSame(welcome, Store.zoom)
        rule.onNodeWithTag("crumb-${welcome.id}").assertExists()
        rule.shot("02-list")
        click("crumb-0") // the path is a way back too
        assertSame(Store.root, Store.zoom)
        click("bullet-${welcome.id}")
        assertSame(welcome, Store.zoom)
        click("back")
        assertSame(Store.root, Store.zoom)
        assertNull(Store.highlightId) // no flash on the list you came from
        click("bullet-${welcome.id}")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertSame(Store.root, Store.zoom)
    }

    @Test
    fun tappingAListsTextEditsIt() {
        click("text-${welcome.id}")
        assertSame(Store.root, Store.zoom)
        assertEquals(welcome.id, Store.focusedId)
        rule.onNodeWithTag("toolbar").assertExists()
    }

    @Test
    fun dotTurnsABulletIntoAList() {
        click("addBullet")
        rule.onNode(isFocused()).performTextInput("Movies")
        val movies = byText("Movies")
        click("bullet-${movies.id}")
        assertSame(movies, Store.zoom)
        click("addBullet")
        rule.onNode(isFocused()).performTextInput("Dune")
        click("bullet-${byText("Dune").id}") // two levels, and that's it
        assertSame(movies, Store.zoom)
        click("back")
        rule.onNodeWithTag("open-${movies.id}").assertExists()
    }

    @Test
    fun addBulletHintAndNotTheSpaceBelowIt() {
        val before = Store.size
        click("footer")
        assertEquals(before, Store.size)
        click("addBullet")
        assertEquals(before + 1, Store.size)
        assertEquals(Store.root.children.last().id, Store.focusedId)
    }

    @Test
    fun enterAddsBulletsAndEnterOnEmptyStops() {
        openBucket()
        click("addBullet")
        rule.onNode(isFocused()).performTextInput("A")
        rule.onNode(isFocused()).performTextInput("\n")
        rule.waitForIdle()
        rule.onNode(isFocused()).performTextInput("B")
        rule.shot("03-typing")
        rule.onNode(isFocused()).performTextInput("\n")
        rule.waitForIdle()
        rule.onNode(isFocused()).performTextInput("\n") // on the new, empty bullet
        rule.waitForIdle()
        assertNull(Store.focusedId)
        assertEquals(listOf("A", "B"), bucket.children.drop(3).map { it.plain })
    }

    @Test
    fun hidingTheKeyboardEndsEditing() {
        openBucket()
        click("text-${lights.id}")
        val view = rule.activity.window.decorView
        fun ime(visible: Boolean) = rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) 900 else 0))
                .setVisible(WindowInsetsCompat.Type.ime(), visible)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        ime(true)
        rule.waitForIdle()
        assertEquals(lights.id, Store.focusedId)
        ime(false) // only a blink, as when Enter moves the cursor to a new bullet
        ime(true)
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(lights.id, Store.focusedId)
        ime(false)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertNull(Store.focusedId)
        rule.onNodeWithTag("toolbar").assertDoesNotExist()
    }

    @Test
    fun swipeRightCompletes() {
        openBucket()
        val n = lights
        rule.mainClock.autoAdvance = false
        swipe("text-${n.id}", 32f, release = false)
        rule.mainClock.advanceTimeBy(100)
        drawWindows("04-swipe-done")
        rule.onNodeWithTag("text-${n.id}").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(n.done)
        rule.onNodeWithTag("snackbar").assertDoesNotExist() // completing isn't announced
        swipe("bullet-${n.id}", 32f)
        assertFalse(n.done)
        swipe("text-${n.id}", 20f, steps = 4) // a swipe, but too short
        assertFalse(n.done)
        assertNull(Store.focusedId)
    }

    @Test
    fun swipeLeftUncoversDelete() {
        openBucket()
        val n = lights
        swipe("text-${n.id}", -20f, steps = 4) // a nudge springs back
        rule.onNodeWithTag("delete-${n.id}").assertDoesNotExist()
        rule.runOnIdle { n.url = "https://example.com" } // a taller row
        swipe("text-${n.id}", -32f)
        assertTrue(Store.isAlive(n)) // swiping alone never deletes
        assertNull(Store.focusedId) // nor starts editing
        assertEquals(
            rule.onNodeWithTag("row-${n.id}").fetchSemanticsNode().size.height,
            rule.onNodeWithTag("delete-${n.id}").fetchSemanticsNode().size.height,
        )
        rule.shot("05-swipe-delete")
        click("delete-${n.id}")
        assertFalse(Store.isAlive(n))
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertTrue(Store.isAlive(n))
        // swiping another row puts the first button away
        swipe("text-${n.id}", -32f)
        swipe("text-${pasta.id}", -32f)
        assertEquals(pasta.id, Store.revealedId)
    }

    @Test
    fun swipePastTheStopGivesWay() {
        openBucket()
        val text = rule.onNodeWithTag("text-${lights.id}")
        fun x() = text.fetchSemanticsNode().positionInRoot.x
        val x0 = x()
        val stop = with(rule.density) { 72.dp.toPx() }
        swipe("text-${lights.id}", -60f, release = false) // 480px of finger
        val pulled = x0 - x()
        assertTrue("$pulled", pulled > stop + 40f && pulled < stop + (480f - stop) / 2) // past the stop, but resisting
        text.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(stop, x0 - x(), 1f) // and back to the Delete button's width
    }

    @Test
    fun swipesDoWhatSettingsSay() {
        openBucket()
        rule.runOnIdle {
            Store.swipeRight = SwipeAction.Select
            Store.swipeLeft = SwipeAction.Move
        }
        swipe("text-${lights.id}", 32f)
        assertEquals(setOf(lights.id), Store.selection)
        click("batch-Done") // and batch mode takes it from there
        assertTrue(lights.done)
        rule.runOnIdle { Store.selection = null }
        swipe("text-${pasta.id}", -32f)
        rule.onNodeWithText("Move “Learn to make fresh pasta” to…").assertExists()
        click("dest-${welcome.id}")
        assertSame(welcome, pasta.parent)
        // Delete can sit on the right too, and Nothing means nothing (not even a tap into the text)
        rule.runOnIdle {
            Store.swipeRight = SwipeAction.Delete
            Store.swipeLeft = SwipeAction.None
        }
        swipe("text-${lights.id}", -32f)
        rule.onNodeWithTag("delete-${lights.id}").assertDoesNotExist()
        assertNull(Store.selection)
        assertNull(Store.focusedId)
        swipe("text-${lights.id}", 32f)
        click("delete-${lights.id}")
        assertFalse(Store.allNodes().any { it.plain == "See the northern lights" })
    }

    @Test
    fun dragReordersBulletsAndLists() {
        openBucket()
        val n = pasta
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("bullet-${n.id}").performTouchInput {
            down(center)
            advanceEventTime(450)
            repeat(20) {
                moveBy(Offset(0f, -40f))
                advanceEventTime(16)
            }
        }
        rule.mainClock.advanceTimeBy(400)
        drawWindows("06-dragging")
        rule.onNodeWithTag("bullet-${n.id}").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertSame(n, bucket.children.first())
        click("back")
        val b = bucket
        rule.onNodeWithTag("bullet-${b.id}").performTouchInput {
            down(center)
            advanceEventTime(450)
            repeat(10) {
                moveBy(Offset(0f, -40f))
                advanceEventTime(16)
            }
            up()
        }
        rule.waitForIdle()
        assertSame(b, Store.root.children.first())
        assertSame(Store.root, Store.zoom) // a drag isn't a tap
    }

    @Test
    fun moveToAnotherListWithUndo() {
        openBucket()
        val n = lights
        longPressRelease("bullet-${n.id}")
        rule.onNodeWithText("Select").assertExists()
        rule.shot("07-menu")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Move “See the northern lights” to…").assertExists()
        rule.shot("08-move")
        click("cancel") // X: nothing moves
        rule.onNodeWithTag("locationSheet").assertDoesNotExist()
        assertSame(bucket, n.parent)
        longPressRelease("bullet-${n.id}")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        click("dest-0") // Home is a place too
        assertSame(Store.root, n.parent)
        rule.onNodeWithText("Moved to Home").assertExists()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertSame(bucket, n.parent)
    }

    @Test
    fun movePickerKeepsTwoLevels() {
        longPressRelease("bullet-${bucket.id}")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("dest-0").assertExists()
        rule.onNodeWithTag("dest-${welcome.id}").assertDoesNotExist() // Bucket list's items would be 3 deep
        rule.onNodeWithTag("dest-${bucket.id}").assertDoesNotExist() // not into itself
    }

    @Test
    fun countsFollowMovesOnTheSamePage() {
        lateinit var loose: Node
        rule.runOnIdle { loose = Store.create(Store.root, 2, "loose") }
        rule.waitForIdle()
        rule.runOnIdle { Store.move(listOf(loose), bucket) }
        rule.waitForIdle()
        rule.onNodeWithTag("open-${bucket.id}").assert(hasText("4"))
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("open-${bucket.id}").assert(hasText("3"))
        // a bullet that gets its first bullet shows its › right away
        rule.runOnIdle { Store.move(listOf(byText("Learn to make fresh pasta")), loose) }
        rule.waitForIdle()
        rule.onNodeWithTag("open-${loose.id}").assert(hasText("1"))
    }

    @Test
    fun batchMode() {
        openBucket()
        menu("Select")
        rule.onNodeWithText("0 selected").assertExists()
        rule.onNodeWithTag("addBullet").assertDoesNotExist()
        click("row-${lights.id}")
        click("row-${pasta.id}")
        rule.onNodeWithText("2 selected").assertExists()
        assertNull(Store.focusedId) // selecting, not editing
        rule.shot("09-select")
        click("batch-Done")
        assertTrue(lights.done && pasta.done)
        click("batch-Undone")
        assertFalse(lights.done || pasta.done)
        rule.onNodeWithText("None").performClick()
        rule.onNodeWithText("0 selected").assertExists()
        rule.onNodeWithText("All").performClick()
        rule.onNodeWithText("3 selected").assertExists()
        click("row-${lights.id}") // tap again to unselect
        click("batch-Move")
        click("dest-${welcome.id}")
        assertSame(welcome, pasta.parent)
        assertNull(Store.selection)
        menu("Select")
        rule.onNodeWithText("All").performClick()
        click("batch-Delete")
        assertTrue(bucket.children.isEmpty())
        assertNull(Store.selection)
    }

    @Test
    fun moveUpKeepsTheBulletInView() {
        rule.runOnIdle { repeat(40) { Store.create(bucket, bucket.children.size, "filler $it") } }
        openBucket()
        val n = pasta
        click("text-${n.id}")
        click("tool-Move up")
        click("tool-Move up")
        assertSame(n, bucket.children[0])
        rule.onNodeWithTag("text-${n.id}").assertIsDisplayed()
        menu("Sort Z–A") // and sorting starts you at the top
        rule.onNodeWithText("See the northern lights").assertIsDisplayed()
    }

    @Test
    fun longPressNamesTheButton() {
        openBucket()
        click("text-${lights.id}")
        rule.onNodeWithTag("tool-Use page title").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Use page title").assertExists() // the tooltip, not the icon's description
        rule.shot("21-tooltip")
        assertTrue(Store.fetching.isEmpty()) // a long-press only names it
        rule.onNodeWithTag("more").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("More").assertExists()
    }

    @Test
    fun sortWithUndo() {
        openBucket()
        menu("Sort A–Z")
        assertEquals(listOf("Find a note app that isn't annoying", "Learn to make fresh pasta", "See the northern lights"), bucket.children.map { it.plain })
        menu("Sort Z–A")
        assertEquals("See the northern lights", bucket.children.first().plain)
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals("Find a note app that isn't annoying", bucket.children.first().plain)
    }

    @Test
    fun readOnlyMode() {
        click("readOnly")
        assertTrue(Store.readOnly)
        rule.onNodeWithTag("addBullet").assertDoesNotExist()
        rule.onNodeWithTag("text-${bucket.id}").assertDoesNotExist() // nothing to type in
        rule.shot("10-read-only")
        click("row-${bucket.id}") // a tap on a list opens it
        assertSame(bucket, Store.zoom)
        click("label-${lights.id}")
        assertNull(Store.focusedId)
        swipe("label-${lights.id}", 32f) // completing still works
        assertTrue(lights.done)
        swipe("label-${lights.id}", -32f) // deleting doesn't
        rule.onNodeWithTag("delete-${lights.id}").assertDoesNotExist()
        assertTrue(Store.isAlive(lights))
        longPressRelease("bullet-${lights.id}") // no menu, no drag
        rule.onNodeWithText("Move to…").assertDoesNotExist()
        click("more") // and the menu has nothing that changes bullets
        rule.onNodeWithText("Select").assertDoesNotExist()
        rule.onNodeWithText("Replace all from clipboard").assertDoesNotExist()
        rule.onNodeWithText("Copy all as text").performClick()
        rule.waitForIdle()
        click("bullet-${lights.id}") // an empty bullet doesn't open
        assertSame(bucket, Store.zoom)
        click("readOnly")
        assertFalse(Store.readOnly)
        rule.onNodeWithTag("addBullet").assertExists()
    }

    @Test
    fun settingsSheet() {
        menu("Settings")
        rule.onNodeWithTag("settings").assertExists()
        rule.shot("11-settings")
        click("size-20")
        assertEquals(20, Store.textSize)
        click("font-mono")
        assertTrue(Store.monospace)
        click("left-Select")
        assertEquals(SwipeAction.Select, Store.swipeLeft)
        click("shareTarget")
        rule.onNodeWithTag("dest-ask").assertExists() // "Always ask" comes first
        rule.shot("12-share-target")
        click("dest-0")
        assertSame(Store.root, Store.shareTarget)
        rule.onNodeWithTag("shareTarget").assert(hasText("Home"))
        click("shareTarget")
        click("dest-ask")
        assertNull(Store.shareTarget)
        // sync: a token goes in, how it went comes out (GitHub faked, and it says no)
        Gist.http = { _, _, _ -> Gist.Reply(401, """{"message":"Bad credentials"}""") }
        rule.onNodeWithTag("gistToken").performScrollTo().performTextInput("ghp_x")
        rule.runOnIdle { assertEquals("ghp_x", Gist.token) }
        rule.runOnIdle { runBlocking { Gist.sync() } }
        rule.onNodeWithTag("gistStatus").assert(hasText("GitHub says: Bad credentials"))
        rule.shot("12-sync")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.shot("12-big-mono")
    }

    @Test
    fun clipboardExportAndImport() {
        rule.runOnIdle { Store.delete(listOf(lights)) }
        rule.waitForIdle()
        rule.onNodeWithText("Undo").assertExists()
        menu("Copy all as text")
        assertEquals(TextFormat.export(Store.root), clipboard())
        assertTrue(clipboard()!!.startsWith("- Welcome to Twig\n\t- "))
        setClipboard("- One\n  - [Two](https://github.com/84436/twig)\n- ~~Three~~")
        menu("Replace all from clipboard")
        rule.onNodeWithText("Replace everything?").assertExists()
        click("confirmImport")
        assertEquals(listOf("One", "Three"), Store.root.children.map { it.plain })
        assertTrue(Store.root.children[1].done)
        rule.onNodeWithText("Undo").assertDoesNotExist()
        setClipboard("   ")
        menu("Replace all from clipboard")
        rule.onNodeWithText("Nothing to import").assertExists()
    }

    @Test
    fun urlFromTheKeyboardBar() {
        openBucket()
        val n = lights
        click("text-${n.id}")
        rule.onNodeWithTag("url-${n.id}").assertDoesNotExist() // no field until asked: the list stays put
        click("tool-Add URL")
        rule.onNodeWithTag("url-${n.id}").assertIsFocused()
        rule.shot("13-url")
        rule.onNodeWithTag("url-${n.id}").performTextInput("Example.com/aurora")
        rule.onNodeWithTag("url-${n.id}").performImeAction() // Done: back to the text
        rule.waitForIdle()
        rule.onNodeWithTag("url-${n.id}").assertDoesNotExist()
        rule.onNodeWithTag("text-${n.id}").assertIsFocused()
        rule.onNodeWithText("example.com").assertExists() // the short look right away, not the typed text
        leave()
        assertEquals("https://Example.com/aurora", n.url)
        rule.onNodeWithTag("link-${n.url}").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("https://Example.com/aurora", clipboard())
        click("text-${n.id}")
        click("tool-Edit URL")
        click("clearUrl-${n.id}")
        leave()
        assertNull(n.url)
    }

    @Test
    fun keyboardBarMovesBoldsAndCompletes() {
        openBucket()
        val n = lights
        click("text-${n.id}")
        click("tool-Move up")
        assertSame(n, bucket.children[0])
        rule.onNodeWithTag("text-${n.id}").assertIsFocused() // still typing in it
        click("tool-Move down")
        assertSame(n, bucket.children[1])
        click("tool-Mark done")
        assertTrue(n.done)
        click("tool-Mark not done")
        assertFalse(n.done)
        rule.onNodeWithTag("text-${n.id}").performTextInputSelection(TextRange(0, 3))
        click("tool-Bold")
        assertEquals("**See** the northern lights", n.plain)
        rule.shot("14-bold-editing")
        leave()
        rule.shot("15-bold")
        click("text-${n.id}")
        click("tool-Move to…")
        click("dest-${welcome.id}")
        assertSame(welcome, n.parent)
    }

    @Test
    fun pageTitleTakesTwoTaps() {
        openBucket()
        val n = lights
        val gate = CountDownLatch(1)
        rule.runOnIdle {
            n.url = "https://example.com/aurora"
            Store.fetchTitle = {
                gate.await()
                "Aurora forecast"
            }
        }
        click("text-${n.id}")
        click("tool-Use page title")
        rule.onNodeWithText("Replace text?").assertExists()
        assertTrue(Store.fetching.isEmpty()) // one tap does nothing yet
        click("tool-Use page title")
        rule.onNodeWithTag("fetching-${n.id}").assertExists()
        rule.shot("16-fetching")
        gate.countDown()
        repeat(200) {
            if (n.id in Store.fetching) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        rule.waitForIdle()
        assertEquals("Aurora forecast", n.plain)
        rule.onNodeWithTag("fetching-${n.id}").assertDoesNotExist()
    }

    @Test
    fun siteSpecificLinks() {
        rule.runOnIdle {
            Store.create(bucket, 0, "Never Gonna Give You Up", "https://youtu.be/dQw4w9WgXcQ")
            Store.create(bucket, 1, "A channel", "https://www.youtube.com/@channel")
            Store.create(bucket, 2, "A playlist", "https://www.youtube.com/playlist?list=PL1")
            Store.create(bucket, 3, "Twig", "https://github.com/84436/twig")
            Store.create(bucket, 4, "Twig releases", "https://github.com/84436/twig/releases")
            Store.create(bucket, 5, "A map", "https://osu.ppy.sh/beatmapsets/1234#osu/5678")
            Store.create(bucket, 6, "A post", "https://www.example.com/post")
        }
        openBucket()
        for (t in listOf("YouTube video", "YouTube channel", "YouTube playlist", "84436/twig", "github.com", "Beatmap", "example.com")) {
            rule.onNodeWithText(t).assertExists()
        }
        rule.shot("17-links")
    }

    @Test
    fun searchIsItsOwnScreen() {
        click("openSearch")
        rule.onNodeWithTag("searchField").assertIsFocused()
        rule.onNodeWithTag("page").assertDoesNotExist()
        rule.onNodeWithTag("searchField").performTextInput("pasta")
        rule.waitForIdle()
        rule.onNodeWithText("1 found").assertExists()
        rule.shot("18-search")
        rule.onNodeWithText("Learn to make fresh pasta").performClick()
        rule.waitForIdle()
        assertSame(bucket, Store.zoom)
        rule.onNodeWithTag("searchField").assertDoesNotExist()
        click("openSearch")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("page").assertExists()
        assertSame(bucket, Store.zoom) // Back closed search, didn't go up
    }

    @Test
    fun snackbarSwipesAway() {
        val n = lights
        rule.runOnIdle { Store.delete(listOf(n)) }
        rule.waitForIdle()
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
        assertFalse(Store.isAlive(n))
        assertNull(Store.notice)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w400dp-h860dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun listDark() {
        rule.onNodeWithTag("open-${byText("Bucket list").id}").performClick()
        rule.waitForIdle()
        rule.shot("19-list-dark")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w400dp-h860dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private fun share(text: String, subject: String?) =
        Intent(ApplicationProvider.getApplicationContext(), ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, subject)

    @Test
    fun alwaysAskShowsTheSheet() {
        Store.awaitLoaded()
        Store.ensureSeeded()
        val bucket = byText("Bucket list")
        ActivityScenario.launch<ShareActivity>(share("https://youtu.be/dQw4w9WgXcQ?si=abc", "Never Gonna Give You Up")).use {
            rule.onNodeWithText("Save to Twig").assertExists()
            rule.onNodeWithText("YouTube video").assertExists()
            rule.onNodeWithTag("dest-0").assertExists() // Home is a place too
            rule.shot("20-share")
            rule.onNodeWithTag("dest-${bucket.id}").performClick()
            rule.waitForIdle()
            val n = bucket.children.first()
            assertEquals("Never Gonna Give You Up", n.plain)
            assertEquals("https://youtu.be/dQw4w9WgXcQ", n.url)
        }
    }

    @Test
    fun cancelSavesNothing() {
        Store.awaitLoaded()
        Store.ensureSeeded()
        val before = Store.size
        ActivityScenario.launch<ShareActivity>(share("https://example.com/a", "A page")).use { s ->
            rule.onNodeWithTag("cancel").performClick()
            rule.waitForIdle()
            assertEquals(before, Store.size)
            s.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun chosenTargetSavesWithoutAsking() {
        Store.awaitLoaded()
        Store.ensureSeeded()
        Store.shareTarget = Store.root
        ActivityScenario.launch<ShareActivity>(share("Some tab https://example.com/a?utm_source=x", null)).use {
            val n = Store.root.children.first()
            assertEquals("Some tab", n.plain)
            assertEquals("https://example.com/a", n.url)
            rule.onNodeWithText("Save to Twig").assertDoesNotExist()
        }
    }
}
