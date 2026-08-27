package com.gios.lightcamera

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A guard for the one bug class here that passes every other test and still crashes on launch.**
 *
 * `viewModelScope` runs on `Dispatchers.Main.immediate` and a `StateFlow` hands over its current
 * value the moment it is collected. So a collector started in `init` runs *during construction*,
 * synchronously — before any property declared below `init` has been initialised. Reading one of
 * those gives back null, and Kotlin does not check: the null goes straight into a call and the app
 * dies on the splash screen.
 *
 * v2.64 shipped exactly that. `preRollRing` was declared next to the shutter code that used it,
 * four hundred lines below `init`, and the pre-roll collector in `init` called `clear()` on a field
 * that was still null. Every unit test passed, CI was green, and Roll crashed the moment it opened.
 *
 * This reads the source rather than running the class, because running it needs a device — and a
 * check that needs a device is a check that would not have caught this either.
 */
class InitOrderTest {

    @Test
    fun `nothing init reaches is declared below init`() {
        val file = sourceFile("ui/CameraViewModel.kt") ?: return
        val lines = file.readLines()

        val initLine = lines.indexOfFirst { it.trim() == "init {" }
        if (initLine < 0) return

        val propertyPattern =
            Regex("""^ {4}(?:private |internal )?(?:val|var) ([A-Za-z_][A-Za-z0-9_]*)""")
        val functionPattern =
            Regex("""^ {4}(?:private |internal )?(?:suspend )?fun ([A-Za-z_][A-Za-z0-9_]*)""")

        val late = lines.withIndex()
            .mapNotNull { (index, line) ->
                propertyPattern.find(line)?.groupValues?.get(1)?.let { it to index }
            }
            .filter { it.second > initLine }
            .toMap()
        if (late.isEmpty()) return

        val initBody = blockAt(lines, initLine)
        val called = Regex("""\b([a-z][A-Za-z0-9_]*)\(""")
            .findAll(initBody)
            .map { it.groupValues[1] }
            .toSet()

        val functions = lines.withIndex().mapNotNull { (index, line) ->
            functionPattern.find(line)?.groupValues?.get(1)?.let { it to index }
        }

        val offenders = buildList {
            functions.filter { it.first in called }.forEach { (name, start) ->
                val body = blockAt(lines, start)
                late.forEach { (property, declaredAt) ->
                    if (Regex("""\b$property\b""").containsMatchIn(body)) {
                        add("$name() reads $property, declared at line ${declaredAt + 1}")
                    }
                }
            }
        }

        assertTrue(
            "These are read during construction but declared below init, so they are still null " +
                "when a Main.immediate collector touches them:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** The text of the brace-balanced block starting at [start]. */
    private fun blockAt(lines: List<String>, start: Int): String {
        var depth = 0
        for (index in start until lines.size) {
            depth += lines[index].count { it == '{' } - lines[index].count { it == '}' }
            if (depth == 0 && index > start) {
                return lines.subList(start, index + 1).joinToString("\n")
            }
        }
        return lines.subList(start, lines.size).joinToString("\n")
    }

    /**
     * The source file, or null when it cannot be found.
     *
     * Null rather than a failure on purpose: the working directory of a unit test is not something
     * this test should be able to break the build over. A guard that goes off when the build layout
     * moves is a guard people delete.
     */
    private fun sourceFile(relative: String): File? {
        val roots = listOf(
            "src/main/kotlin/com/gios/lightcamera",
            "app/src/main/kotlin/com/gios/lightcamera",
        )
        return roots.map { File(it, relative) }.firstOrNull { it.isFile }
    }
}
