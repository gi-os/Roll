package com.gios.lightcamera

import com.gios.lightcamera.filter.Filters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What can be checked about a shader without a GPU.
 *
 * Not much, but the things that can be are exactly the things that fail silently on device:
 * AGSL that doesn't compile leaves the viewfinder unfiltered with only a log line to say so,
 * so a missing entry point or an unbalanced brace would ship unnoticed. The real compile
 * happens in [com.gios.lightcamera.filter.ShaderRuntime] at runtime.
 */
class FiltersTest {

    /**
     * Every shader in the app, which is **not** the same list as the dial's.
     *
     * `Filters.preset` deliberately sits outside [Filters.all] — it shares `none`'s id and its slot,
     * and putting it in the list would give the wheel two Presets to walk through. That also meant
     * it was the one shader no structural check here ever looked at, which is exactly the shader
     * you least want unchecked: it is the default filter, so a brace out of place in it would take
     * out the app's most-used position rather than its least.
     */
    private val shaders = (Filters.all + Filters.preset).filter { it.agsl != null }

    @Test
    fun `ids are unique`() {
        val ids = Filters.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the only filters without a shader are the two that must not have one`() {
        // None, because a plain photograph is the sensor's own JPEG written untouched, and Datamosh,
        // because it edits the compressed file rather than the pixels and so cannot be a shader at
        // all. Anything else appearing here is a filter that silently does nothing.
        val without = Filters.all.filter { it.agsl == null }
        assertEquals(listOf("none", "datamosh"), without.map { it.id })
        assertNull(Filters.none.source)
        assertTrue("datamosh must declare itself a databend", Filters.datamosh.databend)
    }

    @Test
    fun `a databend filter has no shader and no preview`() {
        Filters.all.filter { it.databend }.forEach { filter ->
            assertNull("${filter.id} is a databend and must have no shader", filter.agsl)
            assertTrue("${filter.id} must not animate a preview it does not have", !filter.animated)
            assertTrue("${filter.id} must not claim to be low-res", !filter.lowRes)
        }
    }

    @Test
    fun `every shader declares the entry point AGSL expects`() {
        shaders.forEach { filter ->
            assertTrue("${filter.id} has no main()", filter.agsl!!.contains("half4 main(float2 "))
        }
    }

    @Test
    fun `every shader gets the prelude exactly once`() {
        shaders.forEach { filter ->
            val source = filter.source!!
            assertEquals(
                "${filter.id} declares src ${source.split("uniform shader src").size - 1} times",
                1,
                source.split("uniform shader src").size - 1,
            )
            assertTrue("${filter.id} is missing the helpers", source.contains("float lum(float3"))
        }
    }

    @Test
    fun `braces and parentheses balance`() {
        shaders.forEach { filter ->
            val source = filter.source!!
            assertEquals(
                "${filter.id} has unbalanced braces",
                source.count { it == '{' },
                source.count { it == '}' },
            )
            assertEquals(
                "${filter.id} has unbalanced parens",
                source.count { it == '(' },
                source.count { it == ')' },
            )
        }
    }

    @Test
    fun `shaders only read uniforms the runtime sets`() {
        // ShaderRuntime sets size and seed and binds src, and sets the four face uniforms for a
        // filter that declares itself faces-aware. A shader declaring anything else would compile
        // and then sample garbage; one that declares a face uniform without the flag would never
        // have it written and would warp a stale rectangle for ever.
        val always = setOf("src", "size", "seed")
        val faceUniforms =
            setOf("faceCount", "face0", "face1", "face2", "warp", "wash", "faceTurn")
        // Preset's ten adjustments arrive as three vec4s, written by `ShaderRuntime.setGrade` and
        // only for a filter that declares itself adjustable. Same contract as the face uniforms:
        // declaring one of these without the flag means it is never written and the shader reads
        // whatever the last filter left in it.
        val gradeUniforms = setOf("gradeA", "gradeB", "gradeC")
        shaders.forEach { filter ->
            val declared = Regex("uniform\\s+\\w+\\s+(\\w+)\\s*;")
                .findAll(filter.source!!)
                .map { it.groupValues[1] }
                .toSet()
            val expected = when {
                filter.facesAware -> always + faceUniforms
                filter.adjustable -> always + gradeUniforms
                else -> always
            }
            assertEquals("${filter.id} declares the wrong uniforms", expected, declared)
        }
    }

    @Test
    fun `a faces-aware filter is one the shutter can keep aligned`() {
        // Faces are detected in the preview, so a faces-aware photograph has to be made from the
        // preview frame — anything else would need those rectangles mapped across a different crop,
        // which is how an eye ends up enlarged beside an ear.
        Filters.all.filter { it.facesAware }.forEach { filter ->
            assertTrue(
                "${filter.id} reads faces but never uses faceCount",
                filter.agsl!!.contains("faceCount"),
            )
        }
    }

    @Test
    fun `no shader declares a variable named after a type`() {
        // `half` is a type in AGSL, so `float2 half = ...` is a compile error — and one that only
        // shows up as an unfiltered viewfinder and a line in logcat.
        shaders.forEach { filter ->
            val code = filter.agsl!!.lines().filterNot { it.trimStart().startsWith("//") }
            code.forEach { line ->
                assertTrue(
                    "${filter.id} declares a variable called half",
                    !Regex("\\b(float2|float3|float4|float|int)\\s+half\\b").containsMatchIn(line),
                )
            }
        }
    }

    @Test
    fun `labels fit the top bar`() {
        Filters.all.forEach { filter ->
            assertTrue(
                "${filter.id} label is too long for the viewfinder",
                filter.label.length <= 9,
            )
        }
    }

    @Test
    fun `only the filters that use the seed are animated`() {
        Filters.all.filter { it.animated }.forEach { filter ->
            assertTrue(
                "${filter.id} animates but never reads seed",
                filter.agsl!!.contains("hash("),
            )
        }
    }

    @Test
    fun `stepping wraps in both directions`() {
        val first = Filters.all.first()
        val last = Filters.all.last()
        assertEquals(last.id, Filters.step(first, -1).id)
        assertEquals(first.id, Filters.step(last, 1).id)
        assertEquals(Filters.all[1].id, Filters.step(first, 1).id)
    }

    @Test
    fun `an unknown id falls back to None rather than crashing`() {
        assertEquals(Filters.none.id, Filters.byId("nope").id)
        assertEquals(Filters.none.id, Filters.byId(null).id)
    }
}
