/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
@file:Suppress("SENSELESS_COMPARISON")

package com.bhm.ble.request

import android.annotation.SuppressLint
import android.bluetooth.le.*
import android.os.ParcelUuid
import com.bhm.ble.attribute.BleOptions.Companion.DEFAULT_SCAN_MILLIS_TIMEOUT
import com.bhm.ble.attribute.BleOptions.Companion.DEFAULT_SCAN_RETRY_INTERVAL
import com.bhm.ble.callback.BleScanCallback
import com.bhm.ble.data.BleDevice
import com.bhm.ble.data.BleScanFailType
import com.bhm.ble.utils.BleLogger
import com.bhm.ble.utils.BleUtil
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Ble扫描
 *
 * @author Buhuiming
 * @date 2023年05月22日 09时49分
 */
@SuppressLint("MissingPermission")
internal class BleScanRequest private constructor() : Request(){

    companion object {

        private var instance: BleScanRequest = BleScanRequest()

        @Synchronized
        fun get(): BleScanRequest {
            if (instance == null) {
                instance = BleScanRequest()
            }
            return instance
        }
    }

    private val isScanning = AtomicBoolean(false)

    private val cancelScan = AtomicBoolean(false)

    private var scanJob: Job? = null

    private var waitScanJob: Job? = null

    private var bleScanCallback: BleScanCallback? = null

    private val results: MutableList<BleDevice> = arrayListOf()

    private val duplicateRemovalResults: MutableList<BleDevice> = arrayListOf()

    private var currentReyCount = 0

    /**
     * 开始扫描
     */
    @Synchronized
    fun startScan(bleScanCallback: BleScanCallback) {
        initScannerAndStart(bleScanCallback)
    }

    /**
     * 初始化扫描参数
     */
    private fun initScannerAndStart(bleScanCallback: BleScanCallback) {
        this.bleScanCallback = bleScanCallback
        val bleManager = getBleManager()
        if (!BleUtil.isPermission(bleManager.getContext()?.applicationContext)) {
            BleLogger.e("权限不足，请检查")
            bleScanCallback.callScanFail(BleScanFailType.NoBlePermissionType)
            return
        }
        if (!bleManager.isBleSupport()) {
            BleLogger.e("设备不支持蓝牙")
            bleScanCallback.callScanFail(BleScanFailType.UnTypeSupportBle)
            return
        }
        if (!BleUtil.isGpsOpen(bleManager.getContext()?.applicationContext)) {
            BleLogger.e("设备未打开GPS定位")
            bleScanCallback.callScanFail(BleScanFailType.GPSDisable)
            return
        }
        if (!bleManager.isBleEnable()) {
            BleLogger.e("蓝牙未打开")
            bleScanCallback.callScanFail(BleScanFailType.BleDisable)
            return
        }
        if (isScanning.get()) {
            BleLogger.e("已存在相同扫描")
            bleScanCallback.callScanFail(BleScanFailType.AlReadyScanning)
            return
        }
        duplicateRemovalResults.clear()
        results.clear()
        val scanFilters = arrayListOf<ScanFilter>()
        getBleOptions()?.let { options ->
            try {
                //设置过滤条件-ServiceUuid
                options.scanServiceUuids.forEach { serviceUuid ->
                    val scanFilter = ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(UUID.fromString(serviceUuid)))
                        .build()
                    scanFilters.add(scanFilter)
                }
                //设置过滤条件-设备广播名称
                //这里先不过滤，扫描到后再根据条件过滤
//            options.scanDeviceNames.forEach { deviceName ->
//                val scanFilter = ScanFilter.Builder()
//                    .setDeviceName(deviceName)
//                    .build()
//                scanFilters.add(scanFilter)
//            }
                options.scanDeviceAddresses.forEach { deviceAddress ->
                    val scanFilter = ScanFilter.Builder()
                        .setDeviceAddress(deviceAddress)
                        .build()
                    scanFilters.add(scanFilter)
                }
            } catch (e: IllegalArgumentException) {
                bleScanCallback.callScanFail(BleScanFailType.ScanError(-1, e))
                return
            }
        }
        val scanSetting: ScanSettings? =
            try {
                ScanSettings.Builder()
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            } catch (e: IllegalArgumentException) {
                bleScanCallback.callScanFail(BleScanFailType.ScanError(-1, e))
                return
            }
        val scanner = bleManager.getBluetoothManager()?.adapter?.bluetoothLeScanner
        bleScanCallback.callScanStart()
        bleScan(scanner, scanFilters, scanSetting)
    }

    /**
     * 执行扫描
     */
    private fun bleScan(scanner: BluetoothLeScanner?, scanFilters: ArrayList<ScanFilter>, scanSetting: ScanSettings?) {
        BleLogger.d("开始第${currentReyCount + 1}次扫描")
        isScanning.set(true)
        cancelScan.set(false)
        var scanTime = getBleOptions()?.scanMillisTimeOut?: DEFAULT_SCAN_MILLIS_TIMEOUT
        //不支持无限扫描，可以设置scanMillisTimeOut + setScanRetryCountAndInterval
        if (scanTime <= 0) {
            scanTime = DEFAULT_SCAN_MILLIS_TIMEOUT
        }
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            withTimeout(scanTime) {
                scanner?.startScan(scanFilters, scanSetting, scanCallback)
                delay(scanTime)
            }
        }
        scanJob?.invokeOnCompletion {
            onCompletion(scanner, scanFilters, scanSetting, it)
        }
    }

    /**
     * 是否继续扫描
     */
    private fun ifContinueScan(): Boolean {
        if (!cancelScan.get()) {
            var retryCount = getBleOptions()?.scanRetryCount?: 0
            if (retryCount < 0) {
                retryCount = 0
            }
            if (retryCount > 0 && currentReyCount < retryCount) {
                return true
            }
        }
        return false
    }

    /**
     * 完成
     */
    private fun onCompletion(scanner: BluetoothLeScanner?,
                             scanFilters: ArrayList<ScanFilter>,
                             scanSetting: ScanSettings?,
                             throwable: Throwable?) {
        isScanning.set(false)
        scanner?.stopScan(scanCallback)
        if (ifContinueScan()) {
            val retryInterval = getBleOptions()?.scanRetryInterval?: DEFAULT_SCAN_RETRY_INTERVAL
            waitScanJob = CoroutineScope(Dispatchers.Default).launch {
                delay(retryInterval)
                currentReyCount ++
                bleScan(scanner, scanFilters, scanSetting)
            }
            waitScanJob?.invokeOnCompletion {
                //手动取消，等待扫描任务取消后，要返回最终信息
                //waitScanJob取消后，会导致scanJob被取消
                if (CANCEL_WAIT_JOB_MESSAGE == it?.message) {
                    onCompletion(scanner, scanFilters, scanSetting, it)
                }
            }
        } else {
            throwable?.let {
                if (throwable !is CancellationException) {
                    bleScanCallback?.callScanFail(BleScanFailType.ScanError(-1, it))
                }
            }
            bleScanCallback?.callScanComplete(results, duplicateRemovalResults)
            if (results.isEmpty()) {
                BleLogger.d("没有扫描到数据")
            }
            CoroutineScope(Dispatchers.Default).launch {
                delay(500)
                BleLogger.d("扫描完毕，扫描次数${currentReyCount + 1}次")
                currentReyCount = 0
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if (!isScanning.get()) {
                return
            }
            result?.let {
                val bleDevice = BleUtil.scanResultToBleDevice(it)
                BleLogger.d(bleDevice.toString())
                if (bleDevice.deviceName == null) {
                    filterData(bleDevice)
                } else if (getBleOptions()?.scanDeviceNames?.isEmpty() == true) {
                    filterData(bleDevice)
                } else {
                    getBleOptions()?.scanDeviceNames?.forEach { scanDeviceName ->
                        if ((getBleOptions()?.containScanDeviceName == true &&
                                    bleDevice.deviceName.uppercase()
                                        .contains(scanDeviceName.uppercase())) ||
                            bleDevice.deviceName.uppercase() == scanDeviceName.uppercase()
                        ) {
                            filterData(bleDevice)
                        }
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            /**
             * 扫描错误(这里不再详细区分，具体错误码如下)
             * 1、errorCode = [android.bluetooth.le.ScanCallback.SCAN_FAILED_ALREADY_STARTED]
             * 无法启动扫描，因为应用程序已启动具有相同设置的 BLE 扫描。
             * 2、errorCode = [android.bluetooth.le.ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED]
             * 无法开始扫描，因为无法注册应用程序。
             * 3、errorCode = [android.bluetooth.le.ScanCallback.SCAN_FAILED_INTERNAL_ERROR]
             * 由于内部错误无法开始扫描。
             * 4、errorCode = [android.bluetooth.le.ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED]
             * 无法启动电源优化扫描，因为不支持此功能。
             * 5、errorCode = [android.bluetooth.le.ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES]
             * 由于硬件资源不足，无法启动扫描。
             * 6、errorCode = [android.bluetooth.le.ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY]
             * 由于应用程序尝试扫描过于频繁，无法开始扫描。
             * 7、errorCode = -1，具体看throwable
             */
            bleScanCallback?.callScanFail(BleScanFailType.ScanError(errorCode,
                Throwable("扫描失败，请查验[android.bluetooth.le.ScanCallback错误码]")))
        }
    }

    /**
     * 回调扫描数据
     */
    private fun filterData(bleDevice: BleDevice) {
        results.add(bleDevice)
        bleScanCallback?.callLeScan(bleDevice, currentReyCount + 1)
        if (duplicateRemovalResults.isEmpty()) {
            duplicateRemovalResults.add(bleDevice)
            bleScanCallback?.callLeScanDuplicateRemoval(bleDevice, currentReyCount + 1)
        } else {
            var same = false
            for (mBleDevice in duplicateRemovalResults) {
                if (bleDevice == mBleDevice) {
                    same = true
                    break
                }
            }
            if (!same) {
                duplicateRemovalResults.add(bleDevice)
                bleScanCallback?.callLeScanDuplicateRemoval(bleDevice, currentReyCount + 1)
            }
        }
    }

    /**
     * 是否扫描中
     */
    fun isScanning() = isScanning.get()

    fun removeBleScanCallback() {
        this.bleScanCallback = null
    }

    /**
     * 停止扫描
     */
    @Synchronized
    fun stopScan() {
        isScanning.set(false)
        cancelScan.set(true)
        scanJob?.cancel()
        waitScanJob?.cancel(CancellationException(CANCEL_WAIT_JOB_MESSAGE))
    }
}