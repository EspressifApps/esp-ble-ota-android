package com.espressif.bleota.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.espressif.bleota.android.databinding.BleOtaScanActivityBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@SuppressLint("MissingPermission")
class BleOTAScanActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BleOTAScanActivity"
        private const val DEVICE_NAME_CONTENT = ""
        private const val COMPANY_ID = 0x02E5
        private val SERVICE_UUID = ParcelUuid(bleUUID("8018"))

        private const val REQUEST_PERMISSION = 1
    }

    private val mBinding by lazy(LazyThreadSafetyMode.NONE) {
        BleOtaScanActivityBinding.inflate(layoutInflater)
    }

    private val mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private val mScanCallback = ScanCallback()
    private val mScanResults = LinkedHashMap<String, ScanResult>()

    private var mSelectedScanResult: ScanResult? = null
    private lateinit var mSelectFoldLauncher: ActivityResultLauncher<Intent>

    private val mHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(mBinding.root)

        mBinding.refreshLayout.setColorSchemeResources(R.color.teal_200)
        mBinding.refreshLayout.setOnRefreshListener {
            refresh()
        }

        mBinding.recyclerView.adapter = DeviceAdapter()
        mBinding.recyclerView.addItemDecoration(DividerItemDecoration(this, RecyclerView.VERTICAL))

        registerLaunchers()

        lifecycleScope.launch(Dispatchers.IO) {
            val binDir = getDefaultBinDir()
            if (!binDir.exists()) {
                binDir.mkdirs()
            }
        }

        mHandler.post { refresh() }
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterLaunchers()
        stopScan()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 0, 0, R.string.app_about)

        return super.onCreateOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            0 -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.app_about)
                    .setMessage(getString(R.string.app_version,  BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                    .show()

                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_PERMISSION -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    if (!mBinding.refreshLayout.isRefreshing) {
                        refresh()
                    }
                }
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }

    }

    private fun registerLaunchers() {
        mSelectFoldLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            val scanResult = mSelectedScanResult!!
            mSelectedScanResult = null

            val uri = it.data?.data
            if (uri != null) {
                gotoOta(scanResult, uri)
            }
        }
    }

    private fun unregisterLaunchers() {
        mSelectFoldLauncher.unregister()
    }

    private fun refresh() {
        mBinding.refreshLayout.isRefreshing = true
        if (!mBluetoothAdapter.isEnabled) {
            Toast.makeText(this, R.string.bluetooth_disabled, Toast.LENGTH_SHORT).show()
            mBinding.refreshLayout.isRefreshing = false
            return
        }

        val requiredPermissions = mutableListOf<String>()
        if (isPermissionDenied(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isPermissionDenied(Manifest.permission.BLUETOOTH_SCAN)) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (isPermissionDenied(Manifest.permission.BLUETOOTH_CONNECT)) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (requiredPermissions.isNotEmpty()) {
            requestPermission(REQUEST_PERMISSION, *requiredPermissions.toTypedArray())
            mBinding.refreshLayout.isRefreshing = false
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLocationDisabled()) {
            Toast.makeText(this, R.string.location_disabled, Toast.LENGTH_SHORT).show()
            mBinding.refreshLayout.isRefreshing = false
            return
        }

        // Start scan
        mScanResults.clear()
        val scanFilters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(SERVICE_UUID)
                .build()
        )
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        mBluetoothAdapter.bluetoothLeScanner.startScan(null, scanSettings, mScanCallback)
        mHandler.postDelayed({
            mBluetoothAdapter.bluetoothLeScanner?.stopScan(mScanCallback)
            mBinding.refreshLayout.isRefreshing = false
            mBinding.recyclerView.adapter?.notifyDataSetChanged()
        }, 2500)
    }

    private fun stopScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        mBluetoothAdapter.bluetoothLeScanner?.stopScan(mScanCallback)
    }

    private fun gotoOta(scanResult: ScanResult, binUri: Uri) {
        val intent = Intent(this, BleOTAActivity::class.java).apply {
            putExtra(BleOTAConstants.KEY_SCAN_RESULT, scanResult)
            putExtra(BleOTAConstants.KEY_BIN_URI, binUri)
        }
        startActivity(intent)
    }

    private fun selectBinFromFold(scanResult: ScanResult) {
        mSelectedScanResult = scanResult
        val intent = Intent.createChooser(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            getString(R.string.ble_ota_select_bin)
        )
        mSelectFoldLauncher.launch(intent)
    }

    private fun getDefaultBinDir(): File {
        return File(applicationContext.getExternalFilesDir(null), "BLE-OTA")
    }

    fun showFileDialog(scanResult: ScanResult) {
        val dir = getDefaultBinDir()
        Log.d(TAG, "showFileDialog: dir path = ${dir}")
        val files = dir
            .listFiles()
            ?.filter { it.isFile }
            ?: emptyList()
        MaterialAlertDialogBuilder(this@BleOTAScanActivity).apply {
            setTitle(R.string.ble_ota_select_bin)
            setNegativeButton(android.R.string.cancel, null)
            if (files.isEmpty()) {
                setMessage(R.string.ble_ota_no_bin_found)
            } else {
                setItems(files.map { it.name }.toTypedArray()) { dialog, which ->
                    val uri = Uri.fromFile(files[which])
                    gotoOta(scanResult, uri)
                    dialog.dismiss()
                }
            }
            setPositiveButton(R.string.ble_ota_select_fold) { _, _ ->
                selectBinFromFold(scanResult)
            }
        }.show().apply {
            setCanceledOnTouchOutside(false)
        }
    }

    private inner class DeviceHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
        lateinit var scanResult: ScanResult

        init {
            itemView.setOnClickListener {
                showFileDialog(scanResult)
            }
        }
    }

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceHolder {
            val itemView =
                layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            return DeviceHolder(itemView)
        }

        override fun onBindViewHolder(holder: DeviceHolder, position: Int) {
            val scanResult = mScanResults.getByIndex(position).value
            holder.scanResult = scanResult
            holder.text1.text = scanResult.device.name
            holder.text2.text = getDeviceDesc(scanResult)
        }

        private fun getDeviceDesc(scanResult: ScanResult): String {
            val manufacturerData = scanResult.scanRecord?.getManufacturerSpecificData(COMPANY_ID)
            var productId = "Unknown"
            var version = "Unknown"
            if (manufacturerData != null && manufacturerData.size == 10) {
                productId = String.format(
                    "0x%02x%02x",
                    manufacturerData[3].toInt() and 0xff,
                    manufacturerData[2].toInt() and 0xff
                )

                val versionValue = (manufacturerData[4].toInt() and 0xff) or
                        (manufacturerData[5].toInt() shl 8 and 0xff00)
                version = versionValue.toString()
            }

            return "Address: ${scanResult.device.address}\n" +
                    "Product: $productId\n" +
                    "Version: $version\n" +
                    "RSSI: ${scanResult.rssi}\n"
        }

        override fun getItemCount(): Int {
            return mScanResults.size
        }

    }

    private inner class ScanCallback : android.bluetooth.le.ScanCallback() {
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "onScanFailed: $errorCode")
            Toast.makeText(
                applicationContext,
                getString(R.string.ble_ota_scan_failed, errorCode),
                Toast.LENGTH_SHORT
            ).show()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                onLeScanned(result)
            }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onLeScanned(result)
        }

        private fun onLeScanned(result: ScanResult) {
            if (result.device.name?.contains(DEVICE_NAME_CONTENT) != true) {
                return
            }

            mScanResults[result.device.address] = result
        }
    }
}