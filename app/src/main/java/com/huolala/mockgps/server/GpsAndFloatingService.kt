package com.huolala.mockgps.server

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.location.provider.ProviderProperties
import android.os.*
import android.provider.Settings
import android.view.*
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.route.*
import com.blankj.utilcode.util.*
import com.huolala.mockgps.MockReceiver
import com.huolala.mockgps.R
import com.huolala.mockgps.model.MockMessageModel
import com.huolala.mockgps.model.NaviType
import com.huolala.mockgps.model.PoiInfoModel
import com.huolala.mockgps.utils.CalculationLogLatDistance
import com.huolala.mockgps.utils.LocationUtils
import com.huolala.mockgps.utils.Utils
import kotlinx.android.synthetic.main.layout_floating.view.*
import kotlin.math.min

/**
 * @author jiayu.liu
 */
class GpsAndFloatingService : Service() {
    private val START_MOCK_LOCATION = 1001
    private val START_MOCK_LOCATION_NAVI = 1002
    private lateinit var view: View
    private var isAddView: Boolean = false
    private var locationManager: LocationManager? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isStart = false
    private lateinit var handle: Handler
    private var model: MockMessageModel? = null
    private var index = 0
    private val providerStr: String = LocationManager.GPS_PROVIDER
    private var bearing: Float = 1.0f

    /**
     * 米/S
     */
    private var mSpeed: Float = 60 / 3.6f
    private lateinit var mCurrentLocation: LatLng
    private var mSearch: RoutePlanSearch = RoutePlanSearch.newInstance()

    private lateinit var mMockReceiver: MockReceiver
    private val mScreenWidth = ScreenUtils.getScreenWidth()
    private val mScreenHeight = ScreenUtils.getScreenHeight()

    /**
     * 模拟导航点更新间隔  单位：ms  小于等于1000ms
     */
    private val mNaviUpdateValue = 1000L;

    override fun onCreate() {
        super.onCreate()
        handle = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    START_MOCK_LOCATION -> {
                        if (isStart) {
                            (msg.obj as PoiInfoModel?)?.latLng?.let {
                                view.tv_progress.text = String.format("%d / %d", 0, 0)
                                startSimulateLocation(it, true)
                                handle.sendMessageDelayed(Message.obtain(msg), mNaviUpdateValue)
                            }
                        }
                    }
                    START_MOCK_LOCATION_NAVI -> {
                        if (isStart) {
                            (msg.obj as ArrayList<*>?)?.let {
                                if (it.isEmpty() || it.size == 0) {
                                    return
                                }
                                if (index == 0) {
                                    mCurrentLocation = it[index] as LatLng
                                    index++
                                } else if (index < it.size) {
                                    mCurrentLocation = getLatLngNext(it)
                                }
                                view.tv_progress.text = String.format("%d / %d", index, it.size)
                                view.tv_current_position.text =
                                    String.format(
                                        "%f,%f",
                                        mCurrentLocation.longitude,
                                        mCurrentLocation.latitude
                                    )
                                startSimulateLocation(mCurrentLocation, false)
                                handle.sendMessageDelayed(Message.obtain(msg), mNaviUpdateValue)
                            }
                        }
                    }
                    else -> {
                    }
                }

            }
        }
        initReceiver()
        initView()
        initSearch()
        initWindowAndParams()
    }

    private fun initReceiver() {
        mMockReceiver = MockReceiver()
        val intentFilter = IntentFilter()
        intentFilter.addAction("com.huolala.mockgps.navi")
        registerReceiver(mMockReceiver, intentFilter);
    }

    fun getLatLngNext(polyline: ArrayList<*>): LatLng {
        //根据循环间隔处理  目前按照500ms进行处理  将speed进行除2处理  speed单位:m/s
        val mSpeed = this.mSpeed / (1000.0 / mNaviUpdateValue)

        val indexLonLat = polyline[index] as LatLng
        val polyLineCount = polyline.size

        //计算当前位置到index节点的距离
        val dis = CalculationLogLatDistance.getDistance(mCurrentLocation, indexLonLat)
        //计算角度
        val yaw = CalculationLogLatDistance.getYaw(mCurrentLocation, indexLonLat)

        if (!yaw.isNaN()) {
            bearing = yaw.toFloat()
        }

        if (dis > mSpeed) {
            //距离大于speed 计算经纬度
            var location =
                CalculationLogLatDistance.getNextLonLat(mCurrentLocation, yaw, mSpeed.toDouble())
            println("${location.latitude}  ||  ${location.longitude}")
            //计算经纬度为非法值则直接取下一阶段经纬度更新
            if (location.latitude <= 0.0 || location.longitude <= 0.0 || location.latitude.isNaN() || location.longitude.isNaN()) {
                location = polyline[index] as LatLng
                index++
                println("非法")
            } else {
                println("计算经纬度 $index ,  $mSpeed , $dis , $yaw")
            }
            return location
        }

        //终点
        if (index >= polyLineCount - 1) {
            val latLng = polyline[polyLineCount - 1] as LatLng
            index++
            println("终点")
            return latLng
        }
        if (dis > 0) {
            println("直接取下一阶段经纬 $index ,  $mSpeed , $dis , $yaw")
            index++
            return indexLonLat
        }
        //循环递归计算经纬度
        index++
        println("递归")
        return getLatLngNext(polyline)
    }

    private fun initSearch() {
        mSearch.setOnGetRoutePlanResultListener(object : OnGetRoutePlanResultListener {
            override fun onGetWalkingRouteResult(p0: WalkingRouteResult?) {
                TODO("Not yet implemented")
            }

            override fun onGetTransitRouteResult(p0: TransitRouteResult?) {
                TODO("Not yet implemented")
            }

            override fun onGetMassTransitRouteResult(p0: MassTransitRouteResult?) {
                TODO("Not yet implemented")
            }

            override fun onGetDrivingRouteResult(drivingRouteResult: DrivingRouteResult?) {
                //创建DrivingRouteOverlay实例
                drivingRouteResult?.routeLines?.get(0)?.run {
                    val polylineList = arrayListOf<LatLng>()
                    for (step in allStep) {
                        if (step.wayPoints != null && step.wayPoints.isNotEmpty()) {
                            polylineList.addAll(step.wayPoints)
                        }
                    }
                    //提前计算路线 目前没有使用
//                    val polyLine = Utils.latLngToSpeedLatLng(polylineList, mSpeed)
                    index = 0
                    sendHandler(
                        START_MOCK_LOCATION_NAVI,
                        polylineList
                    )
                }
            }

            override fun onGetIndoorRouteResult(p0: IndoorRouteResult?) {
                TODO("Not yet implemented")
            }

            override fun onGetBikingRouteResult(p0: BikingRouteResult?) {
                TODO("Not yet implemented")
            }
        })
    }

    private fun initWindowAndParams() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        layoutParams = WindowManager.LayoutParams()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            layoutParams?.type = WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams?.gravity = Gravity.CENTER
        //焦点问题
        layoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        //透明度
        layoutParams?.format = PixelFormat.RGBA_8888
        layoutParams?.x = mScreenWidth / 2
        layoutParams?.y = 0
        layoutParams?.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams?.height = WindowManager.LayoutParams.WRAP_CONTENT
    }


    private fun initView() {
        view = LayoutInflater.from(this).inflate(R.layout.layout_floating, null)

        //播放
        view.startAndPause.setOnClickListener {
            isStart = !view.isSelected
            view.isSelected = isStart
            if (!isStart) {
                removeGps()
                handle.removeCallbacksAndMessages(null)
            } else {
                mockLocation()
            }
        }
        view.setOnClickListener(object : ClickUtils.OnMultiClickListener(2, 300) {
            override fun onTriggerClick(v: View?) {
                AppUtils.launchApp(packageName)
            }

            override fun onBeforeTriggerClick(v: View?, count: Int) {
            }
        })

        view.setOnTouchListener(object : View.OnTouchListener {
            private var x: Int = 0
            private var y: Int = 0

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        x = event.rawX.toInt()
                        y = event.rawY.toInt()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val nowX = event.rawX
                        val nowY = event.rawY
                        val movedX = nowX - x
                        val movedY = nowY - y
                        x = nowX.toInt()
                        y = nowY.toInt()
                        layoutParams?.x = if (layoutParams?.x?.plus(movedX.toInt())!! > 0)
                            min(
                                layoutParams?.x?.plus(movedX.toInt())!!,
                                (mScreenWidth - view.width) / 2
                            )
                        else
                            (layoutParams?.x?.plus(movedX.toInt())!!).coerceAtLeast(-(mScreenWidth - view.width) / 2)

                        layoutParams?.y = if (layoutParams?.y?.plus(movedY.toInt())!! > 0)
                            min(
                                layoutParams?.y?.plus(movedY.toInt())!!,
                                (mScreenHeight - view.height) / 2
                            )
                        else
                            (layoutParams?.y?.plus(movedY.toInt())!!).coerceAtLeast(-(mScreenHeight - view.height) / 2)

                        // 更新悬浮窗控件布局
                        windowManager?.updateViewLayout(view, layoutParams);
                    }
                }
                return false
            }

        })
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //开始模拟
        model = null
        if (Utils.isAllowMockLocation(this)) {
            intent?.run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    model = getParcelableExtra("info",MockMessageModel::class.java)
                }else{
                    model = getParcelableExtra("info")
                }
            }
        }
        mockLocation()
        //浮动窗
        addView()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun mockLocation() {
        model?.run {
            when (naviType) {
                NaviType.LOCATION -> {
                    sendHandler(START_MOCK_LOCATION, locationModel)
                }
                NaviType.NAVI -> {
                    mSpeed = speed / 3.6f
                    mSearch.drivingSearch(
                        DrivingRoutePlanOption()
                            .policy(DrivingRoutePlanOption.DrivingPolicy.ECAR_DIS_FIRST)
                            .from(PlanNode.withLocation(startNavi?.latLng))
                            .to(PlanNode.withLocation(endNavi?.latLng))
                    )
                }
                NaviType.NAVI_FILE -> {
                    try {
                        mSpeed = speed / 3.6f
                        val polylineList = arrayListOf<LatLng>()
                        val readFile2String = FileIOUtils.readFile2String(path)
                        readFile2String?.run {
                            split(";").run {
                                if (isNotEmpty()) {
                                    map {
                                        it.split(",").run {
                                            if (size == 2) {
                                                polylineList.add(
                                                    LatLng(
                                                        get(1).toDouble(),
                                                        get(0).toDouble()
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            index = 0
                            sendHandler(
                                START_MOCK_LOCATION_NAVI,
                                polylineList
                            )
                        } ?: kotlin.run { ToastUtils.showShort("文件无法读取") }

                    } catch (e: Exception) {
                        ToastUtils.showShort(
                            "文件解析失败，是否点串格式正确 \n" +
                                    " ${e.printStackTrace()}"
                        )
                    }
                }
                else -> {
                }
            }
        } ?: run {
            isStart = false
            view.isSelected = isStart
        }
    }

    private fun sendHandler(code: Int, model: Any?) {
        val msg: Message = Message.obtain().apply {
            what = code
            obj = model
        }
        msg.let {
            removeGps()
            handle.removeCallbacksAndMessages(null)
            isStart = true
            view.isSelected = isStart
            handle.sendMessage(it)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mMockReceiver)
        handle.removeCallbacksAndMessages(null)
        removeGps()
        mSearch.destroy()
        removeView()
    }


    fun startSimulateLocation(latLng: LatLng, isSingle: Boolean) {
        val pointType = model?.pointType ?: LocationUtils.gcj02
        var gps84 = doubleArrayOf(latLng.longitude, latLng.latitude)
        when (pointType) {
            LocationUtils.gcj02 -> {
                gps84 = LocationUtils.gcj02ToWGS84(latLng.longitude, latLng.latitude)
            }
            LocationUtils.bd09 -> {
                gps84 = LocationUtils.bd09ToWGS84(latLng.longitude, latLng.latitude)
            }
            else -> {}
        }

        val loc = Location(providerStr)

        loc.altitude = 2.0
        loc.accuracy = 1.0f
        loc.bearing = bearing
        loc.speed = if (isSingle) 0F else mSpeed
        loc.longitude = gps84[0]
        loc.latitude = gps84[1]
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        mockGps(loc)
    }

    private fun removeGps() {
        try {
            locationManager?.run {
                removeTestProvider(providerStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun mockGps(location: Location) {
        locationManager?.run {
            try {
                var powerUsageMedium = Criteria.POWER_LOW
                var accuracyCoarse = Criteria.ACCURACY_FINE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    powerUsageMedium = ProviderProperties.POWER_USAGE_LOW
                    accuracyCoarse = ProviderProperties.ACCURACY_FINE
                    addTestProvider(
                        providerStr,
                        false,
                        false,
                        false,
                        false,
                        true,
                        true,
                        true,
                        powerUsageMedium,
                        accuracyCoarse
                    )
                }else{
                    val provider = getProvider(providerStr)
                    if (provider != null) {
                        addTestProvider(
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
                            setTestProviderStatus(
                                providerStr,
                                LocationProvider.AVAILABLE,
                                null,
                                System.currentTimeMillis()
                            )
                        }

                    }

                }
                // @throws IllegalArgumentException if a provider with the given name already exists

                setTestProviderEnabled(providerStr, true)
                setTestProviderLocation(providerStr, location)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addView() {
        try {
            if (isAddView) {
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(applicationContext)
            ) {
                isAddView = true
                windowManager?.addView(view, layoutParams)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeView() {
        try {
            isAddView = false
            windowManager?.removeView(view)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


}