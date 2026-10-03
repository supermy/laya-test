package com.laya

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启:配置了 enabled=true 时拉起前台服务,恢复邮件/MQTT 通道。 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    if (Gateway.cfg(context).optBoolean("enabled")) {
      context.startForegroundService(Intent(context, GatewayService::class.java))
    }
  }
}
