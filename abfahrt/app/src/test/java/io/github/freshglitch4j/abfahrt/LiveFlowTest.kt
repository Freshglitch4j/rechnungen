package io.github.freshglitch4j.abfahrt

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.freshglitch4j.abfahrt.core.Settings
import io.github.freshglitch4j.abfahrt.core.Status
import io.github.freshglitch4j.abfahrt.core.TripKind
import io.github.freshglitch4j.abfahrt.core.ZONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

/** Durchläufe mit nachgestellter Uhr und nachgestelltem VAO-Server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LiveFlowTest {
    private lateinit var ctx: Context
    private lateinit var store: Store
    private lateinit var vao: FakeVao
    private var now: LocalDateTime = LocalDateTime.of(2026, 11, 16, 16, 0)

    private fun at(h: Int, m: Int, s: Int = 0, day: Int = 16) = LocalDateTime.of(2026, 11, day, h, m, s)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        store = Store(ctx)
        vao = FakeVao()
        vao.boards["at:47:64459"] = listOf(FakeDep("16:08", 0, direction = "Breitenbach", via = emptyList()), FakeDep("16:46", 5))
        vao.boards["at:47:1172"] = listOf(
            FakeDep("16:59", 0, "REX 5332", "REX 2", "Innsbruck Hbf", via = listOf("at:47:1000")),
            FakeDep("16:59", 0, "REX 5333", "REX 2", "Wörgl Hbf"),
        )
        vao.boards["at:47:65761"] = listOf(FakeDep("06:33", 0))
        vao.boards["at:47:65724"] = listOf(FakeDep("06:32", 0, "Bus 4056", "4056"), FakeDep("06:42", 0, "Bus 4055", "4055"))
        Live.reset()
        Live.http = vao
        Live.clock = { now }
        store.settings = Settings(accessId = "gueltig")
        Notifier.createChannels(ctx)
    }

    @After fun tearDown() { Live.reset() }

    private fun step(action: String = Controller.ACTION_TICK) = Controller.step(ctx, action)

    private fun nextAlarm(): LocalDateTime? {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return shadowOf(am).scheduledAlarms.minByOrNull { it.triggerAtMs }
            ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.triggerAtMs), ZONE) }
    }

    // ---------------------------------------------------------------- Zeitsteuerung

    @Test fun vorDemFensterWirdNurDerWeckerGestellt() {
        now = at(16, 0)
        assertFalse(Controller.somethingActive(ctx))
        Scheduler.ensure(ctx)
        assertEquals(at(16, 16), nextAlarm())
        assertNull(shadowOf(ctx as Application).nextStartedService)
        assertEquals(0, vao.requests.size)
    }

    @Test fun weckerStartetDenDienstUndDieAnzeigeErscheint() {
        now = at(16, 16)
        AlarmReceiver().onReceive(ctx, Intent(AlarmReceiver.ACTION_START))
        val started = shadowOf(ctx as Application).nextStartedService
        assertEquals(LiveService::class.java.name, started.component!!.className)

        val step = step()
        val d = step.display!!
        assertTrue(step.windowStarted)
        assertEquals(TripKind.HEIM, d.window.kind)
        val bus = d.rows.single { it.conn.id == "heim-bus" }
        assertEquals(Status.DELAYED, bus.status)
        assertEquals(at(16, 51), bus.expected)
        val rex = d.rows.single { it.conn.id == "heim-rex" }
        assertEquals(Status.ON_TIME, rex.status)
        assertEquals(2, vao.boardRequests)
        assertEquals(2, store.usedToday(now.toLocalDate()))
        // nächster Schritt zur nächsten vollen Minute
        assertEquals(at(16, 17, 1), step.nextWake)
    }

    private fun morgenBoards(busDelay: Int?, busCancelled: Boolean = false, rexDelay: Int = 0) {
        vao.boards["at:47:65761"] = listOf(FakeDep("06:33", 0))
        vao.boards["at:47:65724"] = listOf(FakeDep("06:42", busDelay, "Bus 4055", "4055", cancelled = busCancelled,
            arrivals = mapOf("at:47:1170" to ("06:46" to busDelay))))
        vao.boards["at:47:1170"] = listOf(FakeDep("06:54", rexDelay, "REX2", "", "Innsbruck Hbf", via = listOf("at:47:1172"),
            arrivals = mapOf("at:47:1172" to ("06:58" to rexDelay))))
        vao.boards["at:47:1172"] = listOf(FakeDep("07:04", 0, "Bus 760", "760", "Breitenbach", via = listOf("at:47:64459")))
    }

    private fun transferBeforeRex(): io.github.freshglitch4j.abfahrt.core.Transfer {
        Live.reset(); Live.http = vao; Live.clock = { now }
        now = at(6, 30)
        return step().display!!.rows.single { it.conn.id == "hin-kombi-rex" }.transfer!!
    }

    @Test fun umstiegsoptionAlsZweiteOption() {
        morgenBoards(busDelay = 1)
        now = at(6, 20)
        val d = step().display!!
        assertEquals(2, d.options.size)
        assertEquals(listOf("hin-moosegg"), d.options[0].rows.map { it.conn.id })
        assertEquals(listOf("hin-gratten", "hin-kombi-rex", "hin-kombi-bus"), d.options[1].rows.map { it.conn.id })
        val rex = d.rows.single { it.conn.id == "hin-kombi-rex" }
        assertEquals(io.github.freshglitch4j.abfahrt.core.TransferState.OK, rex.transfer!!.state)
        assertEquals(7L, rex.transfer!!.marginMin)            // an 06:47, ab 06:54
        assertEquals("Umstieg Wörgl Hbf · 7 Min", rex.transfer!!.text)
        assertEquals(6L, d.rows.single { it.conn.id == "hin-kombi-bus" }.transfer!!.marginMin)   // REX an Kundl 06:58, Bus ab 07:04
        // nach Abfahrt des 06:33 rückt die Umstiegsoption nach vorn
        now = at(6, 36)
        val later = step().display!!
        assertEquals("hin-gratten", later.primary!!.conn.id)
        assertEquals(1, later.options.size)
    }

    @Test fun umstiegKnappVerpasstUndZubringerfall() {
        morgenBoards(busDelay = 7)
        assertEquals(io.github.freshglitch4j.abfahrt.core.TransferState.TIGHT, transferBeforeRex().state)
        morgenBoards(busDelay = 10)
        assertEquals(io.github.freshglitch4j.abfahrt.core.TransferState.MISSED, transferBeforeRex().state)
        morgenBoards(busDelay = 10, rexDelay = 4)   // Zug wartet bzw. ist selbst verspätet
        assertEquals(io.github.freshglitch4j.abfahrt.core.TransferState.OK, transferBeforeRex().state)
        morgenBoards(busDelay = null, busCancelled = true)
        assertEquals(io.github.freshglitch4j.abfahrt.core.TransferState.BROKEN, transferBeforeRex().state)
    }

    @Test fun farbeNachVerspaetung() {
        fun colorFor(delay: Int?, cancelled: Boolean = false): Int {
            vao.boards["at:47:64459"] = listOf(FakeDep("16:46", delay, cancelled = cancelled))
            Live.reset(); Live.http = vao; Live.clock = { now }
            now = at(16, 40)
            return Notifier.color(step().display!!.rows.single { it.conn.id == "heim-bus" })
        }
        assertEquals(Notifier.GREEN, colorFor(0))
        assertEquals(Notifier.GREEN, colorFor(-1))
        assertEquals(Notifier.YELLOW, colorFor(1))
        assertEquals(Notifier.YELLOW, colorFor(3))
        assertEquals(Notifier.RED, colorFor(4))
        assertEquals(Notifier.PURPLE, colorFor(null, cancelled = true))
        assertEquals(Notifier.GREY, colorFor(null))
    }

    @Test fun abgefahreneFahrtWirdNichtMehrAlsAlternativeGezeigt() {
        vao.boards["at:47:1172"] = listOf(FakeDep("16:59", 8, "REX2", "", "Zell am See"))
        now = at(16, 40); step()
        now = at(17, 4)
        val d = step().display!!
        assertEquals("heim-rex", d.primary!!.conn.id)
        assertTrue(d.others.isEmpty())
    }

    @Test fun sperrbildschirmTestSendetDreiVarianten() {
        Notifier.postLockscreenTest(ctx)
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        val a = nm.getNotification(Notifier.ID_TEST_A)
        val b = nm.getNotification(Notifier.ID_TEST_B)
        val c = nm.getNotification(Notifier.ID_TEST_C)
        assertTrue(a.flags and Notification.FLAG_ONGOING_EVENT == 0)
        assertTrue(b.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNull(b.category)
        assertEquals(Notification.CATEGORY_STATUS, c.category)
    }

    @Test fun standardmaessigNormaleBenachrichtigungOhneLiveAnforderung() {
        now = at(16, 39, 20)
        val n = Notifier.build(ctx, step().display)
        assertFalse(n.extras.getBoolean("android.requestPromotedOngoing"))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Bus 760  16:46 → 16:51  +5 Min", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
    }

    @Test fun benachrichtigungZeigtPlanErwartetUndVerspaetung() {
        store.settings = store.settings.copy(nowBar = true)
        now = at(16, 39, 20)
        val n = Notifier.build(ctx, step().display)
        val x = n.extras
        assertEquals("Bus 760  16:46 → 16:51  +5 Min", x.getString(Notification.EXTRA_TITLE))
        assertEquals("in 11 Min · REX 2 16:59 pünktlich", x.getString(Notification.EXTRA_TEXT))
        val big = x.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(big, big.contains("in 11 Min · Kundl Gemeindesaal"))
        assertTrue(big, big.contains("REX 2  16:59 → 16:59  pünktlich"))
        assertTrue(big, big.contains("in 19 Min · Kundl Bahnhof"))
        assertEquals("Heimfahrt · Stand 16:39", x.getString(Notification.EXTRA_SUB_TEXT))
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(x.getBoolean("android.requestPromotedOngoing"))
        // Vom laufenden System als Live-Benachrichtigung einstufbar
        assertTrue("Live-Update-Anforderungen erfüllt", n.hasPromotableCharacteristics())
        // Variante für Android 16 QPR1+/One UI 8.5 (aktuelle Vorgaben): Standard-/BigText-Stil,
        // laufend, mit Titel, ohne eigene Ansichten, nicht „colorized“, keine Gruppenübersicht
        val plain = Notifier.build(ctx, Live.display, colorized = false)
        assertFalse(plain.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertEquals(Notification.BigTextStyle::class.java.name, plain.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(plain.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(plain.flags and Notification.FLAG_GROUP_SUMMARY == 0)
        assertNull(plain.contentView); assertNull(plain.bigContentView); assertNull(plain.headsUpContentView)
        assertTrue(plain.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(3, n.actions.size)
        // Countdown im Kopf zählt auf die erwartete (nicht die planmäßige) Abfahrt
        assertEquals(at(16, 51).atZone(ZONE).toInstant().toEpochMilli(), n.`when`)
    }

    @Test fun kompletterNachmittagEndetVonSelbst() {
        now = at(16, 16)
        var last = step()
        var titles = mutableSetOf<String>()
        while (last.display != null) {
            titles += Notifier.build(ctx, last.display).extras.getString(Notification.EXTRA_TITLE)!!
            now = last.nextWake!!
            assertTrue("läuft zu lange", now.isBefore(at(17, 30)))
            last = step()
        }
        // Bus (+5) gilt bis 16:53 als sichtbar, danach steht der Zug vorn; Ende 2 Minuten nach 16:59.
        assertTrue(titles.toString(), titles.any { it.startsWith("Bus 760  16:46 → 16:51") })
        assertTrue(titles.toString(), titles.any { it.startsWith("REX 2  16:59 → 16:59") })
        assertEquals(at(17, 1, 1), now)
        assertNull(Live.display)
        // sparsam: deutlich unter dem Tageskontingent von 100
        assertTrue("Abfragen: ${vao.boardRequests}", vao.boardRequests in 20..40)
        // danach ist der nächste Morgen eingeplant
        Scheduler.scheduleNextStart(ctx)
        assertEquals(at(6, 3, day = 17), nextAlarm())
    }

    @Test fun ganzerTagBleibtImKontingent() {
        now = at(5, 50)
        var steps = 0
        while (now.isBefore(at(17, 30))) {
            val s = if (Controller.somethingActive(ctx)) step() else null
            now = s?.nextWake ?: now.plusMinutes(1)
            steps++
        }
        println("Abfragen an einem Montag (Hin- und Heimfahrt): ${vao.boardRequests}")
        assertTrue("Abfragen: ${vao.boardRequests}", vao.boardRequests <= 92)
        assertEquals(vao.boardRequests, store.usedToday(at(12, 0).toLocalDate()))
        // die Schätzung trifft den durchgespielten Tag
        val est = io.github.freshglitch4j.abfahrt.core.Budget.estimate(store.settings, at(12, 0).toLocalDate())
        assertTrue("Schätzung $est, tatsächlich ${vao.boardRequests}", Math.abs(est - vao.boardRequests) <= 6)
        // Verlauf: am Folgetag als gestriger Verbrauch sichtbar
        assertEquals(listOf(at(12, 0).toLocalDate() to vao.boardRequests), store.history(at(12, 0, day = 17).toLocalDate()))
    }

    @Test fun morgensStehtDerGewaehlteEinstiegVorn() {
        now = at(6, 3)
        var d = step().display!!
        assertEquals(TripKind.HIN, d.window.kind)
        assertEquals("hin-moosegg", d.primary!!.conn.id)
        val gratten = d.rows.single { it.conn.id == "hin-gratten" }
        assertEquals(Status.ON_TIME, gratten.status)
        assertEquals(at(6, 42), gratten.expected)
        assertEquals("Bus 4055", gratten.lineLabel)
        // „Wechseln“ stellt die andere Fahrt nach vorn – und merkt es sich
        d = step(Controller.ACTION_SWITCH).display!!
        assertEquals("hin-gratten", d.primary!!.conn.id)
        assertEquals("hin-gratten", store.settings.pinned[TripKind.HIN])
    }

    @Test fun heuteAusblendenUndWiederEinplanen() {
        now = at(16, 20)
        assertNotNull(step().display)
        assertNull(step(Controller.ACTION_DISMISS).display)
        assertEquals(1, store.dismissedOn(now.toLocalDate()))
        assertFalse(Controller.somethingActive(ctx))
        Scheduler.scheduleNextStart(ctx)
        assertEquals(at(6, 3, day = 17), nextAlarm())
        // am nächsten Tag wieder ganz normal
        now = at(16, 20, day = 17)
        vao.date = "2026-11-17"
        assertNotNull(step().display)
    }

    // ---------------------------------------------------------------- Störungen

    @Test fun ohneSchluesselKeineAbfrageAberFahrplanAnzeige() {
        store.settings = Settings(accessId = "")
        now = at(16, 30)
        val d = step().display!!
        assertEquals(0, vao.requests.size)
        assertTrue(d.rows.all { it.status == Status.NO_KEY && it.expected == null })
        assertEquals("Bus 760  16:46  nur Fahrplan", Notifier.build(ctx, d).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun netzausfallWirdAngezeigtUndAlteWerteNichtAlsAktuell() {
        now = at(16, 38)
        assertEquals(Status.DELAYED, step().display!!.primary!!.status)
        vao.offline = true
        // kurze Lücke: Daten gelten noch
        now = at(16, 40, 1)
        assertEquals(Status.DELAYED, step().display!!.primary!!.status)
        // anhaltender Ausfall: veraltet, ohne erwartete Zeit
        var d = step().display!!
        while (now.isBefore(at(16, 45))) { now = now.plusSeconds(30); d = step().display!! }
        val bus = d.rows.single { it.conn.id == "heim-bus" }
        assertEquals(Status.STALE, bus.status)
        assertNull(bus.expected)
        assertEquals("zuletzt +5 Min (16:38)", bus.note)
        assertTrue(Notifier.build(ctx, d).extras.getString(Notification.EXTRA_TITLE)!!.contains("Echtzeit veraltet"))
        // Netz wieder da → sofort wieder aktuelle Werte
        vao.offline = false
        now = now.plusSeconds(61)
        assertEquals(Status.DELAYED, step().display!!.rows.single { it.conn.id == "heim-bus" }.status)
    }

    @Test fun abgelehnterSchluesselWirdNichtDauerndWiederholt() {
        store.settings = Settings(accessId = "falsch")
        now = at(16, 16)
        var s = step()
        while (s.display != null && now.isBefore(at(17, 10))) { now = s.nextWake!!; s = step() }
        // je Versuch beide Schnittstellenversionen, danach erst wieder nach 15 Minuten
        assertTrue("Abfragen: ${vao.boardRequests}", vao.boardRequests <= 16)
    }

    @Test fun erschoepftesKontingentStopptAbfragen() {
        now = at(16, 16)
        repeat(100) { store.countRequest(now.toLocalDate()) }
        val d = step().display!!
        assertEquals(0, vao.boardRequests)
        assertTrue(d.rows.all { it.status == Status.QUOTA })
    }

    @Test fun serverMeldetKontingentErschoepft() {
        vao.errorStatus = 400; vao.errorCode = "API_QUOTA"
        now = at(16, 16)
        step()
        assertEquals(0, store.budgetLeft(now.toLocalDate()))
        val before = vao.boardRequests
        now = at(16, 40); step()
        assertEquals(before, vao.boardRequests)
    }

    @Test fun ohneRichtungsfilterDesServersPrueftDieAppSelbst() {
        vao.directionFilterSupported = false
        now = at(16, 50)
        val d = step().display!!
        val rex = d.rows.single { it.conn.id == "heim-rex" }
        assertEquals(Status.ON_TIME, rex.status)
        assertEquals(false, store.directionFilterWorks)
        // ab jetzt nur noch eine Abfrage je Haltestelle
        val before = vao.boardRequests
        now = at(16, 53); step()
        assertTrue(vao.boardRequests - before <= 2)
    }

    @Test fun ausfallWirdGezeigt() {
        vao.boards["at:47:64459"] = listOf(FakeDep("16:46", null, cancelled = true))
        now = at(16, 30)
        val d = step().display!!
        val bus = d.rows.single { it.conn.id == "heim-bus" }
        assertEquals(Status.CANCELLED, bus.status)
        assertEquals("fällt aus", bus.statusText)
    }

    // ---------------------------------------------------------------- Dienst, Neustart, Widget

    @Test fun dienstPostetDieLaufendeBenachrichtigungUndStelltDenNaechstenWecker() {
        now = at(16, 30)
        val controller = Robolectric.buildService(LiveService::class.java, Intent(ctx, LiveService::class.java).setAction(Controller.ACTION_TICK))
        val service = controller.create().get()
        controller.startCommand(0, 1)
        waitForWorker()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).getNotification(Notifier.ID_LIVE)
        assertNotNull(n)
        assertEquals("Bus 760  16:46 → 16:51  +5 Min", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(Notifier.ID_LIVE, shadowOf(service).lastForegroundNotificationId)
        assertEquals(at(16, 31, 1), nextAlarm())
        controller.destroy()
    }

    @Test fun dienstBeendetSichNachDemFenster() {
        now = at(18, 0)
        val controller = Robolectric.buildService(LiveService::class.java, Intent(ctx, LiveService::class.java).setAction(Controller.ACTION_TICK))
        val service = controller.create().get()
        controller.startCommand(0, 1)
        waitForWorker()
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertEquals(at(6, 3, day = 17), nextAlarm())
        controller.destroy()
    }

    @Test fun nachGeraeteneustartImFensterLaeuftDieAnzeigeWieder() {
        now = at(16, 30)
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(LiveService::class.java.name, shadowOf(ctx as Application).nextStartedService.component!!.className)
        // und außerhalb eines Fensters wird nur der Wecker gestellt
        now = at(20, 0)
        Live.reset(); Live.http = vao; Live.clock = { now }
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(ctx as Application).nextStartedService)
        assertEquals(at(6, 3, day = 17), nextAlarm())
    }

    @Test fun demoZeigtBeispielwerteUndEndetVonSelbst() {
        now = at(10, 0, 30)
        val d = step(Controller.ACTION_DEMO).display!!
        assertTrue(d.demo)
        assertEquals(0, vao.requests.size)
        assertTrue(Notifier.build(ctx, d).extras.getString(Notification.EXTRA_SUB_TEXT)!!.startsWith("DEMO"))
        now = at(10, 6)
        assertNull(step().display)
    }

    private fun waitForWorker() {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            val t = Thread.getAllStackTraces().keys.firstOrNull { it.name == "abfahrt-live" } ?: break
            if (t.state == Thread.State.WAITING || t.state == Thread.State.TIMED_WAITING || t.state == Thread.State.RUNNABLE) {
                // Looper im Leerlauf, sobald der Thread wartet
                if (t.state != Thread.State.RUNNABLE) { Thread.sleep(150); break }
            }
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
