// Ctrl+C and SIGTERM (ADR 16.10.26af D9): the JVM's own handlers run the
// shutdown hooks and exit 130 or 143 from wherever the run was, so the kit
// replaces both. A command that waits (the login listener, the device poll,
// `apps dev`) opens a [Signals.cooperative] section and polls [stopped]; it
// cleans up and **returns** `interrupted` (130). Outside such a section
// nothing needs cleaning, and the handler ends the process with 130 at once.
package com.getlingara.cli.kotlin.core

import sun.misc.Signal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

object Signals {
    private val stop = AtomicBoolean(false)
    private val sections = AtomicInteger(0)
    private val installed = AtomicBoolean(false)

    /** Replaces the JVM's SIGINT and SIGTERM handlers, once a process. */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        for (name in listOf("INT", "TERM")) {
            runCatching { Signal.handle(Signal(name)) { arrived() } }
        }
    }

    private fun arrived() {
        stop.set(true)
        if (sections.get() == 0) exitProcess(Exit.INTERRUPTED.code)
    }

    /** Forgets an arrived signal: the unit tests raise one and then carry on in the same JVM. */
    fun reset() = stop.set(false)

    /** A signal arrived since the run started. */
    fun stopped(): Boolean = stop.get()

    /** Runs [body] with signals left to it: it polls [stopped] and returns `interrupted` itself. */
    fun <T> cooperative(body: () -> T): T {
        sections.incrementAndGet()
        try {
            return body()
        } finally {
            sections.decrementAndGet()
        }
    }

    /** Throws `interrupted` once a signal has arrived. */
    fun check() {
        if (stopped()) throw Fail.interrupted()
    }

    /** Sleeps [millis], a tick at a time, throwing `interrupted` when a signal arrives. */
    fun sleep(millis: Long) {
        val until = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < until) {
            check()
            Thread.sleep(minOf(100L, (until - System.nanoTime()) / 1_000_000 + 1))
        }
        check()
    }
}
