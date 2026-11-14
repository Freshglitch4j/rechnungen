package io.github.freshglitch4j.abfahrt

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.freshglitch4j.abfahrt.core.Settings
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

/** Zeichnet die Oberflächen in Bilddateien (build/screenshots), um das Layout zu begutachten. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {
    private lateinit var ctx: Context
    private var now: LocalDateTime = LocalDateTime.of(2026, 11, 16, 16, 39, 20)
    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val vao = FakeVao()
        vao.boards["at:47:64459"] = listOf(FakeDep("16:46", 5))
        vao.boards["at:47:1172"] = listOf(FakeDep("16:59", 0, "REX 5333", "REX 2", "Wörgl Hbf"))
        vao.boards["at:47:65761"] = listOf(FakeDep("06:33", 2))
        vao.boards["at:47:65724"] = listOf(FakeDep("06:32", 0, "Bus 4056", "4056"), FakeDep("06:42", 0, "Bus 4055", "4055"))
        Live.reset(); Live.http = vao; Live.clock = { now }
        Store(ctx).settings = Settings(accessId = "gueltig")
        Notifier.createChannels(ctx)
    }

    @After fun tearDown() { Live.reset() }

    private fun shoot(view: View, name: String, w: Int = 1080, h: Int = 2340) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF000000.toInt())
        view.draw(canvas)
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(outDir, "$name.png").length() > 1000)
    }

    private fun <T : Activity> shootActivity(cls: Class<T>, name: String) {
        val a = Robolectric.buildActivity(cls).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        shoot(a.window.decorView, name)
    }

    @Test fun standanzeigeHeimfahrt() {
        Controller.step(ctx, Controller.ACTION_TICK)
        shootActivity(StandActivity::class.java, "stand-heimfahrt")
    }

    @Test fun standanzeigeHinfahrt() {
        now = LocalDateTime.of(2026, 11, 16, 6, 21, 5)
        Controller.step(ctx, Controller.ACTION_TICK)
        shootActivity(StandActivity::class.java, "stand-hinfahrt")
    }

    @Test fun appMitLaufenderAnzeige() {
        Controller.step(ctx, Controller.ACTION_TICK)
        shootActivity(MainActivity::class.java, "app-aktiv")
    }

    @Test fun appImLeerlauf() {
        now = LocalDateTime.of(2026, 11, 16, 12, 0)
        Store(ctx).settings = Settings()
        shootActivity(MainActivity::class.java, "app-leerlauf")
    }

    @Test fun widget() {
        Controller.step(ctx, Controller.ACTION_TICK)
        val host = FrameLayout(ctx)
        host.addView(DepartureWidget.render(ctx, Live.display).apply(ctx, host))
        shoot(host, "widget-aktiv", 1000, 420)
        Live.reset(); Live.clock = { LocalDateTime.of(2026, 11, 14, 12, 0) }
        val idle = FrameLayout(ctx)
        idle.addView(DepartureWidget.render(ctx, null).apply(ctx, idle))
        shoot(idle, "widget-leerlauf", 1000, 420)
    }
}
