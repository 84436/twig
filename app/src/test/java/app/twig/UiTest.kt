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
import app.twig.data.TextFormat
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
            advanceEventTime(16)
            if (release) up()
        }
        rule.waitForIdle()
    }

    private fun openBucket() = click("row-${bucket.id}")

    @Test
    fun homeShowsListsLikeFolders() {
        rule.onNodeWithTag("label-${welcome.id}").assertExists()
        rule.onNodeWithText("3  ›").assertExists() // Bucket list holds 3
        rule.onNodeWithTag("searchBar").assertExists()
        rule.onNodeWithText("Add bullet").assertExists()
        rule.shot("01-home")
    }

    @Test
    fun tapListOpensAndBackReturns() {
        click("row-${welcome.id}")
        assertSame(welcome, Store.zoom)
        rule.onNodeWithTag("header").assertExists()
        rule.shot("02-list")
        click("searchBack")
        assertSame(Store.root, Store.zoom)
        click("row-${welcome.id}")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertSame(Store.root, Store.zoom)
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
        click("searchBack")
        rule.onNodeWithTag("label-${movies.id}").assertExists()
        assertEquals("Dune", movies.children.single().plain)
        // one more level, and that's it
        click("row-${movies.id}")
        val dune = byText("Dune")
        click("bullet-${dune.id}")
        assertSame(dune, Store.zoom)
        click("addBullet")
        rule.onNode(isFocused()).performTextInput("Part One")
        click("bullet-${byText("Part One").id}")
        assertSame(dune, Store.zoom)
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
        swipe("text-${n.id}", -32f)
        assertTrue(Store.isAlive(n)) // swiping alone never deletes
        assertNull(Store.focusedId) // nor starts editing
        rule.shot("05-swipe-delete")
        click("delete-${n.id}")
        assertFalse(Store.isAlive(n))
        rule.onNodeWithText("Undo").assertExists()
        rule.shot("06-undo")
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertTrue(Store.isAlive(n))
        // swiping another row puts the first button away
        swipe("text-${n.id}", -32f)
        swipe("text-${pasta.id}", -32f)
        assertEquals(pasta.id, Store.revealedId)
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
        drawWindows("07-dragging")
        rule.onNodeWithTag("bullet-${n.id}").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertSame(n, bucket.children.first())
        click("searchBack")
        val b = bucket
        rule.onNodeWithTag("row-${b.id}").performTouchInput {
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
        rule.shot("08-menu")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pickerField").assertIsFocused()
        rule.shot("09-move")
        click("dest-${welcome.id}")
        assertSame(welcome, n.parent)
        rule.onNodeWithText("Moved to Welcome to Twig").assertExists()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertSame(bucket, n.parent)
    }

    @Test
    fun countsFollowMovesOnTheSamePage() {
        lateinit var loose: Node
        rule.runOnIdle { loose = Store.create(Store.root, 2, "loose") }
        rule.waitForIdle()
        rule.onNodeWithText("3  ›").assertExists()
        rule.runOnIdle { Store.move(listOf(loose), bucket) }
        rule.waitForIdle()
        rule.onNodeWithText("4  ›").assertExists()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("3  ›").assertExists()
        // a bullet that gets its first bullet turns into a list row right away
        rule.runOnIdle { Store.move(listOf(byText("Learn to make fresh pasta")), loose) }
        rule.waitForIdle()
        rule.onNodeWithTag("label-${loose.id}").assertExists()
        rule.onNodeWithText("2  ›").assertExists()
    }

    @Test
    fun movePickerKeepsThreeLevels() {
        lateinit var deep: Node
        rule.runOnIdle {
            deep = Store.create(welcome, 0, "Sub-list")
            Store.create(deep, 0, "item")
        }
        rule.waitForIdle()
        longPressRelease("row-${bucket.id}")
        rule.onNodeWithText("Move to…").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("dest-${welcome.id}").assertExists()
        rule.onNodeWithTag("dest-${deep.id}").assertDoesNotExist() // Bucket list's items would be 4 deep
        rule.onNodeWithTag("dest-${bucket.id}").assertDoesNotExist() // not into itself
        click("dest-${welcome.id}")
        assertSame(welcome, bucket.parent)
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
        rule.shot("10-select")
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
        rule.onNodeWithText("Moved 2 to Welcome to Twig").assertExists()
        menu("Select")
        rule.onNodeWithText("All").performClick()
        click("batch-Delete")
        assertTrue(bucket.children.isEmpty())
        assertNull(Store.selection)
        rule.onNodeWithText("Deleted “See the northern lights”").assertExists()
    }

    @Test
    fun shareTargetSetting() {
        menu("Save shared items to…")
        rule.shot("11-share-target")
        click("target-${bucket.id}")
        assertSame(bucket, Store.shareTarget)
        menu("Save shared items to…")
        click("target-ask")
        assertNull(Store.shareTarget)
    }

    @Test
    fun clipboardExportAndImport() {
        menu("Copy all as text")
        assertEquals(TextFormat.export(Store.root), clipboard())
        assertTrue(clipboard()!!.startsWith("- Welcome to Twig\n\t- "))
        setClipboard("- One\n  - [Two](https://github.com/84436/twig)\n- ~~Three~~")
        menu("Replace all from clipboard")
        rule.onNodeWithText("Replace everything?").assertExists()
        rule.shot("12-import")
        click("confirmImport")
        assertEquals(listOf("One", "Three"), Store.root.children.map { it.plain })
        assertTrue(Store.root.children[1].done)
        rule.onNodeWithText("Undo").assertDoesNotExist()
        setClipboard("   ")
        menu("Replace all from clipboard")
        rule.onNodeWithText("Nothing to import").assertExists()
    }

    @Test
    fun urlFieldAddEditRemove() {
        openBucket()
        val n = lights
        click("text-${n.id}")
        rule.onNodeWithTag("url-${n.id}").assertExists()
        rule.onNodeWithText("Add URL").assertExists()
        rule.shot("13-editing")
        rule.onNodeWithTag("url-${n.id}").performTextInput("Example.com/aurora")
        rule.waitForIdle()
        click("searchField") // leave the bullet
        assertEquals("https://Example.com/aurora", n.url)
        rule.onNodeWithText("example.com").assertExists()
        rule.onNodeWithTag("link-${n.url}").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("https://Example.com/aurora", clipboard())
        // the field is there to change or clear it again
        click("text-${n.id}")
        click("clearUrl-${n.id}")
        click("searchField")
        assertNull(n.url)
        click("text-${n.id}")
        rule.onNodeWithText("Add URL").assertExists()
    }

    @Test
    fun siteSpecificLinks() {
        rule.runOnIdle {
            Store.create(bucket, 0, "Never Gonna Give You Up", "https://youtu.be/dQw4w9WgXcQ")
            Store.create(bucket, 1, "Twig", "https://github.com/84436/twig")
            Store.create(bucket, 2, "Twig releases", "https://github.com/84436/twig/releases")
            Store.create(bucket, 3, "A post", "https://www.example.com/post")
        }
        openBucket()
        rule.onNodeWithText("YouTube").assertExists()
        rule.onNodeWithText("84436/twig").assertExists()
        rule.onNodeWithText("github.com").assertExists()
        rule.onNodeWithText("example.com").assertExists()
        rule.shot("14-links")
    }

    @Test
    fun searchFindsAndJumps() {
        rule.onNodeWithTag("searchField").performTextInput("pasta")
        rule.waitForIdle()
        rule.onNodeWithText("1 found").assertExists()
        rule.shot("15-search")
        rule.onNodeWithText("Learn to make fresh pasta").performClick()
        rule.waitForIdle()
        assertSame(bucket, Store.zoom)
        rule.onNodeWithTag("results").assertDoesNotExist()
        rule.onNodeWithText("Search").assertExists()
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
@Config(sdk = [35], qualifiers = "w400dp-h860dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun listDark() {
        rule.onNodeWithTag("row-${byText("Bucket list").id}").performClick()
        rule.waitForIdle()
        rule.shot("16-list-dark")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
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
            rule.onNodeWithText("YouTube").assertExists()
            rule.shot("17-share")
            rule.onNodeWithTag("dest-${bucket.id}").performClick()
            rule.waitForIdle()
            val n = bucket.children.first()
            assertEquals("Never Gonna Give You Up", n.plain)
            assertEquals("https://youtu.be/dQw4w9WgXcQ", n.url)
        }
    }

    @Test
    fun chosenTargetSavesWithoutAsking() {
        Store.awaitLoaded()
        Store.ensureSeeded()
        val bucket = byText("Bucket list")
        Store.shareTarget = bucket
        ActivityScenario.launch<ShareActivity>(share("Some tab https://example.com/a?utm_source=x", null)).use {
            val n = bucket.children.first()
            assertEquals("Some tab", n.plain)
            assertEquals("https://example.com/a", n.url)
            rule.onNodeWithText("Save to Twig").assertDoesNotExist()
        }
    }
}
