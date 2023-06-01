/*
 * Copyright (c) 2022-2032 buhuiming
 * 不能修改和删除上面的版权声明
 * 此代码属于buhuiming编写，在未经允许的情况下不得传播复制
 */
package com.bhm.demo.ui

import android.os.Build
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.bhm.ble.data.BleDevice
import com.bhm.demo.BaseActivity
import com.bhm.demo.R
import com.bhm.demo.adapter.DetailsExpandAdapter
import com.bhm.demo.adapter.LoggerListAdapter
import com.bhm.demo.databinding.ActivityDetailBinding
import com.bhm.demo.entity.LogEntity
import com.bhm.demo.entity.OperateType
import com.bhm.demo.vm.DetailViewModel
import com.bhm.support.sdk.core.AppTheme
import com.bhm.support.sdk.entity.MessageEvent
import kotlinx.coroutines.launch
import java.util.logging.Level


/**
 * 服务，特征
 *
 * @author Buhuiming
 * @date 2023年06月01日 09时17分
 */
class DetailOperateActivity : BaseActivity<DetailViewModel, ActivityDetailBinding>(){

    override fun createViewModel() = DetailViewModel(application)

    private var bleDevice: BleDevice? = null

    private var expandAdapter: DetailsExpandAdapter? = null

    private var loggerListAdapter: LoggerListAdapter? = null

    override fun initData() {
        super.initData()
        AppTheme.setStatusBarColor(this, R.color.purple_500)
        bleDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", BleDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        if (bleDevice == null) {
            finish()
            return
        }
        viewBinding.tvName.text = buildString {
            append("设备广播名：")
            append(bleDevice?.deviceName)
            append("\r\n")
            append("地址：${bleDevice?.deviceAddress}")
        }
        initList()
    }

    private fun initList() {
        val layoutManager = LinearLayoutManager(this)
        layoutManager.orientation = LinearLayoutManager.VERTICAL
        viewBinding.recyclerView.layoutManager = layoutManager
        viewBinding.recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        expandAdapter = DetailsExpandAdapter(viewModel.getListData(bleDevice!!)) {
                checkBox, operateType, isChecked, node ->
            var logEntity: LogEntity? = null
            when (operateType) {
                is OperateType.Write -> {
                    if (isChecked) {
                        if (viewBinding.btnSend.isEnabled) {
                            checkBox.isChecked = false
                            Toast.makeText(this, "请取消其他特征值写操作", Toast.LENGTH_SHORT).show()
                            return@DetailsExpandAdapter
                        }
                        viewBinding.btnSend.isEnabled = true
                        viewBinding.etContent.isEnabled = true
                        logEntity = LogEntity(Level.INFO, "写： ${node.characteristicUUID}")
                    } else {
                        viewBinding.btnSend.isEnabled = false
                        viewBinding.etContent.isEnabled = false
                        logEntity = LogEntity(Level.OFF, "取消写： ${node.characteristicUUID}")
                    }
                }
                is OperateType.Read -> {
                    if (isChecked) {
                        logEntity = LogEntity(Level.INFO, "读： ${node.characteristicUUID}")
                    } else {
                        logEntity = LogEntity(Level.OFF, "取消读： ${node.characteristicUUID}")
                    }
                }
                is OperateType.Notify -> {
                    if (isChecked) {
                        logEntity = LogEntity(Level.INFO, "Notify： ${node.characteristicUUID}")
                    } else {
                        logEntity = LogEntity(Level.WARNING, "取消Notify： ${node.characteristicUUID}")
                    }
                }
                is OperateType.Indicate -> {
                    if (isChecked) {
                        logEntity = LogEntity(Level.INFO, "Indicate： ${node.characteristicUUID}")
                    } else {
                        logEntity = LogEntity(Level.WARNING, "取消Indicate： ${node.characteristicUUID}")
                    }
                }
            }
            viewModel.addLogMsg(logEntity)
        }
        viewBinding.recyclerView.adapter = expandAdapter
        expandAdapter?.expand(0)

        val logLayoutManager = LinearLayoutManager(this)
        logLayoutManager.orientation = LinearLayoutManager.VERTICAL
        viewBinding.logRecyclerView.setHasFixedSize(true)
        viewBinding.logRecyclerView.layoutManager = logLayoutManager
        (viewBinding.recyclerView.itemAnimator as DefaultItemAnimator).supportsChangeAnimations = false
        loggerListAdapter = LoggerListAdapter(viewModel.listLogData)
        viewBinding.logRecyclerView.adapter = loggerListAdapter

    }

    override fun initEvent() {
        super.initEvent()
        lifecycleScope.launch {
            viewModel.listLogStateFlow.collect {
                viewModel.listLogData.add(it)
                val position = viewModel.listLogData.size - 1
                loggerListAdapter?.notifyItemInserted(position)
                viewBinding.logRecyclerView.smoothScrollToPosition(position)
            }
        }
    }

    /**
     * 接收到断开通知
     */
    override fun onMessageEvent(event: MessageEvent?) {
        super.onMessageEvent(event)
        event?.let {
            val device = event.data as BleDevice
            if (bleDevice == device) {
                finish()
            }
        }
    }
}