package com.laya

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** 前台服务:仅负责保活(进程优先级),网关逻辑在 Gateway 单例。 */
class GatewayService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val nm = getSystemService(NotificationManager::class.java)
    val ch = NotificationChannel("gateway", getString(com.selfhost.layatest.R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
    nm.createNotificationChannel(ch)
    val n = Notification.Builder(this, "gateway")
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(getString(com.selfhost.layatest.R.string.notif_title))
      .setContentText(getString(com.selfhost.layatest.R.string.notif_text))
      .setOngoing(true)
      .build()
    startForeground(1, n)
    Gateway.autoStart(this)
    return START_STICKY
  }
}
