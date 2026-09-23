package com.gios.lightcamera.video

import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.filter.Grade

/**
 * What a video can look like, and the GLSL that makes it look that way.
 *
 * Two kinds, and they get along because they share one frame of reference:
 *
 *  - **Ported** looks are the photo filters, carried across by [GlslPort]. Film in Video is the
 *    same shader as Film in Pro, compiled for a different GPU API, so a clip and a still shot a
 *    minute apart come out matching.
 *  - **Native** looks exist only here, because each of them is about *time*: a frame that remembers
 *    the last one (Trails, Motion), a frame that decides whether to change at all (Super 8, Stop
 *    motion), a frame made of other frames (Slit-scan), or a frame dragged along by its neighbour's
 *    motion (Datamosh). A photograph has no neighbours, which is why none of these could ever have
 *    been a photo filter.
 *
 * Every look is a fragment shader over the **upright panel frame** — the picture exactly as the
 * viewfinder shows it, portrait, top at the top — so a ported filter sees the same geometry it
 * sees on the preview. Native looks that have a horizontal (a VHS tracking line, a CCTV caption,
 * the rows of a slit-scan) convert to the *world's* upright through the same `turn` the photo
 * filters use, because a phone held sideways is recording a sideways world and a caption that
 * runs down the side of the finished clip is a bug.
 *
 * Pure Kotlin on purpose: `VideoShadersTest` walks every entry and hands its source to
 * `glslangValidator` in CI, so a shader typo is a red check rather than a black viewfinder.
 */
object VideoLooks {

    /**
     * One entry on the Video dial.
     *
     * @property glsl the main pass, a complete GLSL ES 3.00 fragment shader.
     * @property prePass an optional first pass drawn at [prePassDiv] of the frame size into the
     *   texture the main pass reads as `uAux`. Datamosh's motion search is the only user.
     * @property history how many past frames to keep, as layers of `uHist`, at [historyEdge] pixels
     *   on the long edge. Zero keeps none and costs nothing.
     * @property photoId the photo filter this was ported from, so switching one off on the photo
     *   dial takes it off this one too — one set of preferences about which looks you use.
     */
    data class Look(
        val id: String,
        val label: String,
        val glsl: String,
        val prePass: String? = null,
        val prePassDiv: Int = 16,
        val history: Int = 0,
        val historyEdge: Int = 640,
        val photoId: String? = null,
        val adjustable: Boolean = false,
        val grade: Grade = Grade.NEUTRAL,
    ) {
        /** True for the look that draws the camera straight through: no pass, no cost. */
        val plain: Boolean get() = glsl.isEmpty()
    }

    /* ------------------------------------------------------------------ */
    /*  The shared prelude for native looks                                */
    /* ------------------------------------------------------------------ */

    /**
     * Everything a native look can read.
     *
     * A uniform the shader does not use is optimised out and its location comes back -1, and GL
     * ignores a write to -1 — unlike AGSL, where setting an undeclared uniform throws. So the
     * processor sets every one of these on every look without asking which ones it declares.
     *
     * Pixel coordinates are **top-left, y down**, the same as AGSL's, so the helpers read the same
     * as the photo filters' `tap`. `toUp`/`fromUp` are the photo filters' own, copied verbatim.
     */
    const val NATIVE_PRELUDE = GlslPort.VERSION + """
uniform sampler2D uSrc;
uniform sampler2D uLast;
uniform sampler2D uPrev;
uniform sampler2D uAux;
uniform highp sampler2DArray uHist;
uniform vec2 size;
uniform float seed;
uniform float time;
uniform float dt;
uniform float turn;
uniform float fresh;
uniform float histHead;
uniform float histLen;
uniform float histSize;
uniform vec4 uClock;
out vec4 fragColor;

vec2 uvOf(vec2 p) { return vec2(p.x, size.y - p.y) / size; }
vec3 tap(vec2 p) { return texture(uSrc, uvOf(p)).rgb; }
vec3 last(vec2 p) { return texture(uLast, uvOf(p)).rgb; }
vec3 prev(vec2 p) { return texture(uPrev, uvOf(p)).rgb; }

float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float unitPx() { return max(1.0, size.y / 640.0); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + seed) * 43758.5453); }
float hash1(float x) { return fract(sin(x * 91.3458 + 17.17) * 47453.5453); }

vec2 upSize() { return (mod(turn, 2.0) == 1.0) ? vec2(size.y, size.x) : size; }
vec2 toUp(vec2 p) {
    float t = mod(turn, 4.0);
    if (t == 1.0) return vec2(size.y - p.y, p.x);
    if (t == 2.0) return vec2(size.x - p.x, size.y - p.y);
    if (t == 3.0) return vec2(p.y, size.x - p.x);
    return p;
}
vec2 fromUp(vec2 q) {
    float t = mod(turn, 4.0);
    if (t == 1.0) return vec2(q.y, size.y - q.x);
    if (t == 2.0) return vec2(size.x - q.x, size.y - q.y);
    if (t == 3.0) return vec2(size.x - q.y, q.x);
    return q;
}
vec3 tapUp(vec2 q) { return tap(fromUp(q)); }

// Whether this frame starts a new picture at [fps], for the looks that hold frames. Time-based
// rather than counted, because the camera's frame rate is the camera's to choose.
bool newFrameAt(float fps) { return fresh > 0.5 || floor(time * fps) != floor((time - dt) * fps); }
"""

    /** Wraps a native look's `vec3 look(vec2 xy)` into a complete shader. */
    private fun native(body: String): String = NATIVE_PRELUDE + body + """
void main() {
    vec2 xy = vec2(gl_FragCoord.x, size.y - gl_FragCoord.y);
    fragColor = vec4(clamp(look(xy), 0.0, 1.0), 1.0);
}
"""

    /* ------------------------------------------------------------------ */
    /*  Native looks                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * A tape that has been played too many times.
     *
     * The parts are the real ones, in the order the signal suffered them. **Chroma is carried at a
     * fraction of luma's bandwidth** on VHS — about a tenth — so colour is blurred along the line
     * and lands late, while the picture's shapes stay comparatively sharp; that is what YIQ is
     * doing here. The tracking wobble is a slow sway plus per-line jitter, the head-switching noise
     * lives in the bottom few lines where the drum changed heads, and now and then a tracking band
     * rolls down the frame tearing the lines it crosses. All of it measured in *lines of a 480-line
     * picture* of the world, not in pixels of the panel, so it is the same tape at every size and
     * every way up.
     */
    private val VHS = native(
        """
vec3 look(vec2 xy) {
    vec2 U = upSize();
    vec2 q = toUp(xy);
    float u = U.y / 480.0;
    float line = floor(q.y / u);
    float tick = floor(time * 30.0);
    float wob = sin(q.y / U.y * 12.566 + time * 1.3) * 1.2 + (hash1(line + tick * 0.37) - 0.5) * 0.9;
    float headZone = smoothstep(U.y * 0.955, U.y, q.y);
    float head = headZone * (hash1(tick * 7.0 + line) * 18.0 + 6.0);
    float bandPos = fract(time * 0.07) * 1.4 - 0.2;
    float bd = (q.y / U.y - bandPos) * 18.0;
    float band = exp(-bd * bd);
    float shift = (wob + head + band * (hash1(line * 1.7 + tick) - 0.5) * 28.0) * u;
    vec2 qs = vec2(q.x - shift, q.y);
    vec3 W = vec3(0.299, 0.587, 0.114);
    float Y = dot(tapUp(qs), W) * 0.5 +
              (dot(tapUp(qs - vec2(u, 0.0)), W) + dot(tapUp(qs + vec2(u, 0.0)), W)) * 0.25;
    vec3 cc = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        cc += tapUp(qs + vec2((float(i) - 1.5) * 2.5 * u - 3.0 * u, 0.0));
    }
    cc /= 6.0;
    float I = dot(cc, vec3(0.596, -0.274, -0.322)) * 1.1;
    float Q = dot(cc, vec3(0.211, -0.523, 0.312)) * 1.1;
    vec3 rgb = vec3(Y + 0.956 * I + 0.621 * Q, Y - 0.272 * I - 0.647 * Q, Y - 1.106 * I + 1.703 * Q);
    rgb = rgb * 0.86 + 0.06;
    rgb = mix(rgb, rgb * vec3(1.03, 1.0, 0.94), 0.8);
    float n = hash(vec2(floor(q.x / (u * 1.5)), line + tick * 13.0)) - 0.5;
    rgb += n * (0.05 + band * 0.30 + headZone * 0.35);
    rgb *= 0.94 + 0.06 * sin(q.y / u * 3.14159);
    return rgb;
}
""",
    )

    /**
     * Home movies, 1974.
     *
     * **The cadence is the look more than the colour is.** Super 8 ran at 18 frames a second, so
     * this holds each picture for as long as the film would have — a new frame only when 1/18 s has
     * passed — and repeats the last one from `uPrev` in between. The camera's 30 fps survives
     * underneath, which is what makes the movement stutter the way projected film does rather than
     * the way a slow camera does.
     *
     * Each new frame also gets what the gate did to it: a little weave (the film never sat in
     * exactly the same place twice, and more vertically than sideways), a flicker in exposure,
     * grain that is new every frame, a speck of dust now and then, a warm reversal-stock grade,
     * a light leak that drifts along one edge, and the rounded corners of the gate.
     */
    private val SUPER8 = native(
        """
vec3 look(vec2 xy) {
    if (!newFrameAt(18.0)) return prev(xy);
    float f = floor(time * 18.0);
    vec2 U = upSize();
    vec2 q = toUp(xy);
    float u = U.y / 480.0;
    vec2 weave = vec2(hash1(f * 3.1) - 0.5, hash1(f * 7.7) - 0.5) * vec2(1.6, 3.8) * u;
    vec2 qs = q + weave;
    vec3 c = tapUp(qs) * 0.4 +
        (tapUp(qs + vec2(u, 0.0)) + tapUp(qs - vec2(u, 0.0)) +
         tapUp(qs + vec2(0.0, u)) + tapUp(qs - vec2(0.0, u))) * 0.15;
    c = pow(max(c, vec3(0.0)), vec3(0.95, 1.0, 1.12)) * vec3(1.08, 1.0, 0.86);
    float L = lum(c);
    c = mix(vec3(L), c, 1.18);
    c = (c - 0.5) * 1.12 + 0.5;
    c *= 0.93 + 0.07 * hash1(f * 1.3);
    float g = hash(floor(q / (u * 1.3)) + vec2(f)) - 0.5;
    c += g * 0.13 * (1.0 - abs(L - 0.5) * 1.2);
    vec2 uv = q / U;
    float drift = sin(time * 0.35) * 0.5 + 0.5;
    vec2 lp = (uv - vec2(1.05, 0.15 + 0.7 * drift)) * vec2(1.7, 1.0);
    float leak = exp(-dot(lp, lp) * 3.0) * (0.22 + 0.2 * sin(time * 0.9));
    c += vec3(1.0, 0.45, 0.15) * max(leak, 0.0);
    for (int i = 0; i < 3; i++) {
        float k = float(i);
        if (hash1(f * 5.0 + k) > 0.6) {
            vec2 p = vec2(hash1(f * 11.0 + k), hash1(f * 17.0 + k * 3.0)) * U;
            float r = u * (1.0 + 2.5 * hash1(f + k * 9.0));
            c = mix(c, vec3(0.04), smoothstep(1.0, 0.35, length(q - p) / r));
        }
    }
    vec2 d = uv - 0.5;
    c *= 1.0 - dot(d, d) * 0.95;
    vec2 e = abs(d) * 2.0;
    float corner = length(max(e - vec2(0.90, 0.87), 0.0));
    c *= smoothstep(0.13, 0.07, corner);
    return c;
}
""",
    )

    /**
     * Movement leaves a wake.
     *
     * Two rules together, because either alone is a worse look. A running blend with the last
     * output (`mix`) ghosts anything that moves, and on a still scene converges back to the scene,
     * so nothing smears that did not move. A decaying maximum keeps **light** hanging in the air
     * for about a quarter of a second — car headlights, a phone screen, a sparkler — which is what
     * people are really after when they ask for trails.
     */
    private val TRAILS = native(
        """
vec3 look(vec2 xy) {
    vec3 c = tap(xy);
    if (fresh > 0.5) return c;
    vec3 p = prev(xy);
    float keep = pow(0.80, dt * 30.0);
    float glow = pow(0.93, dt * 30.0);
    return max(mix(c, p, keep), p * glow);
}
""",
    )

    /**
     * Claymation. The picture changes eight times a second, and each time it lands a hair off
     * where the last one was, a touch brighter or darker, the way hand-posed frames on a real
     * rostrum do.
     */
    private val STOP_MOTION = native(
        """
vec3 look(vec2 xy) {
    if (!newFrameAt(8.0)) return prev(xy);
    float f = floor(time * 8.0);
    float u = unitPx();
    vec2 jit = (vec2(hash1(f * 2.3), hash1(f * 5.9)) - 0.5) * u * 2.0;
    vec3 c = tap(xy + jit);
    c *= 0.96 + 0.08 * hash1(f * 3.7);
    c = (c - 0.5) * 1.06 + 0.5;
    return c;
}
""",
    )

    /**
     * Only what moves.
     *
     * The difference between this frame and the last, lifted and laid on black, with each mark
     * fading over a few frames so a gesture draws itself rather than flickering. A faint ghost of
     * the edges stays in so you can still see what you are pointing at — without it the frame is
     * black until something moves, and a black viewfinder reads as a broken camera.
     */
    private val MOTION = native(
        """
vec3 look(vec2 xy) {
    vec3 c = tap(xy);
    if (fresh > 0.5) return vec3(0.0);
    float m = clamp(length(c - last(xy)) * 3.5 - 0.06, 0.0, 1.0);
    float u = unitPx();
    float edge = abs(lum(c) - lum(tap(xy + vec2(u, 0.0)))) + abs(lum(c) - lum(tap(xy + vec2(0.0, u))));
    vec3 mark = mix(vec3(0.35, 0.8, 1.0), vec3(1.0), m) * m;
    vec3 trail = prev(xy) * pow(0.82, dt * 30.0);
    return max(max(mark, trail), vec3(clamp(edge * 1.4, 0.0, 0.16)));
}
""",
    )

    /**
     * The shop camera over the till.
     *
     * Twelve frames a second, a cheap wide lens bowing the edges, a greenish mono tube, rolling
     * interference, and the caption every one of these cameras burns into its picture — **the real
     * date and time**, handed in through `uClock` so a clip is stamped with when it was shot, in the
     * corner of the world rather than the corner of the panel. The digits are a 3×5 font packed
     * into integers, because a shader has no font and a real one would be a texture upload for the
     * sake of twenty characters.
     */
    private val CCTV = native(
        """
const int GLYPHS[18] = int[18](
    31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695,
    448, 1040, 31015, 31725, 24557, 27565, 31207, 0);

int two(int n, int i) { return i == 0 ? (n / 10) % 10 : n % 10; }

int glyphAt(int i) {
    int y = int(uClock.x); int mo = int(uClock.y); int d = int(uClock.z);
    int s = int(uClock.w); int hh = s / 3600; int mm = (s / 60) % 60; int ss = s % 60;
    if (i == 0) return 12; if (i == 1) return 13; if (i == 2) return 14; if (i == 3) return 17;
    if (i == 4) return 0; if (i == 5) return 1; if (i == 6 || i == 7) return 17;
    if (i >= 8 && i <= 11) { int p = 11 - i; int v = y; for (int k = 0; k < 3; k++) { if (k < p) v /= 10; } return v % 10; }
    if (i == 12) return 10; if (i == 13) return two(mo, 0); if (i == 14) return two(mo, 1);
    if (i == 15) return 10; if (i == 16) return two(d, 0); if (i == 17) return two(d, 1);
    if (i == 18 || i == 19) return 17;
    if (i == 20) return two(hh, 0); if (i == 21) return two(hh, 1); if (i == 22) return 11;
    if (i == 23) return two(mm, 0); if (i == 24) return two(mm, 1); if (i == 25) return 11;
    if (i == 26) return two(ss, 0); if (i == 27) return two(ss, 1);
    return 17;
}

float caption(vec2 q, vec2 U) {
    float px = floor(U.y / 150.0);
    vec2 o = vec2(px * 4.0, px * 4.0);
    vec2 c = floor((q - o) / px);
    if (c.x < 0.0 || c.y < 0.0 || c.y > 4.0) return 0.0;
    int ch = int(floor(c.x / 4.0));
    int col = int(mod(c.x, 4.0));
    if (ch > 27 || col > 2) return 0.0;
    int bits = GLYPHS[glyphAt(ch)];
    int bit = (4 - int(c.y)) * 3 + (2 - col);
    return float((bits >> bit) & 1);
}

vec3 look(vec2 xy) {
    if (!newFrameAt(12.0)) return prev(xy);
    vec2 U = upSize();
    vec2 q = toUp(xy);
    vec2 uv = q / U - 0.5;
    float r2 = dot(uv * vec2(U.x / U.y, 1.0), uv * vec2(U.x / U.y, 1.0));
    vec2 src = (uv * (1.0 - 0.22 * r2) * 0.93 + 0.5) * U;
    float g = lum(tapUp(src));
    g = clamp((g - 0.5) * 1.25 + 0.52, 0.0, 1.0);
    float line = floor(q.y / (U.y / 360.0));
    g *= 0.9 + 0.1 * sin(line * 3.14159);
    float roll = fract(q.y / U.y * 0.6 - time * 0.12);
    g += smoothstep(0.02, 0.0, abs(roll - 0.5)) * 0.08;
    g += (hash(vec2(floor(q.x / 3.0), line)) - 0.5) * 0.10;
    g *= 1.0 - r2 * 1.3;
    vec3 c = g * vec3(0.86, 1.0, 0.88);
    float t = caption(q, U);
    return mix(c, vec3(0.95), t);
}
""",
    )

    /**
     * Every row of the picture is a different moment.
     *
     * The top of the world is now and each row further down is a little further in the past, so
     * anything that moves bends — a turning head becomes a smear, a walking person leans, a spun
     * phone draws a spiral. The past is [Look.history] frames kept on the GPU at a reduced size;
     * the row nearest the top is taken from the live frame at full size, so the picture is sharp
     * where it is current and softens as it ages.
     */
    private val SLITSCAN = native(
        """
vec3 histAt(vec2 xy, float age) {
    float n = max(histLen, 1.0);
    float a = clamp(age, 0.0, n - 1.0);
    float a0 = floor(a);
    float l0 = mod(histHead - a0 + histSize, histSize);
    float l1 = mod(histHead - min(a0 + 1.0, n - 1.0) + histSize, histSize);
    vec2 uv = uvOf(xy);
    vec3 c0 = texture(uHist, vec3(uv, l0)).rgb;
    vec3 c1 = texture(uHist, vec3(uv, l1)).rgb;
    return mix(c0, c1, a - a0);
}

vec3 look(vec2 xy) {
    vec2 U = upSize();
    vec2 q = toUp(xy);
    float age = (q.y / U.y) * (histLen - 1.0);
    if (histLen < 2.0 || age < 0.5) return tap(xy);
    return histAt(xy, age);
}
""",
    )

    /**
     * The motion search behind Datamosh, drawn at one sixteenth of the frame.
     *
     * One texel per macroblock. For each, it asks where in the **last** frame this block came
     * from: a coarse 5×5 search at 6-pixel steps (±12), then a 3×3 refinement at 2-pixel steps
     * around the winner, scoring each candidate by the absolute difference over a 4×4 grid of taps.
     * That is the same question an H.264 encoder asks, answered crudely — which is fine, because the
     * look *is* the answer being applied to the wrong picture.
     *
     * The vector is written into red and green as eighths of a pixel offset by 128, so an ordinary
     * RGBA8 texture carries ±16 pixels and this needs no float render target from the GPU. **Zero
     * has to be exactly representable.** The first version stored `v / 32 + 0.5`, and 0.5 is not a
     * byte: 128/255 decodes as a sixteenth of a pixel, so every block of a perfectly still scene
     * was resampled a sixteenth of a pixel sideways every frame, and a static shot faded to a blur
     * in a couple of seconds. Found in the llvmpipe harness before it reached a phone.
     */
    private val MOSH_SEARCH = GlslPort.VERSION + """
uniform sampler2D uSrc;
uniform sampler2D uLast;
uniform vec2 size;
uniform vec2 auxSize;
out vec4 fragColor;

vec2 uvOf(vec2 p) { return vec2(p.x, size.y - p.y) / size; }
float lumAt(sampler2D s, vec2 p) { return dot(texture(s, uvOf(p)).rgb, vec3(0.299, 0.587, 0.114)); }

float sad(vec2 centre, vec2 off, float cell) {
    float e = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 p = centre + (vec2(float(i), float(j)) - 1.5) * cell;
            e += abs(lumAt(uSrc, p) - lumAt(uLast, p + off));
        }
    }
    return e;
}

void main() {
    vec2 block = size / auxSize;
    vec2 cellIndex = vec2(gl_FragCoord.x, auxSize.y - gl_FragCoord.y) - 0.5;
    vec2 centre = (cellIndex + 0.5) * block;
    float cell = block.x / 4.0;
    vec2 best = vec2(0.0);
    float bestE = sad(centre, best, cell) - 0.02;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 off = vec2(float(i), float(j)) * 6.0;
            float e = sad(centre, off, cell);
            if (e < bestE) { bestE = e; best = off; }
        }
    }
    vec2 coarse = best;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 off = coarse + vec2(float(i), float(j)) * 2.0;
            float e = sad(centre, off, cell);
            if (e < bestE) { bestE = e; best = off; }
        }
    }
    fragColor = vec4(clamp((best * 8.0 + 128.0) / 255.0, 0.0, 1.0), clamp(bestE / 4.0, 0.0, 1.0), 1.0);
}
"""

    /**
     * **Datamosh, the real one** — the thing the photo filter could only imitate.
     *
     * A P-frame is decoded as *reference moved by the motion vectors, plus a correction*. Datamosh
     * is that sum computed against the wrong reference: the I-frame that would have reset the
     * picture has been deleted, so the motion of the new scene drags the pixels of the old one.
     * This does exactly that, live. The reference is this look's own last output (`uPrev`) instead
     * of the last camera frame; the vectors come from [MOSH_SEARCH], applied block by block
     * (`texelFetch`, no smoothing, so the damage has the macroblock edges real damage has); and only
     * a fraction of the correction is added back, so the new picture seeps in rather than
     * replacing the old one.
     *
     * Left alone that would melt into mud for ever, so each block also refreshes itself now and
     * then, at random — the way encoders spread intra-refresh through a stream — and the picture
     * heals slowly between moves. Move the phone and it blooms; hold still and it clears.
     */
    private val MOSH = native(
        """
vec3 look(vec2 xy) {
    vec3 c = tap(xy);
    if (fresh > 0.5) return c;
    vec2 auxSize = vec2(textureSize(uAux, 0));
    vec2 block = size / auxSize;
    ivec2 bi = ivec2(clamp(floor(xy / block), vec2(0.0), auxSize - 1.0));
    ivec2 texel = ivec2(bi.x, int(auxSize.y) - 1 - bi.y);
    vec4 a = texelFetch(uAux, texel, 0);
    vec2 mv = (floor(a.rg * 255.0 + 0.5) - 128.0) / 8.0;
    vec3 ref = prev(xy + mv);
    vec3 residual = c - last(xy + mv);
    vec3 m = ref + residual * 0.15;
    float moving = smoothstep(0.5, 2.5, length(mv));
    float refresh = step(0.998 - (1.0 - moving) * 0.02, hash1(float(bi.x) * 13.1 + float(bi.y) * 71.7 + floor(time * 30.0) * 0.618));
    return mix(m, c, refresh);
}
""",
    )

    /* ------------------------------------------------------------------ */
    /*  The dial                                                           */
    /* ------------------------------------------------------------------ */

    /** The camera straight through. First on the dial and the only look that costs no GPU pass. */
    val plain = Look("none", "Preset", "")

    private fun ported(photoId: String): Look? {
        val filter = Filters.byId(photoId).takeIf { it.id == photoId } ?: return null
        val source = filter.source ?: return null
        val glsl = GlslPort.port(source) ?: return null
        return Look(filter.id, filter.label, glsl, photoId = filter.id)
    }

    /**
     * Preset with the user's adjustments on it, or [plain] when there are none.
     *
     * The grade is the photo grade — one set of adjustments for the camera, whichever mode it is
     * in — which is why a clip and a still shot either side of it come out the same colour.
     */
    fun forGrade(look: Look, grade: Grade): Look {
        if (look.id != plain.id || grade.isNeutral) return look
        val glsl = Filters.preset.source?.let { GlslPort.port(it) } ?: return plain
        return Look(plain.id, plain.label, glsl, photoId = plain.id, adjustable = true, grade = grade)
    }

    private val datamosh = Look("mosh", "Datamosh", MOSH, prePass = MOSH_SEARCH, prePassDiv = 16)

    /**
     * The order the wheel walks.
     *
     * The video-only looks first, because they are the reason the dial is here. **Datamosh is
     * the photo dial's lesson applied again:** it is the look that deliberately wrecks the picture,
     * so it sits as far from Preset as the dial allows rather than one notch backwards from it,
     * where an overshoot reaching for the plain picture would land on it (light-reports#27).
     *
     * Purikura is not carried over: it needs faces handed to it in the frame's coordinates and a
     * printed frame drawn around it, and a clip of a purikura without either is not one. The photo
     * datamosh modes are not either — the real thing is here.
     */
    val all: List<Look> = buildList {
        add(plain)
        ported("film")?.let(::add)
        add(Look("super8", "Super 8", SUPER8))
        add(Look("vhs", "VHS", VHS))
        add(Look("trails", "Trails", TRAILS))
        add(Look("stopmotion", "Stop Motion", STOP_MOTION))
        ported("mono")?.let(::add)
        add(Look("cctv", "CCTV", CCTV))
        add(Look("motion", "Motion", MOTION))
        ported("thermal")?.let(::add)
        ported("xray")?.let(::add)
        ported("glow")?.let(::add)
        ported("comic")?.let(::add)
        add(Look("slitscan", "Slit-scan", SLITSCAN, history = 30, historyEdge = 640))
        add(datamosh)
        ported("gameboy")?.let(::add)
        ported("gbcolor")?.let(::add)
        ported("dither16")?.let(::add)
        ported("dither32")?.let(::add)
        ported("dithergrey")?.let(::add)
        ported("onebit")?.let(::add)
        ported("halftone")?.let(::add)
        ported("mirror")?.let(::add)
        ported("kaleido")?.let(::add)
        ported("twirl")?.let(::add)
        ported("bulge")?.let(::add)
        ported("fisheye")?.let(::add)
        ported("tunnel")?.let(::add)
    }

    fun byId(id: String?): Look = all.firstOrNull { it.id == id } ?: plain

    /**
     * The dial with the photo filters you switched off taken off it too.
     *
     * One list of preferences about which looks you use, not two: a ported look answers to its
     * photo filter's switch. The video-only looks have no photo twin and are always on the dial.
     * Preset can never go, for the same reason it can never go from the photo dial.
     */
    fun dial(off: Set<String>): List<Look> =
        all.filter { it.id == plain.id || it.photoId == null || it.photoId !in off }

    /** Wraps, like the photo dial, because a physical wheel should never dead-end. */
    fun step(from: Look, by: Int, within: List<Look> = all): Look {
        if (within.isEmpty()) return plain
        val here = within.indexOfFirst { it.id == from.id }
        if (here < 0) return if (by >= 0) within.first() else within.last()
        val size = within.size
        return within[((here + by) % size + size) % size]
    }
}
