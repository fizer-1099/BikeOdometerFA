package ir.balasari.bikeodometer

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices

/**
 * موقعیت‌یابی با Google Play Services؛ اگر روی گوشی نبود، با GPS خود اندروید.
 * امضای تابع‌ها مثل FusedLocationProviderClient است تا بقیه‌ی کد تغییری نکند.
 */
class SafeLocation(context: Context) {
    private val app = context.applicationContext

    private val fused: FusedLocationProviderClient? = try {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS) {
            LocationServices.getFusedLocationProviderClient(app)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private val listeners = HashMap<LocationCallback, LocationListener>()

    fun requestLocationUpdates(request: LocationRequest, callback: LocationCallback, looper: Looper) {
        val f = fused
        if (f != null) {
            f.requestLocationUpdates(request, callback, looper)
            return
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        removeLocationUpdates(callback)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                callback.onLocationResult(LocationResult.create(listOf(location)))
            }

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        listeners[callback] = listener
        lm.requestLocationUpdates(provider, request.intervalMillis, 0f, listener, looper)
    }

    fun removeLocationUpdates(callback: LocationCallback) {
        val f = fused
        if (f != null) {
            f.removeLocationUpdates(callback)
            return
        }
        val l = listeners.remove(callback) ?: return
        try {
            (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(l)
        } catch (_: Exception) {
        }
    }
}
