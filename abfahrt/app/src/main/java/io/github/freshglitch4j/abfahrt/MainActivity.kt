package io.github.freshglitch4j.abfahrt

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as Sys
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.freshglitch4j.abfahrt.core.Budget
import io.github.freshglitch4j.abfahrt.core.Connection
import io.github.freshglitch4j.abfahrt.core.Engine
import io.github.freshglitch4j.abfahrt.core.Fmt
import io.github.freshglitch4j.abfahrt.core.Match
import io.github.freshglitch4j.abfahrt.core.Schedule
import io.github.freshglitch4j.abfahrt.core.Settings
import io.github.freshglitch4j.abfahrt.core.TripKind
import io.github.freshglitch4j.abfahrt.core.VaoClient
import io.github.freshglitch4j.abfahrt.core.VaoResult
import io.github.freshglitch4j.abfahrt.core.WOERGL_HBF
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.concurrent.thread

/** Einstellungen, manuelle Eingriffe und Fehlerdiagnose. Im Alltag muss die App nicht geöffnet werden. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var statusBox: LinearLayout
    private val onChange: () -> Unit = { handler.post { renderStatus() } }
    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        content = Ui.column(this).apply {
            val p = Ui.dp(this@MainActivity, 18)
            setPadding(p, p, p, Ui.dp(this@MainActivity, 48))
        }
        // Ohne Benachrichtigungen gibt es keine Anzeige – beim ersten Start gleich fragen.
        if (savedInstanceState == null && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            fitsSystemWindows = true
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onStart() {
        super.onStart()
        Live.addListener(onChange)
        render()
        handler.post(ticker)
        Scheduler.ensure(this)
    }

    override fun onStop() {
        Live.removeListener(onChange)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private fun save(s: Settings) {
        store.settings = s
        Scheduler.ensure(this)
        render()
    }

    // ------------------------------------------------------------------ Aufbau

    private fun render() {
        val s = store.settings
        content.removeAllViews()

        val head = Ui.rowLayout(this)
        head.addView(Ui.text(this, "Abfahrt", 26f, Ui.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Switch(this).apply {
            text = "Anzeige aktiv  "
            isChecked = s.active
            setOnCheckedChangeListener { _, on -> save(store.settings.copy(active = on)) }
        })
        content.addView(head)

        statusBox = Ui.column(this)
        content.addView(statusBox)
        renderStatus()

        renderPinned(s)
        renderConnections(s)
        renderSource(s)
        renderOptions(s)
        renderSetup(s)
        renderLog()
    }

    private fun renderStatus() {
        if (!::statusBox.isInitialized) return
        statusBox.removeAllViews()
        val now = Live.clock()
        val d = Live.display
        statusBox.addView(Ui.section(this, if (d == null) "Status" else if (d.demo) "Demo – erfundene Werte" else d.window.kind.title))
        if (d != null) {
            statusBox.addView(Ui.departures(this, d, now))
            statusBox.addView(Ui.spacer(this, 8))
            statusBox.addView(Ui.text(this, Fmt.asOf(d) + " · Daten: Verkehrsauskunft Österreich (VAO)", 12f, Ui.FAINT))
        } else {
            val s = store.settings
            val next = Schedule.upcoming(now, s).firstOrNull { it.start.isAfter(now) && it.key != store.dismissedKey }
            val msg = when {
                !s.active -> "Die Anzeige ist ausgeschaltet."
                next == null -> "Keine Fahrt geplant."
                else -> "Nächste Anzeige: ${next.kind.title} ${DepartureWidget.dayLabel(next, now.toLocalDate())} ab ${Fmt.time(next.start)}\n" +
                    next.connections.joinToString("\n") { "${Fmt.time(it.time)}  ${it.label}  ${it.stopName}" }
            }
            statusBox.addView(Ui.text(this, msg, 15f, Ui.DIM))
        }
        val buttons = Ui.rowLayout(this)
        if (d != null) {
            buttons.addView(Ui.button(this, "Aktualisieren") { LiveService.start(this, Controller.ACTION_REFRESH) })
            buttons.addView(Ui.button(this, "Standanzeige") { startActivity(Intent(this, StandActivity::class.java)) })
        }
        if (d?.demo == true) {
            buttons.addView(Ui.button(this, "Demo beenden") { LiveService.start(this, Controller.ACTION_DEMO_STOP) })
        } else if (d == null) {
            buttons.addView(Ui.button(this, "Demo (5 Min)") { LiveService.start(this, Controller.ACTION_DEMO) })
        }
        val dismissed = store.dismissedKey
        if (dismissed.isNotEmpty() && Schedule.upcoming(now, store.settings).any { it.key == dismissed }) {
            buttons.addView(Ui.button(this, "Wieder anzeigen") {
                store.dismissedKey = ""
                Scheduler.ensure(this)
                renderStatus()
            })
        }
        statusBox.addView(buttons)
    }

    private fun renderPinned(s: Settings) {
        for (kind in TripKind.entries) {
            val conns = s.connections.filter { it.enabled && it.kind == kind }
            if (conns.map { it.stopId to it.time }.distinct().size < 2) continue
            content.addView(Ui.section(this, "${kind.title}: was steht vorn?"))
            val current = s.pinned[kind]?.takeIf { id -> conns.any { it.id == id } }
            fun option(label: String, id: String?) {
                val selected = current == id
                content.addView(Ui.button(this, (if (selected) "●  " else "○  ") + label) {
                    val st = store.settings
                    save(st.copy(pinned = if (id == null) st.pinned - kind else st.pinned + (kind to id)))
                }.apply { if (selected) setTextColor(Notifier.GREEN) })
            }
            option("Automatisch – die jeweils nächste Abfahrt", null)
            for (c in conns) option("${c.stopName} · ${Fmt.time(c.time)} (${days(c.days)})", c.id)
        }
    }

    private fun renderConnections(s: Settings) {
        content.addView(Ui.section(this, "Verbindungen"))
        for (c in s.connections.sortedWith(compareBy({ it.kind }, { it.time }))) {
            val line = "${c.kind.title} · ${days(c.days)} · ${Fmt.time(c.time)}\n${c.label} · ${c.stopName}" +
                (if (c.towardsName.isNotEmpty()) " → ${c.towardsName}" else "") + if (c.enabled) "" else "  (aus)"
            content.addView(Ui.button(this, line) { editConnection(c) }.apply {
                textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
                if (!c.enabled) alpha = 0.5f
            })
        }
        content.addView(Ui.button(this, "+ Verbindung hinzufügen") {
            editConnection(
                Connection(
                    id = "c" + System.currentTimeMillis(), kind = TripKind.HIN, label = "Bus", stopName = "", stopId = "",
                    time = LocalTime.of(7, 0), days = MO_FR, towardsName = "Wörgl Hbf", towardsId = WOERGL_HBF,
                ),
                isNew = true,
            )
        })
    }

    private fun renderSource(s: Settings) {
        content.addView(Ui.section(this, "Echtzeitdaten"))
        content.addView(
            Ui.text(
                this,
                if (s.accessId.isBlank())
                    "Noch kein Zugangsschlüssel hinterlegt – die App zeigt bis dahin nur Fahrplanzeiten und kennzeichnet das deutlich.\n\n" +
                        "Echtzeitdaten kommen von der offiziellen Schnittstelle der Verkehrsauskunft Österreich. " +
                        "Den kostenlosen persönlichen Schlüssel („VAO Start“) gibt es auf verkehrsauskunft.at/start: " +
                        "Vertrag ausfüllen, unterschreiben und an start@verkehrsauskunft.at senden."
                else "Quelle: Verkehrsauskunft Österreich (VAO Start).",
                14f, Ui.DIM,
            )
        )
        if (s.accessId.isNotBlank()) {
            val used = store.usedToday()
            content.addView(Ui.text(this, "Heute: $used von ${s.dailyLimit} Abfragen verbraucht · noch ${maxOf(0, s.dailyLimit - used)} frei", 15f, Ui.WHITE, true).apply {
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
            })
        }
        val estimate = Budget.perWeekday(s)
        val busy = estimate.filterValues { it > 0 }
        content.addView(Ui.text(
            this,
            if (busy.isEmpty()) "Voraussichtlicher Verbrauch: keine Verbindungen aktiv."
            else "Voraussichtlich pro Tag (alle pünktlich): " + busy.entries.joinToString(" · ") { (d, n) ->
                d.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2) + " " + n
            } + ". Verspätungen kosten etwa 1 Abfrage je Minute, „Aktualisieren“ 1 je Haltestelle, „Verbindungen testen“ 1 je Verbindung.",
            13f, if ((busy.values.maxOrNull() ?: 0) > s.dailyLimit * 0.8) Notifier.AMBER else Ui.DIM,
        ))
        val history = store.history()
        if (history.isNotEmpty()) {
            content.addView(Ui.text(this, "Tatsächlich: " + history.joinToString(" · ") { (d, n) ->
                d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2) + " " + "%02d.%02d.".format(d.dayOfMonth, d.monthValue) + " " + n
            }, 13f, Ui.DIM))
        }
        val key = EditText(this).apply {
            hint = "VAO-Zugangsschlüssel (accessId)"
            setText(s.accessId)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
        }
        content.addView(key)
        val row = Ui.rowLayout(this)
        row.addView(Ui.button(this, "Schlüssel speichern") {
            store.directionFilterWorks = null
            save(store.settings.copy(accessId = key.text.toString().trim()))
        })
        row.addView(Ui.button(this, "Verbindungen testen") { runTest() })
        content.addView(row)
        content.addView(numberSetting("Abfragen pro Tag (VAO Start: 100)", s.dailyLimit) { save(store.settings.copy(dailyLimit = it.coerceIn(10, 100000))) })
    }

    private fun renderOptions(s: Settings) {
        content.addView(Ui.section(this, "Anzeige"))
        content.addView(numberSetting("Anzeige beginnt … Minuten vor der ersten Abfahrt", s.leadMinutes) { save(store.settings.copy(leadMinutes = it.coerceIn(5, 120))) })
        content.addView(Switch(this).apply {
            text = "An österreichischen Feiertagen nicht anzeigen"
            isChecked = s.skipHolidays
            setOnCheckedChangeListener { _, on -> save(store.settings.copy(skipHolidays = on)) }
        })
        content.addView(Switch(this).apply {
            text = "Standanzeige zu Beginn automatisch einschalten (Display bleibt an)"
            isChecked = s.autoStand
            setOnCheckedChangeListener { _, on -> save(store.settings.copy(autoStand = on)) }
        })
        content.addView(Switch(this).apply {
            text = "Now Bar / Always-On-Display: als Live-Benachrichtigung senden (aus: normale Karte am Sperrbildschirm)"
            isChecked = s.nowBar
            setOnCheckedChangeListener { _, on ->
                save(store.settings.copy(nowBar = on))
                // Laufende Anzeige sofort in der neuen Form neu aufbauen
                if (Live.display != null) LiveService.start(this@MainActivity, Controller.ACTION_TICK)
            }
        })
    }

    private fun renderSetup(s: Settings) {
        content.addView(Ui.section(this, "Einrichtung"))
        val nm = getSystemService(NotificationManager::class.java)
        fun item(ok: Boolean, label: String, fix: (() -> Unit)?) {
            val row = Ui.rowLayout(this)
            row.addView(
                Ui.text(this, (if (ok) "✓  " else "!  ") + label, 14f, if (ok) Ui.DIM else Notifier.AMBER),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (!ok && fix != null) row.addView(Ui.button(this, "Beheben") { fix() })
            row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
            content.addView(row)
        }
        item(nm.areNotificationsEnabled(), "Benachrichtigungen erlaubt") {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not()
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else open(Intent(Sys.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Sys.EXTRA_APP_PACKAGE, packageName))
        }
        item(getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName), "Akku: Nutzung nicht eingeschränkt (zuverlässiger Hintergrundbetrieb)") {
            open(Intent(Sys.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        item(getSystemService(AlarmManager::class.java).canScheduleExactAlarms(), "Exakte Wecker erlaubt (pünktlicher Beginn)") {
            open(Intent(Sys.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 36 && s.nowBar) {
            item(nm.canPostPromotedNotifications(), "Live-Benachrichtigung erlaubt (Now Bar, Statusleiste)") {
                open(Intent(Sys.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Sys.EXTRA_APP_PACKAGE, packageName))
            }
        }
        if (s.nowBar) Notifier.isPromotedNow(this)?.let { promoted ->
            item(promoted, if (promoted) "Wird gerade als Live-Benachrichtigung angezeigt" else "Läuft gerade als normale Benachrichtigung (nicht als Live-Benachrichtigung)", null)
        }
        if (s.autoStand) {
            item(nm.canUseFullScreenIntent(), "Vollbild über dem Sperrbildschirm erlaubt (Standanzeige)") {
                open(Intent(Sys.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        item(s.accessId.isNotBlank(), "VAO-Zugangsschlüssel hinterlegt", null)
        content.addView(Ui.spacer(this, 6))
        content.addView(
            Ui.text(
                this,
                "Am Galaxy einmalig einstellen:\n" +
                    "• Einstellungen → Benachrichtigungen → Sperrbildschirm-Benachrichtigungen: „Inhalt anzeigen“ und Stil „Karten“.\n" +
                    "• Einstellungen → Sperrbildschirm und AOD → Now Bar: Live-Benachrichtigungen einschalten.\n" +
                    "• Für „Handy liegt vor mir“: Always On Display einschalten (z. B. „Zum Anzeigen tippen“) oder hier die Standanzeige nutzen.\n" +
                    "• Widget: Startbildschirm lange drücken → Widgets → Abfahrt.",
                13f, Ui.FAINT,
            )
        )
    }

    private fun renderLog() {
        content.addView(Ui.section(this, "Diagnose"))
        content.addView(Ui.button(this, "Sperrbildschirm-Test (3 Min)") {
            Notifier.postLockscreenTest(this)
            toast("Drei Test-Benachrichtigungen A, B, C gesendet – jetzt Handy sperren und Display einschalten.")
        })
        val row = Ui.rowLayout(this)
        row.addView(Ui.button(this, "Protokoll") {
            val log = store.readLog().lines().takeLast(200).joinToString("\n").ifBlank { "Noch keine Einträge." }
            AlertDialog.Builder(this).setTitle("Protokoll").setMessage(log)
                .setPositiveButton("Schließen", null)
                .setNeutralButton("Teilen") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "Protokoll teilen"))
                }
                .setNegativeButton("Leeren") { _, _ -> store.clearLog() }
                .show()
        })
        content.addView(row)
        content.addView(Ui.text(this, "Version ${packageManager.getPackageInfo(packageName, 0).versionName}", 12f, Ui.FAINT))
    }

    // ------------------------------------------------------------------ Dialoge

    private fun numberSetting(label: String, value: Int, onSet: (Int) -> Unit): LinearLayout {
        val row = Ui.rowLayout(this)
        row.addView(Ui.text(this, label, 14f, Ui.DIM), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.button(this, value.toString()) {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
            }
            AlertDialog.Builder(this).setTitle(label).setView(input)
                .setPositiveButton("OK") { _, _ -> input.text.toString().toIntOrNull()?.let(onSet) }
                .setNegativeButton("Abbrechen", null).show()
        })
        return row
    }

    private fun editConnection(original: Connection, isNew: Boolean = false) {
        var kind = original.kind
        var time = original.time
        val days = original.days.toMutableSet()
        val col = Ui.column(this).apply {
            val p = Ui.dp(this@MainActivity, 20)
            setPadding(p, Ui.dp(this@MainActivity, 8), p, 0)
        }
        fun field(hint: String, value: String): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            col.addView(Ui.text(this@MainActivity, hint, 12f, Ui.DIM))
            col.addView(this)
        }

        val kindButton = Ui.button(this, "Richtung: ${kind.title}") {}
        kindButton.setOnClickListener {
            kind = if (kind == TripKind.HIN) TripKind.HEIM else TripKind.HIN
            kindButton.text = "Richtung: ${kind.title}"
        }
        col.addView(kindButton)
        val timeButton = Ui.button(this, "Planmäßige Abfahrt: ${Fmt.time(time)}") {}
        timeButton.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                time = LocalTime.of(h, m)
                timeButton.text = "Planmäßige Abfahrt: ${Fmt.time(time)}"
            }, time.hour, time.minute, true).show()
        }
        col.addView(timeButton)

        val dayRow = Ui.rowLayout(this)
        for (d in DayOfWeek.entries) {
            dayRow.addView(CheckBox(this).apply {
                text = d.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2)
                isChecked = d in days
                setOnCheckedChangeListener { _, on -> if (on) days += d else days -= d }
                setPadding(0, 0, Ui.dp(this@MainActivity, 2), 0)
            })
        }
        col.addView(android.widget.HorizontalScrollView(this).apply { addView(dayRow) })

        val label = field("Anzeigename (z. B. Bus 760)", original.label)
        val stopName = field("Haltestelle", original.stopName)
        val stopId = field("Haltestellen-ID (z. B. at:47:64459)", original.stopId)
        col.addView(Ui.button(this, "Haltestelle suchen (1 Abfrage)") { searchStop(stopName, stopId) })
        val line = field("Linienfilter (z. B. 760 oder REX; leer = alle)", original.line)
        val towardsName = field("Fährt weiter nach", original.towardsName)
        val towardsId = field("ID des Zielhalts (z. B. $WOERGL_HBF)", original.towardsId)
        col.addView(Ui.button(this, "Zielhalt suchen (1 Abfrage)") { searchStop(towardsName, towardsId) })
        val enabled = Switch(this).apply { text = "Aktiv"; isChecked = original.enabled }
        col.addView(enabled)

        val b = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Neue Verbindung" else "Verbindung bearbeiten")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Speichern") { _, _ ->
                val c = original.copy(
                    kind = kind, time = time, days = days.toSet(), label = label.text.toString().trim(),
                    stopName = stopName.text.toString().trim(), stopId = stopId.text.toString().trim(),
                    line = line.text.toString().trim(), towardsName = towardsName.text.toString().trim(),
                    towardsId = towardsId.text.toString().trim(), enabled = enabled.isChecked,
                )
                val st = store.settings
                save(st.copy(connections = st.connections.filter { it.id != c.id } + c))
            }
            .setNegativeButton("Abbrechen", null)
        if (!isNew) b.setNeutralButton("Löschen") { _, _ ->
            val st = store.settings
            save(st.copy(connections = st.connections.filter { it.id != original.id }))
        }
        b.show()
    }

    private fun client(): VaoClient {
        val today = LocalDate.now(io.github.freshglitch4j.abfahrt.core.ZONE)
        return VaoClient(Live.http, store.settings.accessId, store, onCountedRequest = { store.countRequest(today) })
    }

    private fun searchStop(nameField: EditText, idField: EditText) {
        val query = nameField.text.toString().trim()
        if (query.length < 3) return toast("Bitte zuerst einen Namen eingeben.")
        if (store.settings.accessId.isBlank()) return toast("Die Suche braucht den VAO-Schlüssel.")
        thread {
            val r = client().searchStops(query)
            handler.post {
                when (r) {
                    is VaoResult.Fail -> toast("Suche fehlgeschlagen: ${r.message}")
                    is VaoResult.Ok -> {
                        if (r.value.isEmpty()) return@post toast("Nichts gefunden.")
                        AlertDialog.Builder(this).setTitle("Haltestelle wählen")
                            .setItems(r.value.map { "${it.name}\n${it.id}" }.toTypedArray()) { _, i ->
                                nameField.setText(r.value[i].name)
                                idField.setText(r.value[i].id)
                            }.show()
                    }
                }
            }
        }
    }

    /** Fragt jede aktive Verbindung einmal ab und zeigt, was die Schnittstelle liefert. */
    private fun runTest() {
        val s = store.settings
        if (s.accessId.isBlank()) return toast("Bitte zuerst den VAO-Schlüssel speichern.")
        val conns = s.connections.filter { it.enabled }
        toast("Teste ${conns.size} Verbindungen …")
        thread {
            val out = StringBuilder()
            val today = LocalDate.now(io.github.freshglitch4j.abfahrt.core.ZONE)
            val c = client()
            for (conn in conns) {
                val date = (0L..7L).map { today.plusDays(it) }.firstOrNull { it.dayOfWeek in conn.days } ?: continue
                val planned = date.atTime(conn.time)
                out.append("${conn.label} · ${conn.stopName} · ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN)} ${Fmt.time(conn.time)}\n")
                var filtered = conn.towardsId.isNotEmpty()
                var r = c.departureBoard(conn.stopId, planned.minusMinutes(Engine.BOARD_LEAD_MIN), 30, conn.towardsId.ifEmpty { null })
                if (filtered && (r is VaoResult.Fail || (r is VaoResult.Ok && r.value.departures.isEmpty()))) {
                    out.append("   (mit Richtungsfilter kein Ergebnis – zweiter Versuch ohne)\n")
                    r = c.departureBoard(conn.stopId, planned.minusMinutes(Engine.BOARD_LEAD_MIN), 30, null)
                    filtered = false
                }
                when (r) {
                    is VaoResult.Fail -> out.append("   ✗ ${r.kind}: ${r.message}\n")
                    is VaoResult.Ok -> when (val m = Engine.match(conn, planned, r.value, filtered)) {
                        is Match.Found -> {
                            val d = m.dep
                            out.append("   ✓ ${d.name} → ${d.direction}, Plan ${Fmt.time(d.planned)}")
                            out.append(if (d.realtime != null) ", Echtzeit ${Fmt.time(d.realtime)}" else ", noch keine Echtzeit (Tage im Voraus normal)")
                            if (m.timeDeviates) out.append("\n   ! Planzeit weicht von der Einstellung ab")
                            out.append('\n')
                        }
                        is Match.NotFound -> out.append("   ✗ nicht gefunden. Dort: ${m.alternatives.joinToString(", ").ifEmpty { "keine Abfahrten" }}\n")
                    }
                }
                out.append('\n')
            }
            store.log("Test:\n$out")
            handler.post {
                AlertDialog.Builder(this).setTitle("Testergebnis").setMessage(out.toString().ifBlank { "Keine aktiven Verbindungen." })
                    .setPositiveButton("OK", null).show()
                render()
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Sys.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Scheduler.ensure(this)
        render()
    }

    companion object {
        private val MO_FR = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        fun days(d: Set<DayOfWeek>): String {
            if (d.isEmpty()) return "nie"
            val sorted = d.sortedBy { it.value }
            fun short(x: DayOfWeek) = x.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2)
            val contiguous = sorted.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
            return when {
                sorted.size == 7 -> "täglich"
                sorted.size >= 3 && contiguous -> "${short(sorted.first())}–${short(sorted.last())}"
                else -> sorted.joinToString(", ") { short(it) }
            }
        }
    }
}
