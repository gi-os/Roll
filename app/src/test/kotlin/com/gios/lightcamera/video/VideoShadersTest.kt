package com.gios.lightcamera.video

import com.gios.lightcamera.filter.Filters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every video shader, compiled by the reference GLSL compiler before it can reach a phone.
 *
 * **This is the check the AGSL has never had.** A photo filter that fails to compile shows up as
 * an unfiltered viewfinder and one line of logcat, and CI never sees it. The video looks are
 * GLSL ES, which Khronos's `glslangValidator` checks exactly — strictly, too: it refuses an `int`
 * where a `float` belongs, which is the mistake a port from AGSL is most likely to make. CI
 * installs it (`glslang-tools`) and sets `REQUIRE_GLSLANG`, so there the validator missing is a
 * failure rather than a skip. On a laptop without it the structural checks still run.
 *
 * Every shader is also written to `build/video-shaders/`, so a failing one can be read as the GPU
 * would see it rather than reconstructed from Kotlin string templates.
 */
class VideoShadersTest {

    private val out = File("build/video-shaders").apply { mkdirs() }

    /** Every fragment shader the processor can be asked to compile, by a name for the report. */
    private fun everyShader(): Map<String, String> = buildMap {
        VideoLooks.all.filterNot { it.plain }.forEach { look ->
            put(look.id, look.glsl)
            look.prePass?.let { put("${look.id}.pre", it) }
        }
        // Preset with a grade is resolved at runtime and is not on the dial as such.
        val graded = VideoLooks.forGrade(VideoLooks.plain, com.gios.lightcamera.filter.Grade().with(com.gios.lightcamera.filter.Adjust.Warmth, 2))
        put("preset-graded", graded.glsl)
        // And every photo shader, ported, whether or not it is on the video dial today — so that
        // putting one there later cannot be the change that discovers it does not port.
        (Filters.all + Filters.preset).forEach { f ->
            f.source?.let { src -> GlslPort.port(src)?.let { put("photo-${f.id}${if (f.adjustable) "-graded" else ""}", it) } }
        }
        Filters.moshModes.forEach { m ->
            val f = Filters.forMosh(Filters.datamosh, m)
            f.source?.let { src -> GlslPort.port(src)?.let { put("photo-mosh-${m.id}", it) } }
        }
    }

    @Test
    fun `every shader is GLSL ES 3 with an entry point and nothing left over from AGSL`() {
        val all = everyShader()
        assertTrue("expected the whole catalogue, got ${all.size}", all.size > 40)
        all.forEach { (name, glsl) ->
            assertTrue("$name: no version line", glsl.startsWith("#version 300 es"))
            assertTrue("$name: no main()", Regex("""void\s+main\s*\(\s*\)""").containsMatchIn(glsl))
            assertFalse("$name: still calls src.eval", glsl.contains("src.eval("))
            assertFalse("$name: still declares a shader uniform", Regex("""uniform\s+shader\s""").containsMatchIn(glsl))
            assertEquals("$name: unbalanced braces", glsl.count { it == '{' }, glsl.count { it == '}' })
            assertEquals("$name: unbalanced parentheses", glsl.count { it == '(' }, glsl.count { it == ')' })
            File(out, "$name.frag").writeText(glsl)
        }
    }

    @Test
    fun `every photo filter ports except the ones that cannot`() {
        (Filters.all + Filters.preset).forEach { f ->
            val src = f.source ?: return@forEach
            assertNotNull("${f.id} did not port", GlslPort.port(src))
        }
    }

    @Test
    fun `the port refuses what it cannot carry rather than guessing`() {
        // A second shader input has no texture on the GL side.
        val two = "uniform shader src;\nuniform shader other;\nhalf4 main(float2 xy) { return src.eval(xy); }"
        assertEquals(null, GlslPort.port(two))
        // No AGSL entry point, nothing to call.
        assertEquals(null, GlslPort.port("uniform shader src;\nfloat f(float x) { return x; }"))
    }

    @Test
    fun `the reference compiler accepts every shader`() {
        val validator = findValidator()
        if (System.getenv("REQUIRE_GLSLANG") == "1") {
            assertNotNull("REQUIRE_GLSLANG is set but glslangValidator is not on PATH", validator)
        }
        assumeTrue("glslangValidator not installed; structural checks only", validator != null)
        val failures = everyShader().mapNotNull { (name, glsl) ->
            val file = File(out, "$name.frag").apply { writeText(glsl) }
            val proc = ProcessBuilder(validator!!, file.absolutePath).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(60, TimeUnit.SECONDS)
            if (proc.exitValue() != 0) "$name:\n$text" else null
        }
        assertTrue("shaders the GPU would refuse:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * The processor's own five shaders — the straight-through draw, the copy out, and their
     * GLSL ES 1.00 fallback — read out of `VideoFx.kt` itself.
     *
     * They are constants in an Android class that this test cannot load, so they are taken from
     * the source text. Brittle on purpose: renaming one fails here, and the alternative was these
     * five being the only shaders in the app nothing ever compiled before a phone did.
     */
    @Test
    fun `the processor's own shaders compile`() {
        val source = File("src/main/kotlin/com/gios/lightcamera/video/VideoFx.kt").readText()
        val names = listOf("VERTEX" to "vert", "OES_FRAGMENT" to "frag", "COPY_FRAGMENT" to "frag",
            "VERTEX_100" to "vert", "OES_FRAGMENT_100" to "frag")
        val shaders = names.associate { (name, stage) ->
            val m = Regex("const val " + name + " = \"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).find(source)
            assertNotNull("$name not found in VideoFx.kt", m)
            var text = m!!.groupValues[1]
            if (name.endsWith("_100")) text = "#version 100\n" + text.trimStart('\n')
            name to (stage to text)
        }
        shaders.forEach { (name, pair) ->
            assertFalse("$name: mediump texture coordinates are fp16 on Adreno", pair.second.contains("precision mediump float;\nuniform"))
        }
        val validator = findValidator()
        if (System.getenv("REQUIRE_GLSLANG") == "1") assertNotNull(validator)
        assumeTrue(validator != null)
        val failures = shaders.mapNotNull { (name, pair) ->
            val file = File(out, "processor-$name.${pair.first}").apply { writeText(pair.second) }
            val proc = ProcessBuilder(validator!!, file.absolutePath).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(60, TimeUnit.SECONDS)
            if (proc.exitValue() != 0) "$name:\n$text" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun findValidator(): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "glslangValidator") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
}
