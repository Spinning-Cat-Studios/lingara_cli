// 16.10.26ae D4's two git questions: is a file tracked (a warning), and does
// git ignore a path that would hold secrets (a refusal). Outside a work tree,
// or with no `git` on `PATH`, there is no index to commit a file into, so
// both answers are the permissive ones and the checks are skipped.
package com.getlingara.cli.kotlin.project

import com.getlingara.cli.kotlin.supervise.Supervise
import java.io.IOException
import java.nio.file.Path

object Git {
    /** `git -C <dir> <args>`, quietly; `null` when git could not run. */
    private fun git(dir: Path, vararg args: String): Boolean? = try {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(Supervise.NULL_INPUT)
            .start()
        process.waitFor() == 0
    } catch (_: IOException) {
        null
    }

    private fun dirOf(path: Path): Path = path.parent ?: Path.of(".")

    /** Whether [dir] is inside a git work tree, with git available. */
    private fun inWorkTree(dir: Path): Boolean = git(dir, "rev-parse", "--is-inside-work-tree") == true

    /** Whether git tracks [path]; false outside a work tree. */
    fun tracked(path: Path): Boolean {
        val dir = dirOf(path)
        return inWorkTree(dir) && git(dir, "ls-files", "--error-unmatch", "--", path.fileName.toString()) == true
    }

    /** Whether git ignores [path]; true outside a work tree, where nothing can be committed. */
    fun ignored(path: Path): Boolean {
        val dir = dirOf(path)
        return !inWorkTree(dir) || git(dir, "check-ignore", "-q", "--", path.fileName.toString()) == true
    }
}
