package com.gios.lightcamera.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.gios.lightcamera.R

/**
 * LightOS's own icon set. The vector drawables in `res/drawable/ic_*` are copied from
 * `lightphone/light-sdk` (MIT licence, © 2026 The Light Phone — see LICENSE-light-sdk);
 * an app that draws its own back chevron never quite looks like it belongs on the phone.
 *
 * The `ic_camera_*` ones are the drawables LightOS's own camera uses, which is why the
 * flash cycle and the focus-lock bracket here look exactly like the stock app's.
 */
class LightIconSpec(val name: String, @DrawableRes val res: Int)

object LightIcons {
    val Camera = LightIconSpec("camera", R.drawable.ic_camera)
    val FlashOn = LightIconSpec("flash on", R.drawable.ic_camera_flash_on)
    val FlashOff = LightIconSpec("flash off", R.drawable.ic_camera_flash_off)
    val FlashAuto = LightIconSpec("flash auto", R.drawable.ic_camera_flash_auto)
    val FocusLocked = LightIconSpec("focus locked", R.drawable.ic_camera_focus_locked)
    val FocusLocking = LightIconSpec("focusing", R.drawable.ic_camera_focus_locking)
    val CameraSettings = LightIconSpec("camera settings", R.drawable.ic_camera_settings)
    val Exposure = LightIconSpec("exposure", R.drawable.ic_camera_brightness)

    /**
     * The album. `ic_camera_landscape` is the SDK's name for it, but the drawing is a frame
     * with two hills and a sun — the same glyph the stock camera puts in the left slot of its
     * bottom bar to open the album, which is exactly what it is used for here.
     */
    val Album = LightIconSpec("album", R.drawable.ic_camera_landscape)
    val SaveToAlbum = LightIconSpec("save", R.drawable.ic_save_to_album)
    val FlipLens = LightIconSpec("flip", R.drawable.ic_rotate_white)
    val Crosshair = LightIconSpec("crosshair", R.drawable.ic_crosshair_white)
    val Trash = LightIconSpec("delete", R.drawable.ic_trash)
    val Close = LightIconSpec("close", R.drawable.ic_close_white)
    val Back = LightIconSpec("back", R.drawable.ic_back_white)
    val Accept = LightIconSpec("confirm", R.drawable.ic_accept_white)
    val Settings = LightIconSpec("settings", R.drawable.ic_settings_white)
    val Grid = LightIconSpec("filters", R.drawable.ic_large_list_white)
    val List = LightIconSpec("list", R.drawable.ic_list_white)
    val SelectOn = LightIconSpec("selected", R.drawable.ic_select_on_white)
    val SelectOff = LightIconSpec("not selected", R.drawable.ic_select_off_white)
    val Share = LightIconSpec("share", R.drawable.ic_send_white)
    val Down = LightIconSpec("down", R.drawable.ic_down_white)
    val Up = LightIconSpec("up", R.drawable.ic_up_white)
    val ArrowDown = LightIconSpec("arrow down", R.drawable.ic_arrow_down_white)
    val Logo = LightIconSpec("light", R.drawable.ic_light_logo_white)
    val Circle = LightIconSpec("circle", R.drawable.ic_circle_white)
    val Add = LightIconSpec("add", R.drawable.ic_add_white)
    val Refresh = LightIconSpec("refresh", R.drawable.ic_refresh_white)
    val Reverse = LightIconSpec("reverse", R.drawable.ic_reverse_order_white)
    val Star = LightIconSpec("star", R.drawable.ic_star_white)
    val StarOutline = LightIconSpec("unstarred", R.drawable.ic_star_outline_white)

    /**
     * The two glyphs the SDK does not have, drawn here in its own hairline style -- 40dp
     * viewport, 1px strokes, no fills -- so they sit beside the copied ones without a seam.
     * Zoom is the magnifier with a plus; Filter is three lenses overlapped, which is what a
     * filter dial holds.
     */
    val Zoom = LightIconSpec("zoom", R.drawable.ic_zoom_white)
    val Filter = LightIconSpec("filters", R.drawable.ic_filter_white)
}

@Composable
fun LightIcon(
    icon: LightIconSpec,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Icon(
        painter = painterResource(icon.res),
        contentDescription = icon.name,
        modifier = modifier.size(size),
        tint = tint ?: LightThemeTokens.colors.content,
    )
}
