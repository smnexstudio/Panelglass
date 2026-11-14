package com.smnexstudio.panelglass.core.data.download

/**
 * Which networks model and language-pack downloads may use (Settings › Models & downloads › Download over Wi-Fi
 * only, `Settings.downloadWifiOnly`, Wi-Fi only by default). Kept in step with the setting by `PanelglassApp`; read
 * when a download is queued ([SystemDownloads.start], the ML Kit pack manager), so one already queued keeps the
 * networks it was queued with.
 */
object DownloadNetwork {
    @Volatile var wifiOnly: Boolean = true
}
