package app.birch.debian

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.Looper

/** Owns the root session independently of the terminal view, but never restarts it. */
internal class TerminalService : Service(), Console.Display {
    inner class Connection : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    private val main = Handler(Looper.getMainLooper())
    private var console: Console? = null
    private var display: Console.Display? = null
    private var pendingOutput: String? = null

    override fun onBind(intent: Intent) = Connection()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    fun attach(target: Console.Display, cols: Int, rows: Int): Console {
        display = target
        var session = console
        if (session == null) {
            session = Console(RuntimeHost(this).open(RuntimeHost.Operation.SHELL), this)
            console = session
            session.start()
        }
        session.resize(cols, rows)
        val pending = pendingOutput
        if (pending != null) {
            val current = session
            main.post { if (display === target && console === current) target.output(current, pending) }
        }
        return session
    }

    fun detach() { display = null }

    fun consumed() {
        pendingOutput = null
        console?.consumed()
    }

    override fun output(source: Console, base64: String) {
        main.post {
            if (source !== console) return@post
            pendingOutput = base64
            display?.output(source, base64)
        }
    }

    override fun ended(source: Console, reason: String?) {
        main.post {
            if (source !== console) return@post
            console = null
            pendingOutput = null
            val target = display
            display = null
            target?.ended(source, reason)
            stopSelf()
        }
    }

    fun stopSession() {
        val previous = console
        console = null
        display = null
        pendingOutput = null
        previous?.close()
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }
}
