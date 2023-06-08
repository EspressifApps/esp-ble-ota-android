package com.espressif.bleota.android

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat
import java.util.*
import kotlin.collections.LinkedHashMap

val UUID_NOTIFY_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

fun Context.isPermissionGranted(permission: String): Boolean {
    return ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

fun Context.isPermissionDenied(permission: String): Boolean {
    return ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_DENIED
}

fun Activity.requestPermission(permission: String, requestCode: Int) {
    ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
}

fun Context.isLocationEnabled(): Boolean {
    val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return LocationManagerCompat.isLocationEnabled(locationManager)
}

fun Context.isLocationDisabled(): Boolean {
    val locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return !LocationManagerCompat.isLocationEnabled(locationManager)
}

fun View.visible() {
    visibility = View.VISIBLE
}

fun View.invisible() {
    visibility = View.INVISIBLE
}

fun View.gone() {
    visibility = View.GONE
}

fun bleUUID(uuid: String): UUID {
    return UUID.fromString("0000$uuid-0000-1000-8000-00805f9b34fb")
}

fun <K, V> LinkedHashMap<K, V>.getByIndex(index: Int): MutableMap.MutableEntry<K, V> {
    if (index < 0 || index >= size) {
        throw ArrayIndexOutOfBoundsException(index)
    }
    var count = 0
    for (entry in this) {
        if (count == index) {
            return entry
        }
        ++count
    }
    throw ArrayIndexOutOfBoundsException(index)
}