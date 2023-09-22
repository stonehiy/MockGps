package com.huolala.mockgps.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationProvider

import android.location.Criteria

import android.location.LocationManager
import android.location.provider.ProviderProperties

import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.baidu.mapapi.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.lang.IllegalArgumentException
import kotlin.collections.ArrayList


/**
 * @author jiayu.liu
 */
object Utils {



    fun isAllowMockLocation(context: Context): Boolean {
        var canMockPosition: Boolean = false;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) { //6.0以下
            canMockPosition = Settings.Secure.getInt(
                context.applicationContext.getContentResolver(),
                Settings.Secure.ALLOW_MOCK_LOCATION,
                0
            ) !== 0
        } else {
            try {
                val locationManager =
                    context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager //获得LocationManager引用
                val providerStr = LocationManager.GPS_PROVIDER
                if(Build.VERSION.SDK_INT in 23..30){

                    val provider = locationManager.getProvider(providerStr)
                    if (provider != null) {
                        locationManager.addTestProvider(
                            provider.name,
                            provider.requiresNetwork(),
                            provider.requiresSatellite(),
                            provider.requiresCell(),
                            provider.hasMonetaryCost(),
                            provider.supportsAltitude(),
                            provider.supportsSpeed(),
                            provider.supportsBearing(),
                            provider.powerRequirement,
                            provider.accuracy
                        )
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            locationManager.setTestProviderStatus(
                                providerStr,
                                LocationProvider.AVAILABLE,
                                null,
                                System.currentTimeMillis()
                            )
                        }
                    }

                }else{
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        locationManager.getProviderProperties( providerStr)
                        locationManager.addTestProvider(
                            providerStr,
                            true,
                            true,
                            false,
                            false,
                            true,
                            true,
                            true,
                            ProviderProperties.POWER_USAGE_HIGH,
                            ProviderProperties.ACCURACY_FINE
                        )

                    }

                }
                locationManager.setTestProviderEnabled(providerStr, true)
                // 模拟位置可用
                canMockPosition = true
                locationManager.setTestProviderEnabled(providerStr, false)
                locationManager.removeTestProvider(providerStr)
            } catch (e: Exception) {
                e.printStackTrace()
                canMockPosition = false
            }
        }
        return canMockPosition
    }

    /**
     * @param array 原数据
     * @param speed 速度  单位：M/S
     *
     */
    fun latLngToSpeedLatLng(array: ArrayList<LatLng>?, speed: Double): ArrayList<LatLng> {
        val latLngList = arrayListOf<LatLng>()
        runBlocking(Dispatchers.IO) {
            if (array == null || array.isEmpty() || array.size <= 1) {
                return@runBlocking latLngList
            }
            //单位：M/S
            val size = array.size
            //当前经纬度
            var currentLatLng = array[0]
            latLngList.add(currentLatLng)
            //起点
            for (index in 1 until size) {
                //节点经纬度
                val latLng = array[index]
                var dis = CalculationLogLatDistance.getDistance(currentLatLng, latLng)
                var yaw = CalculationLogLatDistance.getYaw(currentLatLng, latLng)
                if (dis < speed) {
                    currentLatLng = latLng
                    latLngList.add(latLng)
                    continue
                }
                while (dis > speed) {
                    val nextLonLat =
                        CalculationLogLatDistance.getNextLonLat(currentLatLng, yaw, speed)
                    dis = CalculationLogLatDistance.getDistance(currentLatLng, latLng)
                    yaw = CalculationLogLatDistance.getYaw(currentLatLng, latLng)
                    if (dis < speed) {
                        currentLatLng = latLng
                        latLngList.add(currentLatLng)
                    } else {
                        currentLatLng = nextLonLat
                        latLngList.add(currentLatLng)
                    }
                }
            }
        }.also {

            return latLngList

        }
    }

    fun checkFloatWindow(context: Context?): Boolean {
        if (context == null) {
            throw NullPointerException("Utils#checkFloatWindow: context is null");
        }
        //悬浮窗权限判断
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context.applicationContext)) {
                return false
            }
        }
        return true
    }

}