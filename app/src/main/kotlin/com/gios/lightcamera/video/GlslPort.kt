package com.gios.lightcamera.video

/**
 * The photo filters, carried across to OpenGL ES so a video can wear them.
 *
 * **Why a translation and not a second copy.** Every photo filter in [com.gios.lightcamera.filter.Filters]
 * is AGSL, which only runs inside the platform's own renderer: a `RenderEffect` on a view or a
 * `RuntimeShader` in a `HardwareRenderer`. Neither can sit between the camera and the video
 * encoder. That slot belongs to a CameraX `SurfaceProcessor`, and a `SurfaceProcessor` speaks GL.
 * Twenty-odd filters written twice would drift apart within a release, so the AGSL is the source of
 * truth and this turns it into GLSL ES 3.00 when a video look asks for it.
 *
 * It works because AGSL *is* GLSL ES with different spellings. Nearly all the difference is type
 * names — `float2` for `vec2`, `half4` for `vec4` — and the preprocessor takes care of those
 * without the source changing at all. What is left is three edits:
 *
 *  - `uniform shader src;` has no GL equivalent. It becomes a `sampler2D`, and `src.eval(p)`,
 *    which takes **pixels with y down**, becomes [SAMPLE], which turns those pixels into a texture
 *    coordinate with y up. That one flip is the entire coordinate story: the frame in the texture
 *    is stored upright in GL's convention, and every filter goes on addressing it the way it
 *    always has.
 *  - `size` is declared up front, because [SAMPLE] needs it and the prelude declares it after.
 *  - AGSL's entry point is `half4 main(float2 xy)`. GLSL's is `void main()`, so the filter's own
 *    is renamed and a real `main` calls it with the same top-left pixel coordinate AGSL would.
 *
 * Nothing Android lives here, so the whole catalogue can be ported and handed to `glslangValidator`
 * in a unit test — the one thing the AGSL itself has never had. See `VideoShadersTest`.
 */
object GlslPort {

    /**
     * The type spellings, as macros. `half` becomes `float`: AGSL's `half` is a precision hint, and
     * highp everywhere costs this GPU nothing it would notice at 1440 pixels.
     */
    const val TYPES = """
#define float2 vec2
#define float3 vec3
#define float4 vec4
#define half float
#define half2 vec2
#define half3 vec3
#define half4 vec4
#define float2x2 mat2
#define float3x3 mat3
#define float4x4 mat4
#define half2x2 mat2
#define half3x3 mat3
#define half4x4 mat4
#define int2 ivec2
#define int3 ivec3
#define int4 ivec4
#define bool2 bvec2
#define bool3 bvec3
#define bool4 bvec4
#define saturate(x) clamp((x), 0.0, 1.0)
"""

    const val VERSION = "#version 300 es\nprecision highp float;\nprecision highp int;\n"

    /**
     * What `src.eval` becomes. Pixels, y down, in — a colour out.
     *
     * Clamped by the texture's own `CLAMP_TO_EDGE`, which is the same answer AGSL's `tap()` gives
     * with its explicit clamp, so the two agree at the border as well as inside it.
     */
    const val SAMPLE = """
uniform sampler2D uSrc;
uniform vec2 size;
out vec4 fragColor;
vec4 srcEval(vec2 p) { return texture(uSrc, vec2(p.x, size.y - p.y) / size); }
"""

    /** The real entry point: the same top-left pixel centre AGSL hands its `main`. */
    const val ENTRY = """
void main() {
    vec2 p = vec2(gl_FragCoord.x, size.y - gl_FragCoord.y);
    fragColor = vec4(fxMain(p).rgb, 1.0);
}
"""

    private val SHADER_UNIFORM = Regex("""uniform\s+shader\s+src\s*;""")
    private val SIZE_UNIFORM = Regex("""uniform\s+(?:float2|half2|vec2)\s+size\s*;""")
    private val AGSL_MAIN = Regex("""(?:half4|float4|vec4)\s+main\s*\(\s*(?:float2|half2|vec2)\s+(\w+)\s*\)""")

    /**
     * An AGSL fragment shader as GLSL ES 3.00, or null when it is not one this can carry.
     *
     * Null rather than a best guess: a shader that reads a second `uniform shader`, or has no
     * `main(float2)`, would compile to something that looks nothing like the photograph, and a
     * video look that silently disagrees with the photo filter of the same name is worse than one
     * that is not offered.
     */
    fun port(agsl: String): String? {
        if (!AGSL_MAIN.containsMatchIn(agsl)) return null
        val withoutSrc = SHADER_UNIFORM.replace(agsl, "")
        // Any other `uniform shader` is an input GL has no texture for.
        if (withoutSrc.contains(Regex("""uniform\s+shader\s"""))) return null
        val body = SIZE_UNIFORM.replace(withoutSrc, "")
            .replace("src.eval(", "srcEval(")
            .let { AGSL_MAIN.replace(it) { m -> "half4 fxMain(float2 ${m.groupValues[1]})" } }
        return VERSION + TYPES + SAMPLE + body + ENTRY
    }
}
