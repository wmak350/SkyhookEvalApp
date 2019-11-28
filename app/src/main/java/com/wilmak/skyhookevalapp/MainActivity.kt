package com.wilmak.skyhookevalapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.GoogleMap
import kotlinx.android.synthetic.main.activity_main.*
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.skyhookwireless.wps.*
import java.io.*
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.os.Build
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import com.google.android.gms.maps.SupportMapFragment
import com.wilmak.skyhookevalapp.LocationUpdateForgroundService.Companion.SKYHOOK_EVALAPP_START_PERIODIC_UPDATE
import com.wilmak.skyhookevalapp.LocationUpdateForgroundService.Companion.SKYHOOK_EVALAPP_STOP_PERIODIC_UPDATE


class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mXPS: IWPS
    private var mMap: GoogleMap? = null
    private var mCancelPeridoicLocationUpdates = false
    private var mPeriodicLocationUpdatesIsStopped = false
    private var mOfflineToken: ByteArray? = null
    private var mRegisteredGeneralInfoReceiver = false
    private val mLocations: MutableList<LocationPoint> = mutableListOf<LocationPoint>()
    private val mSDF = SimpleDateFormat("hh:mm:ss", Locale.getDefault())
    private var mFos: FileOutputStream? = null
    private var mSWriter: OutputStreamWriter? = null

    private val mGeneralInfoReceiver : BroadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (intent.action) {
                    SKYHOOK_EVALAPP_START_PERIODIC_UPDATE -> fetchFirstPostion()
                }
            }
        }
    }

    companion object {

        const val SKYHOOK_ACTION_UPDATE_PERODIC_UPDATE = "Skyhook.DemoApp.update.location"
        const val SKYHOOK_ACTION_STOP_UPDATE_PERODIC_UPDATE = "Skyhook.DemoApp.stop.update.location"

        const val REQUEST_CODE_LOCATION_PERMISSION = 1000
        const val LocInfo_Filename = "skyhook_locations"
        val Offline_Key = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".reversed()
        val GeoFencesDefinitions = arrayOf<GeofenceDefinition>(
            GeofenceDefinition("Metro City II", 22.323514, 114.257857, 200),
            GeofenceDefinition("Po Tsui Park", 22.325172, 114.253125, 100),
            GeofenceDefinition("Mount Sterling Mall", 22.338289, 114.138447, 200),
            GeofenceDefinition("Taikoo Place", 22.287095, 114.211741, 100)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mapFragment = SupportMapFragment()
        mapFragment.getMapAsync(this)
        val tran = supportFragmentManager.beginTransaction()
        tran.add(R.id.frameLayout, mapFragment)
        tran.commit()

        mXPS = XPS(applicationContext)
        mXPS.setKey(applicationContext.getString(R.string.skyhook_key))
        ActivityCompat.requestPermissions(
            this, arrayOf( Manifest.permission.ACCESS_FINE_LOCATION ), REQUEST_CODE_LOCATION_PERMISSION);

        offlineBtn.setOnClickListener{
            if (offlineBtn.text.toString().toLowerCase(Locale.getDefault()) ==
                applicationContext.getString(R.string.go_offline).toLowerCase(Locale.getDefault())) {
                goOffline()
                offlineBtn.setText(applicationContext.getString(R.string.go_online))
            } else {
                goOnline()
                offlineBtn.setText(applicationContext.getString(R.string.go_offline))
            }
        }

        setupGeoFences()
        setupPeriodicLocationUpdates()
        setupForgroundService()

        sendNotification("${applicationContext.getString(R.string.app_name)} is up and running...")
    }

    override fun onResume() {
        super.onResume()

        fetchFirstPostion()
        clearActivityView()
        restoreLastLocations()
        if (!mRegisteredGeneralInfoReceiver) {
            val intf = IntentFilter()
            intf.addAction(SKYHOOK_EVALAPP_START_PERIODIC_UPDATE)
            intf.addAction(SKYHOOK_EVALAPP_STOP_PERIODIC_UPDATE)
            intf.addCategory(Intent.CATEGORY_DEFAULT)
            registerReceiver(mGeneralInfoReceiver, intf)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        killForgroundService()
        closeLocInfoFile()
        mXPS.cancelAllGeoFences()
        if (mRegisteredGeneralInfoReceiver)
            unregisterReceiver(mGeneralInfoReceiver)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_CODE_LOCATION_PERMISSION -> if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    resText.append("Location permission granted @ ${System.currentTimeMillis()}\n")
                }
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // Add a marker in Sydney and move the camera
        var last = LatLng(22.28552, 114.15769)
        if (mLocations.size > 0) {
            val lastPoint = mLocations.last()
            last = LatLng(lastPoint.lat, lastPoint.lng)
        }
        mMap?.let {
            it.setMinZoomPreference(it.maxZoomLevel - 3)
            drawTrackLineOnMap()
            it.moveCamera(CameraUpdateFactory.newLatLng(last))
        }
    }

    private fun clearActivityView() {
        resText.setText("")
    }

    private fun openLocInfoFileForWriting() {
        mFos = applicationContext.openFileOutput(LocInfo_Filename, Context.MODE_APPEND)
        mSWriter = OutputStreamWriter(mFos!!)
    }

    private fun closeLocInfoFile() {
        mSWriter?.close()
        mFos?.close()
        mLocations.clear()
    }

    private fun removeLocInfoFile() {
        val f = applicationContext.getFileStreamPath(LocInfo_Filename)
        if (f.exists())
            f.delete()
    }

    private fun clearLogInfoFile() {
        closeLocInfoFile()
        removeLocInfoFile()
        openLocInfoFileForWriting()
    }

    private fun restoreLastLocations() {
        getAllLocPoints()
        mLocations.forEach {
            resText.append("${it}")
        }
    }

    private fun saveLocationToFileAndDrawIt(location: LocationPoint) {
        var lastLocationPoint: LocationPoint? = null
        if (mLocations.size > 0) {
            lastLocationPoint = mLocations.last()
        }
        lastLocationPoint?.let {
            if (it.lat == location.lat ||
                it.lng == location.lng)
                return
            val dist = getHaversineDistance(LatLng(it.lat, it.lng),
                    LatLng(location.lat, location.lng), DistanceUnit.Kilometers)
            if (dist <= 0.025)
                return
        }
        Log.i("MainActivity", "Added Location: ${location}")
        mLocations.add(location)
        drawTrackLineOnMap()
        mSWriter?.append(location.toCSString())
        mSWriter?.flush()
    }

    private fun getAllLocPoints() {

        try {
            Log.i("MainActivity", "System File Path: ${applicationContext.filesDir}")
            val fisTemp = applicationContext.openFileInput(LocInfo_Filename)
            val isr = InputStreamReader(fisTemp)
            val bufReader = BufferedReader(isr)
            var line = bufReader.readLine()

            mLocations.clear()
            while (!TextUtils.isEmpty(line)) {
                val sArray = line.split(",")
                if (sArray.size == 5) {
                    val currLocPoint = LocationPoint(
                        sArray[0].toLong(),
                        sArray[1].toDouble(),
                        sArray[2].toDouble(),
                        sArray[3].toDouble(),
                        sArray[4].toDouble())
                    mLocations.add(currLocPoint)
                }
                line = bufReader.readLine()
            }
            bufReader.close()
            isr.close()
            fisTemp.close()
        } catch (ex: FileNotFoundException) {
        }
    }

    private fun setupForgroundService() {
        val intent = Intent(this@MainActivity, LocationUpdateForgroundService::class.java)
        intent.action = SKYHOOK_EVALAPP_START_PERIODIC_UPDATE
        startService(intent)
    }

    private fun killForgroundService() {
        val intent = Intent(this@MainActivity, LocationUpdateForgroundService::class.java)
        intent.action = SKYHOOK_EVALAPP_STOP_PERIODIC_UPDATE
        startService(intent)
    }

    private fun drawTrackLineOnMap() {
        if (mLocations.size == 0)
            return
        mMap?.let {
            clearMap()
            val latlngL = mLocations.map {
                LatLng(it.lat, it.lng)
            }.toList()
            it.addPolyline(
                PolylineOptions()
                    .jointType(JointType.ROUND)
                    .clickable(false)
                    .addAll(latlngL)
            )
            val firstPoint = mLocations.first()
            val lastPoint = mLocations.last()
            val firstLatLng = LatLng(firstPoint.lat, firstPoint.lng)
            val lastLatLng = LatLng(lastPoint.lat, lastPoint.lng)
            it.addMarker(MarkerOptions().position(firstLatLng).title("You start from here"))
            it.addMarker(MarkerOptions().position(lastLatLng).title("Your current position"))
            it.moveCamera(CameraUpdateFactory.newLatLng(lastLatLng))
        }
    }

    private fun clearMap() {
        mMap?.clear()
    }

    private fun fetchFirstPostion() {
        mXPS.getLocation(
            null,
            WPSStreetAddressLookup.WPS_NO_STREET_ADDRESS_LOOKUP,
            false,
            object : WPSLocationCallback {
                override fun handleWPSLocation(location: WPSLocation?) {
                    runOnUiThread {
                        // display location
                        location?.let {
                            saveLocationToFileAndDrawIt(it.toLocationPoint())
                        }
                    }
                }

                override fun handleError(error: WPSReturnCode?): WPSContinuation {
                    runOnUiThread {
                        // display error
                        resText.append("getLocationErr: ${error?.name}\n")
                    }

                    return WPSContinuation.WPS_CONTINUE
                }

                override fun done() {
                    // after done() returns, you can make more XPS calls
                }
            })
    }

    private fun cancelPeriodicLocationUpdate() {
        mCancelPeridoicLocationUpdates = true
    }

    private fun goOffline() {
        offlineBtn.isEnabled = false
        cancelPeriodicLocationUpdate()
        mOfflineToken = mXPS.getOfflineToken(null, Offline_Key.toByteArray())
        Timer().schedule(object: TimerTask() {
            override fun run() {
                if (mPeriodicLocationUpdatesIsStopped) {
                    runOnUiThread {
                        offlineBtn.isEnabled = true
                        offlineBtn.setText(applicationContext.getString(R.string.go_online))
                    }
                    cancel()
                }
            }
        }, 0, 1 * 1000)
    }

    private fun goOnline() {
        if (mOfflineToken == null) {
            resText.append("Invalid state encountered.  Offline Token is null")
            return
        }
        offlineBtn.isEnabled = false
        setupPeriodicLocationUpdates()
        mXPS.getOfflineLocation(null, Offline_Key.toByteArray(), mOfflineToken!!, object: WPSLocationCallback {
            override fun handleWPSLocation(location: WPSLocation?) {
                runOnUiThread {
                    location?.let {
                        saveLocationToFileAndDrawIt(it.toLocationPoint())
                    }
                }
            }

            override fun done() {
                runOnUiThread {
                    offlineBtn.isEnabled = true
                    offlineBtn.setText(applicationContext.getString(R.string.go_offline))
                }
            }

            override fun handleError(error: WPSReturnCode?): WPSContinuation {
                runOnUiThread {
                    // display error
                    resText.append("getOfflineLocationErr: ${error?.name}")
                }

                return WPSContinuation.WPS_CONTINUE
            }
        })
    }

    private fun setupPeriodicLocationUpdates() {
        mCancelPeridoicLocationUpdates = false
        mPeriodicLocationUpdatesIsStopped = false
        mXPS.getPeriodicLocation(null, null, false, 45 * 1000 * 60, 0, object: WPSPeriodicLocationCallback {
            override fun handleWPSPeriodicLocation(location: WPSLocation?): WPSContinuation {
                runOnUiThread {
                    location?.let {
                        saveLocationToFileAndDrawIt(it.toLocationPoint())
                    }
                }
                if (mCancelPeridoicLocationUpdates)
                    mPeriodicLocationUpdatesIsStopped = true
                return if (mCancelPeridoicLocationUpdates) WPSContinuation.WPS_STOP else WPSContinuation.WPS_CONTINUE
            }

            override fun done() {

            }

            override fun handleError(error: WPSReturnCode?): WPSContinuation {
                runOnUiThread {
                    // display error
                    resText.append("getPeriodicLocationErr: ${error?.name}")
                }

                return WPSContinuation.WPS_CONTINUE
            }
        })
    }

    private fun setupGeoFences() {
        val entranceList = GeoFencesDefinitions.map{
            GeoFenceName(it.name, WPSGeoFence(it.lat, it.lng, it.radius, WPSGeoFence.b.a, 0))
        }
        val exitList = GeoFencesDefinitions.map{
            GeoFenceName(it.name, WPSGeoFence(it.lat, it.lng, it.radius, WPSGeoFence.b.b, 0))
        }

        entranceList.forEach {
            mXPS.setGeoFence(it.definedFence, object: GeoFenceCallback {
                override fun handleGeoFence(geoFence: WPSGeoFence?, location: WPSLocation?): WPSContinuation {
                    sendNotification("You have entered ${it.name}")
                    return WPSContinuation.WPS_CONTINUE
                }
            })
            mXPS.setGeoFence(it.definedFence, object: GeoFenceCallback {
                override fun handleGeoFence(p0: WPSGeoFence?, p1: WPSLocation?): WPSContinuation {
                    sendNotification("You have left ${it.name}")
                    return WPSContinuation.WPS_CONTINUE
                }
            })
        }
    }

    private fun sendNotification(text: String) {
        val channelId = "my_channel_01"
        val channelName = "my_channel_name"

        val context = this@MainActivity
        val mainIntent = Intent(context, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(context, 0, mainIntent, 0)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // The id of the channel.

        val builder = NotificationCompat.Builder(context, channelId)
        builder.setContentTitle(context.getString(R.string.app_name))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setContentTitle("SK Demo App")
                .setContentText(text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH;
            val channel = NotificationChannel(channelId, channelName, importance)
            // Configure the notification channel.
            with (channel) {
                enableLights(true)
                setLightColor(Color.RED)
                enableVibration(true)
                description = "SK Notification"
            }
            notificationManager.createNotificationChannel(channel);
        } else {
            builder.setContentTitle(context.getString(R.string.app_name))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setLights(Color.YELLOW, 500, 5000)
                    .setAutoCancel(true);
        }
        notificationManager.notify(1, builder.build());
    }

}
