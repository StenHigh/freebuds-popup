package by.dzianis.budspopup

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: permissions, HyperOS tweaks, device choice, test button. Plain views, no XML. */
class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * dp).toInt(); setPadding(p, p * 2, p, p)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        header("Buds Popup")
        note("Карточка с зарядом при открытии кейса FreeBuds. Пройди пункты сверху вниз.")

        val btOk = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        section("1. Разрешения")
        step("Доступ к Bluetooth", btOk) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) }
        step("Показ поверх других окон", overlayOk) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        step("Без ограничений батареи", batteryOk) { requestIgnoreBattery() }

        section("2. HyperOS (иначе система будет глушить приложение)")
        action("Автозапуск → включить") { openMiuiAutostart() }
        action("Другие разрешения → «Всплывающие окна в фоне»") { openMiuiPermEditor() }

        section("3. Наушники")
        if (btOk) devicePicker() else note("Сначала выдай доступ к Bluetooth.")

        section("4. Проверка")
        action("Показать карточку сейчас") { testPopup() }
        note("Последнее событие: ${Prefs.lastLog(this)}")
        note("Если карточка пишет «нет данных о заряде» — закрой приложение Huawei Audio Connect / AI Life: " +
                "оно может занимать служебный канал наушников.")
    }

    // ---------------------------------------------------------------- devices

    @SuppressLint("MissingPermission")
    private fun bonded(): List<BluetoothDevice> =
        getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.toList().orEmpty()
            .sortedByDescending { Prefs.looksLikeFreeBuds(it.name ?: "") }

    @SuppressLint("MissingPermission")
    private fun devicePicker() {
        val selected = Prefs.selectedAddress(this)
        val group = RadioGroup(this)
        val auto = RadioButton(this).apply { text = "Авто: любые FreeBuds / FreeClip"; id = View.generateViewId() }
        group.addView(auto)
        if (selected == null) auto.isChecked = true
        val ids = HashMap<Int, String>()
        for (d in bonded()) {
            val rb = RadioButton(this).apply {
                text = "${d.name ?: "?"}  (${d.address})"
                id = View.generateViewId()
            }
            ids[rb.id] = d.address
            group.addView(rb)
            if (d.address.equals(selected, true)) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { _, id -> Prefs.setSelectedAddress(this, ids[id]) }
        content.addView(group)
    }

    @SuppressLint("MissingPermission")
    private fun testPopup() {
        if (!Settings.canDrawOverlays(this)) { toast("Нужно разрешение на показ поверх окон"); return }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            toast("Нужен доступ к Bluetooth"); return
        }
        val sel = Prefs.selectedAddress(this)
        val dev = bonded().firstOrNull { if (sel != null) it.address.equals(sel, true) else Prefs.looksLikeFreeBuds(it.name ?: "") }
        if (dev == null) { toast("Сопряжённые FreeBuds не найдены"); return }
        // Go home so the card shows over the launcher, like the real event.
        moveTaskToBack(true)
        content.postDelayed({ PopupController.show(applicationContext, dev) {} }, 400)
    }

    // ---------------------------------------------------------------- system screens

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { openAppDetails() }
    }

    private fun openMiuiAutostart() {
        val i = Intent().setComponent(ComponentName(
            "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        runCatching { startActivity(i) }.onFailure { openAppDetails() }
    }

    private fun openMiuiPermEditor() {
        val i = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName)
        runCatching { startActivity(i) }.onFailure { openAppDetails() }
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ---------------------------------------------------------------- tiny UI kit

    private fun header(t: String) = content.addView(TextView(this).apply {
        text = t; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
    })

    private fun section(t: String) = content.addView(TextView(this).apply {
        text = t; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (22 * dp).toInt(), 0, (6 * dp).toInt())
    })

    private fun note(t: String) = content.addView(TextView(this).apply {
        text = t; textSize = 14f; alpha = 0.75f; setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
    })

    private fun step(t: String, ok: Boolean, onClick: () -> Unit) = content.addView(Button(this).apply {
        text = if (ok) "✓  $t" else "○  $t — выдать"
        isEnabled = !ok
        isAllCaps = false
        setOnClickListener { onClick() }
    })

    private fun action(t: String, onClick: () -> Unit) = content.addView(Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    })

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
