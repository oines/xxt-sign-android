package com.xxt.sign.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
import com.xxt.sign.core.GeoCoord
import com.xxt.sign.core.LocationHelper
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.DialogCodeBinding
import com.xxt.sign.databinding.DialogLocationBinding
import com.xxt.sign.databinding.FragmentSignBinding
import com.xxt.sign.net.CxApi
import com.xxt.sign.net.CxSession
import com.xxt.sign.net.QrImageDecoder
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
import java.util.Locale

/**
 * 签到页。
 *
 * 交互只保留一个主按钮：点「一键签到 / 签退」→ 检测出所有账号进行中的活动 →
 * 列出来选一个 → **App 按活动类型自己决定该干什么**：
 *
 *   二维码 → 弹扫码，可选相机或相册图片，所有账号共用这同一份 enc
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

    /** 等人工过验证码的挂起点。**一次只能有一个**，并且要能分辨结果是不是属于它 */
    private class CaptchaWait(
        val prompt: SignFlow.CaptchaPrompt,
        val deferred: CompletableDeferred<String?>
    )

    private var pendingCaptcha: CaptchaWait? = null

    private val captchaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val v = res.data?.getStringExtra(CaptchaActivity.RESULT_VALIDATE)
        val wait = pendingCaptcha
        pendingCaptcha = null
        val ok = res.resultCode == android.app.Activity.RESULT_OK && !v.isNullOrBlank()
        // 超时后自己已经撤掉的挂起点不会被后排的弹窗结果误伤
        wait?.deferred?.complete(if (ok) v else null)
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) {
            pendingScanTarget = null
            // 区分「用户主动取消」和「扫不出来」：
            // ScanContract 两种情况都回 null，但 resultCode 不同（CANCELED vs 正常返回空）。
            showStatus("没有扫到二维码：请让取景框对准二维码、保持 10–20cm，或改用「从相册选择二维码」")
            LogStore.add("[扫码] 相机扫码未返回内容（取景框内没识别到二维码）", false)
        } else {
            LogStore.add("[扫码] 识别到二维码（${text.length} 字符）：${text.take(80)}", true)
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

    /** 相册里的二维码图：不用相机权限，选图后交给 ZXing 解 */
    private val galleryQrPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            pendingScanTarget = null
            showStatus("已取消选择图片")
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            showStatus("正在识别相册里的二维码…")
            val text = withContext(Dispatchers.IO) {
                runCatching { decodeQrFromUri(uri) }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                showStatus("图片里没有识别到二维码，换一张试试")
            } else {
                onQrContent(text)
            }
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
        b.btnPickAccounts.setOnClickListener { showAccountPicker() }

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
        // 页面没了就别让挂起点悬着，得让等待方立刻拿到 null
        pendingCaptcha?.deferred?.complete(null)
        pendingCaptcha = null
        _b = null
    }

    companion object {
        /** 一次人工滑块最多等多久，超时只把当前账号标成「需人工」，不影响其它账号 */
        private const val CAPTCHA_WAIT_MS = 180_000L
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

    /**
     * 「立即签到」实际要处理的账号。
     *
     * [Settings.signAccountIds] 为空 = 全部账号（老行为）；非空 = 只处理选中的那几个，
     * 并且会按当前账号列表过滤一遍（账号被删掉后不会卡在无效 id 上）。
     */
    private fun selectedAccounts(): List<Account> {
        val all = AccountStore.accounts.value
        val ids = Settings.signAccountIds
        if (ids.isEmpty()) return all
        val picked = all.filter { it.id in ids }
        // 选中的账号全被删了 → 退回全部，否则按钮会变成「什么都没发生」
        return picked.ifEmpty { all }
    }

    /**
     * 账号多选。
     *
     * 只影响「立即签到」这次按钮拉取哪些账号的签到任务，不改登录态、不改巡检。
     * 「全选」= 清空选择集合（等于老的「所有账号」）。
     */
    private fun showAccountPicker() {
        val all = AccountStore.accounts.value
        if (all.isEmpty()) return showStatus("还没有添加账号")

        val labels = all.map { a ->
            val n = a.name.ifBlank { a.phone }
            if (a.hasSession) n else "$n（未登录）"
        }.toTypedArray()
        val current = Settings.signAccountIds
        // 空集合视为全选，勾选状态也要显示成全选
        val checked = BooleanArray(all.size) { current.isEmpty() || all[it].id in current }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择要签到的账号（全不勾 = 全部）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("确定") { _, _ ->
                val sel = all.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                Settings.signAccountIds = if (sel.size == all.size) emptySet() else sel
                refreshOptions()
                showStatus(accountSummary())
            }
            .setNeutralButton("全选") { _, _ ->
                Settings.signAccountIds = emptySet()
                refreshOptions()
                showStatus("已选：全部账号")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun accountSummary(): String {
        val all = AccountStore.accounts.value
        val picked = selectedAccounts()
        return if (Settings.signAccountIds.isEmpty() || picked.size == all.size) {
            "签到账号：全部 ${all.size} 个"
        } else {
            "签到账号：${picked.size}/${all.size} 个（${picked.joinToString("、") { it.display }}）"
        }
    }

    private fun doSignAll() {
        val accounts = selectedAccounts()
        if (accounts.isEmpty()) return showStatus("还没有添加账号")
        if (running) return toast("上一个任务还在跑")

        running = true
        b.progress.visibility = View.VISIBLE
        adapter.clear()
        results.clear()
        showStatus(if (accounts.size == 1) "正在检测 ${accounts.first().display} 的签到…"
        else "正在检测 ${accounts.size} 个账号的签到…")

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

        // 所有选中账号的签到任务是同一个 → 直接签，不弹选择框、不重复问签到码/二维码。
        // SignPlanner.execute 会把同一份 signCode / enc 一次性套用到该活动的所有账号上，
        // 所以二维码 / 数字 / 手势只需要用户输入一次。
        SignPlanner.singleTask(list)?.let { d ->
            pendingPlan.clear()
            showStatus("选中账号的签到任务是同一个，一次性签完：${d.title}")
            handlePicked(d)
            return
        }
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
        // 用「本次选中的账号数」做分母，而不是全部账号 —— 否则只选 2 个账号时
        // 会显示成「2 个账号」，看不出是选中范围内的全部。
        val total = selectedAccounts().size
        val who = if (d.accountCount >= total) "选中的全部 $total 个账号"
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
            SignPlanner.Requirement.QR_CODE -> tryRefreshQrThenScan(d)

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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("二维码签到")
            .setItems(arrayOf("打开相机扫码", "从相册选择二维码")) { _, which ->
                when (which) {
                    0 -> startCameraScan()
                    1 -> galleryQrPicker.launch("image/*")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCameraScan() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchScanner() else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                // 只扫二维码。别加一维码格式：会让 ZXing 的扫描线走错分支，暗光下更难识别。
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准教室大屏上的签到二维码，尽量让二维码占满取景框")
                .setBeepEnabled(false)
                // 关掉方向锁定，交给自定义的 QrCaptureActivity 处理竖屏
                .setOrientationLocked(false)
                // 后置主摄（多摄机型必须显式指定）
                .setCameraId(0)
                // 扫完立刻返回
                .setBarcodeImageEnabled(false)
                // **关键**：用我们自己的扫码页。
                // 默认的 zxing CaptureActivity 是「单次对焦」，举着手机扫几米外的大屏时
                // 对焦很容易停在近处，画面看着清楚其实整片都是虚的 —— 表现就是
                // 「相机有画面、二维码对准了，但怎么都扫不上」。
                // QrCaptureActivity 里改成了连续对焦 + 条码场景模式 + 中心测光。
                .setCaptureActivity(QrCaptureActivity::class.java)
        )
    }

    /**
     * 从相册图片解二维码。先按最长边缩到 1600 以内，避免大图 OOM；
     * 解码失败再依次转 90/180/270 度试一次（相册图可能带方向信息）。
     */
    private fun decodeQrFromUri(uri: Uri): String? {
        val resolver = requireContext().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        return try {
            decodeBitmap(src) ?: listOf(90f, 180f, 270f).firstNotNullOfOrNull { degrees ->
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                try {
                    decodeBitmap(rotated)
                } finally {
                    if (rotated !== src) rotated.recycle()
                }
            }
        } finally {
            src.recycle()
        }
    }

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return QrImageDecoder.decodePixels(pixels, bitmap.width, bitmap.height)
    }

    /** 没先选活动就直接扫码：找对应活动再签（只在选中的账号范围内找） */
    private fun scanAndSignDirect(payload: QrPayload) {
        val accounts = selectedAccounts()
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

    /**
     * 动态二维码的 enc 只存在于二维码图片里，但服务端还有 refreshQRCode 接口。
     * 先 best-effort 问一次：拿到 enc 就直接签（不用扫码），拿不到再叫用户扫。
     */
    private fun tryRefreshQrThenScan(d: SignPlanner.Detected) {
        val accounts = selectedAccounts()
        if (accounts.isEmpty()) return showStatus("还没有添加账号")
        if (running) return toast("上一个任务还在跑")
        running = true
        b.progress.visibility = View.VISIBLE
        showStatus("正在向服务端要二维码…")
        viewLifecycleOwner.lifecycleScope.launch {
            val enc = runCatching { SignPlanner.tryRefreshEnc(accounts, d.aid) }.getOrNull()
            running = false
            b.progress.visibility = View.GONE
            if (enc != null) {
                showStatus("已拿到二维码 enc，开始签：${d.title}")
                executePlan(d, null, null, enc)
            } else {
                pendingScanTarget = d
                requestScan()
            }
        }
    }

    /**
     * 执行：选中账号里、有该活动的那些逐个签。
     *
     * 关键点：`qr` / `signCode` / `qrEnc` 是**整批共用**的一份 —— 同一个活动对所有账号来说
     * 二维码 enc、签到码、手势都完全一样，所以只问用户一次，内部按账号逐个套用。
     * 这也是「所有账号签到任务相同 → 一次性签完」的实现方式。
     */
    private fun executePlan(
        d: SignPlanner.Detected,
        qr: QrPayload?,
        signCode: String?,
        qrEnc: String? = null
    ) {
        // 只签本次选中的账号。SignPlanner.execute 内部还会按 d.accountIds 再过滤一次，
        // 这里先传全量账号列表也不会把没选中的签上——但传选中集合更清晰、日志也少。
        val accounts = selectedAccounts()
        if (running) return toast("上一个任务还在跑")
        running = true
        b.progress.visibility = View.VISIBLE
        adapter.clear()
        results.clear()
        showStatus("开始签：${d.title}（${accounts.count { it.id in d.accountIds }} 个账号）")

        viewLifecycleOwner.lifecycleScope.launch {
            var done = false
            try {
                val rs = SignPlanner.execute(
                    target = d,
                    accounts = accounts,
                    qr = qr,
                    signCode = signCode,
                    photoBytes = photoBytes(),
                    qrEnc = qrEnc,
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
            "坐标纠偏　—　${if (Settings.coordShift) "开（系统坐标→地图坐标，推荐）" else "关（用系统原始坐标）"}",
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
                    2 -> showCoordShiftPicker()
                    3 -> showPhotoOptions()
                    4 -> showCoursePicker()
                    5 -> showIntervalPicker()
                    6 -> previewGesture()
                    7 -> pickAccountForWebSign(null)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 坐标纠偏开关（默认开）。
     *
     * 虚拟定位 App 用高德底图（GCJ-02）显示，注入系统时会换算成 WGS-84；
     * 学习通按 GCJ-02 校验，所以必须转回来，否则差 531 米、报「不在可签到范围内」。
     *
     * **注意**：绝对不能在这里用 `setMessage` —— `MaterialAlertDialogBuilder` 同时
     * `setMessage` + `setSingleChoiceItems` 时，部分版本会只渲染 message、把选项列表吞掉，
     * 表现就是「设置里点进去只有一段文字，选不了」。说明文字放 title。
     */
    private fun showCoordShiftPicker() {
        // 用带状态标记的文案，让用户一眼看出当前是开还是关
        val modes = arrayOf(
            "开：系统坐标 → 地图坐标 GCJ-02（推荐）",
            "关：直接用系统给的原始坐标"
        )
        val idx = if (Settings.coordShift) 0 else 1
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                "坐标纠偏（当前：${if (Settings.coordShift) "开" else "关"}）\n" +
                    "开＝显示和提交你在地图上选的点；关＝提交系统原始值。"
            )
            .setSingleChoiceItems(modes, idx) { dialog, which ->
                Settings.coordShift = which == 0
                dialog.dismiss()
                refreshOptions()
                showStatus(
                    if (Settings.coordShift) "坐标纠偏：开（与地图/学习通坐标一致）"
                    else "坐标纠偏：关（用系统原始坐标）"
                )
            }
            .setNegativeButton("取消", null)
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

    private fun showPhotoOptions() {
        val hasPhoto = Settings.photoPath.isNotBlank()
        val options = if (hasPhoto) {
            arrayOf("选择图片", "恢复默认（清除已选图片）")
        } else {
            arrayOf("选择图片")
        }
        // 注意：MaterialAlertDialogBuilder 同时 setMessage + setItems 时，部分版本会只显示 message，
        // 选项列表整个不渲染。这里把说明放到 title，保证选项可见。
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (hasPhoto) "拍照签到图片（已选，可恢复默认）"
                else "拍照签到图片（未选）"
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> photoPicker.launch("image/*")
                    1 -> resetPhoto()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetPhoto() {
        Settings.photoPath = ""
        runCatching { File(requireContext().filesDir, "sign_photo.png").delete() }
        refreshOptions()
        showStatus("拍照签到图片已恢复默认：签到时再现场选择")
        toast("已恢复默认")
    }

    private fun showGpsSourcePicker() {
        val modes = arrayOf("仅 GPS（准，但室内可能拿不到）", "自动：从系统获取并取最准的")
        val idx = if (Settings.gpsOnly) 0 else 1
        // 同上：不要同时 setMessage + setSingleChoiceItems，否则选项可能不显示。
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("定位来源（只影响取坐标，手动坐标优先）")
            .setSingleChoiceItems(modes, idx) { dialog, which ->
                Settings.gpsOnly = which == 0
                dialog.dismiss()
                refreshOptions()
                showStatus(if (Settings.gpsOnly) "定位来源：仅 GPS" else "定位来源：自动（从系统获取）")
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

        // 没存过坐标时：
        // · 自动模式：直接向系统要一次实时定位（填「当前定位」按钮改为强制不用缓存）
        // · 仅 GPS：先把系统缓存填进来，用户再点「用当前定位填充」实时取
        if (Settings.latitude.isBlank() && Settings.longitude.isBlank()) {
            if (!Settings.gpsOnly) {
                if (hasLocationPermission()) fillCurrentLocation(d) else requestLocationPermission(d)
            } else {
                val cached = LocationHelper.manager(requireContext())?.let { LocationHelper.bestCached(it) }
                if (cached != null) {
                    val rawLat = cached.location.latitude
                    val rawLon = cached.location.longitude
                    fillLatLon(d, rawLat, rawLon)
                    showStatus(
                        "已预填系统缓存位置（${cached.provider}·精度 ${cached.accuracy} 米·${cached.ageText}）" +
                            shiftNote(rawLat, rawLon) + "，不对就点「用当前定位填充」"
                    )
                }
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
                    else LocationHelper.SourceMode.AUTO,
                    // 「用目前定位填充」必须重新向系统要一次，不能拿系统缓存糊弄；
                    // 实时拿不到就报错，让用户重试/手填，而不是悄悄提交旧位置。
                    acceptCachedImmediately = false,
                    useCache = false
                )
            }
            if (fix == null) {
                showStatus("没取到实时定位（已忽略系统缓存）")
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("取不到定位")
                    .setMessage(
                        "已经跳过系统缓存，本次实时定位没有返回。\n\n" +
                            "· 到窗边或室外再试（室内收不到卫星）\n" +
                            "· 确认系统「位置信息」已打开\n" +
                            "· 实在不行，教室坐标可搜一下直接手填"
                    )
                    .setPositiveButton("重试") { _, _ -> fillCurrentLocation(d) }
                    .setNegativeButton("手动填写", null)
                    .show()
            } else {
                val rawLat = fix.location.latitude
                val rawLon = fix.location.longitude
                fillLatLon(d, rawLat, rawLon)
                val src = if (fix.fromCache) "缓存" else "实时"
                val warn = when {
                    fix.coarse -> "　⚠ 这是粗略定位（${fix.accuracy}m），可能超出签到范围"
                    fix.stale -> "　⚠ 这是 ${fix.ageText} 的位置，不对请手动改"
                    else -> ""
                }
                // 诊断：把「系统给的原始坐标」和「最终要提交的坐标」都打出来。
                // 只要这两个数不一样，就是纠偏在起作用 —— 排查「模拟器显示的和 App 里的不一致」
                // 时，这一行能直接定位。
                val finalLat = d.etLat.text?.toString()?.trim().orEmpty()
                val finalLon = d.etLon.text?.toString()?.trim().orEmpty()
                LogStore.add(
                    "[定位] 系统原始 ${"%.6f".format(Locale.US, rawLat)},${"%.6f".format(Locale.US, rawLon)}" +
                        " → 将提交 $finalLat,$finalLon" +
                        "（纠偏=${if (Settings.coordShift) "开" else "关"}）",
                    true
                )
                showStatus(
                    "已填入定位（$src · ${fix.provider} · 精度 ${fix.accuracy} 米 · ${fix.ageText}）" +
                        shiftNote(rawLat, rawLon) + warn
                )
            }
        }
    }

    /**
     * 把系统坐标写进输入框。
     *
     * **默认做 WGS-84 → GCJ-02 转换**（[Settings.coordShift] 默认开）。
     *
     * 真机实测：虚拟定位 App 用高德底图显示 GCJ-02（`31.889087, 118.902128`），
     * 但注入系统时会换算成 WGS-84（`31.891269, 118.897120`）。
     * `LocationManager` 交给 App 的是 WGS-84，学习通按 GCJ-02 校验，
     * 所以必须转回来 —— 转完正好等于模拟器上显示的那个点，误差 0。
     *
     * 手动粘贴坐标不走这里，粘贴的通常已经是地图坐标。
     */
    private fun fillLatLon(d: DialogLocationBinding, rawLat: Double, rawLon: Double) {
        val target = if (Settings.coordShift) GeoCoord.wgsToGcj(rawLat, rawLon)
        else GeoCoord.LatLng(rawLat, rawLon)
        d.etLat.setText(String.format(Locale.US, "%.6f", target.lat))
        d.etLon.setText(String.format(Locale.US, "%.6f", target.lon))
    }

    /** 纠偏时给状态栏补一句「原始坐标 + 差多少米」，方便和地图 App 对点 */
    private fun shiftNote(rawLat: Double, rawLon: Double): String {
        if (!Settings.coordShift) return ""
        val t = GeoCoord.wgsToGcj(rawLat, rawLon)
        val d = GeoCoord.distanceMeters(rawLat, rawLon, t.lat, t.lon)
        return String.format(
            Locale.US, " · 已纠偏为地图坐标（原始 %.6f,%.6f，差 %.0f 米）", rawLat, rawLon, d
        )
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

    /**
     * 弹人工滑块并等结果。
     *
     * 调用方（[SignPlanner]）已经把多个账号的验证码排成队列，一次只会进来一个；
     * 这里再自己守一道：超时/取消就把挂起点撤掉，避免迟到的页面结果发给下一个账号。
     */
    private suspend fun askHumanCaptcha(p: SignFlow.CaptchaPrompt): String? {
        if (!isAdded) return null
        val d = CompletableDeferred<String?>()
        val wait = CaptchaWait(p, d)
        pendingCaptcha = wait
        showStatus("${p.title} 需要人机验证，请滑动拼图")
        withContext(Dispatchers.Main) {
            captchaLauncher.launch(
                CaptchaActivity.intent(
                    requireContext(), p.accountId,
                    CaptchaActivity.CAPTCHA_SIGN, CaptchaActivity.TYPE_SLIDE,
                    "${p.reason}，请完成拼图后继续签到",
                    title = p.title
                )
            )
        }
        val v = withTimeoutOrNull(CAPTCHA_WAIT_MS) { d.await() }
        if (pendingCaptcha === wait) pendingCaptcha = null
        if (v.isNullOrBlank()) {
            showStatus("${p.title} 人机验证未完成，这个账号先跳过")
            LogStore.add("[人机验证] ${p.title} 未完成，标记为需人工", false)
        }
        return v
    }

    private fun offerWebSignFor(rs: List<SignEngine.Outcome>) {
        val blocked = rs.filter {
            it.status == SignEngine.SignStatus.NEED_CAPTCHA ||
                it.status == SignEngine.SignStatus.NEED_HUMAN ||
                it.detail.contains("人脸")
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
            append(accountSummary())
            append('\n')
            append("位置：").append(locSummary())
            append("　｜　拍照：").append(if (Settings.photoPath.isBlank()) "未选" else "已选")
            append('\n')
            append("课堂：").append(courseSummary())
            append("　｜　巡检：").append(Settings.intervalSeconds).append(" 秒/轮")
            append("　｜　账号间隔 0-").append(Settings.jitterSeconds).append(" 秒")
        }
        b.btnPickAccounts.text = if (Settings.signAccountIds.isEmpty()) "选择账号（全部）"
        else "选择账号（${selectedAccounts().size} 个）"
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
