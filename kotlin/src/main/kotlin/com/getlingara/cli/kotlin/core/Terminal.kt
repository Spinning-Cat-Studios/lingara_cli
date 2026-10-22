// The terminal the delete ritual talks to (16.10.26ad D5 step 1):
// interactive means stdin **and** stderr are terminals, and prompts go to
// stderr, so `lingara apps delete x > out.json` still prompts.
//
// The test is isatty(3) on fds 0 and 2 through JNA (ADR 16.10.26af D2),
// never `System.console()`: on JDK 17 that is null whenever stdout is a
// pipe, which every conformance `tty: true` case makes it.
package com.getlingara.cli.kotlin.core

import com.sun.jna.Library
import com.sun.jna.Native
import java.io.BufferedReader
import java.io.InputStreamReader

interface Terminal {
    fun interactive(): Boolean

    /** One line from stdin, `null` at end of input. */
    fun readLine(): String?
}

/** libc's isatty, the one native call the kit makes itself. */
interface LibC : Library {
    fun isatty(fd: Int): Int
}

object Tty {
    /** Loaded on first use, so a run that never prompts never extracts JNA's native stub. */
    private val libc: LibC? by lazy { runCatching { Native.load("c", LibC::class.java) }.getOrNull() }

    /** Whether file descriptor [fd] is a terminal; `false` when libc cannot be reached. */
    fun isatty(fd: Int, lib: LibC? = libc): Boolean = (lib?.isatty(fd) ?: 0) == 1

    /** B3.1: stdin and stderr, fds 0 and 2. */
    fun interactive(lib: LibC? = libc): Boolean = isatty(0, lib) && isatty(2, lib)
}

class Stdio : Terminal {
    private val reader by lazy { BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)) }

    override fun interactive(): Boolean = Tty.interactive()

    override fun readLine(): String? = runCatching { reader.readLine() }.getOrNull()
}
