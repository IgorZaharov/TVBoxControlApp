package com.tvboxremote.app.receiver

/**
 * The fixed download link of the newest box receiver. The Release workflow attaches receiver.apk to each GitHub
 * release, and GitHub sends "latest/download" to the newest release. iOS uses the same link (ReceiverDownload.swift).
 */
object ReceiverDownload {
    const val URL = "https://github.com/IgorZaharov/TVBoxControlApp/releases/latest/download/receiver.apk"
}
