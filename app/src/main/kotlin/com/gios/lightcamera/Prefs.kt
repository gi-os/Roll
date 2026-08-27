package com.gios.lightcamera

import android.content.Context
import com.gios.lightcamera.camera.AfMode
import com.gios.lightcamera.camera.ExposureMode
import com.gios.lightcamera.camera.FlashMode
import com.gios.lightcamera.camera.FrameAspect
import com.gios.lightcamera.camera.PuriArt
import com.gios.lightcamera.filter.Adjust
import com.gios.lightcamera.filter.Grade
import com.gios.lightcamera.filter.FaceTune
import com.gios.lightcamera.filter.Filters
import com.gios.lightcamera.camera.PuriStrip
import com.gios.lightcamera.camera.Ring
import com.gios.lightcamera.hw.Binding
import com.gios.lightcamera.hw.DialAction
import com.gios.lightcamera.hw.PressAction
import com.gios.lightcamera.media.CaptureFormat
import com.gios.lightcamera.media.RollScope
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the viewfinder draws over the image, beyond focus and faces.
 *
 * Deliberately short. The stock Light camera puts nothing on the picture at all, and an
 * unobstructed viewfinder turned out to be the single biggest improvement to this app — so
 * the only thing on offer here is a grid, for anyone who wants one.
 */
enum class Chrome(val label: String) {
    /** Focus, faces and the level. Nothing else. */
    Clean("Clean"),

    /** Rule-of-thirds lines. */
    Thirds("Thirds"),
}

/**
 * What can go in one of the two free slots at the end of the band.
 *
 * The rest of the band is not negotiable — the album, the mode-and-filter chip and the flash are
 * the stock camera's own bar, in the stock camera's own order, and an app that let you take the
 * album out of the album slot would stop looking like it belongs on the phone. These two are the
 * end of the row, where the exposure icon already was.
 *
 * Most of them are a word rather than a glyph, because the SDK has no icon for zoom or for a
 * self timer and a word that reads "1.8x" tells you the value as well as the control. The mode
 * chip beside them is already a word, so the row stays consistent.
 */
enum class BandSlot(val label: String) {
    None("Nothing"),

    /** The brightness icon, and the strip it opens. Where this used to be the only option. */
    Exposure("Exposure"),

    /** The current ratio as a word, and a strip that goes from 1x to the sensor's limit in one drag. */
    Zoom("Zoom"),

    /** Front or rear. Until now this was a double tap on the viewfinder and nothing else. */
    Flip("Front / rear"),
    Timer("Self timer"),
    Shape("Shape"),
    Grid("Grid"),
}

/**
 * When to lift LightOS's greyscale.
 *
 * See `ui/ColorMode.kt`. [Viewfinder] is the default because a camera showing you a grey
 * version of the colour photograph it is about to save is misrepresenting the picture — and
 * because half the filters in this app are about colour. The same argument covers every
 * photograph the app draws, so [Viewfinder] holds colour for the roll as well; it is the
 * chrome, not the pictures, that [Always] adds.
 */
enum class Colour(val label: String) {
    /** Leave the phone as Light set it. */
    Off("Off"),

    /**
     * Colour wherever a photograph is: the viewfinder, the roll and the full-screen viewer.
     * Grey everywhere else — settings, the send picker, and the rest of the phone.
     */
    Viewfinder("Pictures"),

    /** Colour for the whole app, settings and the send picker included. */
    Always("Whole app"),
}

/**
 * What the camera is set to, in the stock app's own terms.
 *
 * The three the Light camera offers, and the same three here. [Selfie] is not a separate
 * pipeline — it is the front lens — but the stock app presents it as a mode, and being a mode is
 * what makes it reachable without a hidden gesture.
 */
enum class CaptureMode(val label: String) {
    /**
     * **The one the camera opens on, and the one that just takes a photograph.**
     *
     * Everything this app is proud of — the filters, the stamps, the crops, the booth — costs a decode
     * and a re-encode, and a decode of a 50-megapixel JPEG is most of a second before anything else
     * happens. Simple takes none of those options, which is not a restriction so much as the whole point:
     * with no filter, no crop and no stamp, [com.gios.lightcamera.camera.Frames] writes **the sensor's own
     * JPEG, untouched** — no decode, no re-encode, EXIF intact — and the shutter is as quick as the
     * hardware is.
     *
     * Quality is not what is traded away. It shoots 12 megapixels, which is four times the largest print
     * anybody makes from a phone, and the file is the ISP's own output rather than something this app
     * re-compressed.
     */
    Simple("Simple"),

    /** Everything: filters, sizes up to 50MP, crops, date backs, self timer, the booth. */
    Photo("Pro"),
    Video("Video"),
    Selfie("Selfie"),

    /**
     * **The camera pointed at a code instead of at a picture.**
     *
     * A mode rather than a separate app, because it is the same sentence as every other mode here:
     * point the camera at a thing, press the button, get the thing. LightQR was that app, and it was
     * one launcher entry, one cold start and one camera bind away from the viewfinder that is
     * already open — for a job that takes two seconds and is then over.
     *
     * Nothing is written and nothing lands on the roll: a scan produces a string, and the string is
     * either opened or copied. Which is also why the shutter means something else here — see
     * [com.gios.lightcamera.ui.CameraViewModel.shoot].
     */
    Scan("QR"),

    /**
     * **The camera pointed at a page.**
     *
     * The same argument as [Scan] — point, press, get the thing — for the case QR does not
     * cover, which is most printed matter. A menu, a receipt, a serial number on the back of a
     * router, a paragraph you want to keep. Roll could already read a photograph on the roll;
     * this is that without the photograph, because standing in front of a noticeboard and then
     * going to look for the picture you just took is two steps too many.
     *
     * **Nothing lands on the roll.** The frame is grabbed off the panel, read, and dropped when
     * you close the sheet. It is a reading, not a photograph, and a roll filling up with pictures
     * of car park signs would be the wrong outcome.
     *
     * Unlike [Scan] there is no live analyzer. A code is a small target you sweep for and want
     * acted on within a second; a page is a thing you frame and press. Running a recogniser on
     * every preview frame would cost far more than the viewfinder can spare here, and would be
     * answering a question nobody asked — see [com.gios.lightcamera.ocr.PageReader].
     */
    Text("Text"),
    ;

    /** What the mode slot in the band reads. */
    val bandLabel: String
        get() = when (this) {
            Simple -> "SIMPLE"
            Photo -> "PRO"
            Video -> "VIDEO"
            Selfie -> "SELFIE"
            Scan -> "QR"
            Text -> "TEXT"
        }

    /** True where the app gets out of the way: no filter, no crop, no stamp, no timer. */
    val isSimple: Boolean get() = this == Simple

    /** True in the one mode that scans continuously. */
    val isScan: Boolean get() = this == Scan

    /** True while the camera is being used to read something rather than to photograph it. */
    val isText: Boolean get() = this == Text

    /**
     * True in the two modes that produce a result instead of a file.
     *
     * Most of the places that used to ask `isScan` meant this: no filter dial, no film counter,
     * no roll, clean chrome. Kept separate from [isScan] because the two differ in the one place
     * it matters — whether an analyzer is bound to the stream.
     */
    val isReader: Boolean get() = isScan || isText
}

/**
 * How big a photograph is, which on this phone is the same question as how fast the shutter is.
 *
 * Reading out and encoding a 50MP frame is most of a second of the ISP's time; each step down is
 * roughly a halving. [Screen] is a different thing altogether — see [CameraEngine.previewFrame].
 */
enum class PhotoSize(val label: String, val longEdge: Int) {
    /** Everything the sensor has. Slowest by a wide margin. */
    Full("50MP", 8160),

    /** Four times the largest print you'd make from a phone. The default. */
    Large("12MP", 4000),

    Medium("5MP", 2560),

    Small("2MP", 1600),

    /**
     * The frame off the viewfinder, at panel resolution. No sensor capture at all, so it is as
     * instant as the app can be — and with a filter on, it is the very frame you were looking at.
     */
    Screen("Screen", 0),
    ;

    val isPreviewGrab: Boolean get() = this == Screen
}

/**
 * Which date back. Three real ones, each with its own era, order and typography.
 *
 * They are not skins on one drawing: the dot matrix is lamps behind a mask, the quartz is seven
 * segments, and the camcorder stamp is an actual typeface with an outline. Drawing all three the
 * same way is what makes fake ones look fake.
 */
enum class StampStyle(val label: String) {
    /** Amber-green dot matrix, leaning. `11  5 '21`. The compact-camera one. */
    Dots("Dots"),

    /** Orange-red seven segment, leaning. `'99 12 29`. The film SLR quartz back. */
    Quartz("Quartz"),

    /** Solid orange with a black outline, upright. `08/31/2015`. Camcorders and dashcams. */
    Outline("Camcorder"),
}

/** Seconds before the shutter fires. */
enum class SelfTimer(val seconds: Int, val label: String) {
    Off(0, "Off"),
    Three(3, "3s"),
    Ten(10, "10s"),
}

/**
 * Settings, as flows.
 *
 * `SharedPreferences` rather than DataStore: every value here is read on the way into a
 * frame — the filter, the aspect, whether a roll is loaded — and the shutter cannot wait on
 * a coroutine to find out what it is supposed to be doing. Synchronous reads at startup,
 * flows for the UI, and writes that are fire-and-forget.
 */
class Prefs(context: Context) {

    private val prefs = context.getSharedPreferences(PrefsFile.NAME, Context.MODE_PRIVATE)

    /**
     * **The camera opens plain, every time, and this is deliberately not persisted.**
     *
     * It used to be, seeded from `"film"`, which meant the filter you last chose was still on when
     * you next reached for the camera — and a filter is not a setting, it is a decision you made
     * about one photograph. The failure mode is the whole argument: you shoot a roll through Game
     * Boy on a Tuesday, and on Thursday somebody does something worth photographing and you get a
     * 160-cell dither of it. There is no undo on that. The reverse mistake costs one turn of the
     * wheel.
     *
     * The same reasoning as [mode] below, and it is the reason both live in memory rather than in
     * `SharedPreferences`: what the camera hands you when you open it should be a camera.
     *
     * **"Open" means the process starting, not every glance at the app.** The value survives for as
     * long as Roll is alive, so walking to the roll, opening a photograph, going to settings and
     * coming back leaves the dial exactly where you left it — resetting on every resume would take
     * the filter away every time you checked the shot you just took, which is the one moment you are
     * most likely to want another frame of the same thing.
     */
    private val _filterId = MutableStateFlow(Filters.none.id)
    val filterId: StateFlow<String> = _filterId.asStateFlow()

    /**
     * Which filters are on the dial, and in what order. **This one is persisted** — and the
     * contrast with [filterId] above is the point, not an inconsistency.
     *
     * Which filter is on right now is a decision about one photograph. Which filters exist at all
     * is a decision about your camera, and it is exactly the kind of thing that is worth setting
     * once. Twenty-two positions is a long spin to reach the four you shoot, and every one you
     * never use is a notch between you and the one you want.
     *
     * Stored newline-joined for the same reason [recentRecipients] is: the order is the content.
     * Both start empty, which means "never arranged" rather than "nothing on the dial" — see
     * [Filters.ordered], which is where that reading lives.
     */
    private val _filterOrder = MutableStateFlow(readLines(FILTER_ORDER))
    val filterOrder: StateFlow<List<String>> = _filterOrder.asStateFlow()

    private val _filtersOff = MutableStateFlow(readLines(FILTERS_OFF).toSet())
    val filtersOff: StateFlow<Set<String>> = _filtersOff.asStateFlow()

    /** The dial as arranged, ready to hand to [Filters.step] or to draw. */
    fun dial(): List<Filters.Filter> = Filters.ordered(_filterOrder.value, _filtersOff.value)

    fun moveFilter(id: String, by: Int) {
        val next = Filters.move(_filterOrder.value, id, by)
        set(_filterOrder, next) { putString(FILTER_ORDER, next.joinToString("\n")) }
    }

    /**
     * Switch one filter off the dial, or back on.
     *
     * [Filters.none] is refused rather than handled: the settings row for it is not tappable, so
     * arriving here with it means something else called this, and quietly doing nothing is a
     * better answer than a camera that cannot take a plain photograph.
     */
    fun toggleFilter(id: String) {
        if (id == Filters.none.id) return
        val next = _filtersOff.value.toMutableSet().apply { if (!add(id)) remove(id) }
        // Taking the filter you are currently shooting off the dial leaves the viewfinder showing
        // something you can no longer reach: the wheel steps past it and the grid does not draw
        // it, but the preview keeps applying it until you turn. Step off it here instead.
        if (id in next && _filterId.value == id) _filterId.value = Filters.none.id
        set(_filtersOff, next) { putString(FILTERS_OFF, next.joinToString("\n")) }
    }

    fun resetFilters() {
        set(_filterOrder, emptyList()) { remove(FILTER_ORDER) }
        set(_filtersOff, emptySet()) { remove(FILTERS_OFF) }
    }

    private fun readLines(key: String): List<String> =
        prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * Deliberately not persisted. A camera should open in the mode you take photographs in;
     * finding it still in video a day later, with the shutter recording instead of shooting, is
     * a photograph missed.
     */
    // **Pro, and Simple is opt-in.** Simple exists because a still costs 1.8 s on this camera, and the way
    // round that is a panel-resolution frame — a real trade, not a free win. So it is a switch you turn on
    // rather than the thing the camera hands you: off, it is not in the mode picker at all.
    private val _mode = MutableStateFlow(CaptureMode.Photo)
    val mode: StateFlow<CaptureMode> = _mode.asStateFlow()

    private val _aspect = MutableStateFlow(FrameAspect.byLabel(prefs.getString(ASPECT, null)))
    val aspect: StateFlow<FrameAspect> = _aspect.asStateFlow()

    private val _photoSize = MutableStateFlow(
        PhotoSize.entries.firstOrNull { it.name == prefs.getString(SIZE, null) } ?: PhotoSize.Large,
    )
    val photoSize: StateFlow<PhotoSize> = _photoSize.asStateFlow()

    private val _chrome = MutableStateFlow(
        Chrome.entries.firstOrNull { it.name == prefs.getString(CHROME, null) } ?: Chrome.Clean,
    )
    val chrome: StateFlow<Chrome> = _chrome.asStateFlow()

    private val _flash = MutableStateFlow(
        FlashMode.entries.firstOrNull { it.name == prefs.getString(FLASH, null) } ?: FlashMode.Off,
    )
    val flash: StateFlow<FlashMode> = _flash.asStateFlow()

    private val _afMode = MutableStateFlow(
        AfMode.entries.firstOrNull { it.name == prefs.getString(AF_MODE, null) } ?: AfMode.Single,
    )
    val afMode: StateFlow<AfMode> = _afMode.asStateFlow()

    private val _facePriority = MutableStateFlow(prefs.getBoolean(FACE_PRIORITY, true))
    val facePriority: StateFlow<Boolean> = _facePriority.asStateFlow()

    private val _timer = MutableStateFlow(
        SelfTimer.entries.firstOrNull { it.name == prefs.getString(TIMER, null) } ?: SelfTimer.Off,
    )
    val timer: StateFlow<SelfTimer> = _timer.asStateFlow()

    /**
     * The roll shows **everything** by default.
     *
     * Narrowing it to `DCIM` is technically the definition of a camera roll, but in practice
     * that hides screenshots, saved pictures and anything a messaging app wrote elsewhere —
     * so the roll appeared to be missing photographs that are plainly on the phone. All
     * images, with a toggle in the header for anyone who wants only their own.
     */
    private val _scope = MutableStateFlow(
        RollScope.entries.firstOrNull { it.name == prefs.getString(SCOPE, null) }
            ?: RollScope.Everything,
    )
    val scope: StateFlow<RollScope> = _scope.asStateFlow()

    /**
     * Where the viewer's send button goes, if anywhere.
     *
     * Off by default and genuinely disabled rather than hidden, because a share sheet is the one
     * place a Light Phone stops feeling like a Light Phone: a grid of every app that ever
     * registered for an image, on a phone whose whole argument is that there aren't any. Turned
     * on, it has exactly one destination and no chooser.
     */
    private val _sendToLightChat = MutableStateFlow(prefs.getBoolean(SEND_LIGHTCHAT, false))
    val sendToLightChat: StateFlow<Boolean> = _sendToLightChat.asStateFlow()

    /**
     * The date back, per kind of photograph. Off by default — it writes on the photograph and there
     * is no undo.
     *
     * **Three switches, not one, because a date back suits one kind of picture and ruins another.**
     * An amber dot-matrix date in the corner of a plain photograph is the look. The same date over a
     * Game Boy frame is two incompatible resolutions arguing: the stamp is drawn at full pixel
     * precision over an image quantised to 160 cells, so it reads as a caption pasted on rather than
     * something the camera did. So the coarse filters get their own switch and it starts off, and the
     * plain photograph and the ordinary filters get one each.
     *
     * The old single `dateStamp` key migrates into plain and filtered, leaving coarse off — nobody
     * who turned the stamp on was asking for it over a dither.
     */
    private val _stampPlain = MutableStateFlow(
        prefs.getBoolean(STAMP_PLAIN, prefs.getBoolean(DATE_STAMP, false)),
    )
    val stampPlain: StateFlow<Boolean> = _stampPlain.asStateFlow()

    private val _stampFiltered = MutableStateFlow(
        prefs.getBoolean(STAMP_FILTERED, prefs.getBoolean(DATE_STAMP, false)),
    )
    val stampFiltered: StateFlow<Boolean> = _stampFiltered.asStateFlow()

    private val _stampCoarse = MutableStateFlow(prefs.getBoolean(STAMP_COARSE, false))
    val stampCoarse: StateFlow<Boolean> = _stampCoarse.asStateFlow()

    /** Whether any of the three is on — what the style picker is worth showing for. */
    val dateStampAnywhere: Boolean
        get() = _stampPlain.value || _stampFiltered.value || _stampCoarse.value

    private val _stampStyle = MutableStateFlow(
        StampStyle.entries.firstOrNull { it.name == prefs.getString(STAMP_STYLE, null) }
            ?: StampStyle.Dots,
    )
    val stampStyle: StateFlow<StampStyle> = _stampStyle.asStateFlow()

    /**
     * Everything about a Purikura that is not the shader: the frame, the two kinds of sticker, its
     * own date, and whether the shutter takes four.
     *
     * **Remembered, all of it.** The first version of this was not: the argument was that a booth does not
     * recall what you chose last week, which is true of booths and wrong for a camera you own. Turning the
     * stickers off and finding them back on tomorrow is not charm, it is a setting that does not work.
     *
     * The frame and the date still *default* to Random, which is resolved per photograph from the seed — so
     * the surprise is per shot rather than per launch, which is where it belonged all along. Four-shot still
     * defaults off, because a strip is something you decide to do.
     */
    private val _puriFrame = MutableStateFlow(prefs.getString(PURI_FRAME, null) ?: PuriArt.RANDOM)
    val puriFrame: StateFlow<String> = _puriFrame.asStateFlow()

    private val _puriFaceStickers = MutableStateFlow(prefs.getBoolean(PURI_FACE, true))
    val puriFaceStickers: StateFlow<Boolean> = _puriFaceStickers.asStateFlow()

    private val _puriMarginStickers = MutableStateFlow(prefs.getBoolean(PURI_MARGIN, true))
    val puriMarginStickers: StateFlow<Boolean> = _puriMarginStickers.asStateFlow()

    private val _puriDate = MutableStateFlow(prefs.getString(PURI_DATE, null) ?: PuriArt.RANDOM)
    val puriDate: StateFlow<String> = _puriDate.asStateFlow()

    private val _puriStrip = MutableStateFlow(prefs.getString(PURI_STRIP, null) ?: PuriStrip.OFF)
    val puriStrip: StateFlow<String> = _puriStrip.asStateFlow()

    /**
     * The five parts of the look, each on its own switch.
     *
     * **Not randomised, unlike the frame and the stickers.** These are what Purikura *is* rather than
     * decoration on top of it, and a filter that arrived with the eyes off half the time would look
     * broken rather than surprising. The wash, the smoothing and the eyes start on because that is the
     * effect; the chin and the slimming start off because they are the two that can look uncanny on a
     * face the detector has boxed slightly wrong.
     */
    private val _puriWash = MutableStateFlow(prefs.getBoolean(PURI_WASH, true))
    val puriWash: StateFlow<Boolean> = _puriWash.asStateFlow()

    private val _puriSkin = MutableStateFlow(prefs.getBoolean(PURI_SKIN, true))
    val puriSkin: StateFlow<Boolean> = _puriSkin.asStateFlow()

    private val _puriEyes = MutableStateFlow(prefs.getBoolean(PURI_EYES, true))
    val puriEyes: StateFlow<Boolean> = _puriEyes.asStateFlow()

    private val _puriChin = MutableStateFlow(prefs.getBoolean(PURI_CHIN, false))
    val puriChin: StateFlow<Boolean> = _puriChin.asStateFlow()

    private val _puriSlim = MutableStateFlow(prefs.getBoolean(PURI_SLIM, false))
    val puriSlim: StateFlow<Boolean> = _puriSlim.asStateFlow()

    /** The five, as the shader wants them. */
    fun puriTune(turns: Int = 0): FaceTune = FaceTune.of(
        eyes = _puriEyes.value,
        chin = _puriChin.value,
        slim = _puriSlim.value,
        skin = _puriSkin.value,
        wash = _puriWash.value,
        turns = turns,
    )

    /**
     * The photographs you starred, by file name.
     *
     * **By name, not by id.** A MediaStore id is a row number: rescan the volume, move a file, restore a
     * backup, and the same photograph comes back with a different one — and a favourites list that
     * quietly empties itself is worse than none. The name is what the file is called, which survives all
     * of that.
     *
     * Persisted, unlike the Purikura settings: a star is a statement about a photograph, and it should
     * still be there next week.
     */
    private val _favourites = MutableStateFlow(prefs.getStringSet(FAVOURITES, null)?.toSet() ?: emptySet())
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

    fun toggleFavourite(name: String): Boolean {
        val next = _favourites.value.toMutableSet()
        val starred = next.add(name)
        if (!starred) next.remove(name)
        set(_favourites, next.toSet()) { putStringSet(FAVOURITES, next) }
        return starred
    }

    fun isFavourite(name: String): Boolean = name in _favourites.value

    /**
     * Whether Simple is offered at all.
     *
     * Off by default. Simple trades resolution for an instant shutter — panel-sized rather than 12MP — and
     * that is a decision worth making deliberately rather than finding yourself in. Switched on, it joins the
     * mode picker and the wheel walks into it; switched off, it does not exist as far as the camera is
     * concerned.
     */
    private val _simpleMode = MutableStateFlow(prefs.getBoolean(SIMPLE_MODE, false))
    val simpleMode: StateFlow<Boolean> = _simpleMode.asStateFlow()

    /**
     * The people last sent a photograph, most recent first, so the picker opens with them on
     * screen instead of at the top of the alphabet.
     *
     * **Held by normalised address, not by contact id.** A contact id belongs to one
     * address-book database and does not survive a restore or a phone swap, so a list keyed by
     * it would silently empty itself — the same reasoning as keying starred photographs by
     * filename. The address is the thing that identifies the person across all of that, and
     * `Recipients.key` is what makes two spellings of it compare equal.
     *
     * Stored newline-joined rather than as a `StringSet`, because a set has no order and the
     * order is the entire content of this list.
     */
    private val _recentRecipients = MutableStateFlow(
        prefs.getString(RECENT_RECIPIENTS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
    )
    val recentRecipients: StateFlow<List<String>> = _recentRecipients.asStateFlow()

    fun clearRecentRecipients() =
        set(_recentRecipients, emptyList()) { remove(RECENT_RECIPIENTS) }

    fun rememberRecipient(key: String) {
        val next = com.gios.lightcamera.send.Recipients.remember(_recentRecipients.value, key)
        set(_recentRecipients, next) { putString(RECENT_RECIPIENTS, next.joinToString("\n")) }
    }

    /**
     * Show how long each shot took, in milliseconds, split into capture and save.
     *
     * A developer diagnostic, off by default. It answered its question — 1.8 s inside `takePicture`, 87 ms
     * to save — and what it is for now is checking that a change did what it claimed.
     */
    private val _timings = MutableStateFlow(prefs.getBoolean(TIMINGS, false))
    val timings: StateFlow<Boolean> = _timings.asStateFlow()

    /**
     * The horizon line.
     *
     * On by default, because it only appears when the phone is crooked and disappears a beat after you
     * straighten up — but it is still a line through the middle of the frame, and some people would
     * rather compose without one.
     */
    private val _level = MutableStateFlow(prefs.getBoolean(LEVEL, true))
    val level: StateFlow<Boolean> = _level.asStateFlow()

    /** The digicam focus beep and the shutter tick. */
    private val _sounds = MutableStateFlow(prefs.getBoolean(SOUNDS, true))
    val sounds: StateFlow<Boolean> = _sounds.asStateFlow()

    private val _colour = MutableStateFlow(
        Colour.entries.firstOrNull { it.name == prefs.getString(COLOUR, null) } ?: Colour.Viewfinder,
    )
    val colour: StateFlow<Colour> = _colour.asStateFlow()

    /** Frames on a newly loaded roll. */
    private val _rollLength = MutableStateFlow(prefs.getInt(ROLL_LENGTH, 24))
    val rollLength: StateFlow<Int> = _rollLength.asStateFlow()

    /** Whether the wheel is doing anything. Off for anyone who finds it twitchy. */
    private val _wheelEnabled = MutableStateFlow(prefs.getBoolean(WHEEL, true))
    val wheelEnabled: StateFlow<Boolean> = _wheelEnabled.asStateFlow()

    /**
     * Whether the filter dial has to be woken before a bare turn will move it.
     *
     * **Off by default, and it has to be.** v2.49 shipped this as unconditional behaviour whose only
     * escape was a hardware key, and on a phone where LightControl has claimed the wheel system-wide
     * that key never reaches us — so the dial was locked with nothing on the device able to open it,
     * and the settings screen sits behind the mode picker, which is reached with the wheel. Off by
     * default means an update can never take somebody's dial away, and the switch that turns it on
     * is the same one that turns it off.
     */
    private val _dialLock = MutableStateFlow(prefs.getBoolean(DIAL_LOCK, false))
    val dialLock: StateFlow<Boolean> = _dialLock.asStateFlow()

    /**
     * What each physical control does, keyed by [Binding] name.
     *
     * One flow holding the whole map rather than five flows, because the safety rule in
     * `Controls.shutterSafe` is a statement about the map and not about any one entry — a
     * per-binding flow would let the UI check the rule against a map that was half updated.
     * Every binding is present at construction, so reads never have to consider a missing key.
     */
    private val _bindings = MutableStateFlow(
        Binding.entries.associate { it to (prefs.getString(bindKey(it), null) ?: it.default) },
    )
    val bindings: StateFlow<Map<Binding, String>> = _bindings.asStateFlow()

    /**
     * The exposure aids, both off by default.
     *
     * The stock Light camera puts nothing on the picture at all, and an unobstructed viewfinder is
     * the single biggest thing this app got right — so a histogram nobody asked for would be a
     * regression, and these are for the person who has gone looking for them.
     */
    private val _histogram = MutableStateFlow(prefs.getBoolean(HISTOGRAM, false))
    val histogram: StateFlow<Boolean> = _histogram.asStateFlow()

    private val _clipping = MutableStateFlow(prefs.getBoolean(CLIPPING, false))
    val clipping: StateFlow<Boolean> = _clipping.asStateFlow()

    /**
     * Keep the sharpest of a short burst, where the photograph comes off the panel.
     *
     * Off by default because it changes *which* frame you get: press the button and the file is one
     * of the last eight frames rather than the newest, which is what you want for a face and not
     * what you want for timing a jump.
     */
    private val _burst = MutableStateFlow(prefs.getBoolean(BURST, false))
    val burst: StateFlow<Boolean> = _burst.asStateFlow()

    fun setHistogram(value: Boolean) = set(_histogram, value) { putBoolean(HISTOGRAM, value) }

    fun setClipping(value: Boolean) = set(_clipping, value) { putBoolean(CLIPPING, value) }

    fun setBurst(value: Boolean) = set(_burst, value) { putBoolean(BURST, value) }

    /**
     * How far behind the press the shutter is allowed to reach, in milliseconds. Zero is off.
     *
     * **The frame you wanted is usually slightly before the one you got.** You see the expression,
     * then decide, then your thumb moves; by then it is a third of a second later. A ring of recent
     * frames lets the shutter reach back to where the photograph actually was.
     *
     * Off by default, and not because it is unfinished. It costs power continuously — the frames
     * have to be arriving all the time for any of them to be there when you press — and it changes
     * *which moment* you get, which is wrong for anything you are timing deliberately.
     */
    private val _preRollMs = MutableStateFlow(prefs.getInt(PRE_ROLL, 0))
    val preRollMs: StateFlow<Int> = _preRollMs.asStateFlow()

    fun setPreRollMs(value: Int) {
        val safe = value.coerceIn(0, Ring.MAX_PRE_ROLL_MS.toInt())
        set(_preRollMs, safe) { putInt(PRE_ROLL, safe) }
    }

    /** The offered settings, so the picker and the clamp cannot disagree. */
    val preRollChoices: List<Int> = listOf(0, 80, 160, Ring.MAX_PRE_ROLL_MS.toInt())

    /**
     * **Which files one press writes.**
     *
     * A set rather than a mode, because these are not alternatives: a negative and a print are
     * different things to have, and the reason to shoot RAW is rarely a reason to give up the file
     * you can actually send someone. Every other camera on this phone makes you choose, which is
     * the thing this replaces.
     *
     * JPEG alone is the default, and is what the app has always done.
     *
     * The set is never allowed to empty — a shutter that writes nothing is not a setting, it is a
     * broken camera, and it would be discovered an afternoon later.
     */
    private val _formats = MutableStateFlow(readFormats())
    val formats: StateFlow<Set<CaptureFormat>> = _formats.asStateFlow()

    private fun readFormats(): Set<CaptureFormat> {
        val stored = prefs.getStringSet(FORMATS, null)
            ?.mapNotNull { name -> CaptureFormat.entries.firstOrNull { it.name == name } }
            ?.toSet()
        return stored?.takeIf { it.isNotEmpty() } ?: setOf(CaptureFormat.Jpeg)
    }

    fun toggleFormat(format: CaptureFormat) {
        val next =
            if (format in _formats.value) _formats.value - format else _formats.value + format
        setFormats(next)
    }

    fun setFormats(value: Set<CaptureFormat>) {
        // The last one cannot be turned off. Refusing here rather than in the UI means every route
        // in — a settings tap, a restored preference, a future binding — obeys the same rule.
        val safe = value.takeIf { it.isNotEmpty() } ?: setOf(CaptureFormat.Jpeg)
        set(_formats, safe) { putStringSet(FORMATS, safe.map { it.name }.toSet()) }
    }

    /** True when this press has to go through the shader path to produce what was asked for. */
    fun wantsLossless(): Boolean = CaptureFormat.Png in _formats.value

    /**
     * Write where you were into every photograph.
     *
     * **On by default, which is what every other camera does and is worth being deliberate
     * about.** The map is empty without it. But Roll's send picker shares files with people, and a
     * coordinate in a photograph travels with the file — so the viewfinder shows an indicator
     * while this is on, rather than the setting being the only place it is ever mentioned.
     */
    private val _tagLocation = MutableStateFlow(prefs.getBoolean(TAG_LOCATION, true))
    val tagLocation: StateFlow<Boolean> = _tagLocation.asStateFlow()

    fun setTagLocation(value: Boolean) =
        set(_tagLocation, value) { putBoolean(TAG_LOCATION, value) }

    /**
     * The four camera-state settings, written down.
     *
     * These lived only in [com.gios.lightcamera.camera.CameraEngine] at first, which meant every
     * one of them silently reverted at the next launch — a settings screen whose switches read
     * "On" yesterday and "Off" today is indistinguishable from a bug, and the flat profile in
     * particular is a choice about every photograph, not about a session. The engine stays the
     * source of truth for what the camera is *doing*; these are what was *chosen*, and a collector
     * in the view model keeps the two in agreement.
     */
    private val _flat = MutableStateFlow(prefs.getBoolean(FLAT, false))
    val flat: StateFlow<Boolean> = _flat.asStateFlow()

    fun setFlat(value: Boolean) = set(_flat, value) { putBoolean(FLAT, value) }

    private val _lensCorrection = MutableStateFlow(prefs.getBoolean(LENS_CORRECTION, true))
    val lensCorrection: StateFlow<Boolean> = _lensCorrection.asStateFlow()

    fun setLensCorrection(value: Boolean) =
        set(_lensCorrection, value) { putBoolean(LENS_CORRECTION, value) }

    private val _zoneFocus = MutableStateFlow(prefs.getBoolean(ZONE_FOCUS, false))
    val zoneFocus: StateFlow<Boolean> = _zoneFocus.asStateFlow()

    fun setZoneFocus(value: Boolean) = set(_zoneFocus, value) { putBoolean(ZONE_FOCUS, value) }

    private val _exposureMode = MutableStateFlow(
        ExposureMode.entries.firstOrNull { it.name == prefs.getString(EXPOSURE_MODE, null) }
            ?: ExposureMode.Auto,
    )
    val exposureMode: StateFlow<ExposureMode> = _exposureMode.asStateFlow()

    fun setExposureMode(value: ExposureMode) =
        set(_exposureMode, value) { putString(EXPOSURE_MODE, value.name) }

    /** True when the negative is wanted. Whether it is *possible* is the camera's answer. */
    fun wantsNegative(): Boolean = CaptureFormat.Dng in _formats.value

    /** The two free slots at the end of the band. Exposure then nothing is where the app started. */
    private val _bandSlots = MutableStateFlow(
        listOf(
            slotOf(prefs.getString(BAND_ONE, null), BandSlot.Exposure),
            slotOf(prefs.getString(BAND_TWO, null), BandSlot.None),
        ),
    )
    val bandSlots: StateFlow<List<BandSlot>> = _bandSlots.asStateFlow()

    fun pressFor(binding: Binding): PressAction =
        PressAction.byName(_bindings.value[binding] ?: binding.default)

    fun dialFor(binding: Binding): DialAction =
        DialAction.byName(_bindings.value[binding] ?: binding.default)

    fun setBinding(binding: Binding, action: String) {
        _bindings.value = _bindings.value + (binding to action)
        prefs.edit().putString(bindKey(binding), action).apply()
    }

    /** Back to the mapping the app shipped with, which is the one the documentation describes. */
    fun resetBindings() {
        _bindings.value = Binding.entries.associate { it to it.default }
        prefs.edit().apply { Binding.entries.forEach { remove(bindKey(it)) } }.apply()
    }

    fun setBandSlot(index: Int, slot: BandSlot) {
        if (index !in 0..1) return
        _bandSlots.value = _bandSlots.value.toMutableList().also { it[index] = slot }
        prefs.edit().putString(if (index == 0) BAND_ONE else BAND_TWO, slot.name).apply()
    }

    fun setFilter(id: String) {
        _filterId.value = id
    }

    fun setMode(value: CaptureMode) {
        _mode.value = value
    }

    fun setAspect(value: FrameAspect) = set(_aspect, value) { putString(ASPECT, value.label) }

    fun setPhotoSize(value: PhotoSize) = set(_photoSize, value) { putString(SIZE, value.name) }

    fun setChrome(value: Chrome) = set(_chrome, value) { putString(CHROME, value.name) }

    fun setFlash(value: FlashMode) = set(_flash, value) { putString(FLASH, value.name) }

    fun setAfMode(value: AfMode) = set(_afMode, value) { putString(AF_MODE, value.name) }

    fun setFacePriority(value: Boolean) =
        set(_facePriority, value) { putBoolean(FACE_PRIORITY, value) }

    fun setTimer(value: SelfTimer) = set(_timer, value) { putString(TIMER, value.name) }

    fun setScope(value: RollScope) = set(_scope, value) { putString(SCOPE, value.name) }

    fun setRollLength(value: Int) = set(_rollLength, value) { putInt(ROLL_LENGTH, value) }

    fun setWheelEnabled(value: Boolean) = set(_wheelEnabled, value) { putBoolean(WHEEL, value) }

    fun setDialLock(value: Boolean) = set(_dialLock, value) { putBoolean(DIAL_LOCK, value) }

    fun setSounds(value: Boolean) = set(_sounds, value) { putBoolean(SOUNDS, value) }

    fun setLevel(value: Boolean) = set(_level, value) { putBoolean(LEVEL, value) }

    fun setTimings(value: Boolean) = set(_timings, value) { putBoolean(TIMINGS, value) }

    fun setSimpleMode(value: Boolean) = set(_simpleMode, value) { putBoolean(SIMPLE_MODE, value) }

    /* ---------------- the Preset grade ---------------- */

    /**
     * The ten adjustments, stored one key per adjustment.
     *
     * **One key each rather than a serialised blob**, keyed off the enum's own name. A blob would
     * have to be versioned the first time an adjustment is added or renamed, and this way a key
     * that is not there yet simply reads as zero — which is exactly what a new adjustment should
     * default to.
     */
    private val _grade = MutableStateFlow(readGrade())
    val grade: StateFlow<Grade> = _grade.asStateFlow()

    private fun readGrade(): Grade {
        var out = Grade()
        Adjust.entries.forEach { adjust ->
            out = out.with(adjust, prefs.getInt(gradeKey(adjust), 0))
        }
        return out
    }

    fun setGrade(value: Grade) = set(_grade, value) {
        Adjust.entries.forEach { adjust -> putInt(gradeKey(adjust), value[adjust]) }
    }

    fun stepGrade(adjust: Adjust, by: Int) = setGrade(_grade.value.step(adjust, by))

    /** Back to the plain photograph, in one tap. The menu's only destructive control. */
    fun clearGrade() = setGrade(Grade.NEUTRAL)

    // All written down: a switch you flick should stay flicked.
    fun setPuriFrame(value: String) = set(_puriFrame, value) { putString(PURI_FRAME, value) }

    fun setPuriFaceStickers(value: Boolean) =
        set(_puriFaceStickers, value) { putBoolean(PURI_FACE, value) }

    fun setPuriMarginStickers(value: Boolean) =
        set(_puriMarginStickers, value) { putBoolean(PURI_MARGIN, value) }

    fun setPuriDate(value: String) = set(_puriDate, value) { putString(PURI_DATE, value) }

    fun setPuriStrip(value: String) = set(_puriStrip, value) { putString(PURI_STRIP, value) }

    fun setPuriWash(value: Boolean) = set(_puriWash, value) { putBoolean(PURI_WASH, value) }

    fun setPuriSkin(value: Boolean) = set(_puriSkin, value) { putBoolean(PURI_SKIN, value) }

    fun setPuriEyes(value: Boolean) = set(_puriEyes, value) { putBoolean(PURI_EYES, value) }

    fun setPuriChin(value: Boolean) = set(_puriChin, value) { putBoolean(PURI_CHIN, value) }

    fun setPuriSlim(value: Boolean) = set(_puriSlim, value) { putBoolean(PURI_SLIM, value) }

    fun setStampPlain(value: Boolean) = set(_stampPlain, value) { putBoolean(STAMP_PLAIN, value) }

    fun setStampFiltered(value: Boolean) =
        set(_stampFiltered, value) { putBoolean(STAMP_FILTERED, value) }

    fun setStampCoarse(value: Boolean) = set(_stampCoarse, value) { putBoolean(STAMP_COARSE, value) }

    fun setStampStyle(value: StampStyle) =
        set(_stampStyle, value) { putString(STAMP_STYLE, value.name) }

    fun setColour(value: Colour) = set(_colour, value) { putString(COLOUR, value.name) }

    fun setSendToLightChat(value: Boolean) =
        set(_sendToLightChat, value) { putBoolean(SEND_LIGHTCHAT, value) }

    private fun <T> set(
        flow: MutableStateFlow<T>,
        value: T,
        write: android.content.SharedPreferences.Editor.() -> Unit,
    ) {
        flow.value = value
        prefs.edit().apply(write).apply()
    }

    private companion object {
        const val ASPECT = "aspect"
        const val SIZE = "photoSize"
        const val CHROME = "chrome"
        const val FLASH = "flash"
        const val AF_MODE = "afMode"
        const val FACE_PRIORITY = "facePriority"
        const val TIMER = "timer"
        const val SCOPE = "scope"
        const val ROLL_LENGTH = "rollLength"
        const val WHEEL = "wheel"
        const val DIAL_LOCK = "dialLock"
        const val SOUNDS = "sounds"
        const val LEVEL = "level"
        const val TIMINGS = "timings"
        const val SIMPLE_MODE = "simpleMode"
        const val PURI_FRAME = "puriFrame2"
        const val PURI_DATE = "puriDate"
        const val PURI_STRIP = "puriStrip"
        const val PURI_FACE = "puriFace"
        const val PURI_MARGIN = "puriMargin"
        const val PURI_WASH = "puriWash"
        const val PURI_SKIN = "puriSkin"
        const val PURI_EYES = "puriEyes"
        const val PURI_CHIN = "puriChin"
        const val PURI_SLIM = "puriSlim"
        /** `grade.warmth`, `grade.vibrance`. Namespaced so a future adjustment cannot collide. */
        fun gradeKey(adjust: Adjust): String = "grade." + adjust.name

        const val FAVOURITES = PrefsFile.FAVOURITES
        /** Kept only so an existing setting can be read forward once. */
        const val DATE_STAMP = "dateStamp"
        const val STAMP_PLAIN = "stampPlain"
        const val STAMP_FILTERED = "stampFiltered"
        const val STAMP_COARSE = "stampCoarse"
        const val STAMP_STYLE = "stampStyle"
        const val COLOUR = "colour"
        const val SEND_LIGHTCHAT = "sendLightChat"
        const val RECENT_RECIPIENTS = "recentRecipients"
        const val FILTER_ORDER = "filterOrder"
        const val FILTERS_OFF = "filtersOff"
        const val HISTOGRAM = "histogram"
        const val CLIPPING = "clipping"
        const val BURST = "burst"
        const val FORMATS = "captureFormats"
        const val PRE_ROLL = "preRollMs"
        const val TAG_LOCATION = "tagLocation"
        const val FLAT = "flatProfile"
        const val LENS_CORRECTION = "lensCorrection"
        const val ZONE_FOCUS = "zoneFocus"
        const val EXPOSURE_MODE = "exposureMode"
        const val BAND_ONE = "bandSlot1"
        const val BAND_TWO = "bandSlot2"

        fun bindKey(binding: Binding): String = "bind_${binding.name}"

        fun slotOf(name: String?, fallback: BandSlot): BandSlot =
            BandSlot.entries.firstOrNull { it.name == name } ?: fallback
    }
}

/**
 * The two things another app needs to read a star without building the whole of [Prefs].
 *
 * A top-level object rather than a companion: [Prefs] already has one and it is private, a class may
 * only have the one, and making that private companion public would export every preference key in
 * the app to satisfy two of them.
 *
 * `StarsProvider` can be queried while nothing else here is running, and constructing Prefs with its
 * dozen state flows to answer a list of strings would be absurd — so it opens the file itself, and
 * these are the names it needs to do that.
 */
object PrefsFile {
    const val NAME = "camera"
    const val FAVOURITES = "favourites"
}
