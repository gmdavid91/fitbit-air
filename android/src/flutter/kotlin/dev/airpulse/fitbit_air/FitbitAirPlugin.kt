package dev.airpulse.fitbit_air

import android.app.Activity
import android.content.pm.PackageManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener

/**
 * Flutter bridge for the experimentally verified Fitbit Air haptic path.
 * All transport logic lives in [AirEngine]; this class only adapts channels.
 */
class FitbitAirPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private lateinit var engine: AirEngine
    private lateinit var channel: MethodChannel
    private val streams = mutableListOf<EventChannel>()
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var pendingPermission: ((Boolean) -> Unit)? = null
    private val permissionListener = RequestPermissionsResultListener { requestCode, _, grantResults ->
        if (requestCode != PERMISSION_REQUEST) return@RequestPermissionsResultListener false
        val callback = pendingPermission
        pendingPermission = null
        callback?.invoke(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
        true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        engine = AirEngine(binding.applicationContext)
        engine.permissionRequester = requester@{ permissions, onResult ->
            val host = activity ?: return@requester false
            pendingPermission = onResult
            host.requestPermissions(permissions, PERMISSION_REQUEST)
            true
        }
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        fun stream(name: String, listen: (Any?, AirEventSink) -> Unit, cancel: () -> Unit) {
            val events = EventChannel(binding.binaryMessenger, name)
            events.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) = listen(arguments, sink.asAirSink())
                override fun onCancel(arguments: Any?) = cancel()
            })
            streams += events
        }
        stream("fitbit_air/heart_rate", { args, sink ->
            engine.listenHeartRate((args as? Map<*, *>)?.get("deviceId") as? String, sink)
        }, engine::cancelHeartRate)
        stream("fitbit_air/secure_events", { args, sink ->
            val seconds = (args as? Map<*, *>)?.get("durationSeconds") ?: 30
            if (seconds !is Int) {
                sink.error("INVALID_ARGUMENT", "durationSeconds must be an integer from 30 to 90.", null)
                sink.endOfStream()
            } else engine.listenSecureEvents(seconds, sink)
        }, engine::cancelSecureEvents)
        stream("fitbit_air/connection", { _, sink -> engine.listenConnection(sink) }, engine::cancelConnection)
        stream("fitbit_air/channel_activity", { _, sink -> engine.listenChannelActivity(sink) }, engine::cancelChannelActivity)
        stream("fitbit_air/device_events", { _, sink -> engine.listenDeviceEvents(sink) }, engine::cancelDeviceEvents)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        @Suppress("UNCHECKED_CAST")
        val args = (call.arguments as? Map<String, Any?>) ?: emptyMap()
        val reply = object : AirReply {
            override fun success(value: Any?) = result.success(value)
            override fun error(code: String, message: String?, details: Any?) = result.error(code, message, details)
        }
        if (!engine.handle(call.method, args, reply)) result.notImplemented()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        streams.forEach { it.setStreamHandler(null) }
        streams.clear()
        engine.permissionRequester = null
        engine.dispose()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(permissionListener)
    }

    override fun onDetachedFromActivityForConfigChanges() = detachActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
    override fun onDetachedFromActivity() = detachActivity()

    private fun detachActivity() {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener)
        activityBinding = null
        activity = null
    }

    private fun EventChannel.EventSink.asAirSink(): AirEventSink {
        val sink = this
        return object : AirEventSink {
            override fun success(event: Any?) = sink.success(event)
            override fun error(code: String, message: String?, details: Any?) = sink.error(code, message, details)
            override fun endOfStream() = sink.endOfStream()
        }
    }

    private companion object {
        const val CHANNEL = "fitbit_air"
        const val PERMISSION_REQUEST = 49217
    }
}
