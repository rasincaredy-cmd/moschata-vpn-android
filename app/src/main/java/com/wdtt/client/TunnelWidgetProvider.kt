package com.wdtt.client

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_TOGGLE) {
            if (TunnelManager.running.value || TunnelManager.isConnecting.value) {
                TunnelControl.stop(context)
            } else {
                TunnelManager.scope.launch {
                    val appCtx = context.applicationContext
                    val gate = com.wdtt.client.detector.WhiteListDetectorManager.canStartSavedTunnel(appCtx)
                    if (!gate.allowed) {
                        val msg = gate.message ?: "Интернет свободен — Обход БС отключён."
                        TunnelManager.lastFatalError.value = msg
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    val needsVpnPermission = runCatching { VpnService.prepare(appCtx) != null }
                        .getOrDefault(true)
                    val mode = SettingsStore(appCtx).connectionMode.first()
                    withContext(Dispatchers.Main) {
                        if (needsVpnPermission && mode != SettingsStore.CONNECTION_MODE_SOCKS) {
                            Toast.makeText(
                                appCtx,
                                "Разрешите Moschata VPN создать VPN-подключение",
                                Toast.LENGTH_LONG,
                            ).show()
                            openVpnPermissionActivity(appCtx)
                        } else {
                            TunnelControl.startFromSavedSettings(appCtx)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "net.qwdtt.client.ACTION_TOGGLE"

        fun updateWidgetState(context: Context, running: Boolean, statsText: String?) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TunnelWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            
            for (widgetId in allWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.tunnel_widget)
                
                // Update status dot and title
                if (running) {
                    views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_green)
                    views.setTextViewText(R.id.widget_status_title, "Moschata VPN: Активен")
                    views.setTextViewText(R.id.widget_stats_text, statsText ?: "Туннель запущен")
                    views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_pause)
                    views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#FF5252")) // Red pause button
                } else {
                    views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_gray)
                    views.setTextViewText(R.id.widget_status_title, "Moschata VPN: Отключен")
                    views.setTextViewText(R.id.widget_stats_text, "Нажмите для подключения")
                    views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_play)
                    views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#3DDC84")) // Green play button
                }
                
                bindWidgetClicks(context, views)
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }

        private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.tunnel_widget)
            val running = TunnelManager.running.value
            val stats = TunnelManager.stats.value
            
            // Set initial state
            if (running) {
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_green)
                views.setTextViewText(R.id.widget_status_title, "Moschata VPN: Активен")
                views.setTextViewText(R.id.widget_stats_text, stats)
                views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_pause)
                views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#FF5252"))
            } else {
                views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_dot_gray)
                views.setTextViewText(R.id.widget_status_title, "Moschata VPN: Отключен")
                views.setTextViewText(R.id.widget_stats_text, "Нажмите для подключения")
                views.setImageViewResource(R.id.widget_toggle_button, android.R.drawable.ic_media_play)
                views.setInt(R.id.widget_toggle_button, "setColorFilter", android.graphics.Color.parseColor("#3DDC84"))
            }
            
            bindWidgetClicks(context, views)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun bindWidgetClicks(context: Context, views: RemoteViews) {
            val toggleIntent = Intent(context, TunnelWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_toggle_button, togglePendingIntent)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val openPendingIntent = PendingIntent.getActivity(
                context, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)
        }

        private fun openVpnPermissionActivity(context: Context) {
            openActivity(
                context,
                Intent(context, VpnPermissionActivity::class.java),
                201,
            )
        }

        private fun openActivity(context: Context, intent: Intent, requestCode: Int) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                pendingIntent.send()
            }
        }
    }
}
