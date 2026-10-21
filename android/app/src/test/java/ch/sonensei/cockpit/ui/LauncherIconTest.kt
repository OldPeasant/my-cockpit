package ch.sonensei.cockpit.ui

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher icon (task app-icons): the manifest points at the adaptive mipmaps, which inflate with the
 * brand background, foreground and a monochrome layer for themed icons, and the old placeholder is gone.
 * What the icon looks like is checked by rendering, see docs/brand/README.md.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestUsesTheAdaptiveLauncherMipmap() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, info.icon)
    }

    @Test
    fun squareAndRoundIconsAreAdaptiveWithAllThreeLayers() {
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = ContextCompat.getDrawable(context, id)
            assertTrue("mipmap $id should be an adaptive icon, was $icon", icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            assertNotNull(icon.background)
            assertNotNull(icon.foreground)
            // Themed icons (Android 13+) use this layer; without it the launcher shows the full-colour icon.
            assertNotNull(icon.monochrome)
        }
    }

    @Test
    fun layersUseTheFullAdaptiveIconCanvas() {
        val density = context.resources.displayMetrics.density
        val canvas = Math.round(108 * density)
        for (id in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome)) {
            val layer = ContextCompat.getDrawable(context, id)!!
            assertEquals("width of drawable $id", canvas, layer.intrinsicWidth)
            assertEquals("height of drawable $id", canvas, layer.intrinsicHeight)
        }
    }

    @Test
    fun placeholderDrawableIconIsRemoved() {
        val drawables = R.drawable::class.java.fields.map { it.name }
        assertTrue("reflection sees the drawables", "ic_launcher_foreground" in drawables)
        assertFalse("drawable/ic_launcher (old placeholder) still exists", "ic_launcher" in drawables)
    }
}
