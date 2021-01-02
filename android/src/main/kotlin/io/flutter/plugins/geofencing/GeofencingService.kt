// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.google.android.gms.location.GeofencingEvent
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.PluginRegistrantCallback
import io.flutter.view.FlutterCallbackInformation
import io.flutter.view.FlutterMain
import io.flutter.view.FlutterNativeView
import io.flutter.view.FlutterRunArguments
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import java.util.UUID

import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback


class GeofencingService : MethodCallHandler, JobIntentService() {
    private val queue = ArrayDeque<List<Any>>()
    private lateinit var mBackgroundChannel: MethodChannel
    private lateinit var mContext: Context

    companion object {
        const val MINIMUM_ACCURACY = 100

        @JvmStatic
        private val TAG = "GeofencingService"

        @JvmStatic
        private val JOB_ID = UUID.randomUUID().mostSignificantBits.toInt()

        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null
        @JvmStatic
        private val sServiceStarted = AtomicBoolean(false)

        @JvmStatic
        private lateinit var sPluginRegistrantCallback: PluginRegistrantCallback

        @JvmStatic
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, GeofencingService::class.java, JOB_ID, work)
        }

        @JvmStatic
        fun setPluginRegistrant(callback: PluginRegistrantCallback) {
            sPluginRegistrantCallback = callback
        }
    }

    private fun startGeofencingService(context: Context) {
        synchronized(sServiceStarted) {
            mContext = context
            if (sBackgroundFlutterEngine == null) {
                val callbackHandle = context.getSharedPreferences(
                        GeofencingPlugin.SHARED_PREFERENCES_KEY,
                        Context.MODE_PRIVATE)
                        .getLong(GeofencingPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0L)
                if (callbackHandle == 0L) {
                    Log.e(TAG, "Geofencing Fatal error: CallbackHandle is invalid $callbackHandle")
                    stopSelf()
                    return
                }

                Log.i(TAG, "Starting GeofencingService...")
                sBackgroundFlutterEngine = FlutterEngine(context)
                // We need to create an instance of `FlutterEngine` before looking up the
                // callback. If we don't, the callback cache won't be initialized and the
                // lookup will fail.
                val callbackInfo: FlutterCallbackInformation?
                try {
                    callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                } catch (e: Exception) {
                    Log.e(TAG, "Geofencing Fatal error: couldn't find FlutterCallbackInformation by this $callbackHandle key: $e")
                    stopSelf()
                    return
                }

                val args = DartCallback(
                    context.getAssets(),
                    FlutterMain.findAppBundlePath(context)!!,
                    callbackInfo
                )
                sBackgroundFlutterEngine!!.getDartExecutor().executeDartCallback(args)
                IsolateHolderService.setBackgroundFlutterEngine(sBackgroundFlutterEngine)
            }
        }
        try {
            mBackgroundChannel = MethodChannel(sBackgroundFlutterEngine!!.getDartExecutor().getBinaryMessenger(),
                    "plugins.flutter.io/geofencing_plugin_background")
            mBackgroundChannel.setMethodCallHandler(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing geofencing_plugin_background plugin: $e")
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "GeofencingService.initialized" -> {
                synchronized(sServiceStarted) {
                    while (!queue.isEmpty()) {
                        try {
                            mBackgroundChannel.invokeMethod("", queue.remove())
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed GeofencingService.initialized: $e")
                        }
                    }
                    sServiceStarted.set(true)
                }
            }
            "GeofencingService.promoteToForeground" -> {
                mContext.startForegroundService(Intent(mContext, IsolateHolderService::class.java))
            }
            "GeofencingService.demoteToBackground" -> {
                val intent = Intent(mContext, IsolateHolderService::class.java)
                intent.setAction(IsolateHolderService.ACTION_SHUTDOWN)
                mContext.startForegroundService(intent)
            }
            else -> result.notImplemented()
        }
        result.success(null)
    }

    override fun onCreate() {
        super.onCreate()
        startGeofencingService(this)
    }

    private fun createLocationRequest(): LocationRequest {
        val locationRequest = LocationRequest()

        locationRequest.interval = 60000 // Every 60 seconds
        locationRequest.fastestInterval = 30000 // Every 30 seconds
        locationRequest.maxWaitTime = 200000 // Every 5 minutes

        locationRequest.priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY

        return locationRequest
    }

    private fun requestSingleAccurateLocation(intent: Intent) {
        val maxRetries = 5
        val request = createLocationRequest()
        request.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        request.numUpdates = maxRetries
        LocationServices.getFusedLocationProviderClient(mContext)
            .requestLocationUpdates(
                request,
                object : LocationCallback() {
                    val wakeLock: PowerManager.WakeLock? =
                        getSystemService(mContext, PowerManager::class.java)
                            ?.newWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK,
                                "GeoFencing::AccurateLocation"
                            )?.apply { acquire(2 * 60 * 1000L /*2 minutes*/) }
                    var numberCalls = 0
                    override fun onLocationResult(locationResult: LocationResult?) {
                        numberCalls++
                        Log.d(
                            TAG,
                            "Got single accurate location update: ${locationResult?.lastLocation}"
                        )
                        if (locationResult == null) {
                            Log.w(TAG, "No location provided.")
                            return
                        }

                        when {
                            locationResult.lastLocation.accuracy <= MINIMUM_ACCURACY -> {
                                Log.d(TAG, "Location accurate enough, all done with high accuracy.")
                                callCallback(intent, listOf(locationResult.lastLocation.latitude, locationResult.lastLocation.longitude,
                                             locationResult.lastLocation.accuracy.toDouble()));
                                if (wakeLock?.isHeld == true) wakeLock.release()
                            }
                            numberCalls >= maxRetries -> {
                                Log.d(
                                    TAG,
                                    "No location was accurate enough, sending our last location anyway"
                                )
                                if (locationResult.lastLocation.accuracy <= MINIMUM_ACCURACY * 2)
                                    callCallback(intent, listOf(locationResult.lastLocation.latitude, locationResult.lastLocation.longitude,
                                                 locationResult.lastLocation.accuracy.toDouble()));
                                if (wakeLock?.isHeld == true) wakeLock.release()
                            }
                            else -> {
                                Log.w(
                                    TAG,
                                    "Location not accurate enough on retry $numberCalls of $maxRetries"
                                )
                            }
                        }
                    }
                },
                mContext.mainLooper
            )
    }

    private fun callCallback(intent: Intent, locationList: List<Double>) {
        val callbackHandle = intent.getLongExtra(GeofencingPlugin.CALLBACK_HANDLE_KEY, 0)

        val geofenceUpdateList = listOf(callbackHandle, locationList)

        synchronized(sServiceStarted) {
            if (!sServiceStarted.get()) {
                // Queue up geofencing events while background isolate is starting
                queue.add(geofenceUpdateList)
            } else {
                try {// Callback method name is intentionally left blank.
                    Handler(mContext.mainLooper).post { mBackgroundChannel.invokeMethod("", geofenceUpdateList) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed running Handler.post invoking geofence update $e")
                }
            }
        }
    }

    override fun onHandleWork(intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val location = geofencingEvent.triggeringLocation
        if (location.accuracy > MINIMUM_ACCURACY) {
            requestSingleAccurateLocation(intent);
            return;
        }

        val locationList = listOf(location.latitude, location.longitude, location.accuracy.toDouble())
        callCallback(intent, locationList);

    }
}
