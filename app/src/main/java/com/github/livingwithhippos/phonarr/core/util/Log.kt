package com.github.livingwithhippos.phonarr.core.util

/** Tiny logging facade so the core can run in plain JVM unit tests. The app plugs in android.util.Log. */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: (Level, String, Throwable?) -> Unit = { _, _, _ -> }

    fun d(msg: String) = sink(Level.DEBUG, msg, null)
    fun i(msg: String) = sink(Level.INFO, msg, null)
    fun w(msg: String, t: Throwable? = null) = sink(Level.WARN, msg, t)
    fun e(msg: String, t: Throwable? = null) = sink(Level.ERROR, msg, t)
}
