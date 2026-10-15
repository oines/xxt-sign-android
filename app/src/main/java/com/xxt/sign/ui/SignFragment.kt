package com.xxt.sign.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.xxt.sign.MonitorService
import com.xxt.sign.R
import com.xxt.sign.core.Account
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LocationHelper
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.DialogCodeBinding
import com.xxt.sign.databinding.DialogLocationBinding
import com.xxt.sign.databinding.FragmentSignBinding
import com.xxt.sign.net.CxApi
import com.xxt.sign.net.CxSession
import com.xxt.sign.net.QrPayload
import com.xxt.sign.net.QrParser
import com.xxt.sign.net.SignOptions
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.sign.SignFlow
import com.xxt.sign.sign.SignPlanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 签到页。
 *
 * 交互只保留一个主按钮：点「一键签到 / 签退」→ 检测出所有账号进行中的活动 →
 * 列出来选一个 → **App 按活动类型自己决定该干什么**：
 *
 *   二维码 → 弹扫码，所有账号共用这同一份 enc
 *   手势/签到码 → 服务端有签到码就用，没有才问一次
 *   位置 → 用存过的坐标，没有才让填
 *   拍照 → 用存过的图，没有才让选
 *   普通 → 直接签
 *
 * 所以界面上不再需要「扫码签到」「签到码签到」这些模式按钮。
 */
class SignFragment : Fragment() {

    private var _b: FragmentSignBinding? = null
    private val b get() = _b!!
    private val adapter = StatusAdapter()
    private var running = false
    private var suppressSwitch = false

    /** 结果累积，供列表滚动展示 */
    private val results = mutableListOf<StatusItem>()

    /** 等扫码的活动 */
    private var pendingScanTarget: SignPlanner.Detected? = null

    /** 等人工过验证码的挂起点 */
    private var captchaDeferred: CompletableDeferred<String?>? = null

    private val captchaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val v = res.data?.getStringExtra(CaptchaActivity.RESULT_VALIDATE)
        val d = captchaDeferred
        captchaDeferred = null
        d?.complete(if (res.resultCode == android.app.Activity.RESULT_OK) v else null)
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) {
            pendingScanTarget = null
            showStatus("已取消扫码")
        } else {
            onQrContent(text)
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner() else {
            pendingScanTarget = null
            showStatus("没有相机权限，无法扫码")
        }
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(requireContext().filesDir, "sign_photo.png")
                    requireContext().contentResolver.openInputStream(uri)!!.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    Settings.photoPath = f.absolutePath
                    true
                }.getOrDefault(false)
            }
            toast(if (ok) "拍照签到图片已保存" else "图片读取失败")
            refreshOptions()
        }
    }

    private var locDialog: DialogLocationBinding? = null

    private val locPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val dialog = locDialog
        if (result.values.any { it }) {
            if (dialog != null) fillCurrentLocation(dialog)
        } else {
            toast("没有定位权限，只能手动填写坐标")
        }
    }

    private var pendingWebAccount: String? = null

    private val webSignLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val id = pendingWebAccount
        if (res.resultCode != android.app.Activity.RESULT_OK || id == null) {
            showStatus("网页操作未完成")
            return@registerForActivityResult
        }
        val acc = AccountStore.get(id) ?: return@registerForActivityResult
        val d = lastTarget
        if (d != null) executePlan(d, null, null) else showStatus("已同步 ${acc.display} 的会话")
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMonitorService() else {
            toast("没给通知权限，签到结果弹不出来")
            setSwitch(false)
        }
    }

    /** 上一次选中的活动，供人工兜底后复核 */
    private var lastTarget: SignPlanner.Detected? = null

    /** 本轮检测出来、还没处理的活动列表 */
    private val pendingPlan = mutableListOf<SignPlanner.Detected>()

    // ------------------------------------------------------------ 生命周期

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSignBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.btnSignAll.setOnClickListener { doSignAll() }
        // 隐藏诊断：长按主按钮跑一次自动解验证码
        b.btnSignAll.setOnLongClickListener { diagCaptcha(); true }
        b.btnSettings.setOnClickListener { showSettings() }

        b.swAuto.isChecked = Settings.autoMonitor
        b.swAuto.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            Settings.autoMonitor = checked
            if (checked) requestNotificationThenStart() else stopMonitorService()
        }

        refreshOptions()
        syncSwitch()
    }

    override fun onResume() {
        super.onResume()
        syncSwitch()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun setSwitch(on: Boolean) {
        suppressSwitch = true
        _b?.swAuto?.isChecked = on
        suppressSwitch = false
    }

    private fun syncSwitch() {
        setSwitch(MonitorService.running)
        if (MonitorService.running) showStatus("自动巡检运行中（每 ${Settings.intervalSeconds} 秒一轮）")
    }

    // ---------------------------------------------------------- 主流程

    private fun doSignAll() {
        val accounts = AccountStore.accounts.value
        if (accounts.isEmpty()) return showStatus("还没有添加账号")
        if (running) return toast("上一个任务还在跑")

        running = true
        b.progress.visibility = View.VISIBLE
        adapter.clear()
        results.clear()
        showStatus("正在检测所有账号的签到…")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detected = SignPlanner.detect(accounts, Settings.courseFilter) { showStatus(it) }
                running = false
                b.progress.visibility = View.GONE

                when {
                    detected.isEmpty() -> {
                        showStatus("没有检测到进行中的签到 / 签退")
                        toast("没有进行中的签到")
                    }

                    else -> showPlanPicker(detected)
                }
            } catch (e: Exception) {
                running = false
                b.progress.visibility = View.GONE
                showStatus("检测出错：${e.message}")
            }
        }
    }

    /** 弹窗列出所有活跃活动；选一个就签，签完继续问下一个 */
    private fun showPlanPicker(list: List<SignPlanner.Detected>) {
        pendingPlan.clear()
        pendingPlan.addAll(list)
        presentNextPlan()
    }

    private fun presentNextPlan() {
        if (pendingPlan.isEmpty()) {
            showStatus("所有活动都已处理完")
            return
        }
        if (pendingPlan.size == 1) {
            val d = pendingPlan.first()
            pendingPlan.clear()
            handlePicked(d)
            return
        }

        val labels = pendingPlan.map { planTitle(it) to planSub(it) }
        val adapter = object : android.widget.ArrayAdapter<Pair<String, String>>(
            requireContext(), R.layout.item_plan, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_plan, parent, false)
                val (t, s) = labels[position]
                v.findViewById<android.widget.TextView>(R.id.tvPlanTitle).text = t
                v.findViewById<android.widget.TextView>(R.id.tvPlanSub).text = s
                return v
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("检测到 ${pendingPlan.size} 个进行中的活动，选一个签")
            .setAdapter(adapter) { _, which -> handlePicked(pendingPlan[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun planTitle(d: SignPlanner.Detected): String {
        val need = when (d.requirement) {
            SignPlanner.Requirement.QR_CODE -> "　需扫码"
            SignPlanner.Requirement.SIGN_CODE -> "　需签到码"
            SignPlanner.Requirement.LOCATION -> "　需位置"
            SignPlanner.Requirement.PHOTO -> "　需选图"
            else -> ""
        }
        return d.title + need
    }

    private fun planSub(d: SignPlanner.Detected): String {
        val total = AccountStore.accounts.value.size
        val who = if (d.accountCount >= total) "全部 $total 个账号"
        else "${d.accountCount} 个账号：${d.accountNames.joinToString("、")}"
        val extra = if (d.requirement == SignPlanner.Requirement.NONE) "　可直接签" else ""
        return who + extra
    }

    /**
     * 选中活动后，按它自己的类型决定下一步。
     * 类型是活动自带属性，该做的事完全可推导，只在真缺东西时才问用户。
     */
    private fun handlePicked(d: SignPlanner.Detected) {
        lastTarget = d
        when (d.requirement) {
            SignPlanner.Requirement.QR_CODE -> {
                pendingScanTarget = d
                requestScan()
            }

            SignPlanner.Requirement.SIGN_CODE -> promptSignCode(d)
            SignPlanner.Requirement.LOCATION -> promptLocation(d)
            SignPlanner.Requirement.PHOTO -> {
                toast("请选择用于拍照签到的图片")
                photoPicker.launch("image/*")
            }

            SignPlanner.Requirement.NONE -> executePlan(d, null, null)
        }
    }

    private fun promptSignCode(d: SignPlanner.Detected) {
        // 手势签到画一遍就行，签到码是四位数字输入
        if (d.otherId == CxApi.TYPE_GESTURE) promptGesture(d) else promptNumericCode(d)
    }

    /** 手势签到：3×3 点阵上画一遍，直接得出 signCode */
    private fun promptGesture(d: SignPlanner.Detected) {
        val v = com.xxt.sign.databinding.DialogGestureBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("绘制手势 · ${d.title}")
            .setView(v.root)
            .setPositiveButton("签到", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        v.gestureLock.onComplete = { c ->
            v.tvGestureCode.text = "手势码：$c"
        }
        v.btnGestureReset.setOnClickListener {
            v.gestureLock.reset()
            v.tvGestureCode.text = "尚未绘制"
        }
        // 自取校验：没画就不让点确定，避免把空值提交上去
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val code = v.gestureLock.code
            if (code.isBlank()) {
                toast("请先在点阵上画出手势")
                return@setOnClickListener
            }
            dialog.dismiss()
            executePlan(d, null, code)
        }
    }

    /** 签到码签到：教室屏幕上那个四位数字 */
    private fun promptNumericCode(d: SignPlanner.Detected) {
        val v = DialogCodeBinding.inflate(layoutInflater)
        v.tvHint.text = "${d.title} 需要签到码（教室屏幕上那串数字）。服务端没有下发，只能手输。"
        MagicDialog.show(requireContext(), "输入签到码", v.root) {
            val code = v.etCode.text?.toString()?.trim().orEmpty()
            if (code.isBlank()) {
                showStatus("没有输入签到码，已取消")
                return@show
            }
            executePlan(d, null, code)
        }
    }

    private fun promptLocation(d: SignPlanner.Detected) {
        toast("位置签到需要坐标，先设置一次")
        showLocationDialog { executePlan(d, null, null) }
    }

    private fun onQrContent(text: String) {
        val payload = QrParser.parse(text)
        if (!payload.usable) {
            showStatus("二维码内容无法识别：$text")
            return
        }
        val target = pendingScanTarget
        pendingScanTarget = null
        if (target != null) {
            executePlan(target, payload, null)
        } else {
            scanAndSignDirect(payload)
        }
    }

    private fun requestScan() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准教室大屏上的签到二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    /** 没先选活动就直接扫码：找对应活动再签 */
    private fun scanAndSignDirect(payload: QrPayload) {
        val accounts = AccountStore.accounts.value
        if (accounts.isEmpty()) return showStatus("还没有添加账号")
        if (running) return toast("上一个任务还在跑")
        running = true
        b.progress.visibility = View.VISIBLE
        adapter.clear()
        results.clear()
        showStatus("已识别二维码，正在确认对应活动…")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detected = SignPlanner.detect(accounts, Settings.courseFilter) { showStatus(it) }
                running = false
                b.progress.visibility = View.GONE
                val hit = detected.firstOrNull { it.aid == payload.aid }
                    ?: detected.firstOrNull { it.needsQr }
                if (hit == null) {
                    showStatus("没有找到该二维码对应的进行中活动")
                    return@launch
                }
                executePlan(hit, payload, null)
            } catch (e: Exception) {
                running = false
                b.progress.visibility = View.GONE
                showStatus("出错：${e.message}")
            }
        }
    }

    /** 执行：所有有该活动的账号逐个签 */
    private fun executePlan(d: SignPlanner.Detected, qr: QrPayload?, signCode: String?) {
        val accounts = AccountStore.accounts.value
        if (running) return toast("上一个任务还在跑")
        running = true
        b.progress.visibility = View.VISIBLE
        adapter.clear()
        results.clear()
        showStatus("开始签：${d.title}")

        viewLifecycleOwner.lifecycleScope.launch {
            var done = false
            try {
                val rs = SignPlanner.execute(
                    target = d,
                    accounts = accounts,
                    qr = qr,
                    signCode = signCode,
                    photoBytes = photoBytes(),
                    onProgress = { showStatus(it) },
                    onResult = { o ->
                        results.add(StatusItem(o.title, o.detail, o.ok))
                        adapter.submit(results.toList())
                    },
                    askHuman = { p -> askHumanCaptcha(p) }
                )
                val ok = rs.count { it.ok }
                showStatus("完成：成功 $ok / ${rs.size}（${d.title}）")
                offerWebSignFor(rs)
                done = true
            } catch (e: Exception) {
                showStatus("出错：${e.message}")
            } finally {
                running = false
                b.progress.visibility = View.GONE
            }
            // 必须等 running 归位之后再问，否则点点“继续签”会被当成“上一个任务还在跑”
            if (done) continueIfMore(d)
        }
    }

    /** 签完一个，如果还有别的活动，问要不要接着签 */
    private fun continueIfMore(done: SignPlanner.Detected) {
        pendingPlan.removeAll { it.aid == done.aid }
        if (pendingPlan.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("还剩 ${pendingPlan.size} 个活动")
            .setMessage(pendingPlan.joinToString("\n") { "· ${planTitle(it)}" })
            .setPositiveButton("继续签") { _, _ -> presentNextPlan() }
            .setNegativeButton("稍后", null)
            .show()
    }

    // ---------------------------------------------------------- 设置

    private fun showSettings() {
        val items = arrayOf(
            "位置坐标　—　${locSummary()}",
            "定位来源　—　${if (Settings.gpsOnly) "仅 GPS（准）" else "自动（取最准）"}",
            "拍照签到图片　—　${if (Settings.photoPath.isBlank()) "未选" else "已选"}",
            "课堂范围　—　${courseSummary()}",
            "巡检频率　—　${Settings.intervalSeconds} 秒/轮",
            "手势签到练习（画一下看手势码）",
            "网页签到（人工兜底）"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLocationDialog(null)
                    1 -> showGpsSourcePicker()
                    2 -> photoPicker.launch("image/*")
                    3 -> showCoursePicker()
                    4 -> showIntervalPicker()
                    5 -> previewGesture()
                    6 -> pickAccountForWebSign(null)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 手势控件预览，用于验证绘制与编码是否正常 */
    private fun previewGesture() {
        val v = com.xxt.sign.databinding.DialogGestureBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("手势控件预览")
            .setView(v.root)
            .setPositiveButton("关闭", null)
            .show()
        v.gestureLock.onComplete = { c ->
            v.tvGestureCode.text = "手势码：$c"
        }
        v.btnGestureReset.setOnClickListener {
            v.gestureLock.reset()
            v.tvGestureCode.text = "尚未绘制"
        }
    }

    private fun showGpsSourcePicker() {
        val modes = arrayOf("仅 GPS（准，但室内可能拿不到）", "自动：所有来源取最准的")
        val idx = if (Settings.gpsOnly) 0 else 1
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("定位来源")
            .setSingleChoiceItems(modes, idx) { dialog, which ->
                Settings.gpsOnly = which == 0
                dialog.dismiss()
                refreshOptions()
                showStatus(if (Settings.gpsOnly) "定位来源：仅 GPS" else "定位来源：自动取最准")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun showLocationDialog(then: (() -> Unit)?) {
        val d = DialogLocationBinding.inflate(layoutInflater)
        d.etLat.setText(Settings.latitude)
        d.etLon.setText(Settings.longitude)
        d.etAddress.setText(Settings.address)

        // 没存过坐标时，先把系统缓存的位置预填进去。
        // 室内 GPS 拿不到、而网络定位又依赖 Google 服务（国内经常不可达），
        // 所以实时定位常常失败；系统缓存那份往往是唯一可用的，不该让用户空着。
        if (Settings.latitude.isBlank() && Settings.longitude.isBlank()) {
            val cached = LocationHelper.manager(requireContext())?.let { LocationHelper.bestCached(it) }
            if (cached != null) {
                d.etLat.setText(String.format("%.6f", cached.location.latitude))
                d.etLon.setText(String.format("%.6f", cached.location.longitude))
                showStatus("已预填系统缓存位置（${cached.provider}·精度 ${cached.accuracy} 米·${cached.ageText}），不对就点「用当前定位填充」")
            }
        }
        d.btnUseCurrent.setOnClickListener {
            if (hasLocationPermission()) fillCurrentLocation(d) else requestLocationPermission(d)
        }
        MagicDialog.show(requireContext(), "位置签到坐标", d.root) {
            val lat = d.etLat.text?.toString()?.trim().orEmpty()
            val lon = d.etLon.text?.toString()?.trim().orEmpty()
            val addr = d.etAddress.text?.toString()?.trim().orEmpty()

            // 两个都空 = 想清空。若原来存着坐标，先确认一次，
            // 避免误点确定把好数据冲掉（真被空值覆盖过一次）
            if (lat.isBlank() && lon.isBlank() &&
                (Settings.latitude.isNotBlank() || Settings.longitude.isNotBlank())
            ) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("清空已保存的坐标？")
                    .setMessage("当前存的是 ${Settings.latitude},${Settings.longitude}。确定要清空吗？")
                    .setPositiveButton("清空") { _, _ ->
                        Settings.latitude = ""; Settings.longitude = ""; Settings.address = ""
                        refreshOptions()
                    }
                    .setNegativeButton("保留", null)
                    .show()
                return@show
            }

            Settings.latitude = lat
            Settings.longitude = lon
            Settings.address = addr
            refreshOptions()
            then?.invoke()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission(d: DialogLocationBinding) {
        locDialog = d
        locPermLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun fillCurrentLocation(d: DialogLocationBinding) {
        showStatus("正在获取定位…")
        viewLifecycleOwner.lifecycleScope.launch {
            val lm = LocationHelper.manager(requireContext())
            if (lm == null) return@launch toast("设备不支持定位")

            if (LocationHelper.enabledProviders(lm).isEmpty()) {
                showStatus("系统定位服务没打开")
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("定位服务未开启")
                    .setMessage("去系统设置里打开「位置信息」，或者直接手动填写经纬度。")
                    .setPositiveButton("去设置") { _, _ ->
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    }
                    .setNegativeButton("手动填写", null)
                    .show()
                return@launch
            }

            val fix = withContext(Dispatchers.IO) {
                LocationHelper.current(
                    requireContext(),
                    mode = if (Settings.gpsOnly) LocationHelper.SourceMode.GPS_ONLY
                    else LocationHelper.SourceMode.AUTO
                )
            }
            if (fix == null) {
                showStatus("没取到定位")
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("取不到定位")
                    .setMessage(
                        "系统里连一份历史定位都没有，而且实时定位也没返回。\n\n" +
                            "· 到窗边或室外再试（室内收不到卫星）\n" +
                            "· 确认系统「位置信息」已打开\n" +
                            "· 实在不行，教室坐标可搜一下直接手填"
                    )
                    .setPositiveButton("重试") { _, _ -> fillCurrentLocation(d) }
                    .setNegativeButton("手动填写", null)
                    .show()
            } else {
                d.etLat.setText(String.format("%.6f", fix.location.latitude))
                d.etLon.setText(String.format("%.6f", fix.location.longitude))
                val src = if (fix.fromCache) "缓存" else "实时"
                val warn = when {
                    fix.coarse -> "　⚠ 这是粗略定位（${fix.accuracy}m），可能超出签到范围"
                    fix.stale -> "　⚠ 这是 ${fix.ageText} 的位置，不对请手动改"
                    else -> ""
                }
                showStatus("已填入定位（$src · ${fix.provider} · 精度 ${fix.accuracy} 米 · ${fix.ageText}）$warn")
            }
        }
    }

    private fun showCoursePicker() {
        val accounts = AccountStore.accounts.value
        if (accounts.isEmpty()) return showStatus("还没有添加账号")
        showStatus("正在拉取课程列表…")
        viewLifecycleOwner.lifecycleScope.launch {
            b.progress.visibility = View.VISIBLE
            val courses = withContext(Dispatchers.IO) {
                val acc = accounts.first().copy()
                val s = CxSession(acc)
                if (!s.hasCookies() || !s.checkSession()) {
                    if (s.login().isNotEmpty()) return@withContext emptyList()
                }
                AccountStore.upsert(acc)
                runCatching { CxApi(s).getCourses() }.getOrDefault(emptyList())
            }
            b.progress.visibility = View.GONE
            if (courses.isEmpty()) return@launch showStatus("没拉到课程（会话可能已失效）")

            val keys = courses.map { "${it.courseId}:${it.classId}" }
            val labels = courses.map { c -> "${c.name.ifBlank { "未命名课程" }}  ·  ${c.courseId}" }.toTypedArray()
            val checked = BooleanArray(courses.size) { keys[it] in Settings.courseFilter }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择要签到的课堂（都不勾 = 全部）")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("保存") { _, _ ->
                    val sel = keys.filterIndexed { i, _ -> checked[i] }.toSet()
                    Settings.courseFilter = sel
                    Settings.courseNames = courses
                        .mapIndexed { i, c -> keys[i] to c.name.ifBlank { c.courseId } }
                        .filter { it.first in sel }
                        .toMap()
                    refreshOptions()
                    showStatus(if (sel.isEmpty()) "已选：全部课程" else "已选 ${sel.size} 个课堂")
                }
                .setNeutralButton("全部课程") { _, _ ->
                    Settings.courseFilter = emptySet()
                    Settings.courseNames = emptyMap()
                    refreshOptions()
                    showStatus("已选：全部课程")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showIntervalPicker() {
        val options = arrayOf("1 秒（极速）", "2 秒", "3 秒", "5 秒（推荐）", "10 秒", "20 秒", "30 秒（省电）")
        val values = intArrayOf(1, 2, 3, 5, 10, 20, 30)
        val idx = values.indexOfFirst { it == Settings.intervalSeconds }.coerceAtLeast(0)
        val labels = options.copyOf()
        labels[idx] = "✓ " + labels[idx]
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("巡检频率")
            .setSingleChoiceItems(labels, idx) { dialog, which ->
                Settings.intervalSeconds = values[which]
                dialog.dismiss()
                refreshOptions()
                showStatus("巡检频率已设为 ${values[which]} 秒")
                if (MonitorService.running) {
                    MonitorService.stop(requireContext())
                    MonitorService.start(requireContext())
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------- 人工兜底

    private suspend fun askHumanCaptcha(p: SignFlow.CaptchaPrompt): String? {
        if (!isAdded) return null
        val d = CompletableDeferred<String?>()
        captchaDeferred = d
        showStatus("${p.accountName} 需要人机验证，请滑动拼图")
        withContext(Dispatchers.Main) {
            captchaLauncher.launch(
                CaptchaActivity.intent(
                    requireContext(), p.accountId,
                    CaptchaActivity.CAPTCHA_SIGN, CaptchaActivity.TYPE_SLIDE,
                    "自动解失败（${p.autoFailedReason}），请手动滑动拼图"
                )
            )
        }
        return withTimeoutOrNull(180_000) { d.await() }
    }

    private fun offerWebSignFor(rs: List<SignEngine.Outcome>) {
        val blocked = rs.filter {
            it.status == SignEngine.SignStatus.NEED_CAPTCHA || it.detail.contains("人脸")
        }
        if (blocked.isEmpty()) return
        val acc = AccountStore.get(blocked.first().accountId) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("有 ${blocked.size} 项需要人工处理")
            .setMessage("自动解验证码没过去，或活动有人脸识别等硬性限制。\n\n可以用官方网页手动完成。")
            .setPositiveButton("去网页签到") { _, _ -> startWebSign(acc) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun pickAccountForWebSign(only: Account?) {
        val accounts = if (only != null) listOf(only) else AccountStore.accounts.value
        if (accounts.isEmpty()) return showStatus("还没有添加账号")
        if (accounts.size == 1) return startWebSign(accounts.first())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选一个账号去网页签到")
            .setItems(accounts.map { it.display }.toTypedArray()) { _, which ->
                startWebSign(accounts[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startWebSign(account: Account) {
        showStatus("正在查找进行中的活动…")
        viewLifecycleOwner.lifecycleScope.launch {
            val ref = withContext(Dispatchers.IO) { SignEngine.locateActivity(account) }
            if (ref == null) {
                showStatus("没找到进行中的签到")
                return@launch
            }
            pendingWebAccount = account.id
            webSignLauncher.launch(
                WebAuthActivity.intent(
                    requireContext(), account.id,
                    WebAuthActivity.signUrl(ref.courseId, ref.classId, ref.aid, account.schoolId, account.uid),
                    "网页签到 · ${account.display}"
                )
            )
        }
    }

    // ---------------------------------------------------------- 巡检

    private fun requestNotificationThenStart() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startMonitorService()
    }

    private fun startMonitorService() {
        MonitorService.start(requireContext())
        showStatus("自动巡检已启动（每 ${Settings.intervalSeconds} 秒一轮）")
        toast("自动巡检已启动")
    }

    private fun stopMonitorService() {
        MonitorService.stop(requireContext())
        showStatus("自动巡检已停止")
    }

    // ---------------------------------------------------------- 工具

    private fun photoBytes(): ByteArray? =
        Settings.photoPath.takeIf { it.isNotBlank() }?.let { p ->
            runCatching { File(p).readBytes() }.getOrNull()
        }

    private fun locSummary(): String =
        if (Settings.latitude.isNotBlank() && Settings.longitude.isNotBlank())
            "${Settings.latitude},${Settings.longitude}"
        else "未设置"

    private fun courseSummary(): String {
        val f = Settings.courseFilter
        return if (f.isEmpty()) "全部课程" else "${f.size} 个课堂"
    }

    private fun refreshOptions() {
        b.tvOptions.text = buildString {
            append("位置：").append(locSummary())
            append("　｜　拍照：").append(if (Settings.photoPath.isBlank()) "未选" else "已选")
            append('\n')
            append("课堂：").append(courseSummary())
            append("　｜　巡检：").append(Settings.intervalSeconds).append(" 秒/轮")
            append("　｜　账号间隔 0-").append(Settings.jitterSeconds).append(" 秒")
        }
    }

    private fun showStatus(text: String) {
        _b?.tvStatus?.text = text
    }

    /**
     * 诊断：直接跑一遍自动解验证码。
     * 验证码服务与具体活动无关，所以不需要有进行中的签到也能测。
     */
    private fun diagCaptcha() {
        if (running) return toast("有任务在跑")
        running = true
        b.progress.visibility = View.VISIBLE
        showStatus("诊断：正在自动解验证码…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val r = com.xxt.sign.net.CxCaptcha.solve(
                    client = com.xxt.sign.net.Http.base,
                    captchaId = com.xxt.sign.net.CxCaptcha.CAPTCHA_ID_SIGN,
                    referer = "https://mobilelearn.chaoxing.com/newsign/preSign?activePrimaryId=0"
                )
                val ms = System.currentTimeMillis() - t0
                val msg = if (r.ok) "✅ 自动解成功（${ms}ms）validate=${r.validate?.take(40)}…"
                else "❌ 自动解失败（${ms}ms）：${r.error}"
                showStatus(msg)
                LogStore.add("[诊断] $msg", r.ok)
            } catch (e: Exception) {
                showStatus("诊断异常：${e.message}")
                LogStore.add("[诊断] 异常：${e.message}", false)
            } finally {
                running = false
                b.progress.visibility = View.GONE
            }
        }
    }
}

/** 一行输入框的 MaterialDialog 小封装 */
object MagicDialog {
    fun show(
        ctx: android.content.Context,
        title: String,
        content: View,
        onOk: () -> Unit
    ) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }
}
