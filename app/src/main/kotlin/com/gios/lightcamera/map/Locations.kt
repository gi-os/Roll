package com.gios.lightcamera.map

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a photograph was taken: putting it on, and getting it back off.
 *
 * **Two separate permissions, and the second one surprises people.** Writing a coordinate needs
 * location access, obviously. *Reading* one back off a photograph needs `ACCESS_MEDIA_LOCATION` as
 * well, because since Android 10 MediaStore strips GPS out of anything it hands you unless you ask
 * for the original — which is a deliberate protection and not a bug to work around. The map is
 * empty without it, and empty in a way that looks like the photographs have no location rather
 * than like a permission is missing.
 */
object Locations {

    private const val TAG = "Locations"

    /**
     * The last fix the phone already has, rather than a fresh one.
     *
     * **A camera must not wait for a GPS fix.** Asking for a live update at the shutter costs
     * seconds and a radio, on a press whose entire argument is that it happens now. The last known
     * position is nearly always the right one — you were standing there a moment ago — and where
     * it is stale or missing the photograph simply has no coordinate, which is a better outcome
     * than a slow shutter.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Location? {
        if (!granted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            // Fine first where it is granted, then coarse. Network fixes are usually fresher
            // indoors, which is where most photographs of people are taken.
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            providers.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
        }.onFailure { Log.e(TAG, "no last known location", it) }.getOrNull()
    }

    /**
     * Everything the map needs, for one request dialog.
     *
     * **These were declared in the manifest and requested nowhere.** A manifest entry is a
     * declaration of intent, not a grant, and every code path here checks and quietly does nothing
     * when the check fails — which is right at the shutter, and adds up to a map that is always
     * empty and a tagging setting that never tags, with no line anywhere saying why. Found in
     * review: the feature shipped dead.
     *
     * Fine is included alongside coarse so the system offers the choice; either grant is enough
     * for a map. `ACCESS_MEDIA_LOCATION` is the one nobody expects — it gates *reading* a
     * coordinate back off a photograph, including one this app wrote a second earlier.
     */
    fun wanted(): Array<String> = buildList {
        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }.toTypedArray()

    /** Whether anything at all can be tagged. Coarse is enough; fine is a bonus. */
    fun canTag(context: Context): Boolean =
        granted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canReadLocations(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION)

    /**
     * Write a coordinate into a file that has already been saved.
     *
     * After the fact rather than into the bytes, because the bytes are produced in three different
     * places — the ISP's own JPEG, the shader's re-encode, and whatever CameraX wrote for a
     * negative — and threading a location through all three is three chances to lose it.
     *
     * PNG is skipped on purpose: it has no dependable place to keep this, and the lossless copy is
     * always a sibling of a JPEG that does. The map reads a group, not a file.
     */
    suspend fun stamp(resolver: ContentResolver, uri: Uri, location: Location): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                    val exif = ExifInterface(descriptor.fileDescriptor)
                    exif.setLatLong(location.latitude, location.longitude)
                    if (location.hasAltitude()) exif.setAltitude(location.altitude)
                    exif.saveAttributes()
                } ?: error("no descriptor")
                true
            }.onFailure { Log.e(TAG, "could not stamp a location onto $uri", it) }
                .getOrDefault(false)
        }

    /**
     * Read a coordinate back off a saved photograph.
     *
     * `setRequireOriginal` is the whole function. Without it MediaStore returns a copy with the
     * GPS tags removed and this reads null for every photograph on the phone, including ones this
     * app stamped itself a second earlier.
     */
    suspend fun read(resolver: ContentResolver, uri: Uri): Point? = withContext(Dispatchers.IO) {
        runCatching {
            val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(uri)
            } else {
                uri
            }
            resolver.openInputStream(original)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                @Suppress("DEPRECATION")
                if (exif.getLatLong(latLong)) {
                    Point(latLong[0].toDouble(), latLong[1].toDouble())
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
