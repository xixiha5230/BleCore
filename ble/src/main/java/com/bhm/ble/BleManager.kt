@file:Suppress("SENSELESS_COMPARISON")

package com.bhm.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.bhm.ble.attribute.BleOptions
import com.bhm.ble.callback.BleConnectCallback
import com.bhm.ble.callback.BleNotifyCallback
import com.bhm.ble.callback.BleScanCallback
import com.bhm.ble.data.BleDevice
import com.bhm.ble.request.BleBaseRequest
import com.bhm.ble.request.BleRequestImp
import com.bhm.ble.utils.BleLogger
import com.bhm.ble.utils.BleUtil


/**
 * Android蓝牙低功耗核心类
 * @author Buhuiming
 * @date 2023年05月18日 13时37分
 */
class BleManager private constructor() {

    private var application: Application? = null

    private var bleOptions: BleOptions? = null

    private var bluetoothManager: BluetoothManager? = null

    private var bleBaseRequest: BleBaseRequest? = null

    companion object {

        private var instance: BleManager = BleManager()

        @Synchronized
        fun get(): BleManager {
            if (instance == null) {
                instance = BleManager()
            }
            return instance
        }
    }

    /**
     * 初始化，使用BleManager其他方法前，需先调用此方法
     */
    fun init(context: Application, option: BleOptions? = null) {
        application = context
        bleOptions = option
        if (bleOptions == null) {
            bleOptions = BleOptions.getDefaultBleOptions()
        }
        bleBaseRequest = BleRequestImp.get()
        bluetoothManager = application?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        BleLogger.isLogger = bleOptions?.enableLog?: false
        BleLogger.d("ble Successful initialization")
    }

    /**
     * 设备是否支持蓝牙
     *  @return true = 支持
     */
    fun isBleSupport(): Boolean {
        return BleUtil.isBleSupport(application?.applicationContext)
    }

    /**
     * 蓝牙是否打开
     * @return true = 打开
     */
    fun isBleEnable(): Boolean {
        val bluetoothAdapter = bluetoothManager?.adapter
        return isBleSupport() && (bluetoothAdapter?.isEnabled?: false)
    }

    /**
     * 开始扫描
     */
    @Synchronized
    fun startScan(bleScanCallback: BleScanCallback.() -> Unit) {
        checkInitialize()
        bleBaseRequest?.startScan(bleScanCallback)
    }

    /**
     * 是否扫描中
     * @return true = 扫描中
     */
    fun isScanning(): Boolean {
        checkInitialize()
        return bleBaseRequest?.isScanning()?: false
    }

    /**
     * 停止扫描
     */
    @Synchronized
    fun stopScan() {
        checkInitialize()
        bleBaseRequest?.stopScan()
    }

    /**
     * 是否已连接
     * 操作断开连接后，getConnectionState马上回去到的状态还是连接状态，所以需要bleBaseRequest?.isConnected判断
     */
    @SuppressLint("MissingPermission")
    fun isConnected(bleDevice: BleDevice?): Boolean {
        checkInitialize()
        bleDevice?.let {
            return bluetoothManager?.getConnectionState(it.deviceInfo, BluetoothProfile.GATT) ==
                    BluetoothProfile.STATE_CONNECTED && bleBaseRequest?.isConnected(it) == true
        }
        return false
    }

    /**
     * 连接
     */
    @Synchronized
    fun connect(bleDevice: BleDevice, bleConnectCallback: BleConnectCallback.() -> Unit) {
        checkInitialize()
        stopScan()
        bleBaseRequest?.connect(bleDevice, bleConnectCallback)
    }

    /**
     * 通过地址连接
     */
    @Synchronized
    fun connect(address: String, bleConnectCallback: BleConnectCallback.() -> Unit) {
        connect(buildBleDeviceByDeviceAddress(address), bleConnectCallback)
    }

    /**
     * 断开连接
     */
    @Synchronized
    fun disConnect(bleDevice: BleDevice) {
        checkInitialize()
        bleBaseRequest?.disConnect(bleDevice)
    }

    /**
     * 通过地址断开连接
     */
    @Synchronized
    fun disConnect(address: String) {
        disConnect(buildBleDeviceByDeviceAddress(address))
    }

    /**
     * 移除该设备的连接回调
     */
    @Synchronized
    fun removeBleConnectCallback(bleDevice: BleDevice) {
        checkInitialize()
        bleBaseRequest?.removeBleConnectCallback(bleDevice)
    }

    /**
     * 获取设备的BluetoothGatt对象
     */
    @Synchronized
    fun getBluetoothGatt(bleDevice: BleDevice): BluetoothGatt? {
        checkInitialize()
        return bleBaseRequest?.getBluetoothGatt(bleDevice)
    }

    /**
     * notify
     */
    @Synchronized
    fun notify(bleDevice: BleDevice,
               serviceUUID: String,
               notifyUUID: String,
               useCharacteristicDescriptor: Boolean = false,
               bleNotifyCallback: BleNotifyCallback.() -> Unit) {
        checkInitialize()
        bleBaseRequest?.notify(
            bleDevice,
            serviceUUID,
            notifyUUID,
            useCharacteristicDescriptor,
            bleNotifyCallback
        )
    }

    /**
     * stop notify
     */
    fun stopNotify(
        bleDevice: BleDevice,
        serviceUUID: String,
        notifyUUID: String,
        useCharacteristicDescriptor: Boolean = false
    ): Boolean? {
        checkInitialize()
        return bleBaseRequest?.stopNotify(
            bleDevice,
            serviceUUID,
            notifyUUID,
            useCharacteristicDescriptor
        )
    }

    /**
     * 断开所有连接 释放资源
     */
    @Synchronized
    fun releaseAll() {
        checkInitialize()
        bleBaseRequest?.releaseAll()
        BleLogger.i("资源释放完毕，BleCore SDK退出")
    }

    /**
     * 断开某个设备的连接 释放资源
     */
    @Synchronized
    fun release(bleDevice: BleDevice) {
        checkInitialize()
        bleBaseRequest?.release(bleDevice)
        BleLogger.i("${bleDevice}资源释放完毕")
    }


//    @Synchronized
//    fun startScanAndConnect() {
//
//    }

    fun getOptions() = bleOptions

    internal fun getContext() = application

    internal fun getBluetoothManager() = bluetoothManager

    private fun checkInitialize() {
        if (bleBaseRequest == null) {
            BleLogger.e("未初始化，请调用BleManager.init()")
        }
    }

    /**
     * 通过设备地址构建BleDevice对象
     */
    fun buildBleDeviceByDeviceAddress(deviceAddress: String): BleDevice {
        val deviceInfo = bluetoothManager?.adapter?.getRemoteDevice(deviceAddress)
        return BleDevice(deviceInfo, "", deviceAddress, 0, 0, null, 0)
    }
}