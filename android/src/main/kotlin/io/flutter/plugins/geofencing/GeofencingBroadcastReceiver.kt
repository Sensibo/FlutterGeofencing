// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.flutter.view.FlutterMain

class GeofencingBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = GeofencingBroadcastReceiver::class.java.simpleName
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            FlutterMain.startInitialization(context)
            FlutterMain.ensureInitializationComplete(context, null)
            GeofencingService.enqueueWork(context, intent)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed enqueueing work: $e")
//            throw GeofencingException(e, "Failed enqueueing work:")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed running ensureInitializationComplete on ${System.getProperty("os.arch")} architecture: $e")
//            throw GeofencingError(e, "Failed running ensureInitializationComplete on ${System.getProperty("os.arch")} architecture: ")
        } catch (e: Exception) {
            Log.e(TAG, "Unknown error: $e")
//            throw GeofencingException(e, "Unknown error:")
        }
    }
}

/// TODO Uncomment in case there will be crashes but no records in Crashlytics
//class GeofencingException(e: Exception, private val msg: String? = null) : Exception(e) {
//    override val message: String?
//        get() = "${msg ?: ""} ${super.message}"
//}
//
//class GeofencingError(e: Error, private val msg: String? = null) : Error(e) {
//    override val message: String?
//        get() = "${msg ?: ""} ${super.message}"
//}