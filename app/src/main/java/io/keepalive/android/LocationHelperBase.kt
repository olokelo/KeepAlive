package io.keepalive.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale


// base class for the location helper, this will be extended based on whether
//  we are using android.location (for f-droid) or com.google.android.gms.location (for google play)
open class LocationHelperBase(
    val context: Context,
    val myCallback: (Context, String) -> Unit,
) {

    lateinit var locationManager: LocationManager
    private var locationEnabled = false
    private var isDeviceIdleMode = false
    private var isPowerSaveMode = false
    var availableProviders: MutableList<String> = arrayListOf()

    // how long to wait for location requests to complete before timing out
    val locationRequestTimeoutLength = 30000L

    // how long to wait for geocoding requests to complete before timing out
    val geocodingRequestTimeoutLength = 30000L

    // how long to wait for everything to complete before timing out
    private val globalTimeoutLength = 61000L

    // timeout handler to make sure the entire location process doesn't hang
    private val globalTimeoutHandler = Handler(context.mainLooper)

    init {

        // I am worried that some of this may throw an exception in certain edge cases so wrap
        //  everything in a try/catch and use separate vars
        try {
            // check whether the GPS, network and location services are enabled
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            locationEnabled = locationManager.isLocationEnabled

            availableProviders = locationManager.getProviders(true)

            // remove the passive provider because it relies on another app requesting location
            //  as a way to save battery. this causes it to time out most of the time
            // note that this is only relevant when using android.location
            if (LocationManager.PASSIVE_PROVIDER in availableProviders) {
                availableProviders.remove(LocationManager.PASSIVE_PROVIDER)
            }

            // check whether the device is in idle or power save mode
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            isDeviceIdleMode = powerManager.isDeviceIdleMode
            isPowerSaveMode = powerManager.isPowerSaveMode

        } catch (e: Exception) {
            Log.e("LocationHelperBase", "Error checking GPS or network provider", e)
        }

        Log.d(
            "LocationHelperBase",
            "Location enabled: $locationEnabled. Available providers: $availableProviders"
        )
    }

    // these will get overridden with platform specific implementations based on whether
    //  we are using android.location or com.google.android.gms.location
    open fun getLastLocation() {}
    open fun getCurrentLocation() {}

    open val globalTimeoutRunnable = Runnable {

        DebugLogger.d("globalTimeoutRunnable", "Timeout reached while getting location?!")

        myCallback(context, context.getString(R.string.location_invalid_message))
    }

    private fun startGlobalTimeoutHandler() {
        globalTimeoutHandler.postDelayed(globalTimeoutRunnable, globalTimeoutLength)
    }

    private fun stopGlobalTimeoutHandler() {
        globalTimeoutHandler.removeCallbacks(globalTimeoutRunnable)
    }

    fun executeCallback(locationString: String) {

        // stop the global timeout handler and execute the callback
        stopGlobalTimeoutHandler()
        myCallback(context, locationString)
    }

    // depending on the current power state, try to get the current location or the last location
    //  and then geocode it and then pass it to the callback
    // all paths should result in the callback being executed or we may fail to send an alert!
    fun getLocationAndExecute() {

        startGlobalTimeoutHandler()

        DebugLogger.d("getLocationAndExecute", "Attempting to get location...")
        try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                Log.d(
                    "getLocationAndExecute",
                    "Power save mode is $isPowerSaveMode. " +
                            "Is device idle? $isDeviceIdleMode"
                )

                // if the device is in power save mode then we can't get the current location or it
                //  will just freeze and never return?
                if (!isDeviceIdleMode) {

                    // do a bunch of stuff to try to get the current location and then
                    //  execute the callback with whatever the results are
                    getCurrentLocation()

                } else {

                    DebugLogger.d(
                        "getLocationAndExecute",
                        "Device is in idle mode, not getting current location"
                    )

                    // if the device is in idle mode then try to get the last location
                    getLastLocation()
                }
            } else {

                // if we don't have location permissions then just execute the callback
                DebugLogger.d("getLocationAndExecute", "Don't have location permission, executing callback")
                stopGlobalTimeoutHandler()
                myCallback(context, context.getString(R.string.location_invalid_message))
            }
        } catch (e: Exception) {

            // if we for some reason fail while building the request then try
            //   to get the last location
            DebugLogger.d("getLocationAndExecute", "Failed getting current location?!", e)
            getLastLocation()
        }
    }

    inner class GeocodingHelper {

        private var locationString = ""
        private val geocodingTimeoutHandler = Handler(context.mainLooper)


        // timeout handler for the geocoding process, really only necessary in API 33+
        //  because the old geocoding method is synchronous
        private val geocodingTimeoutRunnable = Runnable {

            DebugLogger.d("geocodingTimeoutRunnable", "Timeout reached, locationString is $locationString")

            // the global timeout handler should still be running so need to stop it
            stopGlobalTimeoutHandler()

            myCallback(context, locationString)
        }

        private fun startGeocodingTimeoutHandler() {
            geocodingTimeoutHandler.postDelayed(
                geocodingTimeoutRunnable,
                geocodingRequestTimeoutLength
            )
        }

        private fun stopGeocodingTimeoutHandler() {
            geocodingTimeoutHandler.removeCallbacks(geocodingTimeoutRunnable)
        }

        // try to geocode the location and then execute the callback
        // all paths need to lead to the callback being executed or we may fail to send an alert!!
        fun geocodeLocationAndExecute(loc: Location) {

            try {
                Log.d(
                    "geocodeLocationAndExecute",
                    "Geocoding location: ${loc.latitude}, ${loc.longitude}, ${loc.accuracy}"
                )

                // default to a message indicating we couldn't geocode the location and just include
                //  the raw GPS coordinates
                locationString = String.format(
                    context.getString(R.string.geocode_invalid_message),
                    loc.latitude, loc.longitude, loc.accuracy
                )

                // start a timeout handler in case the geocoder hangs
                startGeocodingTimeoutHandler()

                val geocoder = Geocoder(context, Locale.getDefault())

                // GeocodeListener is only available in API 33+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                    val geocodeListener = Geocoder.GeocodeListener { addresses ->

                        Log.d(
                            "geocodeLocationAndExecute",
                            "listener done, geocode result: $addresses"
                        )
                        val addressString = processGeocodeResult(addresses)
                        locationString = buildGeocodedLocationStr(addressString, loc)

                        // execute the callback with the new location string
                        stopGeocodingTimeoutHandler()
                        executeCallback(locationString)
                    }

                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1, geocodeListener)
                    return

                } else {

                    // the synchronous version is deprecated but nothing else available in <33
                    val addresses: List<Address> =
                        geocoder.getFromLocation(loc.latitude, loc.longitude, 1)!!

                    Log.d("geocodeLocationAndExecute", "geocode result: $addresses")
                    val addressString = processGeocodeResult(addresses)
                    locationString = buildGeocodedLocationStr(addressString, loc)
                }

            } catch (e: Exception) {
                DebugLogger.d("geocodeLocationAndExecute", "Failed geocoding GPS coordinates?!", e)
            }

            // if we aren't using geocode listener or if there was an error
            stopGeocodingTimeoutHandler()
            executeCallback(locationString)
        }

        // take the list of possible addresses and build a string
        private fun processGeocodeResult(addresses: List<Address>): String {

            var addressString = ""

            if (addresses.isNotEmpty()) {
                Log.d(
                    "processGeocodeResult",
                    "Address has ${addresses[0].maxAddressLineIndex + 1} lines"
                )

                // we should have only requested a single address so just check the first in the list
                // most addresses only have a single line?
                for (i in 0..addresses[0].maxAddressLineIndex) {

                    val addressLine: String = addresses[0].getAddressLine(i)

                    // include as many address lines as we can in the SMS
                    // +2 because we are adding a period and a space
                    if ((addressString.length + addressLine.length + 2) < AppController.SMS_MESSAGE_MAX_LENGTH) {
                        addressString += "$addressLine. "
                    } else {
                        Log.d(
                            "processGeocodeResult",
                            "Not adding address line, would exceed character limit: $addressLine"
                        )
                    }
                }

            } else {
                DebugLogger.d("processGeocodeResult", "No address results")
            }
            return addressString
        }

        // build the location string that will be sent to the callback
        private fun buildGeocodedLocationStr(addressStr: String, loc: Location): String {
            return if (addressStr == "") {
                String.format(
                    context.getString(R.string.geocode_invalid_message),
                    loc.latitude, loc.longitude, loc.accuracy
                )
            } else {
                String.format(
                    context.getString(R.string.geocode_valid_message),
                    loc.latitude, loc.longitude, loc.accuracy, addressStr
                )
            }
        }
    }
}