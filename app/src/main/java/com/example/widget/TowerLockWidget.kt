package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.telephony.CellModel

class TowerLockWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.example.widget.UPDATE_STATE") {
            val tech = intent.getStringExtra("TECH") ?: "Unknown"
            val band = intent.getStringExtra("BAND") ?: "Unknown"
            val rsrp = intent.getIntExtra("RSRP", -140)
            val address = intent.getStringExtra("ADDRESS") ?: "Unmapped cell tower"

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TowerLockWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            val tempCell = CellModel(
                tech = tech,
                bandName = band,
                rsrp = rsrp
            )

            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId, tempCell, address)
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        cell: CellModel?,
        addressStr: String? = null
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        if (cell != null) {
            views.setTextViewText(R.id.widget_tech, cell.tech)
            views.setTextViewText(R.id.widget_band, cell.bandName)
            views.setTextViewText(R.id.widget_rsrp, "${cell.rsrp} dBm")
            views.setTextViewText(R.id.widget_address, addressStr ?: "Locating active tower...")

            // Set background tech color
            val techColor = if (cell.tech.contains("5G")) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt()
            // Views don't have direct color state modifiers easily in older API, but we can set text color or drawables
            views.setInt(R.id.widget_tech, "setBackgroundColor", techColor)

            // Set RSRP color
            val rsrpColor = when {
                cell.rsrp >= -80 -> 0xFF4CAF50.toInt()
                cell.rsrp >= -95 -> 0xFF8BC34A.toInt()
                cell.rsrp >= -110 -> 0xFFFFB74D.toInt()
                else -> 0xFFE57373.toInt()
            }
            views.setTextColor(R.id.widget_rsrp, rsrpColor)
        } else {
            views.setTextViewText(R.id.widget_tech, "OFFLINE")
            views.setTextViewText(R.id.widget_band, "Tap to start")
            views.setTextViewText(R.id.widget_rsrp, "---")
            views.setTextViewText(R.id.widget_address, "No active monitoring service")
        }

        // Tap opens app
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        fun sendWidgetUpdate(context: Context, cell: CellModel, address: String) {
            val intent = Intent(context, TowerLockWidget::class.java).apply {
                action = "com.example.widget.UPDATE_STATE"
                putExtra("TECH", cell.tech)
                putExtra("BAND", cell.bandName)
                putExtra("RSRP", cell.rsrp)
                putExtra("ADDRESS", address)
            }
            context.sendBroadcast(intent)
        }
    }
}
