package dev.airpulse.fitbit_air

/** One reply to one [AirEngine.handle] call. Delivered on the main thread. */
interface AirReply {
    fun success(value: Any?)
    fun error(code: String, message: String?, details: Any? = null)
}

/** A stream of events from [AirEngine]. Delivered on the main thread. */
interface AirEventSink {
    fun success(event: Any?)
    fun error(code: String, message: String?, details: Any? = null)
    fun endOfStream()
}
