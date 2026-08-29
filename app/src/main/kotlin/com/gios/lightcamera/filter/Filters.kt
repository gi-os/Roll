package com.gios.lightcamera.filter

/**
 * The filters, as AGSL.
 *
 * Every one of them is a single fragment shader, and that is the design decision the whole
 * app hangs off. A shader can be handed to the platform twice:
 *
 *  - wrapped in a `RenderEffect` on the preview's `TextureView`, where it filters the live
 *    image on the GPU with no per-frame work on our side, and
 *  - wrapped in a `Paint` over a `BitmapShader`, where it filters the captured photo.
 *
 * So there is exactly one implementation of "what Halftone looks like", and the photo you
 * get is the photo you framed. The alternative — a preview approximation plus a separate
 * CPU pass at capture — is how filter apps end up lying to you.
 *
 * Two rules keep the two paths honest:
 *
 *  - **Patterns are sized in design pixels, not device pixels.** [PRELUDE]'s `unitPx()`
 *    divides by the image height, so a halftone dot covers the same fraction of the frame
 *    in a 340px preview and a 4000px capture. Without this, every dithered filter
 *    dissolves into invisible noise the moment it is applied at full resolution.
 *  - **Nothing samples outside the frame** without clamping, because the two paths differ
 *    on what lies outside: a view returns transparent, a clamped `BitmapShader` returns
 *    the edge pixel.
 *
 * AGSL requires API 33, which is why this app does.
 */
object Filters {

    /**
     * Prepended to every shader. `src` is the image, `size` its dimensions in pixels, and
     * `seed` moves the grain between frames.
     *
     * `bayer2/4/8` build an ordered-dither threshold matrix by recursion rather than from a
     * lookup table — SkSL has no arrays in a runtime shader worth relying on, and the
     * recursion is exact: `bayer2` alone yields {0, .5, .75, .25}, the 2x2 Bayer matrix
     * over four, and each level adds a quarter-weighted finer copy of it.
     */
    private const val PRELUDE = """
uniform shader src;
uniform float2 size;
uniform float seed;

float lum(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

float3 tap(float2 p) {
    float2 q = clamp(p, float2(0.0, 0.0), size - float2(1.0, 1.0));
    return float3(src.eval(q).rgb);
}

float unitPx() { return max(1.0, size.y / 640.0); }

float bayer2(float2 a) { a = floor(a); return fract(a.x * 0.5 + a.y * a.y * 0.75); }
float bayer4(float2 a) { return bayer2(a * 0.5) * 0.25 + bayer2(a); }
float bayer8(float2 a) { return bayer4(a * 0.5) * 0.25 + bayer2(a); }

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233)) + seed) * 43758.5453);
}

half4 grey(float g) {
    float v = clamp(g, 0.0, 1.0);
    return half4(float4(v, v, v, 1.0));
}
"""

    /**
     * Prepended to the shaders that have a left and a right, on top of [PRELUDE].
     *
     * **The bug this exists for.** The preview shader runs on the panel image, and the panel is
     * portrait-locked — so held sideways the scene lies on its side in it. The capture shader runs
     * on a bitmap that has already been turned upright, so `size.x` and `size.y` are the other way
     * round and `xy.x` runs along what was the panel's vertical axis. For a filter that only reads
     * luminance, none of that matters. For Mirror it matters completely: the fold you framed runs
     * one way on the panel and the other way in the file, and the photograph you get is not the
     * one you were looking at.
     *
     * So a directional shader is told which way up the world is and does its work there.
     * [toUp]/[fromUp] convert between the image's own pixels and upright ones, [upSize] gives the
     * frame's dimensions once upright, and `turn` is quarter turns **clockwise** — the same number
     * and the same sign as `CameraEngine.previewRotationDegrees()`, divided by ninety.
     *
     * Which means the capture path passes zero, because by the time the shader sees those pixels
     * the turn has already been baked into them. Only the panel-space callers — the live preview,
     * the frozen frame and the filter grid's thumbnails — pass a real one. Purikura solved the
     * same problem the same way with `faceTurn`; this is that idea for the frame rather than for
     * a face.
     */
    private const val TURN = """
uniform float turn;

float2 upSize() { return (mod(turn, 2.0) == 1.0) ? float2(size.y, size.x) : size; }

float2 toUp(float2 p) {
    float t = mod(turn, 4.0);
    if (t == 1.0) return float2(size.y - p.y, p.x);
    if (t == 2.0) return float2(size.x - p.x, size.y - p.y);
    if (t == 3.0) return float2(p.y, size.x - p.x);
    return p;
}

float2 fromUp(float2 q) {
    float t = mod(turn, 4.0);
    if (t == 1.0) return float2(q.y, size.y - q.x);
    if (t == 2.0) return float2(size.x - q.x, size.y - q.y);
    if (t == 3.0) return float2(size.x - q.y, q.x);
    return q;
}

float3 tapUp(float2 q) { return tap(fromUp(q)); }
"""

    /**
     * Grain, halation and a vignette. The default, and the reason the app is called Roll.
     *
     * The grain is modulated by the midtones because that is where silver halide actually
     * clumps — flat grain over the whole frame reads as digital noise, not as film. The
     * halation is a four-tap blur of the highlights added back on top, which is the same
     * trick as a real bloom, done cheaply enough to run on a live preview.
     */
    private const val FILM = """
half4 main(float2 xy) {
    float2 uv = xy / size;
    float3 c = tap(xy);
    float u = unitPx() * 3.0;
    float3 blur = (tap(xy + float2(u, 0.0)) + tap(xy - float2(u, 0.0)) +
                   tap(xy + float2(0.0, u)) + tap(xy - float2(0.0, u))) * 0.25;
    float halation = max(0.0, lum(blur) - 0.70);
    c = c * 1.06 + halation * 0.45;
    c = (c - 0.5) * 1.10 + 0.5;
    float g = lum(c);
    float n = hash(floor(xy / unitPx())) - 0.5;
    c += n * 0.09 * (1.0 - abs(g - 0.5) * 1.4);
    float2 d = uv - 0.5;
    c *= 1.0 - dot(d, d) * 0.60;
    c = clamp(c, 0.0, 1.0);
    return half4(float4(c, 1.0));
}
"""

    /** Black and white with a print-like S-curve. What the panel does best. */
    private const val MONO = """
half4 main(float2 xy) {
    float g = lum(tap(xy));
    g = clamp((g - 0.5) * 1.28 + 0.5, 0.0, 1.0);
    g = pow(g, 0.92);
    return grey(g);
}
"""

    /**
     * Sixteen colours, ordered-dithered. The EGA palette, which is the one your eye reads
     * as "computer" rather than as "low quality".
     *
     * Dithering happens *before* quantisation: the threshold matrix nudges each pixel's
     * colour, then the nearest palette entry is picked, so flat gradients break into the
     * cross-hatch instead of banding.
     */
    /**
     * Sixteen greys, ordered-dithered — the grayscale half of Dither 16.
     *
     * Not the colour one desaturated: quantising *after* a colour match would land on whichever of the
     * sixteen EGA entries happened to be nearest and then flatten it, which throws away most of the tonal
     * range. This quantises luminance directly, so all sixteen steps are used and the gradients stay smooth
     * in the way a 4-bit greyscale image does.
     *
     * The dither offset is a step and a half wide rather than a step: at exactly one step the pattern is
     * almost invisible on a photograph, and the point of dithering is that you can see it.
     */
    private const val DITHER_GREY = """
half4 main(float2 xy) {
    float3 c = tap(xy);
    // A touch of contrast first. Sixteen levels across a flat photograph is mud; across a slightly punchy
    // one it reads as an old greyscale scan.
    float l = clamp((lum(c) - 0.5) * 1.18 + 0.5, 0.0, 1.0);
    float steps = 15.0;
    float t = bayer8(xy / (unitPx() * 2.0)) - 0.5;
    l = clamp(l + t * (1.5 / steps), 0.0, 1.0);
    float q = floor(l * steps + 0.5) / steps;
    return half4(float4(q, q, q, 1.0));
}
"""

    /**
     * Thirty-two colours, ordered-dithered — one step up from [DITHER16].
     *
     * Eight greys and eight hues at three brightnesses, matched by nearest distance exactly as the
     * sixteen-colour one is. **The extra sixteen entries mostly go into the greys and the dark end**, which is
     * where a 16-colour palette shows its seams worst: EGA has one mid-grey and nothing between black and half
     * brightness, so skin and shadow both collapse onto the same few swatches. Photographs spend most of their
     * range there.
     *
     * The dither offset is smaller than DITHER16's — with twice the palette the error to spread is half the
     * size, and reusing the wider offset would scatter pixels past the entries either side of the right one.
     */
    private const val DITHER32 = """
void nearer(float3 c, float3 cand, inout float3 best, inout float bd) {
    float3 e = c - cand;
    float d = dot(e, e);
    if (d < bd) { bd = d; best = cand; }
}

half4 main(float2 xy) {
    float3 c = tap(xy);
    c = clamp((c - 0.5) * 1.12 + 0.5, 0.0, 1.0);
    float t = bayer8(xy / (unitPx() * 2.0)) - 0.5;
    c = clamp(c + t * 0.15, 0.0, 1.0);

    float3 best = float3(0.0, 0.0, 0.0);
    float bd = 1000.0;
    nearer(c, float3(0.00, 0.00, 0.00), best, bd);
    nearer(c, float3(0.14, 0.14, 0.14), best, bd);
    nearer(c, float3(0.29, 0.29, 0.29), best, bd);
    nearer(c, float3(0.43, 0.43, 0.43), best, bd);
    nearer(c, float3(0.57, 0.57, 0.57), best, bd);
    nearer(c, float3(0.71, 0.71, 0.71), best, bd);
    nearer(c, float3(0.86, 0.86, 0.86), best, bd);
    nearer(c, float3(1.00, 1.00, 1.00), best, bd);
    nearer(c, float3(0.34, 0.00, 0.00), best, bd);
    nearer(c, float3(0.00, 0.34, 0.00), best, bd);
    nearer(c, float3(0.00, 0.00, 0.34), best, bd);
    nearer(c, float3(0.34, 0.34, 0.00), best, bd);
    nearer(c, float3(0.34, 0.00, 0.34), best, bd);
    nearer(c, float3(0.00, 0.34, 0.34), best, bd);
    nearer(c, float3(0.34, 0.17, 0.00), best, bd);
    nearer(c, float3(0.17, 0.00, 0.34), best, bd);
    nearer(c, float3(0.62, 0.00, 0.00), best, bd);
    nearer(c, float3(0.00, 0.62, 0.00), best, bd);
    nearer(c, float3(0.00, 0.00, 0.62), best, bd);
    nearer(c, float3(0.62, 0.62, 0.00), best, bd);
    nearer(c, float3(0.62, 0.00, 0.62), best, bd);
    nearer(c, float3(0.00, 0.62, 0.62), best, bd);
    nearer(c, float3(0.62, 0.31, 0.00), best, bd);
    nearer(c, float3(0.31, 0.00, 0.62), best, bd);
    nearer(c, float3(1.00, 0.00, 0.00), best, bd);
    nearer(c, float3(0.00, 1.00, 0.00), best, bd);
    nearer(c, float3(0.00, 0.00, 1.00), best, bd);
    nearer(c, float3(1.00, 1.00, 0.00), best, bd);
    nearer(c, float3(1.00, 0.00, 1.00), best, bd);
    nearer(c, float3(0.00, 1.00, 1.00), best, bd);
    nearer(c, float3(1.00, 0.50, 0.00), best, bd);
    nearer(c, float3(0.50, 0.00, 1.00), best, bd);
    return half4(float4(best, 1.0));
}
"""

    private const val DITHER16 = """
void nearer(float3 c, float3 cand, inout float3 best, inout float bd) {
    float3 e = c - cand;
    float d = dot(e, e);
    if (d < bd) { bd = d; best = cand; }
}

half4 main(float2 xy) {
    float3 c = tap(xy);
    c = clamp((c - 0.5) * 1.15 + 0.5, 0.0, 1.0);
    float t = bayer8(xy / (unitPx() * 2.0)) - 0.5;
    c = clamp(c + t * 0.26, 0.0, 1.0);

    float3 best = float3(0.0, 0.0, 0.0);
    float bd = 1000.0;
    nearer(c, float3(0.00, 0.00, 0.00), best, bd);
    nearer(c, float3(0.50, 0.00, 0.00), best, bd);
    nearer(c, float3(0.00, 0.50, 0.00), best, bd);
    nearer(c, float3(0.50, 0.50, 0.00), best, bd);
    nearer(c, float3(0.00, 0.00, 0.50), best, bd);
    nearer(c, float3(0.50, 0.00, 0.50), best, bd);
    nearer(c, float3(0.00, 0.50, 0.50), best, bd);
    nearer(c, float3(0.66, 0.66, 0.66), best, bd);
    nearer(c, float3(0.33, 0.33, 0.33), best, bd);
    nearer(c, float3(1.00, 0.00, 0.00), best, bd);
    nearer(c, float3(0.00, 1.00, 0.00), best, bd);
    nearer(c, float3(1.00, 1.00, 0.00), best, bd);
    nearer(c, float3(0.00, 0.00, 1.00), best, bd);
    nearer(c, float3(1.00, 0.00, 1.00), best, bd);
    nearer(c, float3(0.00, 1.00, 1.00), best, bd);
    nearer(c, float3(1.00, 1.00, 1.00), best, bd);
    return half4(float4(best, 1.0));
}
"""

    /** One bit. Newsprint, or the phone's own idea of an image. */
    private const val ONE_BIT = """
half4 main(float2 xy) {
    float g = lum(tap(xy));
    g = clamp((g - 0.48) * 1.35 + 0.5, 0.0, 1.0);
    float t = bayer8(xy / (unitPx() * 1.6));
    return grey(step(t, g));
}
"""

    /**
     * A rotated dot screen. Each cell reads the image once at its own centre and grows a
     * dot to match, so this is a genuine halftone rather than a thresholded texture — the
     * dots stay round and evenly spaced no matter what the image does.
     */
    private const val HALFTONE = """
half4 main(float2 xy) {
    float cell = unitPx() * 6.0;
    float a = 0.5236;
    float ca = cos(a);
    float sa = sin(a);
    float2 p = float2(xy.x * ca - xy.y * sa, xy.x * sa + xy.y * ca);
    float2 centre = (floor(p / cell) + 0.5) * cell;
    float2 back = float2(centre.x * ca + centre.y * sa, -centre.x * sa + centre.y * ca);
    float g = lum(tap(back));
    g = clamp((g - 0.5) * 1.2 + 0.5, 0.0, 1.0);
    float r = sqrt(1.0 - g) * cell * 0.70;
    float ink = 1.0 - smoothstep(r - 1.0, r + 1.0, distance(p, centre));
    return grey(1.0 - ink);
}
"""

    /** Posterised, with the edges inked in. Sobel, four levels, no outline shader tricks. */
    private const val COMIC = """
half4 main(float2 xy) {
    float u = unitPx();
    float l00 = lum(tap(xy + float2(-u, -u)));
    float l10 = lum(tap(xy + float2(0.0, -u)));
    float l20 = lum(tap(xy + float2(u, -u)));
    float l01 = lum(tap(xy + float2(-u, 0.0)));
    float l21 = lum(tap(xy + float2(u, 0.0)));
    float l02 = lum(tap(xy + float2(-u, u)));
    float l12 = lum(tap(xy + float2(0.0, u)));
    float l22 = lum(tap(xy + float2(u, u)));
    float gx = -l00 - 2.0 * l01 - l02 + l20 + 2.0 * l21 + l22;
    float gy = -l00 - 2.0 * l10 - l20 + l02 + 2.0 * l12 + l22;
    float e = sqrt(gx * gx + gy * gy);
    float3 c = tap(xy);
    c = clamp((c - 0.5) * 1.35 + 0.5, 0.0, 1.0);
    float3 q = floor(c * 4.0 + 0.5) / 4.0;
    q *= 1.0 - smoothstep(0.30, 0.62, e);
    return half4(float4(q, 1.0));
}
"""

    /** False colour up a five-stop ramp. Photo Booth's thermal camera, more or less. */
    private const val THERMAL = """
half4 main(float2 xy) {
    float g = lum(tap(xy));
    float3 c = mix(float3(0.0, 0.0, 0.10), float3(0.15, 0.0, 0.55), smoothstep(0.00, 0.25, g));
    c = mix(c, float3(0.72, 0.0, 0.45), smoothstep(0.25, 0.50, g));
    c = mix(c, float3(1.0, 0.25, 0.0), smoothstep(0.50, 0.72, g));
    c = mix(c, float3(1.0, 0.90, 0.10), smoothstep(0.72, 0.90, g));
    c = mix(c, float3(1.0, 1.0, 1.0), smoothstep(0.90, 1.00, g));
    return half4(float4(c, 1.0));
}
"""

    /**
     * The Game Boy Camera.
     *
     * Two things make that look, and only one of them is the green. The other is the
     * resolution: the GB Camera's sensor was **128 x 112**, so the image is quantised onto a
     * grid of that many cells across the short edge before anything else happens — sampling
     * once per cell rather than averaging, because that is what a 128-pixel sensor does.
     *
     * Then four shades and nothing between them, the DMG palette from the real thing
     * (`0f380f`, `306230`, `8bac0f`, `9bbc0f`), reached through a Bayer threshold so gradients
     * break into the cross-hatch the hardware produced instead of banding. The contrast is
     * pushed first: four levels of a flat exposure is mud.
     */
    private const val GAMEBOY = """
half4 main(float2 xy) {
    float cell = max(1.0, min(size.x, size.y) / 128.0);
    float2 grid = (floor(xy / cell) + 0.5) * cell;
    float g = lum(tap(grid));
    g = clamp((g - 0.5) * 1.45 + 0.5, 0.0, 1.0);
    // Three thresholds for four shades, nudged by the dither so the steps break up.
    float t = (bayer4(xy / cell) - 0.5) * 0.30;
    float level = floor(clamp(g + t, 0.0, 0.999) * 4.0);
    float3 c = float3(0.059, 0.220, 0.059);
    if (level > 0.5) c = float3(0.188, 0.384, 0.188);
    if (level > 1.5) c = float3(0.545, 0.675, 0.059);
    if (level > 2.5) c = float3(0.608, 0.737, 0.059);
    return half4(float4(c, 1.0));
}
"""

    /**
     * The same sensor, on a Game Boy Color.
     *
     * The GBC kept the low resolution and gained fifteen bits of colour — five per channel — so
     * this is the same 128-cell grid with each channel dithered to five levels rather than
     * everything crushed to four greens. What you get is not "colour": it is the specific,
     * slightly sour palette of a 1998 handheld, which is the point of asking for it.
     */
    private const val GB_COLOR = """
half4 main(float2 xy) {
    float cell = max(1.0, min(size.x, size.y) / 128.0);
    float2 grid = (floor(xy / cell) + 0.5) * cell;
    float3 c = tap(grid);
    c = clamp((c - 0.5) * 1.30 + 0.5, 0.0, 1.0);
    // Five steps a channel, which is 125 colours — close enough to the GBC's usable palette,
    // and the dither is what stops it looking like a posterise filter.
    float t = (bayer4(xy / cell) - 0.5) * 0.26;
    float3 q = floor(clamp(c + t, 0.0, 0.999) * 5.0) / 4.0;
    // A touch warm and green, the way that screen was.
    q *= float3(0.98, 1.02, 0.90);
    return half4(float4(clamp(q, 0.0, 1.0), 1.0));
}
"""

    /** Inverted, gamma-lifted, cooled. Bones. */
    private const val X_RAY = """
half4 main(float2 xy) {
    float g = 1.0 - lum(tap(xy));
    g = pow(clamp(g, 0.0, 1.0), 0.78);
    return half4(float4(g * 0.80, g * 0.94, g, 1.0));
}
"""

    /** A soft ring blur added back over the highlights. Everything looks kinder. */
    private const val GLOW = """
half4 main(float2 xy) {
    float u = unitPx() * 5.0;
    float3 b = tap(xy + float2(u, 0.0)) + tap(xy - float2(u, 0.0)) +
               tap(xy + float2(0.0, u)) + tap(xy - float2(0.0, u)) +
               tap(xy + float2(u, u)) + tap(xy - float2(u, u)) +
               tap(xy + float2(u, -u)) + tap(xy - float2(u, -u));
    b *= 0.125;
    float3 c = tap(xy);
    c = mix(c, b, 0.35);
    c += max(float3(0.0, 0.0, 0.0), b - 0.55) * 1.15;
    return half4(float4(clamp(c, 0.0, 1.0), 1.0));
}
"""

    /** Polar warp, strongest at the centre and easing out to nothing at the edge. */
    private const val TWIRL = """
half4 main(float2 xy) {
    float2 ctr = size * 0.5;
    float2 d = xy - ctr;
    float r = length(d);
    float R = min(size.x, size.y) * 0.58;
    float t = clamp(1.0 - r / R, 0.0, 1.0);
    float a = t * t * 2.8;
    float ca = cos(a);
    float sa = sin(a);
    float2 p = ctr + float2(d.x * ca - d.y * sa, d.x * sa + d.y * ca);
    return half4(float4(tap(p), 1.0));
}
"""

    /** A lens on the middle of the frame. */
    private const val BULGE = """
half4 main(float2 xy) {
    float2 ctr = size * 0.5;
    float2 d = xy - ctr;
    float R = min(size.x, size.y) * 0.62;
    float t = clamp(length(d) / R, 0.0, 1.0);
    float k = mix(0.48, 1.06, t * t);
    return half4(float4(tap(ctr + d * k), 1.0));
}
"""

    /**
     * A fisheye, meaning the projection rather than the word.
     *
     * [BULGE] is a bulge: a blob of magnification in the middle of a frame that stays full. This
     * is what a lens actually does. The angle off the axis grows linearly with distance from the
     * centre of the image circle — the equidistant projection every fisheye adapter approximates
     * — while the rectilinear sensor underneath put whatever it saw at that angle out at
     * `tan(angle)`. Sampling at `tan(r * FOV)` undoes the sensor's projection and imposes the
     * lens's. Dividing by `tan(FOV)` pins the rim of the circle to the rim of the frame, so
     * nothing is cropped: the middle is magnified and the edge is squeezed, which is the entire
     * look. At `FOV = 1.15` (66 degrees off axis) the middle comes up about 1.9x -- rendered
     * against a grid, 1.35 ate so much of the frame that the subject was all that was left, and
     * 1.0 did not bow the straight lines enough to read as a lens at all.
     *
     * The black corners are not decoration. A fisheye adapter on a phone projects a circle onto a
     * rectangle, and the circle is most of why the result reads as a lens rather than as a warp.
     * It is inscribed in the short side, so a portrait frame gets bands and a square gets none.
     *
     * `min(r, 1.0)` matters more than it looks: a 3:4 frame reaches `r = 1.67` in the corners, and
     * `tan(1.67 * 1.15)` is past the asymptote and negative, which would fold the corners back
     * through the centre inside out. They are multiplied away a moment later, but NaN is not.
     */
    private const val FISHEYE = """
half4 main(float2 xy) {
    float2 ctr = size * 0.5;
    float R = min(size.x, size.y) * 0.5;
    float2 d = (xy - ctr) / R;
    float r = length(d);
    float k = tan(min(r, 1.0) * 1.15) / 2.2345;
    float3 c = tap(ctr + d / max(r, 0.0001) * k * R);
    float feather = unitPx() * 1.5 / R;
    c = c * (1.0 - smoothstep(1.0 - feather, 1.0, r));
    return half4(float4(c, 1.0));
}
"""

    /**
     * The left half of the frame, and the left half of the frame again.
     *
     * Folded across the world's vertical, not the image's — see [TURN]. A fold has a direction,
     * and getting it from `size.x` meant it ran down the panel in the viewfinder and across the
     * picture in the file the moment you turned the phone.
     */
    private const val MIRROR = """
half4 main(float2 xy) {
    float2 s = upSize();
    float2 q = toUp(xy);
    float halfW = s.x * 0.5;
    q.x = halfW - abs(q.x - halfW);
    return half4(float4(tapUp(q), 1.0));
}
"""

    /**
     * Six segments folded around the centre.
     *
     * Same reason as [MIRROR] for working upright: `atan(d.y, d.x)` measures from the image's own
     * +x, so the six wedges swing round by ninety degrees when the phone turns and the pattern
     * you framed is not the pattern you get.
     */
    private const val KALEIDO = """
half4 main(float2 xy) {
    float2 s = upSize();
    float2 ctr = s * 0.5;
    float2 d = toUp(xy) - ctr;
    float r = length(d);
    float a = atan(d.y, d.x);
    float seg = 1.0471976;
    a = abs(mod(a, seg) - seg * 0.5);
    return half4(float4(tapUp(ctr + float2(cos(a), sin(a)) * r), 1.0));
}
"""

    /** Everything past a small central disc is smeared out from its rim. */
    private const val TUNNEL = """
half4 main(float2 xy) {
    float2 ctr = size * 0.5;
    float2 d = xy - ctr;
    float r = max(length(d), 0.0001);
    float R = min(size.x, size.y) * 0.30;
    float2 p = r > R ? ctr + d * (R / r) : xy;
    return half4(float4(tap(p), 1.0));
}
"""

    /**
     * **Purikura.** The Japanese photo-booth look, and the only shader here that knows where a face
     * is.
     *
     * A booth does four things to you, all of them too much on purpose, and this does the same four:
     *
     *  1. **Eyes twice the size.** A radial magnification centred on each eye — sampled *towards*
     *     the eye's centre, which is what makes it grow. The eyes are guessed from the face
     *     rectangle rather than detected: a quarter of the face's width either side of centre, a
     *     fifth of its height above the middle. That is where eyes are on a face, and the hardware
     *     detector's landmarks are not available on every camera, whereas its rectangle always is.
     *  2. **Skin blown out.** Luminance lifted hard and the top end crushed flat, so faces come out
     *     poreless and papery. This is the part people actually go for.
     *  3. **Pink.** A wash pulled towards a cool rose in the shadows and a warm one in the
     *     highlights, saturation up, contrast down. Booth prints have almost no black in them.
     *  4. **Glitter.** Four-pointed stars scattered on a hash grid, brighter near a face, drifting
     *     with `seed` so they twinkle in the viewfinder.
     *
     * The face uniforms are `face0..face2` as (centre x, centre y, half width, half height) in
     * fractions of the image, and `faceCount` says how many are real. Three separate `float4`s
     * rather than an array because an unset uniform is a compile-time promise a `RuntimeShader`
     * will not let you break, and three is a photo booth's worth of people.
     *
     * With no face in frame it is still the wash, the bloom and the glitter — a booth with nobody
     * in it is a pink room.
     */
    private const val PURIKURA = """
uniform float4 face0;
uniform float4 face1;
uniform float4 face2;
uniform float faceCount;

// **Every part of the look, switchable.** (eyes, chin, slim, skin) and the wash on its own — all 0..1,
// all multiplying an amount rather than gating a branch, so a half-strength setting would work if one
// were ever offered and turning something off costs a multiply rather than a different shader.
uniform float4 warp;
uniform float wash;

/**
 * Which way up the face is, in quarter turns: 0, 1, 2, 3.
 *
 * **Without this every warp was wrong the moment you turned the phone.** The shader runs on the panel,
 * and the panel is portrait-locked — so held sideways a face lies on its side in the image, with the eyes
 * one above the other. The eye positions were being guessed left-and-right of centre regardless, which
 * put the magnification on a forehead and a chin. Everything below is measured along the face's own axes
 * instead of the image's.
 */
uniform float faceTurn;

float4 faceAt(int i) {
    if (i == 0) return face0;
    if (i == 1) return face1;
    return face2;
}

// How much this colour looks like skin: warm, red above green above blue, not too saturated.
// Deliberately generous — it decides how hard to smooth, not whether to, so being wrong about a
// wooden floor costs a slightly soft floor.
float skinness(float3 c) {
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    float sat = mx - mn;
    float warm = clamp((c.r - c.b) * 3.0, 0.0, 1.0);
    float order = (c.r >= c.g && c.g >= c.b) ? 1.0 : 0.35;
    float bright = smoothstep(0.15, 0.45, mx);
    float notNeon = 1.0 - smoothstep(0.45, 0.8, sat);
    return clamp(warm * order * bright * notNeon, 0.0, 1.0);
}

// Magnify around a point: sample nearer its centre, so what is there grows.
float2 magnify(float2 p, float2 centre, float radius, float amount) {
    float2 d = p - centre;
    float dist = length(d);
    if (dist >= radius || radius <= 0.0) return p;
    float t = dist / radius;
    // Smooth all the way to the rim, or the enlargement has a visible edge — a disc of face
    // sitting on a face, which is the tell of a bad beauty filter.
    float k = mix(1.0 / amount, 1.0, smoothstep(0.0, 1.0, t));
    return centre + d * k;
}

half4 main(float2 xy) {
    float2 p = xy;
    int n = int(faceCount);

    // ---- the shape of the face ----
    // No `break` and no `continue`: SkSL wants a loop it can unroll, and a face that is not there is
    // handled by a zero half-extent, which every warp below refuses, rather than by leaving early.
    //
    // Three warps, in the order a booth applies them: the eyes grow, the jaw comes in, the whole head
    // shrinks a little. All three read the *original* rectangle rather than each other's output, which
    // compounds slightly and is invisible at these strengths — and is far easier to reason about than
    // three warps chasing a moving centre.
    // The face's own axes: `ax` runs along the eye line, `ay` down from the brow to the chin. At a
    // quarter turn they are the image's y and -x, which is what makes every offset below correct in
    // every pose instead of only in portrait.
    float turns = mod(faceTurn, 4.0);
    float sideways = (turns == 1.0 || turns == 3.0) ? 1.0 : 0.0;
    float ca = (turns == 0.0) ? 1.0 : ((turns == 2.0) ? -1.0 : 0.0);
    float sa = (turns == 1.0) ? 1.0 : ((turns == 3.0) ? -1.0 : 0.0);
    float2 ax = float2(ca, sa);
    // **Negated, and determined on the device rather than from first principles.** `ay` is "down the
    // face", from brow to jaw. Built the obvious way round it pointed the other way: the chin squeeze
    // appeared on the forehead, which also means the eye magnification — placed at minus ay — had been
    // landing near the mouth this whole time. One sign, both faults. If a future change makes the warps
    // look upside down again, this is the line.
    float2 ay = -float2(-sa, ca);

    for (int i = 0; i < 3; ++i) {
        float4 f = faceAt(i);
        float2 mid = float2(f.x, f.y) * size;
        // Half extents, in pixels, in the *image's* axes. `ext` rather than `half`, which is a type here.
        float2 ext = float2(f.z, f.w) * size * (i < n ? 1.0 : 0.0);
        // The same two numbers along the face's axes: sideways, the face's width is the box's height.
        float across = mix(ext.x, ext.y, sideways);
        float down = mix(ext.y, ext.x, sideways);

        // Eyes. Guessed from the rectangle — a fifth of the face's width either side of centre, a
        // quarter of its height above the middle — because the hardware's landmarks are not available on
        // every camera and its rectangle always is. Kept gentle: the box is loose, often taking in hair
        // and forehead, so a strong magnification lands on an eyebrow as readily as an eye.
        float2 eyeL = mid - ax * (across * 0.40) - ay * (down * 0.26);
        float2 eyeR = mid + ax * (across * 0.40) - ay * (down * 0.26);
        float radius = across * 0.38;
        p = magnify(p, eyeL, radius, 1.0 + 0.55 * warp.x);
        p = magnify(p, eyeR, radius, 1.0 + 0.55 * warp.x);

        // Chin. Squeezed along the eye line, and only below the middle of the face: the amount ramps from
        // nothing at the cheekbones to full at the jaw, which is the difference between a taper and a
        // waist. Sampling *further out* pulls the picture in, so the multiplier is above one.
        if (down > 0.0 && warp.y > 0.0) {
            float2 d = p - mid;
            float lower = clamp((dot(d, ay) - down * 0.15) / (down * 1.0), 0.0, 1.0);
            float near = 1.0 - smoothstep(across * 0.9, across * 1.9, abs(dot(d, ax)));
            float k = 0.18 * warp.y * smoothstep(0.0, 1.0, lower) * near;
            p = p + ax * (dot(d, ax) * k);
        }

        // The whole head, in a little. A radial version of the same trick, falling off to nothing well
        // outside the rectangle so there is no seam at the hairline.
        float reach = max(max(ext.x, ext.y) * 1.8, 0.0001);
        float away = length(p - mid) / reach;
        if (away < 1.0 && ext.x > 0.0 && warp.z > 0.0) {
            float k = 1.0 + 0.10 * warp.z * (1.0 - smoothstep(0.0, 1.0, away));
            p = mid + (p - mid) * k;
        }
    }

    // ---- skin smoothing ----
    // **Edge-preserving, and only on skin.** A plain blur is what makes a beauty filter look like
    // a smear: it takes the eyelashes and the hairline with it. This weights each tap by how far
    // its colour is from the centre pixel — a cross-bilateral filter — so a tap that has fallen
    // off the face onto hair or background contributes almost nothing, and the edge survives while
    // the pores inside it average away.
    //
    // Two rings of six taps at different radii rather than one dense ring: the wide ring does the
    // smoothing and the tight one keeps it from banding, for twelve samples instead of the
    // twenty-five a 5x5 kernel would need.
    float3 here = tap(p);
    float smoothing = mix(0.35, 1.0, skinness(here)) * warp.w;
    float rad = unitPx() * 3.4 * smoothing;
    float3 sum = here;
    float weight = 1.0;
    for (int i = 0; i < 6; ++i) {
        float a = float(i) * 1.0471976;
        float2 dir = float2(cos(a), sin(a));
        for (int ring = 1; ring <= 2; ++ring) {
            float2 q = p + dir * rad * float(ring);
            float3 t = tap(q);
            // Colour distance decides everything. 9.0 is tuned so that skin-to-skin variation
            // passes and skin-to-anything-else does not.
            float w = exp(-dot(t - here, t - here) * 9.0) / float(ring);
            sum += t * w;
            weight += w;
        }
    }
    float3 smoothed = sum / weight;

    // Put a little of the real detail back, or the face reads as plastic rather than as a booth
    // print. Booth prints are soft, not featureless.
    float3 col = mix(smoothed, here, 0.12);
    // And keep the eyes sharp — they are the one thing a purikura wants crisp, having just doubled
    // them in size.
    float sharpness = 0.0;
    for (int i = 0; i < 3; ++i) {
        float4 f = faceAt(i);
        float2 mid = float2(f.x, f.y) * size;
        float2 ext = float2(f.z, f.w) * size;
        float2 eyeL = mid - ax * (mix(ext.x, ext.y, sideways) * 0.40) - ay * (mix(ext.y, ext.x, sideways) * 0.26);
        float2 eyeR = mid + ax * (mix(ext.x, ext.y, sideways) * 0.40) - ay * (mix(ext.y, ext.x, sideways) * 0.26);
        float reach = max(mix(ext.x, ext.y, sideways) * 0.40, 1.0);
        float near = max(
            1.0 - clamp(length(p - eyeL) / reach, 0.0, 1.0),
            1.0 - clamp(length(p - eyeR) / reach, 0.0, 1.0)
        );
        sharpness = max(sharpness, near * (i < n ? 1.0 : 0.0));
    }
    col = mix(col, here, sharpness * 0.75);

    // ---- skin blown out ----
    // From here down is the wash: the blow-out, the pink and the glitter. Switched off, what is left is
    // the smoothing and the warps — a beauty filter without the booth, which is a reasonable thing to
    // want and is why it is one switch rather than part of the filter's identity.
    float l = lum(col);
    // Lift, then crush the top: 0.55 arrives at 0.82, and 0.8 and 1.0 are nearly the same white.
    float lifted = pow(clamp(l * 1.22 + 0.16, 0.0, 1.0), 0.62);
    col = mix(col, col + (lifted - l), wash);

    // ---- pink ----
    float3 shadow = float3(1.02, 0.94, 1.02);
    float3 light = float3(1.06, 0.93, 0.95);
    col *= mix(float3(1.0), mix(shadow, light, clamp(lifted, 0.0, 1.0)), wash);
    float grey = lum(col);
    col = mix(float3(grey), col, 1.0 + 0.35 * wash);
    // Nothing is allowed to be properly black. Booth prints wash out in the shadows and that
    // missing black is half of why they look like booth prints.
    col = mix(col, float3(1.0, 0.97, 0.98), 0.10 * wash);

    // ---- glitter ----
    // On a grid so the stars keep still between frames, jittered inside their cells so the grid
    // cannot be seen, and only about one cell in twelve lights up.
    float cell = unitPx() * 26.0;
    float2 g = floor(p / cell);
    float pick = hash(g);
    float bright = 0.0;
    if (pick > 0.90) {
        float2 jitter = float2(hash(g + 3.1), hash(g + 7.7)) - 0.5;
        float2 star = (g + 0.5 + jitter) * cell;
        float2 d = abs(p - star);
        float arm = cell * 0.34 * (0.55 + 0.45 * sin(seed * 0.11 + pick * 40.0));
        // Two thin bars crossed, plus a hot centre: a four-pointed star, which is the shape a
        // booth's sparkle overlay uses and also the cheapest one to draw.
        float horiz = max(0.0, 1.0 - d.x / arm) * max(0.0, 1.0 - d.y / (arm * 0.13));
        float vert = max(0.0, 1.0 - d.y / arm) * max(0.0, 1.0 - d.x / (arm * 0.13));
        float core = max(0.0, 1.0 - length(d) / (arm * 0.22));
        bright = clamp(horiz + vert + core * 0.9, 0.0, 1.0);
    }
    // Denser where the people are, because that is where a booth puts them.
    float nearFace = 0.0;
    for (int i = 0; i < 3; ++i) {
        float4 f = faceAt(i);
        float2 mid = float2(f.x, f.y) * size;
        float2 ext = float2(f.z, f.w) * size;
        float reach = max(max(ext.x, ext.y) * 2.4, 1.0);
        float near = 1.0 - clamp(length(p - mid) / reach, 0.0, 1.0);
        nearFace = max(nearFace, near * (i < n ? 1.0 : 0.0));
    }
    col += bright * (0.55 + 0.45 * nearFace) * wash;

    // ---- a white glow in from the corners ----
    float2 q = (p / size - 0.5) * 2.0;
    float edge = clamp(length(q) - 0.72, 0.0, 1.0);
    col = mix(col, float3(1.0, 0.98, 0.99), edge * 0.55 * wash);

    return half4(float4(clamp(col, 0.0, 1.0), 1.0));
}
"""

    /**
     * The mosh, drawn: macroblocks displaced in runs along the raster.
     *
     * **What a P-frame does, done on purpose.** A macroblock in a predicted frame carries a vector
     * rather than a picture — "this block came from over there" — and an I-frame is what periodically
     * resets that. Delete the I-frame and the vector keeps being applied to pixels it was never
     * measured against, so the block is dragged across the frame, repeating, until something resets
     * it. That drag is the whole look.
     *
     * Reproduced here in three parts:
     *
     *  - **Runs, not blocks.** The frame is cut into horizontal runs several macroblocks long, a
     *    different length on every row, because a vector survives for as long as nothing resets it
     *    and how long that is has no reason to line up between rows.
     *  - **One source per run.** Every macroblock in a run samples the *same* source block, offset by
     *    that run's vector. That is what makes it a smear rather than a shuffle: the same content is
     *    repainted along the row, which is what you see in a real mosh.
     *  - **Most runs are left alone.** A mosh where everything moves is noise. Roughly a third of the
     *    runs take a vector and the rest keep the photograph, which is what leaves a subject standing
     *    in the middle of it.
     *
     * The colour bleed is the second half of the look and comes free from the same mechanism: the
     * channels are dragged by slightly different amounts, so an edge smears into fringes the way a
     * chroma-subsampled frame does when its blocks stop lining up.
     *
     * Sized off `unitPx`, so a macroblock covers the same fraction of a 340px preview and a 4000px
     * capture — without which the effect would be invisible at full resolution, which is the rule the
     * whole file is built around.
     */
    private const val DATAMOSH = """
half4 main(float2 xy) {
    // Every coordinate below is upright. A codec's macroblocks run along the *picture's* scan
    // lines, so a smear that dragged down the frame in the file and across it in the viewfinder
    // was not one look at two sizes, it was two looks. See TURN.
    float2 p = toUp(xy);

    // Sixteen pixels at preview size, and the same fraction of the frame at any other.
    float mb = max(3.0, unitPx() * 16.0);
    float row = floor(p.y / mb);

    // How long this row's runs are, in macroblocks. Long runs read as a drag; short ones as chatter.
    float runBlocks = 3.0 + floor(hash(float2(row, 11.0)) * 17.0);
    float segLen = mb * runBlocks;
    float seg = floor(p.x / segLen);
    float segStart = seg * segLen;

    float3 here = tapUp(p);
    float pick = hash(float2(seg * 3.1, row * 1.7));
    if (pick < 0.64) {
        return half4(float4(here, 1.0));
    }

    // Where in its own macroblock this pixel sits. Adding this back to the run's source block is
    // what paints the same block over and over along the run.
    float2 inBlock = p - floor(p / mb) * mb;
    float dx = (hash(float2(seg, row + 5.0)) - 0.5) * mb * 7.0;
    float dy = (hash(float2(seg + 2.0, row)) - 0.5) * mb * 1.4;
    float2 from = float2(segStart + dx, row * mb + dy) + inBlock;

    // The channels drag by slightly different amounts, which is where the fringing comes from.
    float spread = mb * 0.35 * (hash(float2(seg + 7.0, row)) - 0.5);
    float3 dragged = float3(
        tapUp(from + float2(spread, 0.0)).r,
        tapUp(from).g,
        tapUp(from - float2(spread, 0.0)).b
    );

    // Not all the way: a trace of the original under the smear is what keeps a photograph in it.
    float3 c = mix(here, dragged, 0.82);

    // The leading edge of a run stays bright, the way a freshly moshed block does before the
    // residual catches up with it.
    float edge = 1.0 - smoothstep(0.0, mb * 1.5, p.x - segStart);
    c = clamp(c + edge * 0.10, 0.0, 1.0);
    return half4(float4(c, 1.0));
}
"""

    /**
     * The datamosh.js modes, ported. [DATAMOSH] draws its own idea of the look; these are
     * the actual algorithms from the `Datamosh-js/datamosh` library, each one a fragment
     * shader that reproduces what the library does to a pixel buffer. The two modes that
     * rearrange the *buffer* rather than the pixels — `abna` reverses and re-rotates the
     * byte stream and `schifty` concatenates random chunks, both changing the buffer's
     * length — cannot be a per-pixel shader and are not here. Everything expressible
     * per-pixel is.
     */

    /**
     * The library's five luminance bands become four neon colours; a pixel is only
     * recoloured when all three of its channels agree on a band, so saturated pixels keep
     * their own colour — which is half of why the look reads as Vaporwave rather than as a
     * posterise.
     */
    private const val VAPORWAVE = """
float band(float v) {
    if (v <= 15.0) return 0.0;
    if (v <= 60.0) return 1.0;
    if (v <= 120.0) return 2.0;
    if (v <= 180.0) return 3.0;
    if (v <= 234.0) return 4.0;
    return 5.0;
}

half4 main(float2 xy) {
    float3 c = tap(xy);
    float3 b = c * 255.0;
    float b0 = band(b.r), b1 = band(b.g), b2 = band(b.b);
    if (b0 == b1 && b1 == b2) {
        float3 pal = float3(0.0, 0.0, 0.0);
        if (b0 == 1.0) pal = float3(0.0, 184.0, 255.0);
        else if (b0 == 2.0) pal = float3(255.0, 0.0, 193.0);
        else if (b0 == 3.0) pal = float3(150.0, 0.0, 255.0);
        else if (b0 == 4.0) pal = float3(0.0, 255.0, 249.0);
        else if (b0 == 5.0) pal = float3(255.0, 255.0, 255.0);
        c = pal / 255.0;
    }
    return half4(float4(c, 1.0));
}
"""

    /**
     * Four passes of the same 1.4x gain, clamps relaxing 280 → 256 → 255 in the source.
     * In 0..1 that is gain-to-white either way; written as the four passes so the constant
     * matches the library and a future tuning pass has somewhere to point.
     */
    private const val FATCAT = """
half4 main(float2 xy) {
    float3 c = tap(xy);
    c = min(c * 1.4, float3(1.0));
    c = min(c * 1.4, float3(1.0));
    c = min(c * 1.4, float3(1.0));
    c = min(c * 1.4, float3(1.0));
    return half4(float4(c, 1.0));
}
"""

    /**
     * `giveSeed` rolled one channel a gain of at least 0.3 and a second one half the time;
     * each channel is then scaled by its seed and offset with another channel's. The hash
     * calls are fixed points, so the roll is decided by `seed` and changes per capture the
     * way `Math.random()` did per run.
     */
    private const val VANA = """
float3 vanaSeed() {
    float3 s = float3(0.0, 0.0, 0.0);
    float i1 = floor(hash(float2(17.0, 19.0)) * 3.0);
    float i2 = floor(hash(float2(23.0, 29.0)) * 3.0);
    float v1 = max(hash(float2(31.0, 37.0)), 0.3);
    if (i1 == 0.0) s.x = v1; else if (i1 == 1.0) s.y = v1; else s.z = v1;
    if (i2 != i1 && hash(float2(41.0, 43.0)) > 0.5) {
        float v2 = max(hash(float2(47.0, 53.0)), 0.3);
        if (i2 == 0.0) s.x = v2; else if (i2 == 1.0) s.y = v2; else s.z = v2;
    }
    return s;
}

half4 main(float2 xy) {
    float3 s = vanaSeed();
    float3 c = tap(xy) * 255.0;
    c.r = min(c.r * s.x + 100.0 * s.z, 255.0);
    c.g = min(c.g * s.y + 100.0 * s.x, 255.0);
    c.b = min(c.b * s.z + 100.0 * s.y, 255.0);
    return half4(float4(c / 255.0, 1.0));
}
"""

    /**
     * Random per-channel high/low gates; a channel outside its gate is remapped to
     * `high - low + value * multiplier`. The library's blue line writes `data[1 + 2]` —
     * the alpha byte — which is a typo; it is ported here as the blue channel it was
     * clearly meant to be.
     */
    private const val WALTER = """
half4 main(float2 xy) {
    float3 h = float3(hash(float2(1.0, 1.0)), hash(float2(1.0, 2.0)), hash(float2(1.0, 3.0))) * 255.0;
    float3 l = float3(hash(float2(2.0, 1.0)), hash(float2(2.0, 2.0)), hash(float2(2.0, 3.0))) * 255.0;
    float mx = max(max(max(h.r, max(h.g, h.b)), max(l.r, max(l.g, l.b))), 0.0);
    float m = hash(float2(3.0, 1.0)) * (255.0 - mx);
    float3 b = tap(xy) * 255.0;
    float3 o = b;
    if (b.r < l.r || b.r > h.r) o.r = h.r - l.r + (b.r / 255.0) * m;
    if (b.g < l.g || b.g > h.g) o.g = h.g - l.g + (b.g / 255.0) * m;
    if (b.b < l.b || b.b > h.b) o.b = h.b - l.b + (b.b / 255.0) * m;
    return half4(float4(clamp(o, 0.0, 255.0) / 255.0, 1.0));
}
"""

    /**
     * Every third pixel keeps its own colours — `i % 12 == 0` on the byte index is the
     * same test as pixel % 3 == 0 — and the rest are driven to white (bright) or a random
     * pick between the pixel's own min and max (everything else). The library's `value`
     * is `0` for dark pixels and `0` is falsy in its `if (!value)`, so dark pixels take
     * the random branch too — ported as written, not as the comment in the source implies.
     * The kept pixels are what stop it reading as a plain posterise.
     */
    private const val GAZETTE = """
half4 main(float2 xy) {
    float3 c = tap(xy);
    if (mod(xy.x + xy.y * size.x, 3.0) < 1.0) {
        return half4(float4(c, 1.0));
    }
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    float L = (c.r + c.g + c.b) / 3.0;
    if (L > 0.65) return half4(1.0, 1.0, 1.0, 1.0);
    float3 q = float3(
        hash(xy) > 0.5 ? mx : mn,
        hash(xy + 7.0) > 0.5 ? mx : mn,
        hash(xy + 13.0) > 0.5 ? mx : mn);
    return half4(float4(q, 1.0));
}
"""

    /** A band-pass per channel: kept only inside (80, 165), dropped to black outside it. */
    private const val CASTLES = """
half4 main(float2 xy) {
    float3 b = tap(xy) * 255.0;
    b.r = (b.r > 80.0 && b.r < 165.0) ? b.r : 0.0;
    b.g = (b.g > 80.0 && b.g < 165.0) ? b.g : 0.0;
    b.b = (b.b > 80.0 && b.b < 165.0) ? b.b : 0.0;
    return half4(float4(b / 255.0, 1.0));
}
"""

    /**
     * Row-aligned like the library: the seed is re-rolled every few rows and the channel
     * maths wraps at 256, so each band of rows slides through its own colour.
     * [TURN]-aware for the same reason [DATAMOSH] is — "rows" are the picture's rows, and
     * the panel is portrait-locked.
     */
    private const val VENENEUX = """
half4 main(float2 xy) {
    float2 p = toUp(xy);
    float band = floor(p.y / max(1.0, upSize().y / 8.0));
    float s0 = max(hash(float2(band, 1.0)), 0.1);
    float s1 = max(hash(float2(band, 3.0)), 0.1);
    float s2 = max(hash(float2(band, 5.0)), 0.1);
    float3 b = tapUp(p) * 255.0;
    b.r = mod(b.r * s0 + 1000.0 * s2, 256.0);
    b.g = mod(b.g * s1 + 1000.0 * s1, 256.0);
    b.b = mod(b.b * s2 + 1000.0 * s0, 256.0);
    return half4(float4(b / 255.0, 1.0));
}
"""

    /**
     * The void: subtract 1..15 with wraparound at zero (mod, not clamp — the library lets
     * it roll back around to 255), a per-pixel noise flip, a darken of 20..40, and a grain
     * that puts 0..19 back on a fifth of pixels.
     */
    private const val VOID = """
half4 main(float2 xy) {
    float3 v = tap(xy) * 255.0;
    float3 n;
    n.r = hash(xy + 1.0); n.g = hash(xy + 2.0); n.b = hash(xy + 3.0);
    v = mod(v - (1.0 + n * 14.0), 255.0);
    if (hash(xy + 4.0) < 0.2) {
        v.r += 1.0 + hash(xy + 5.0) * 14.0;
        v.g += 1.0 + hash(xy + 6.0) * 9.0;
        v.b += 1.0 + hash(xy + 7.0) * 9.0;
    }
    v += hash(xy + 8.0) * 20.0 - 40.0;
    float g = hash(xy + 9.0);
    if (g < 0.4) v += floor(g * 50.0);
    return half4(float4(clamp(v, 0.0, 255.0) / 255.0, 1.0));
}
"""

    /**
     * Random byte runs along the raster, gaps twice as long as the runs. Runs here are
     * segments scaled to the frame, lit about half the time — the library's sequential
     * counter cannot survive a GPU, but its look (random bands, then clean, then random)
     * is a per-pixel coin flip on which segment is lit.
     */
    private const val BLURBOBB = """
half4 main(float2 xy) {
    float run = 16.0 * unitPx();
    float seg = floor((xy.x + xy.y * size.x) / run);
    float phase = hash(float2(seg, 7.0)) * 2.0;
    if (phase <= 1.0) {
        float3 noise = float3(hash(float2(seg, 1.0)), hash(float2(seg, 2.0)), hash(float2(seg, 3.0)));
        return half4(float4(noise, 1.0));
    }
    return half4(float4(tap(xy), 1.0));
}
"""

    /**
     * The library's three stages in one pass: random rectangles displace the pixels (the
     * coarse grid of cells below is the GPU's version of its hundred random rects, each
     * shifted by its own hash vector with one of the three rectangle modes — add, subtract,
     * or blank), a horizontal-or-vertical blur averages, and the chimera weight matrix
     * cross-mixes the channels (0.25 / 0.5). Noise, darken and grain finish it. Worked
     * upright because displacement has a direction; see [TURN].
     */
    private const val CHIMERA = """
half4 main(float2 xy) {
    float2 p = toUp(xy);
    float u = unitPx();
    float cell = u * 48.0;
    float2 g = floor(p / cell);
    float pick = hash(g);
    float mode = hash(g + 3.0);
    float3 c = tapUp(p);
    float2 disp = (float2(hash(g + 1.0), hash(g + 2.0)) - 0.5) * cell * 0.8;
    float2 q = clamp(p + disp, float2(0.0, 0.0), upSize() - float2(1.0, 1.0));
    float3 d = tapUp(q);
    if (pick < 0.55) {
        // The library's three rectangle modes: add r(5,20), subtract r(0,20), or -255 blank.
        if (mode >= 0.66) d += 0.02 + hash(g + 4.0) * 0.08;
        else if (mode >= 0.33) d -= hash(g + 5.0) * 0.08;
        else d -= 1.0;
        c = d;
    }
    float dir = step(0.5, hash(g + 6.0));
    float rad = u * 5.0;
    float2 o = mix(float2(rad, 0.0), float2(0.0, rad), dir);
    float3 bb = (tapUp(clamp(p + o, float2(0.0, 0.0), upSize() - float2(1.0, 1.0))) +
                 tapUp(clamp(p - o, float2(0.0, 0.0), upSize() - float2(1.0, 1.0)))) * 0.5;
    c = mix(c, bb, 0.35);
    c = float3(c.r + c.g * 0.5 + c.b * 0.25,
               c.r * 0.5 + c.g + c.b * 0.25,
               c.r * 0.25 + c.g * 0.5 + c.b);
    if (hash(p + 1.0) < 0.2) c += float3(hash(p + 2.0), hash(p + 3.0), hash(p + 4.0)) * 0.05;
    c += hash(p + 5.0) * 0.15 - 0.25;
    if (hash(p + 6.0) < 0.4) c += hash(p + 7.0) * 0.2;
    return half4(float4(clamp(c, 0.0, 1.0), 1.0));
}
"""

    /**
     * The library's two phases: dominant-channel runs along the scanline (the block's
     * strongest channel kept, the others zeroed — `size` in the source) with black skip
     * gaps, then vertical smears that repaint the dominant channel of a pixel a few rows
     * up down a twenty-line streak. Both worked upright; see [TURN].
     */
    private const val MANTICORE = """
float3 dominant(float3 b) {
    float mx = max(max(b.r, b.g), b.b);
    if (mx == b.r) return float3(b.r, 0.0, 0.0);
    if (mx == b.g) return float3(0.0, b.g, 0.0);
    return float3(0.0, 0.0, b.b);
}

half4 main(float2 xy) {
    float2 p = toUp(xy);
    float u = unitPx();
    float cell = max(2.0, upSize().x / 40.0);
    float2 g = floor(p / float2(cell, cell * 4.0));
    float run = hash(g);
    float3 c = tapUp(p) * 255.0;
    if (run < 0.6) {
        c = dominant(c);
    } else if (run > 0.9) {
        c = float3(0.0);
    }
    float col = floor(p.x / (cell * 3.0));
    if (hash(float2(col, 7.0)) > 0.85) {
        float y = clamp(p.y - u * 4.0, 0.0, upSize().y - 1.0);
        float3 streak = dominant(tapUp(float2(p.x, y)) * 255.0);
        c = mix(c, streak, 0.8);
    }
    return half4(float4(clamp(c / 255.0, 0.0, 1.0), 1.0));
}
"""

    /**
     * Preset: ten adjustments, and the plain photograph when they are all at zero.
     *
     * Ordered the way a darkroom would order them and not the way the data class lists them, because
     * these operations do not commute. Sharpening after a vignette sharpens the vignette's edge.
     * Grain before contrast gets its own contrast stretched. So: detail, then tone, then colour, then
     * the two things that are laid on top of the finished photograph.
     *
     * Every uniform arrives as -1..1, so the constants below are "what one end of the stepper does".
     */
    private const val PRESET = """
uniform float4 gradeA;   // exposure, contrast, highlights, shadows
uniform float4 gradeB;   // vibrance, warmth, tint, sharpness
uniform float4 gradeC;   // grain, vignette, unused, unused

half4 main(float2 xy) {
    float3 c = tap(xy);

    // ---- sharpness ----
    // One control, both directions: an unsharp mask above zero and a plain blur below it. The blur
    // is the same four taps read the other way round, so softening costs exactly what sharpening
    // does and there is no second code path to keep honest.
    float sh = gradeB.w;
    if (sh != 0.0) {
        float u = unitPx();
        float3 blur = (tap(xy + float2(u, 0.0)) + tap(xy - float2(u, 0.0)) +
                       tap(xy + float2(0.0, u)) + tap(xy - float2(0.0, u))) * 0.25;
        c = sh > 0.0 ? c + (c - blur) * (sh * 1.7) : mix(c, blur, -sh * 0.85);
    }

    // ---- exposure ----
    // In stops, because that is the unit the number means something in: the top of the stepper is
    // a stop and a half, which is the range a photograph is recoverable across.
    c *= pow(2.0, gradeA.x * 1.5);

    // ---- highlights and shadows ----
    // Weighted by where the pixel already sits, with the two windows overlapping only in the
    // midtones — otherwise the two controls fight over the middle and each one undoes the other.
    //
    // The direction trick: pushing up moves a pixel toward white by a fraction of the room it has
    // left (1 - c), pushing down moves it toward black by a fraction of what it has (c). Both are
    // asymptotic, so neither can clip, and nothing ever crosses over.
    float l = lum(c);
    float hiW = smoothstep(0.42, 1.0, l);
    float loW = 1.0 - smoothstep(0.0, 0.58, l);
    float hi = gradeA.z;
    float lo = gradeA.w;
    c += hi * hiW * 0.50 * mix(c, 1.0 - c, step(0.0, hi));
    c += lo * loW * 0.55 * mix(c, 1.0 - c, step(0.0, lo));

    // ---- contrast ----
    // Pivoted on mid grey rather than on the frame's own average: an average-pivoted curve makes
    // the same slider do different things to a snow scene and a night one, which is not what the
    // person turning it expects.
    c = (c - 0.5) * (1.0 + gradeA.y * 0.65) + 0.5;

    // ---- warmth and tint ----
    // The two axes a white balance actually has. Warmth trades red against blue; tint trades green
    // against the other two, which is the magenta direction.
    float w = gradeB.y;
    c.r *= 1.0 + w * 0.20;
    c.b *= 1.0 - w * 0.20;
    float t = gradeB.z;
    c.g *= 1.0 - t * 0.15;
    c.r *= 1.0 + t * 0.07;
    c.b *= 1.0 + t * 0.07;

    // ---- vibrance ----
    // **Not saturation, and the difference is the whole reason this control is the one here.**
    // Saturation multiplies everything, so the already-loud parts of a frame go first and skin goes
    // orange. Vibrance is weighted twice: by how much saturation a pixel is *missing*, so the dull
    // parts move and the vivid parts are left alone, and by how much the pixel looks like skin, so
    // a face keeps its colour while the sky behind it comes up.
    float3 lit = clamp(c, 0.0, 1.0);
    float mx = max(lit.r, max(lit.g, lit.b));
    float mn = min(lit.r, min(lit.g, lit.b));
    float sat = mx - mn;
    // Skin: red above green above blue, with a red-to-blue spread in a narrow band. Crude, and
    // crude is correct — it only has to hold back a fraction of the effect, not make a matte.
    float ordered = step(lit.b, lit.g) * step(lit.g, lit.r);
    float spread = clamp(1.0 - abs((lit.r - lit.b) * 3.4 - 0.62) * 1.8, 0.0, 1.0);
    float skin = ordered * spread;
    float room = 1.0 - clamp(sat * 1.7, 0.0, 1.0);
    float amount = gradeB.x * (0.15 + 0.85 * room) * (1.0 - skin * 0.65);
    float g = lum(c);
    c = mix(float3(g, g, g), c, 1.0 + amount * 1.15);

    // ---- grain ----
    // Modulated by the midtones, the same as Film's: silver clumps where there is something to
    // clump in, and flat noise over the whole frame reads as a bad sensor rather than as film.
    if (gradeC.x > 0.0) {
        float n = hash(floor(xy / unitPx())) - 0.5;
        c += n * gradeC.x * 0.18 * (1.0 - abs(lum(c) - 0.5) * 1.4);
    }

    // ---- vignette ----
    // Negative brightens the corners, which is not a thing lenses do but is a thing people want
    // when a photograph has gone dark at the edges and they would like it not to be.
    float2 d = xy / size - 0.5;
    c *= 1.0 - dot(d, d) * gradeC.y * 1.25;

    return half4(float4(clamp(c, 0.0, 1.0), 1.0));
}
"""

    /**
     * A filter, as the rest of the app sees it.
     *
     * [agsl] is null for [none] only. [animated] marks the ones whose look depends on
     * `seed`, so the preview re-applies them a few times a second and the grain crawls the
     * way it does on a projector; everything else is applied once and left alone.
     *
     * [lowRes] marks the ones that quantise the image onto a coarse grid of their own — the dithers,
     * the halftone, the two Game Boys. **There is nothing for a sensor capture to give these.** A
     * 12MP frame and a panel-sized one both come out of a 160-cell dither as the same picture, so
     * these always take the viewfinder frame instead: instant, silent, and exactly what you were
     * looking at when you pressed.
     *
     * [facesAware] marks the one that is handed the detected faces as uniforms. It also takes the
     * viewfinder frame, for a different and stricter reason: the faces are detected **in the
     * preview**, and a photograph made from a second, differently-cropped frame would have to have
     * those rectangles mapped across — which is exactly the arithmetic that puts an enlarged eye
     * next to somebody's ear. Filtering the frame the faces were found in cannot be misaligned.
     */
    data class Filter(
        val id: String,
        val label: String,
        val agsl: String?,
        val animated: Boolean = false,
        val lowRes: Boolean = false,
        val facesAware: Boolean = false,
        /**
         * Marks the one filter whose look is not fixed: Preset, which is handed the ten
         * adjustments as uniforms. See [forGrade] for why it is a second `Filter` sharing an id
         * with [none] rather than a flag on [none] itself.
         */
        val adjustable: Boolean = false,
        /**
         * Marks the filters whose output has no colour of its own.
         *
         * Read by the date back, and by nothing else. The stamp is drawn **after** the filter on
         * purpose — a date back printed through the film gate puts the date on the emulsion rather
         * than under it, and dithering the digits along with the picture turns them into confetti —
         * so an amber dot-matrix date was landing at full colour on a black-and-white photograph
         * (light-reports#25). This is what lets the stamp desaturate itself without the filter
         * having to run over it.
         *
         * **Greyscale output only.** Game Boy and X-Ray are not here: they have a colour of their
         * own, and a white date on a Game Boy's green is not more correct than an amber one, only
         * different. The ask was black and white on black and white.
         */
        val mono: Boolean = false,
        /**
         * The look has a left and a right, so it is told which way up the world is. See [TURN].
         *
         * A flag rather than something every shader gets, for the same reason [facesAware] is
         * one: setting a uniform a shader does not declare throws, and SkSL strips a uniform that
         * nothing reads. Only the filters that use `turn` may be handed it.
         */
        val turnAware: Boolean = false,
        /**
         * The ten adjustments, carried on the filter rather than passed beside it.
         *
         * **This is why nothing between the shutter and the shader had to change.** A grade is
         * per-photograph state, the same as which filter is on, and the capture path already
         * carries a `Filter` from the view model through `Frames.process` and into
         * `ShaderRuntime`. Adding a parallel `Grade` parameter to each of those would have been
         * four signatures and four call sites able to disagree with each other. Only [preset]
         * ever has a non-neutral one.
         */
        val grade: Grade = Grade.NEUTRAL,
    ) {
        /** The whole shader, prelude included. */
        val source: String? get() = agsl?.let { PRELUDE + (if (turnAware) TURN else "") + it }
    }

    /**
     * The first slot on the dial, with nothing set.
     *
     * Still a null shader, and that matters more than the name: `agsl == null` is what the capture
     * path reads as "write the camera's own JPEG, untouched" — no decode, no GPU, no re-encode. A
     * neutral Preset has to be exactly that photograph, so it has to be exactly this filter.
     */
    val none = Filter("none", "Preset", null)

    /**
     * The same slot with adjustments on it.
     *
     * **Deliberately shares [none]'s id.** The id is what the wheel positions itself by, what is
     * written to preferences and what "the app asked for plain" clears to — and by every one of those measures this is the same slot, not a nineteenth filter. What
     * differs is only whether there is a shader to run, which is decided per photograph by whether
     * the grade is neutral. So there are two `Filter` values for one dial position and [forGrade]
     * picks between them; nothing else in the app has to know.
     *
     * It is not in [all]: putting it there would give the dial two Presets to walk through.
     */
    val preset = Filter("none", "Preset", PRESET, adjustable = true)

    /**
     * Datamosh, and it is a shader now.
     *
     * **Three releases were spent trying to get this out of the file instead of out of the pixels,
     * and the approach could not have worked.** v2.46 through v2.51 datamoshed the encoded JPEG —
     * quantisation tables, Huffman tables, the entropy stream — on the reasoning, written into the
     * old `Databend`, that "you cannot get them by drawing, only by breaking". That is true of *JPEG*
     * artifacts. It is not true of the thing people mean by datamoshing, and the two had been
     * conflated.
     *
     * Datamoshing is a **video** technique. You delete an I-frame, and the P-frames that follow — which
     * carry only *motion*, "this macroblock came from over there" — get applied to whatever pixels
     * happened to be in the reference buffer. The frame melts along the motion of a scene it does not
     * belong to. Every tool that does it, Datamosher-Pro included, works on video for that reason.
     *
     * A JPEG has no motion vectors. There is nothing in the file that says where a block came from,
     * so there is nothing to misapply, and breaking the entropy stream cannot produce the effect
     * however hard it is broken. What it produces instead is a broken *DC difference chain*: every
     * block's average is stored relative to the one before it, so one bad seam recolours everything
     * below it in raster order. Flat coloured bands over an untouched photograph — which is exactly
     * what got reported, three times, and what the last three releases kept re-tuning.
     *
     * So this draws the motion. Blocks are displaced in **runs** along the raster, every macroblock
     * in a run painted from the same source, which is what an un-reset motion vector does — a smear
     * that drags sideways and repeats. It scales with the frame through [PRELUDE]'s `unitPx`, so a
     * macroblock is the same fraction of the picture in a preview and in a 12MP capture.
     *
     * **And it previews.** The old one could not, by construction: there was no compressed file to
     * damage until the shutter had already been pressed, so you framed blind and found out afterwards.
     */
    val datamosh = Filter("datamosh", "Datamosh", DATAMOSH, turnAware = true)

    /**
     * A selectable datamosh look, and its shader.
     *
     * Datamosh is one dial position whose look is picked inside it, the way Purikura is one
     * dial position whose frame, date and stickers are picked inside it. Each of these is a
     * `Filter` with [Filter.id] `"datamosh"` so the wheel, the preferences and the capture
     * path all keep treating it as the same slot — only the shader that slot runs differs.
     */
    data class MoshMode(
        val id: String,
        val label: String,
        val agsl: String,
        val turnAware: Boolean = false,
    )

    /**
     * The looks Datamosh can be. First is the classic smear [DATAMOSH] itself, then the
     * datamosh.js mode library as ported shaders. `abna` and `schifty` are not here: they
     * rearrange the *buffer* rather than the pixels and cannot be a per-pixel shader.
     */
    val moshModes: List<MoshMode> = listOf(
        MoshMode("classic", "Classic", DATAMOSH, turnAware = true),
        MoshMode("vaporwave", "Vaporwave", VAPORWAVE),
        MoshMode("fatcat", "Fatcat", FATCAT),
        MoshMode("vana", "Vana", VANA),
        MoshMode("walter", "Walter", WALTER),
        MoshMode("gazette", "Gazette", GAZETTE),
        MoshMode("castles", "Castles", CASTLES),
        MoshMode("veneneux", "Veneneux", VENENEUX, turnAware = true),
        MoshMode("void", "Void", VOID),
        MoshMode("blurbobb", "Blurbobb", BLURBOBB),
        MoshMode("chimera", "Chimera", CHIMERA, turnAware = true),
        MoshMode("manticore", "Manticore", MANTICORE, turnAware = true),
    )

    fun moshById(id: String?): MoshMode = moshModes.firstOrNull { it.id == id } ?: moshModes.first()

    /**
     * The datamosh slot wearing the chosen [MoshMode], or [filter] unchanged.
     *
     * The same shape as [forGrade]: the dial position's identity is fixed, and the shader it
     * runs is resolved wherever a filter is about to be rendered. Everything downstream — the
     * viewfinder, the shutter, the filter grid — keeps treating it as one opaque `Filter`.
     */
    fun forMosh(filter: Filter, mode: MoshMode): Filter =
        if (filter.id == datamosh.id) {
            Filter("datamosh", "Datamosh", mode.agsl, turnAware = mode.turnAware)
        } else {
            filter
        }

    /**
     * Which filter to actually run.
     *
     * The only place the [none] / [preset] pair is resolved. Call it wherever a filter is about to
     * be rendered — the viewfinder, the shutter, the filter grid — and everything downstream can go
     * on treating a filter as one opaque thing.
     */
    fun forGrade(filter: Filter, grade: Grade): Filter =
        if (filter.id == none.id && !grade.isNeutral) preset.copy(grade = grade) else filter

    /**
     * Order matters: this is the order the wheel and a sideways swipe walk through, so it
     * runs from the ones you would actually shoot with to the ones you would not.
     *
     * **[datamosh] is the one entry placed by arithmetic rather than by taste.** It used to sit last,
     * which on a dial that wraps put it exactly *one notch backwards* from Preset — so reaching for
     * the plain photograph and overshooting by a single click landed on the one filter that
     * deliberately damages the file, and it was being switched on by accident (light-reports#27).
     *
     * It is now at index 11 of twenty-two, which is as far from Preset as any entry can be: eleven
     * notches forwards and eleven back. Nothing else moved, and the wrap stayed — a physical dial
     * should never dead-end, and special-casing the step to refuse one neighbour would have made
     * the wheel feel broken to fix an ordering problem. The datamosh.js modes are not dial
     * positions of their own — Datamosh is one slot and the look is picked inside it, the way
     * Purikura's frame and date are — so they add nothing to this walk, and the one entry stays
     * exactly where it is.
     */
    val all: List<Filter> = listOf(
        none,
        Filter("film", "Film", FILM, animated = true),
        Filter("mono", "Mono", MONO, mono = true),
        Filter("dither16", "Dither 16", DITHER16, lowRes = true),
        Filter("dither32", "Dither 32", DITHER32, lowRes = true),
        Filter("dithergrey", "Dither BW", DITHER_GREY, lowRes = true, mono = true),
        Filter("onebit", "1-Bit", ONE_BIT, lowRes = true, mono = true),
        Filter("halftone", "Halftone", HALFTONE, lowRes = true, mono = true),
        Filter("gameboy", "Game Boy", GAMEBOY, lowRes = true),
        Filter("gbcolor", "GB Color", GB_COLOR, lowRes = true),
        Filter("comic", "Comic", COMIC),
        datamosh,
        Filter("purikura", "Purikura", PURIKURA, animated = true, facesAware = true),
        Filter("thermal", "Thermal", THERMAL),
        Filter("xray", "X-Ray", X_RAY),
        Filter("glow", "Glow", GLOW),
        Filter("twirl", "Twirl", TWIRL),
        Filter("bulge", "Bulge", BULGE),
        Filter("fisheye", "Fisheye", FISHEYE),
        Filter("mirror", "Mirror", MIRROR, turnAware = true),
        Filter("kaleido", "Kaleido", KALEIDO, turnAware = true),
        Filter("tunnel", "Tunnel", TUNNEL),
    )

    fun byId(id: String?): Filter = all.firstOrNull { it.id == id } ?: none

    fun indexOf(filter: Filter): Int = all.indexOfFirst { it.id == filter.id }.coerceAtLeast(0)

    /**
     * Whether the wheel may leave the filter track while this filter is on.
     *
     * Film and Mono are looks, not worlds: a colour grade and a desaturation leave the scene's
     * exposure and framing alone, so EV, zone focus and zoom still mean something underneath them.
     * Every other filter takes the photograph over — the low-res ones, the distorting ones, the
     * datamosh — and offering those channels under a look you cannot see through is a control
     * that lies. The wheel stays on the filter until one of the plain looks is back.
     */
    fun keepsOtherChannels(filter: Filter): Boolean =
        filter.id == none.id || filter.id == "film" || filter.id == "mono"

    /**
     * The dial as the user has arranged it: their order, minus what they switched off.
     *
     * [all] is the catalog and stays the catalog — this is the view of it that the wheel turns
     * through and the grid draws. Twenty-two filters is a lot to spin past to reach the four you
     * actually shoot, and the ones you never use are not neutral: they are what stands between
     * you and the one you want, on a dial with no way to jump.
     *
     * Two rules, and the second is the one that matters:
     *
     *  1. **[none] can never be switched off.** [byId] falls back to it, the capture path forces
     *     it, and Video, Simple and Reader modes are it. A dial that could lose it would be a
     *     camera that could not take a plain photograph.
     *  2. **A filter the saved order has never heard of goes on the end rather than vanishing.**
     *     The order is stored as ids, so the alternative is that shipping a new filter hides it
     *     from everyone who has ever touched this screen — the update arrives and nothing
     *     happens, which is indistinguishable from a broken update.
     *
     * An empty [order] means "never arranged", which is [all] in the order it was written.
     */
    fun ordered(order: List<String>, off: Set<String>): List<Filter> {
        val known = all.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val arranged = mutableListOf<Filter>()
        order.forEach { id -> known[id]?.let { if (seen.add(id)) arranged += it } }
        all.forEach { if (seen.add(it.id)) arranged += it }
        return arranged.filter { it.id == none.id || it.id !in off }
    }

    /**
     * Move one filter by [by] places, and hand back an order that names every filter.
     *
     * Deliberately reordering the **full** catalog rather than the visible list: positions have
     * to survive being switched off and on again, and an order that only recorded what was
     * showing would shuffle everything else the moment you hid something.
     *
     * Clamped rather than wrapped. The wheel wraps because it is a dial; a list you are editing
     * with two arrows does not, or the top item leaps to the bottom under your thumb.
     */
    fun move(order: List<String>, id: String, by: Int): List<String> {
        val ids = ordered(order, emptySet()).map { it.id }.toMutableList()
        val at = ids.indexOf(id)
        if (at < 0) return ids
        val to = (at + by).coerceIn(0, ids.size - 1)
        if (to == at) return ids
        ids.removeAt(at)
        ids.add(to, id)
        return ids
    }

    /**
     * Stepping wraps, because a physical dial should never dead-end.
     *
     * [within] is the arranged dial, defaulting to the whole catalog. A filter that is current
     * but no longer on the dial — you switched it off with it selected — is not an error: the
     * next turn lands on the end of the list you turn toward, rather than refusing to move.
     */
    fun step(from: Filter, by: Int, within: List<Filter> = all): Filter {
        if (within.isEmpty()) return none
        val size = within.size
        val here = within.indexOfFirst { it.id == from.id }
        if (here < 0) return if (by >= 0) within.first() else within.last()
        val next = ((here + by) % size + size) % size
        return within[next]
    }

}
