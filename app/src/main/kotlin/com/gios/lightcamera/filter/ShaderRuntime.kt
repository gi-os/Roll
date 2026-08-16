package com.gios.lightcamera.filter

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.util.Log

/**
 * Running an AGSL filter, on a live view and on a still.
 *
 * The live case is easy — `View.setRenderEffect` exists for exactly this. The still case is
 * the interesting one, and the reason this file is longer than it looks like it should be:
 *
 * **A `RuntimeShader` cannot draw onto a software `Canvas`.** AGSL is compiled for the GPU,
 * and a `Canvas` wrapping a `Bitmap` is a CPU rasteriser, so the obvious three lines —
 * paint the shader over a bitmap canvas — silently produce nothing. The supported way to
 * get a hardware-accelerated draw without a `View` on screen is to drive the same renderer
 * the view hierarchy uses: a [RenderNode] holding the display list, a [HardwareRenderer]
 * pointed at an [ImageReader]'s surface, and a read-back through the resulting
 * [HardwareBuffer]. That is [Offscreen].
 *
 * The alternative would be a second, CPU implementation of every filter, which would drift
 * from the shader within a week.
 */
object ShaderRuntime {

    private const val TAG = "ShaderRuntime"

    /**
     * Compiled shaders, by filter id. Compilation is not free and the filter grid asks for
     * fifteen of them several times a second.
     *
     * A `RuntimeShader`'s uniforms are mutable state on the object, so callers must set
     * `size` and `seed` before every use rather than assuming what the last caller left.
     */
    private val compiled = HashMap<String, RuntimeShader>()

    private fun shader(filter: Filters.Filter): RuntimeShader? = shader(filter, compiled)

    /**
     * Compile into a caller-owned cache.
     *
     * Uniforms live on the shader object, so a shader shared between the preview (main
     * thread) and the filter grid (a background thread) would have the two of them
     * overwriting each other's `size` mid-draw. Each user keeps its own copies.
     */
    private fun shader(
        filter: Filters.Filter,
        cache: MutableMap<String, RuntimeShader>,
    ): RuntimeShader? {
        val source = filter.source ?: return null
        cache[filter.id]?.let { return it }
        val made = runCatching { RuntimeShader(source) }
            .onFailure { Log.e(TAG, "AGSL failed to compile for ${filter.id}", it) }
            .getOrNull() ?: return null
        cache[filter.id] = made
        return made
    }

    /**
     * Hand the detected faces to a shader that asks for them.
     *
     * **Only when the filter declares them.** Setting a uniform a shader does not have throws, so
     * this is gated on the flag rather than attempted and caught — and every slot is written every
     * time, because a `RuntimeShader` keeps its uniforms between draws and a face left over from the
     * last frame would go on warping an empty room.
     */
    private fun setFaces(
        shader: RuntimeShader,
        filter: Filters.Filter,
        faces: List<FaceQuad>,
        tune: FaceTune,
    ) {
        if (!filter.facesAware) return
        shader.setFloatUniform("warp", tune.eyes, tune.chin, tune.slim, tune.skin)
        shader.setFloatUniform("wash", tune.wash)
        shader.setFloatUniform("faceTurn", (((tune.turns % 4) + 4) % 4).toFloat())
        val used = faces.take(FaceQuads.MAX)
        shader.setFloatUniform("faceCount", used.size.toFloat())
        for (slot in 0 until FaceQuads.MAX) {
            val quad = used.getOrNull(slot)
            shader.setFloatUniform(
                "face$slot",
                quad?.cx ?: 0f,
                quad?.cy ?: 0f,
                quad?.hw ?: 0f,
                quad?.hh ?: 0f,
            )
        }
    }

    /**
     * Hand the ten adjustments to Preset.
     *
     * Packed into three `float4`s rather than ten scalars, for the same reason the face quads are:
     * a uniform write is a driver call, and Preset's uniforms are rewritten on every preview frame
     * that a grain or a seed changes. Ten calls per frame instead of three is not free on this GPU.
     *
     * Gated on the flag, not attempted and caught: setting a uniform a shader does not declare
     * throws, and every other filter in the app declares none of these.
     */
    private fun setGrade(shader: RuntimeShader, filter: Filters.Filter) {
        if (!filter.adjustable) return
        val grade = filter.grade
        shader.setFloatUniform(
            "gradeA",
            grade.normalised(Adjust.Exposure),
            grade.normalised(Adjust.Contrast),
            grade.normalised(Adjust.Highlights),
            grade.normalised(Adjust.Shadows),
        )
        shader.setFloatUniform(
            "gradeB",
            grade.normalised(Adjust.Vibrance),
            grade.normalised(Adjust.Warmth),
            grade.normalised(Adjust.Tint),
            grade.normalised(Adjust.Sharpness),
        )
        shader.setFloatUniform(
            "gradeC",
            grade.normalised(Adjust.Grain),
            grade.normalised(Adjust.Vignette),
            0f,
            0f,
        )
    }

    /**
     * The effect to hang on the preview.
     *
     * Returns null for [Filters.none], which the caller must read as "clear the effect"
     * rather than as a failure. A compile error also lands here as null — better an
     * unfiltered viewfinder than a black one.
     */
    fun effectFor(
        filter: Filters.Filter,
        width: Int,
        height: Int,
        seed: Float,
        faces: List<FaceQuad> = emptyList(),
        tune: FaceTune = FaceTune(),
    ): RenderEffect? {
        if (width <= 0 || height <= 0) return null
        // A Preset with nothing set is not a filter, and the caller reads null as "clear the
        // effect" — so the viewfinder goes back to costing nothing rather than running an identity
        // shader thirty times a second.
        if (filter.adjustable && filter.grade.isNeutral) return null
        // The uniforms are inside the net along with the effect, for the same reason they are in
        // [Offscreen.render]: this runs from a composition on the main thread, so a shader that turns
        // out not to declare a uniform this code sets would take the app down rather than the filter.
        return runCatching {
            val shader = shader(filter) ?: return@runCatching null
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("seed", seed)
            setFaces(shader, filter, faces, tune)
            setGrade(shader, filter)
            RenderEffect.createRuntimeShaderEffect(shader, "src")
        }.onFailure { Log.e(TAG, "effect failed for ${filter.id}", it) }.getOrNull()
    }

    /**
     * One-shot filtering of a still. Convenient, but it builds and tears down a renderer
     * each time; the filter grid uses a long-lived [Offscreen] instead.
     *
     * **Returns the source unchanged when the filter cannot be run**, and never throws — callers on
     * the shutter's path read that as "unfiltered", which is the right answer to a driver that will
     * not give a texture this big or a shader the platform declines. The one thing it must never do
     * is lose the frame.
     */
    fun applyToBitmap(
        source: Bitmap,
        filter: Filters.Filter,
        seed: Float,
        faces: List<FaceQuad> = emptyList(),
        tune: FaceTune = FaceTune(),
    ): Bitmap {
        if (filter.agsl == null) return source
        // The same short-circuit as the preview's, and here it is worth more: this is on the
        // shutter's path, and it is the difference between a photograph that costs a decode, a GPU
        // pass and a re-encode and one that costs nothing at all.
        if (filter.adjustable && filter.grade.isNeutral) return source
        // **The shader was never the expensive part.** An `Offscreen` is an ImageReader, a
        // HardwareRenderer bound to its surface and a RenderNode — a GPU surface allocation and a
        // renderer handshake — and this used to build one, draw a single rectangle through it and
        // tear it all down again on *every photograph*. The draw is a fraction of a frame; the
        // setup and teardown around it were most of the time the filtered path spent on the GPU.
        synchronized(pool) {
            val renderer = pooled(source.width, source.height) ?: return source
            return runCatching { renderer.render(source, filter, seed, faces, tune) }
                .onFailure { Log.e(TAG, "pooled render failed", it) }
                .getOrNull() ?: source
        }
    }

    /**
     * Renderers kept between shots, newest last. Only ever touched inside `synchronized(pool)`.
     *
     * Two sizes, which is what the app actually asks for: the panel, for everything that comes off
     * the viewfinder, and the capture size, for a filtered Pro still. A third request evicts the
     * least recently used.
     */
    private val pool = LinkedHashMap<String, Held>()

    /**
     * **Thread affinity is why an entry remembers where it was built.** A `HardwareRenderer` is not
     * documented as safe to use from a thread other than the one that made it, and captures run on
     * whichever worker the dispatcher hands out. A request from the same thread reuses the renderer;
     * one from a different thread rebuilds — which costs exactly what this function used to cost
     * every single time, and only on the first shot after the pool moves threads.
     */
    private class Held(val offscreen: Offscreen, val thread: Long)

    private const val POOL_MAX = 2

    private fun pooled(width: Int, height: Int): Offscreen? {
        if (width <= 0 || height <= 0) return null
        val key = "${width}x$height"
        val here = Thread.currentThread().id
        val existing = pool[key]
        if (existing != null) {
            if (existing.thread == here) {
                // Re-inserting moves it to the end, which is what makes the eviction below
                // least-recently-used rather than arbitrary.
                pool.remove(key)
                pool[key] = existing
                return existing.offscreen
            }
            pool.remove(key)
            existing.offscreen.close()
        }
        val made = Offscreen(width, height) ?: return null
        pool[key] = Held(made, here)
        while (pool.size > POOL_MAX) {
            val oldest = pool.keys.first()
            pool.remove(oldest)?.offscreen?.close()
        }
        return made
    }

    /**
     * Let the renderers go.
     *
     * A GPU surface held by a process that is no longer showing a viewfinder is a surface held for
     * nothing, and there are only so many of them.
     */
    fun releasePool() {
        synchronized(pool) {
            pool.values.forEach { it.offscreen.close() }
            pool.clear()
        }
    }

    /**
     * A reusable offscreen GPU surface at one fixed size.
     *
     * Keep one per size and call [render] as often as you like. Not thread-safe; each
     * instance belongs to whichever thread built it.
     */
    class Offscreen private constructor(
        private val width: Int,
        private val height: Int,
        private val reader: ImageReader,
        private val renderer: HardwareRenderer,
        private val node: RenderNode,
    ) {

        private val paint = Paint()
        private val owned = HashMap<String, RuntimeShader>()

        /**
         * Filter [source]. Null when the filter could not be run at all.
         *
         * **Total: it does not throw, whatever the platform says.** Every caller is on the way from a
         * shutter press to a file, and there the only acceptable failure is an unfiltered photograph
         * — so the uniforms, the shader compile, the recording and the read-back are all inside the
         * same net. They were not, and the ones outside it were the interesting ones: setting a
         * uniform a shader does not declare throws, and so does a `BitmapShader` over a bitmap that
         * something else has already recycled.
         */
        fun render(
            source: Bitmap,
            filter: Filters.Filter,
            seed: Float,
            faces: List<FaceQuad> = emptyList(),
            tune: FaceTune = FaceTune(),
        ): Bitmap? = runCatching {
            val shader = shader(filter, owned) ?: return@runCatching null
            // The bitmap is sampled in its own pixel space, so `size` here is the image and
            // every pattern in the shader scales to it. Same numbers the preview uses,
            // which is what makes the capture match the viewfinder.
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("seed", seed)
            setFaces(shader, filter, faces, tune)
            setGrade(shader, filter)
            val bitmapShader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // Scale the source into the node if the caller handed us a different size —
            // used by the filter grid, whose cells are smaller than the preview frame.
            if (source.width != width || source.height != height) {
                val m = android.graphics.Matrix()
                m.setScale(width.toFloat() / source.width, height.toFloat() / source.height)
                bitmapShader.setLocalMatrix(m)
            }
            shader.setInputShader("src", bitmapShader)
            paint.shader = shader

            // **`endRecording` in a `finally`, because a node left recording never recovers.** Every
            // later `beginRecording` on it throws "Recording currently in progress", so one draw that
            // failed halfway would take the filter grid's long-lived renderer down with it for good.
            val canvas = node.beginRecording()
            try {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            } finally {
                node.endRecording()
            }

            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireLatestImage() ?: return@runCatching null
            try {
                val buffer = image.hardwareBuffer ?: return@runCatching null
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(
                        buffer,
                        ColorSpace.get(ColorSpace.Named.SRGB),
                    ) ?: return@runCatching null
                    // Copy out: the hardware bitmap is a view onto a buffer that is
                    // about to be handed back to the reader, and JPEG encoding needs
                    // pixels it can read on the CPU anyway.
                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
            } finally {
                image.close()
            }
        }.onFailure { Log.e(TAG, "offscreen render failed", it) }.getOrNull()

        fun close() {
            runCatching {
                node.discardDisplayList()
                renderer.destroy()
                reader.close()
            }
        }

        companion object {
            operator fun invoke(width: Int, height: Int): Offscreen? {
                if (width <= 0 || height <= 0) return null
                return runCatching {
                    val reader = ImageReader.newInstance(
                        width,
                        height,
                        PixelFormat.RGBA_8888,
                        2,
                        // Both flags matter: COLOR_OUTPUT so the GPU may render into it,
                        // GPU_SAMPLED so Bitmap.wrapHardwareBuffer will accept it.
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
                    )
                    val renderer = HardwareRenderer().apply {
                        setSurface(reader.surface)
                        isOpaque = true
                    }
                    val node = RenderNode("lightcamera-filter").apply {
                        setPosition(0, 0, width, height)
                    }
                    renderer.setContentRoot(node)
                    Offscreen(width, height, reader, renderer, node)
                }.onFailure { Log.e(TAG, "offscreen setup failed", it) }.getOrNull()
            }
        }
    }
}
