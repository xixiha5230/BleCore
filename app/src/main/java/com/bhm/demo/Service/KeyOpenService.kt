package com.bhm.demo.Service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.SparseArray
import androidx.core.app.NotificationCompat
import com.bhm.ble.BleManager
import com.bhm.ble.callback.BleConnectCallback
import com.bhm.ble.data.Constants
import com.bhm.ble.device.BleDevice
import com.bhm.ble.utils.BleLogger
import com.bhm.ble.utils.BleUtil
import com.bhm.demo.R
import com.bhm.demo.entity.CharacteristicNode
import com.bhm.support.sdk.entity.MessageEvent
import org.greenrobot.eventbus.EventBus
import timber.log.Timber

const val TAG: String = "KeyOpenService"

class KeyOpenService : Service() {
    private var bleDevice: BleDevice? = null
    private val characteristicNode = CharacteristicNode(
        "1",
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
        "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
        "4",
        5,
        enableNotify = false,
        enableIndicate = false,
        enableWrite = false
    )

    private var mRunnable: Runnable = Runnable {
        Timber.tag(TAG).d("running ")
        if (!BleManager.get().isInitialized()) {
            BleManager.get().init(application)
        }
        if (bleDevice == null) {
            bleDevice = BleManager.get().buildBleDeviceByDeviceAddress("94:C9:60:44:5D:08")
        }
        bleDevice.let { device ->
            if (!BleManager.get().isConnected(device)) {
                BleManager.get().connect(device!!, 1000, 0, 0, false, connectCallback)
            }
        }
    }

    private val connectCallback: BleConnectCallback.() -> Unit = {
        onConnectStart {
            BleLogger.e("-----onConnectStart")
        }
        onConnectFail { bleDevice, connectFailType ->
            BleLogger.e("-----${bleDevice.deviceAddress} -> onConnectFail: $connectFailType")
        }
        onDisConnecting { isActiveDisConnected, bleDevice, _, _ ->
            BleLogger.e("-----${bleDevice.deviceAddress} -> onDisConnecting: $isActiveDisConnected")
        }
        onDisConnected { isActiveDisConnected, bleDevice, _, _ ->
            BleLogger.e("-----${bleDevice.deviceAddress} -> onDisConnected: $isActiveDisConnected")
            //发送断开的通知
            val message = MessageEvent()
            message.data = bleDevice
            EventBus.getDefault().post(message)
        }
        onConnectSuccess { bleDevice, _ ->
            BleLogger.e("-----${bleDevice.deviceAddress} -> onConnectSuccess")
            writeData(bleDevice, characteristicNode, "{\"cmd\":\"open\"}")
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        mRunnable.run()
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "KeyOpenServiceChannel"
        val channelName = "Key Open Service Channel"
        val notificationBuilder =
            NotificationCompat.Builder(this, channelId).setContentTitle("Key Open Service")
                .setContentText("Running in the background")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notification = notificationBuilder.build()
        startForeground(1, notification)
    }

    private fun writeData(
        bleDevice: BleDevice, node: CharacteristicNode, text: String
    ) {

        val data = text.toByteArray()
        BleLogger.i("data is: ${BleUtil.bytesToHex(data)}")
        val mtu = BleManager.get().getOptions()?.mtu ?: Constants.DEFAULT_MTU
        //mtu长度包含了ATT的opcode一个字节以及ATT的handle2个字节
        val maxLength = mtu - 3
        val listData: SparseArray<ByteArray> = BleUtil.subpackage(data, maxLength)
        BleManager.get().writeData(bleDevice, node.serviceUUID, node.characteristicUUID, listData) {
            onWriteFail { _, currentPackage, _, t ->
                Timber.tag(TAG).d("第%s包数据写失败：%s", currentPackage, t.message)
            }
            onWriteSuccess { _, currentPackage, _, justWrite ->
                Timber.tag(TAG).d("第%s包数据写成功：%s", currentPackage, justWrite)
            }
            onWriteComplete { _, allSuccess ->
                //代表所有数据写成功，可以在这个方法中处理成功的逻辑
                Timber.tag(TAG).d("所有数据写成功：%s", allSuccess)
            }
        }
    }
}