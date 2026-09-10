package io.github.romanvht.byedpi.core

import io.github.romanvht.byedpi.utility.AppLog

class ByeDpiProxy {
    companion object {
        private const val TAG = "ByeDpiProxy"

        init {
            System.loadLibrary("byedpi")
        }
    }

    fun startProxy(preferences: ByeDpiProxyPreferences): Int {
        val args = prepareArgs(preferences)
        AppLog.i(TAG, "Starting ciadpi with args: ${args.joinToString(" ")}")
        val code = jniStartProxy(args)

        if (code == -1) {
            // "proxy already running": a stale instance leaked from a
            // previous failed start. Kill it and retry once.
            AppLog.w(TAG, "ciadpi reports already running, force closing stale instance")
            jniForceClose()
            val retryCode = jniStartProxy(args)
            AppLog.i(TAG, "Retry after force close returned $retryCode")
            return retryCode
        }

        return code
    }

    fun stopProxy(): Int {
        return jniStopProxy()
    }

    private fun prepareArgs(preferences: ByeDpiProxyPreferences): Array<String> =
        when (preferences) {
            is ByeDpiProxyCmdPreferences -> preferences.args
            is ByeDpiProxyUIPreferences -> preferences.uiargs
        }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    external fun jniForceClose(): Int
}