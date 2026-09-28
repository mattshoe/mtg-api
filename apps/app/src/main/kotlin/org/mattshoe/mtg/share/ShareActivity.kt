package org.mattshoe.mtg.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Receive a shared decklist and put it in the collection.
 *
 * The flow is the same one the web app enforces and for the same reason:
 * list, then whose, then a real dry run against the server, then apply.
 * Nothing writes without having been previewed, and the owner is never
 * preselected — a list landing in the wrong collection is the mistake this
 * is shaped to prevent.
 */
class ShareActivity : Activity() {

    private val work = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout

    private var list: String = ""
    private var sources: List<SharedList.Source> = emptyList()
    private var problem: String? = null
    private var owner: String? = null
    private var preview: Api.Applied? = null
    private var result: Api.Applied? = null
    private var busy: String? = null
    private var error: String? = null

    private val prefs by lazy { getSharedPreferences("mtg", Context.MODE_PRIVATE) }
    private var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        take(intent)
    }

    /** singleTask, so a second share arrives here rather than in a new task. */
    override fun onNewIntent(next: Intent?) {
        super.onNewIntent(next)
        setIntent(next)
        take(next)
    }

    private fun take(from: Intent?) {
        owner = null
        preview = null
        result = null
        error = null

        val isShare = from?.action == Intent.ACTION_SEND
            || from?.action == Intent.ACTION_SEND_MULTIPLE
            || from?.action == Intent.ACTION_VIEW

        if (isShare) {
            val read = SharedList.from(this, from)
            list = read.list
            sources = read.sources
            problem = read.problem
        } else {
            list = ""
            sources = emptyList()
            problem = null
        }
        render()
    }

    // ------------------------------------------------------------ render

    private fun render() {
        root.removeAllViews()

        root.addView(heading("MTG Collection"))

        when {
            busy != null -> root.addView(body(busy!!))
            // Why a share came to nothing is worth saying whether or not
            // anyone is unlocked. Hiding it behind the password meant a
            // failure looked identical to a fresh install.
            list.isBlank() -> { renderNothing(); if (token == null) renderUnlock() }
            result != null -> renderDone()
            preview != null -> renderPreview()
            // The list first, always. Seeing what arrived before being
            // asked for anything is the point, and it is the only way to
            // tell a share that worked from one that did not.
            else -> { renderList(); if (token == null) renderUnlock() }
        }

        error?.let {
            root.addView(spacer(16))
            root.addView(body(it).apply { setTextColor(BAD) })
        }
    }

    private fun renderUnlock() {
        root.addView(spacer(20))
        root.addView(label("Unlock to continue"))
        root.addView(spacer(6))
        root.addView(small("The password, once. It is exchanged for a token that does not expire, and the password itself is never stored."))
        root.addView(spacer(14))

        val field = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(TEXT)
            setHintTextColor(MUTED)
        }
        root.addView(field)
        root.addView(spacer(12))
        root.addView(primary("Unlock") {
            val pw = field.text.toString()
            if (pw.isEmpty()) return@primary
            run("Checking the password…", {
                token = Api.unlock(pw)
            }, { render() })
        })
    }

    private fun renderNothing() {
        if (problem != null) {
            root.addView(body("The last share came through empty: $problem"))
        } else {
            root.addView(body("Share a decklist or a collection export here and it lands in the collection."))
        }
        sources.forEach { root.addView(small("${it.name} — ${it.type}, ${it.bytes} bytes")) }
        if (token != null) {
            root.addView(spacer(20))
            root.addView(ghost("Forget the token") {
                token = null
                render()
            })
        }
    }

    private fun renderList() {
        val n = SharedList.countCards(list)
        val kind = if (SharedList.looksLikeCsv(list)) "CSV" else "decklist"
        root.addView(body("$n card${if (n == 1) "" else "s"} · $kind"))
        sources.forEach { root.addView(small("${it.name} — ${it.type}, ${it.bytes} bytes")) }

        root.addView(spacer(14))
        root.addView(mono(list.lineSequence().take(12).joinToString("\n")
            + if (list.lines().size > 12) "\n…" else ""))

        if (token == null) return

        root.addView(spacer(22))
        root.addView(label("Whose collection?"))
        root.addView(spacer(8))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("matt", "kayla").forEach { who ->
            row.addView(choice(who.replaceFirstChar(Char::uppercase), owner == who) {
                owner = who
                render()
            })
        }
        root.addView(row)

        root.addView(spacer(20))
        val who = owner
        if (who == null) {
            root.addView(small("Pick one to continue. Nothing is preselected on purpose."))
        } else {
            root.addView(primary("Preview · $who") {
                run("Checking against Scryfall…", {
                    preview = Api.addCards(token!!, who, list, dryRun = true)
                }, { render() })
            })
        }
    }

    private fun renderPreview() {
        val p = preview!!
        root.addView(label("Preview — nothing written yet"))
        root.addView(spacer(6))
        root.addView(body("${p.resolved} resolved, ${p.failed} failed, ${p.changes.size} printings"))
        root.addView(spacer(12))
        root.addView(mono(p.changes.take(20).joinToString("\n") {
            "${it.name} (${it.set} ${it.collectorNumber}) ${it.before} → ${it.after}"
        } + if (p.changes.size > 20) "\n… and ${p.changes.size - 20} more" else ""))

        if (p.errors.isNotEmpty()) {
            root.addView(spacer(14))
            root.addView(label("${p.errors.size} problem${if (p.errors.size == 1) "" else "s"}"))
            root.addView(mono(p.errors.take(10).joinToString("\n")).apply { setTextColor(BAD) })
        }

        root.addView(spacer(22))
        if (p.changes.isEmpty()) {
            root.addView(small("Nothing resolved, so there is nothing to apply."))
        } else {
            root.addView(primary("Add ${p.changes.size} to ${owner}") {
                run("Writing…", {
                    result = Api.addCards(token!!, owner!!, list, dryRun = false)
                }, { render() })
            })
        }
        root.addView(spacer(10))
        root.addView(ghost("Back") {
            preview = null
            render()
        })
    }

    private fun renderDone() {
        val r = result!!
        root.addView(label(if (r.applied) "Added" else "Nothing applied"))
        root.addView(spacer(6))
        root.addView(body("${r.resolved} resolved, ${r.failed} failed, ${r.changes.size} printings into ${owner}'s collection"))
        if (r.errors.isNotEmpty()) {
            root.addView(spacer(12))
            root.addView(mono(r.errors.take(10).joinToString("\n")).apply { setTextColor(BAD) })
        }
        root.addView(spacer(22))
        root.addView(primary("Done") { finish() })
    }

    // ------------------------------------------------------------- plumbing

    /** Off the main thread, back onto it, with the failure surfaced either way. */
    private fun run(what: String, job: () -> Unit, then: () -> Unit) {
        busy = what
        error = null
        render()
        work.execute {
            var failure: String? = null
            try {
                job()
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            runOnUiThread {
                busy = null
                error = failure
                if (failure == null) then() else render()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        work.shutdownNow()
    }

    // ------------------------------------------------------------- widgets

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun heading(s: String) = TextView(this).apply {
        text = s
        setTextColor(TEXT)
        textSize = 24f
        setPadding(0, 0, 0, dp(14))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        setTextColor(TEXT)
        textSize = 17f
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        setTextColor(TEXT)
        textSize = 15f
    }

    private fun small(s: String) = TextView(this).apply {
        text = s
        setTextColor(MUTED)
        textSize = 13f
        setPadding(0, dp(2), 0, 0)
    }

    private fun mono(s: String) = TextView(this).apply {
        text = s
        setTextColor(MUTED)
        textSize = 13f
        typeface = android.graphics.Typeface.MONOSPACE
        setBackgroundColor(PANEL)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h))
    }

    private fun primary(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.BLACK)
        setBackgroundColor(ACCENT)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(54))
        setOnClickListener { onClick() }
    }

    private fun ghost(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setTextColor(MUTED)
        setBackgroundColor(PANEL)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(46))
        setOnClickListener { onClick() }
    }

    private fun choice(s: String, on: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (on) Color.BLACK else TEXT)
        setBackgroundColor(if (on) ACCENT else PANEL)
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(8)
        }
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        const val BG = 0xFF0E1116.toInt()
        const val PANEL = 0xFF171C24.toInt()
        const val TEXT = 0xFFE8EDF4.toInt()
        const val MUTED = 0xFF94A1B2.toInt()
        const val ACCENT = 0xFFD9A441.toInt()
        const val BAD = 0xFFFF8A80.toInt()
    }
}
