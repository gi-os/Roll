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
    fun `the only filter without a shader is the one that must not have one`() {
        // None, because a plain photograph is the sensor's own JPEG written untouched. Anything else
        // appearing here is a filter that silently does nothing.
        //
        // Datamosh was the second entry here until v2.52, when it stopped being a JPEG databend and
        // became a shader like everything else — see `Filters.datamosh`. A filter with no shader can
        // have no preview, which is the cost that finally decided it.
        val without = Filters.all.filter { it.agsl == null }
        assertEquals(listOf("none"), without.map { it.id })
        assertNull(Filters.none.source)
    }

    @Test
    fun `datamosh is a shader and previews like everything else`() {
        assertTrue("datamosh must have a shader", Filters.datamosh.agsl != null)
        // Nothing else in the app should be reaching for a compressed file any more.
        assertTrue("datamosh must be in the dial", Filters.all.any { it.id == "datamosh" })
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
    fun `Datamosh is as far from Preset as the dial allows`() {
        // light-reports#27. Datamosh was last in the list, and because stepping wraps, last is one
        // notch *backwards* from first — so overshooting Preset by a single click landed on the one
        // filter that deliberately damages the file, and it was being switched on by accident.
        //
        // Guarded as a distance rather than as an index so that inserting a filter cannot quietly
        // walk it back to Preset's shoulder.
        val size = Filters.all.size
        val at = Filters.indexOf(Filters.datamosh)
        val away = minOf(at, size - at)
        assertTrue("Datamosh sits $away notch(es) from Preset", away >= size / 3)
        assertEquals(
            "the dial must not step from Preset straight onto Datamosh",
            listOf(false, false),
            listOf(Filters.step(Filters.none, 1), Filters.step(Filters.none, -1))
                .map { it.id == Filters.datamosh.id },
        )
    }

    @Test
    fun `only greyscale filters ask the date stamp to go neutral`() {
        // The stamp is printed after the filter, so it cannot see what it landed on and is told
        // instead. A filter with a colour of its own must not claim to be mono — a white date on a
        // Game Boy's green is not more correct than an amber one, only different.
        assertEquals(
            listOf("mono", "dithergrey", "onebit", "halftone"),
            Filters.all.filter { it.mono }.map { it.id },
        )
    }

    @Test
    fun `an unknown id falls back to None rather than crashing`() {
        assertEquals(Filters.none.id, Filters.byId("nope").id)
        assertEquals(Filters.none.id, Filters.byId(null).id)
    }
}
