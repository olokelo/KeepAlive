package io.keepalive.android

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task


// implementation for use with Google Play Services Location APIs
class LocationHelper(
    context: Context,
    myCallback: (Context, String) -> Unit
) : LocationHelperBase(context, myCallback) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // cancellation token for use with fusedLocationClient.getCurrentLocation()
    private val cancellationTokenSource = CancellationTokenSource()

    // override the global timeout function so we can cancel the cancellationTokenSource
    override val globalTimeoutRunnable = Runnable {

        // cancel the token, may or may not still be in use
        cancellationTokenSource.cancel()

        DebugLogger.d("globalTimeoutRunnable", "Timeout reached while getting " +
                "location from google play?!")

        myCallback(context, context.getString(R.string.location_invalid_message))
    }

    // get the current location the Google Play Services location API
    @SuppressLint("MissingPermission")
    override fun getCurrentLocation() {

        // was originally done like this because we thought we needed to customize
        //  the options but we really don't?
        // https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder
        val currentLocReq = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)

            // timeout isn't respected in doze mode???
            .setDurationMillis(locationRequestTimeoutLength)
            //.setMaxUpdateAgeMillis(5000)
            .build()

        // this is more accurate and up to date than .lastLocation
        fusedLocationClient.getCurrentLocation(currentLocReq, cancellationTokenSource.token)
            .addOnCompleteListener { task ->
                processLocationResult(task, "current")
            }
    }

    @SuppressLint("MissingPermission")
    override fun getLastLocation() {

        DebugLogger.d("getLastLocation", "Attempting to get lastLocation...")

        try {

            fusedLocationClient.lastLocation.addOnCompleteListener { task ->
                processLocationResult(task, "last")
            }

        } catch (e: Exception) {
            DebugLogger.d("getLastLocation", "Failed while getting last location:", e)

            // if we failed to get the last location then just send the error message
            executeCallback(context.getString(R.string.location_invalid_message))
        }
    }

    // process the result from getting either the Last or the Current location
    private fun processLocationResult(task: Task<Location>, locationSource: String) {

        // This block will be executed whether the task was successful or not
        if (task.isSuccessful && task.result != null) {
            val location = task.result

            // it doesn't matter where we get the location from, if we get one then we can
            //  geocode it and send it to the callback
            Log.d(
                "processLocationResult",
                "$locationSource Location from ${location.provider} is " +
                        "(${location.latitude}, ${location.longitude}) ${location.accuracy}acc"
            )

            // try to geocode the location and then execute the callback
            GeocodingHelper().geocodeLocationAndExecute(location)

        } else {

            DebugLogger.d(
                "processLocationResult",
                "Failed while trying to get the $locationSource location",
                task.exception
            )

            // if we just failed to get the current location then try to get the last location
            if (locationSource == "current") {
                getLastLocation()

                // otherwise if we fail to get the last location then just send the error message
            } else {
                Log.d("processLocationResult", "Unable to determine location, executing callback")
                executeCallback(context.getString(R.string.location_invalid_message))
            }
        }
    }
}