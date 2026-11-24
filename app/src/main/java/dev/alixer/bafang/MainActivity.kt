package dev.alixer.bafang

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Bafang Companion mobile dashboard. One page per task, existing BLE and routing unchanged. */
class MainActivity : Activity(), RideService.Observer {
    private val ui = Handler(Looper.getMainLooper())
    private val addressWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bafang-address-search").apply { isDaemon = true }
    }
    private val search = AddressSearch()
    private var service: RideService? = null
    private lateinit var updater: GitHubUpdater
    private val pages = mutableListOf<LinearLayout>()
    private val tabs = mutableListOf<Button>()
    private lateinit var scroll: ScrollView
    private var pageIndex = 0
    private var initialized = false

    private lateinit var statusTop: TextView
    private lateinit var speedMain: TextView
    private lateinit var speedOrigin: TextView
    private lateinit var batteryMain: TextView
    private lateinit var pasMain: TextView
    private lateinit var tripMain: TextView
    private lateinit var avgMain: TextView
    private lateinit var bikeStatus: TextView
    private lateinit var bikeMetrics: TextView
    private lateinit var assistLabel: TextView
    private lateinit var brightnessLabel: TextView
    private lateinit var navigation: TextView
    private lateinit var updateState: TextView
    private lateinit var devices: LinearLayout
    private lateinit var logs: TextView
    private lateinit var results: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var selectedAddress: TextView
    private lateinit var routeButton: Button
    private lateinit var startNavButton: Button
    private lateinit var routeGuidanceStatus: TextView
    private lateinit var assistMinus: Button
    private lateinit var assistPlus: Button
    private lateinit var lightToggle: Button
    private val assistButtons = mutableListOf<Button>()
    private val brightnessButtons = mutableListOf<Button>()
    private val logLines = ArrayDeque<String>()
    private val seenDevices = mutableSetOf<String>()

    private lateinit var map: MapView
    private lateinit var routeLine: Polyline
    private lateinit var traceLine: Polyline
    private lateinit var positionMarker: Marker
    private lateinit var destinationMarker: Marker
    private var mapCentered = false
    private var oldRoute: Routing.Route? = null
    private var chosenPoint: GeoPoint? = null
    private var chosenName: String? = null
    @Volatile private var addressRevision = 0
    private var suggestionTask: Runnable? = null
    private var changingSearchProgrammatically = false
    private val permissionsRequest = 210
    private val saveGpxRequest = 211

    private val bg = Color.rgb(9, 21, 29)
    private val cardColor = Color.rgb(21, 39, 49)
    private val surfaceColor = Color.rgb(30, 52, 63)
    private val accent = Color.rgb(52, 221, 178)
    private val ink = Color.rgb(243, 249, 249)
    private val secondary = Color.rgb(159, 188, 198)
    private val alert = Color.rgb(255, 196, 119)

    private fun dp(v: Int) = (resources.displayMetrics.density * v).roundToInt()
    private fun shape(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun ripple(color: Int, radius: Int = 14) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(55, 255, 255, 255)), shape(color, radius), null
    )
    private fun txt(value: String, size: Float = 14f, bold: Boolean = false, color: Int = ink): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            includeFontPadding = true
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun add(parent: LinearLayout, view: View, top: Int = 0, bottom: Int = 0) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top); lp.bottomMargin = dp(bottom)
        parent.addView(view, lp)
    }
    private fun stack(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card(): LinearLayout = stack().apply {
        background = shape(cardColor, 20)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 13.5f; isAllCaps = false
            minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
            setPadding(dp(7), 0, dp(7), 0)
            setTextColor(if (primary) bg else ink)
            background = ripple(if (primary) accent else surfaceColor)
            stateListAnimator = null
            setOnClickListener { action() }
        }
    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, view ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.marginStart = dp(7)
            addView(view, lp)
        }
    }
    private fun page(title: String, subtitle: String): LinearLayout {
        val p = stack().apply { setPadding(dp(17), dp(16), dp(17), dp(28)) }
        add(p, txt("BAFANG  /  COMPANION", 11f, true, accent), bottom = 6)
        add(p, txt(title, 29f, true), bottom = 3)
        add(p, txt(subtitle, 13f, color = secondary), bottom = 18)
        pages.add(p)
        return p
    }
    private fun mini(label: String, value: String): Pair<LinearLayout, TextView> {
        val c = card()
        add(c, txt(label.uppercase(Locale.ROOT), 10f, true, secondary), bottom = 4)
        val t = txt(value, 23f, true)
        add(c, t)
        return c to t
    }
    private fun showTab(index: Int) {
        pageIndex = index
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, tab ->
            tab.setTextColor(if (i == index) bg else secondary)
            tab.background = ripple(if (i == index) accent else bg, 13)
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        // Android 15/target 35 forces edge-to-edge; apply real system/IME insets below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        Configuration.getInstance().userAgentValue = "BafangCompanion/0.17 (Android; Photon/OSM)"
        updater = GitHubUpdater(this) { message ->
            updateState.text = message
            if (message.contains("disponible") || message.contains("cancelada")) toast(message)
        }
        val root = stack().apply { setBackgroundColor(bg) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val body = stack().apply { setBackgroundColor(bg) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        buildDashboard(body)
        buildNavigation(body)
        buildBike(body)
        buildUpdates(body)
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(Color.rgb(13, 28, 37), 0)
            setPadding(dp(9), dp(8), dp(9), dp(10))
        }
        listOf("⌂  Panel", "➤  Ruta", "▣  Bici", "⚙  Ajustes").forEachIndexed { i, label ->
            val tab = button(label) { showTab(i) }
            tab.textSize = 11.5f
            tab.minHeight = dp(48)
            tab.minimumHeight = dp(48)
            tabs.add(tab)
            val lp = LinearLayout.LayoutParams(0, dp(52), 1f)
            if (i > 0) lp.marginStart = dp(3)
            navBar.addView(tab, lp)
        }
        root.addView(navBar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val keyboard = if (keyboardVisible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            navBar.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard))
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        chosenPoint = savedInstanceState?.let {
            if (it.containsKey("destination_lat") && it.containsKey("destination_lon"))
                GeoPoint(it.getDouble("destination_lat"), it.getDouble("destination_lon")) else null
        }
        chosenName = savedInstanceState?.getString("destination_name")
        chosenPoint?.let { updateDestination(it, chosenName ?: "Destino guardado", false) }
        showTab(savedInstanceState?.getInt("active_tab", 0)?.coerceIn(0, 3) ?: 0)
        initialized = true
        requestPermissionsAndLaunch()
    }

    private fun buildDashboard(body: LinearLayout) {
        val p = page("Tu recorrido", "Toda la información importante, de un vistazo")
        body.addView(p)
        val hero = card().apply { setPadding(dp(22), dp(21), dp(22), dp(19)) }
        add(hero, txt("VELOCIDAD ACTUAL  ·  GPS", 11f, true, accent), bottom = 6)
        speedMain = txt("—", 68f, true)
        add(hero, speedMain)
        add(hero, txt("km/h", 19f, true, secondary), bottom = 8)
        speedOrigin = txt("Esperando ubicación GPS…", 12f, color = secondary)
        add(hero, speedOrigin, bottom = 14)
        statusTop = txt("○  Bicicleta sin conectar", 14f, true, alert)
        add(hero, statusTop)
        add(p, hero, bottom = 12)
        val r1 = row()
        val (batCard, bat) = mini("Batería", "— %")
        batteryMain = bat
        val (pasCard, pas) = mini("Asistencia", "PAS —")
        pasMain = pas
        r1.addView(batCard, LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(pasCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        add(p, r1, bottom = 10)
        val r2 = row()
        val (tripCard, trip) = mini("Trip bici", "— km")
        tripMain = trip
        val (avgCard, avg) = mini("Media", "— km/h")
        avgMain = avg
        r2.addView(tripCard, LinearLayout.LayoutParams(0, -2, 1f))
        r2.addView(avgCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        add(p, r2, bottom = 12)
        val routeCard = card()
        add(routeCard, txt("PRÓXIMO GIRO", 11f, true, accent), bottom = 8)
        navigation = txt("Sin ruta activa · elige un destino", 15f, true)
        add(routeCard, navigation, bottom = 12)
        add(routeCard, row(button("Ir a mapa", true) { showTab(1) }, button("Conectar bici") { showTab(2); scanBike() }))
        add(p, routeCard, bottom = 12)
        add(p, row(button("Exportar GPX") { saveGpx() }, button("Nuevo recorrido") {
            service?.clearTrip(); toast("Contador GPS reiniciado")
        }))
    }

    private fun buildNavigation(body: LinearLayout) {
        val p = page("Planifica tu ruta", "Dirección, mapa y navegación en tu pantalla")
        body.addView(p)
        val addressCard = card()
        add(addressCard, txt("BUSCAR DESTINO", 11f, true, accent), bottom = 8)
        searchInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(secondary)
            hint = "Calle, número, ciudad o lugar"
            setPadding(dp(12), dp(9), dp(12), dp(9))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = shape(surfaceColor, 12)
            setOnEditorActionListener { _, id, event ->
                if (id == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    searchAddress(); true
                } else false
            }
        }
        add(addressCard, searchInput, bottom = 9)
        add(addressCard, button("Buscar ahora", true) { searchAddress() }, bottom = 8)
        results = stack()
        add(addressCard, results)
        add(addressCard, txt("Sugerencias con Photon · datos © OpenStreetMap contributors · servicio comunitario", 10.5f, color = secondary), top = 5)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (changingSearchProgrammatically) return
                chosenPoint = null
                chosenName = null
                selectedAddress.text = "Selecciona una dirección sugerida"
                routeButton.isEnabled = false
                startNavButton.isEnabled = false
                scheduleSuggestions()
            }
        })
        add(p, addressCard, bottom = 12)
        val routeCard = card()
        add(routeCard, txt("DESTINO Y NAVEGACIÓN", 11f, true, accent), bottom = 7)
        selectedAddress = txt("Selecciona una dirección o mantén pulsado el mapa", 14f)
        add(routeCard, selectedAddress, bottom = 11)
        routeButton = button("1 · Calcular y previsualizar ruta", true) { launchRoute() }
        routeButton.isEnabled = false
        add(routeCard, routeButton, bottom = 8)
        routeGuidanceStatus = txt("Calcula un recorrido. Después podrás iniciar las flechas y distancias en B02NC.", 13f, color = secondary)
        add(routeCard, routeGuidanceStatus, bottom = 9)
        startNavButton = button("2 · Iniciar navegación en B02NC", true) {
            val svc = service ?: run { toast("Esperando al servicio de navegación"); return@button }
            if (svc.startNavigation()) {
                toast(svc.navigationText)
                showTab(0)
            } else {
                toast(svc.navigationText)
            }
        }
        startNavButton.isEnabled = false
        add(routeCard, startNavButton, bottom = 9)
        add(routeCard, row(button("Mi posición") { centerOnPosition() },
            button("Detener navegación") { service?.stopRoute() }))
        add(p, routeCard, bottom = 12)
        val mapCard = card().apply { setPadding(dp(8), dp(8), dp(8), dp(12)) }
        map = MapView(this).apply {
            setMultiTouchControls(true)
            controller.setZoom(15.5)
            // The map is embedded in a vertical ScrollView. Keep drag, pinch-zoom
            // and double-tap gestures inside the map instead of scrolling the page.
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE -> parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        parent?.requestDisallowInterceptTouchEvent(false)
                }
                false // let osmdroid process the entire gesture stream
            }
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) updateDestination(p, "Punto elegido en el mapa", true)
                return true
            }
        }))
        traceLine = Polyline().apply {
            outlinePaint.color = Color.rgb(80, 180, 242)
            outlinePaint.strokeWidth = dp(3).toFloat()
        }
        routeLine = Polyline().apply {
            outlinePaint.color = accent
            outlinePaint.strokeWidth = dp(6).toFloat()
        }
        map.overlays.add(routeLine)
        map.overlays.add(traceLine)
        positionMarker = Marker(map).apply {
            title = "Mi posición"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); isEnabled = false
        }
        destinationMarker = Marker(map).apply {
            title = "Destino"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); isEnabled = false
        }
        map.overlays.add(positionMarker)
        map.overlays.add(destinationMarker)
        mapCard.addView(map, LinearLayout.LayoutParams(-1, dp(350)))
        add(mapCard, txt("Arrastra con un dedo · pellizca con dos para ampliar · mantén pulsado para elegir destino · © OpenStreetMap contributors", 11f, color = secondary), top = 8)
        add(p, mapCard, bottom = 12)
    }

    private fun buildBike(body: LinearLayout) {
        val p = page("Mi bicicleta", "Conexión, asistencia y controles B02NC")
        body.addView(p)
        val connect = card()
        add(connect, txt("ESTADO BLUETOOTH", 11f, true, accent), bottom = 7)
        bikeStatus = txt("Sin conectar", 15f, true, alert)
        add(connect, bikeStatus, bottom = 10)
        add(connect, row(button("Buscar pantalla", true) { scanBike() }, button("Desconectar") { service?.ble?.disconnect() }), bottom = 8)
        devices = stack()
        add(connect, devices)
        add(p, connect, bottom = 12)
        val assistCard = card()
        add(assistCard, txt("ASISTENCIA AL PEDALEO", 11f, true, accent), bottom = 5)
        assistLabel = txt("PAS — / 5", 23f, true)
        add(assistCard, assistLabel, bottom = 9)
        assistMinus = button("−  Bajar") { service?.ble?.let { it.setAssist((it.assistLevel - 1).coerceAtLeast(0)) } }
        assistPlus = button("+  Subir") { service?.ble?.let { it.setAssist((it.assistLevel + 1).coerceAtMost(5)) } }
        add(assistCard, row(assistMinus, assistPlus), bottom = 9)
        for (r in 0..1) {
            val controls = (r * 3..r * 3 + 2).map { level ->
                button("PAS $level") { service?.ble?.setAssist(level) }.also { assistButtons.add(it) }
            }
            add(assistCard, row(*controls.toTypedArray()), bottom = 7)
        }
        add(p, assistCard, bottom = 12)
        val lights = card()
        add(lights, txt("LUCES Y PANTALLA", 11f, true, accent), bottom = 9)
        lightToggle = button("◉  Encender luces", true) {
            val ble = service?.ble ?: return@button
            val turnOn = ble.lightsOnForUi != true
            ble.setLights(turnOn)
            updateLightButton(ble.lightsOnForUi, ble.isAuthenticated)
        }
        add(lights, lightToggle, bottom = 6)
        add(lights, txt("B02NC: estado de luces leído por telemetría BLE cuando está disponible.", 11f, color = secondary), bottom = 13)
        brightnessLabel = txt("Brillo: sin lectura", 14f, true)
        add(lights, brightnessLabel, bottom = 8)
        val brightness = (1..5).map { level ->
            button("$level") { service?.ble?.setBrightness(level) }.also { brightnessButtons.add(it) }
        }
        add(lights, row(*brightness.toTypedArray()))
        add(p, lights, bottom = 12)
        val ride = card()
        add(ride, txt("TELEMETRÍA", 11f, true, accent), bottom = 7)
        bikeMetrics = txt("Esperando datos de B02NC", 13f, color = secondary)
        add(ride, bikeMetrics, bottom = 12)
        add(ride, button("Ayuda a la marcha · información") { showWalkHelp() })
        add(p, ride)
    }

    private fun buildUpdates(body: LinearLayout) {
        val p = page("Ajustes y versión", "Actualiza sin desinstalar la aplicación")
        body.addView(p)
        val updates = card()
        add(updates, txt("ACTUALIZACIONES SEGURAS", 11f, true, accent), bottom = 7)
        add(updates, txt("Bafang Companion 0.19.0", 24f, true), bottom = 7)
        add(updates, txt("Busca la última versión, descarga el APK firmado y permite su instalación desde Android. Se conserva la configuración y la conexión habitual.", 13f, color = secondary), bottom = 12)
        updateState = txt("Sin comprobar · pulsa Buscar actualización", 13f)
        add(updates, updateState, bottom = 10)
        add(updates, button("Buscar e instalar actualización", true) { updater.check() }, bottom = 9)
        add(updates, button("Ver Releases públicos") { openReleases() }, bottom = 8)
        add(updates, txt("Actualizaciones públicas de GitHub: sin cuenta ni token. Android solicitará confirmar la instalación.", 12f, color = secondary))
        add(p, updates, bottom = 12)
        val diagnostics = card()
        add(diagnostics, txt("DIAGNÓSTICO BLUETOOTH", 11f, true, accent), bottom = 8)
        add(diagnostics, button("Compartir registro BLE") { shareLog() }, bottom = 10)
        logs = txt("Todavía no hay eventos de Bluetooth", 11f, color = secondary).apply {
            setTextIsSelectable(true)
            background = shape(bg, 12)
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }
        add(diagnostics, logs)
        add(p, diagnostics)
    }

    private fun searchAddress() {
        val query = searchInput.text.toString().trim()
        if (query.length < 3) { toast("Escribe al menos tres caracteres"); return }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
        scheduleSuggestions(immediate = true)
    }

    /** Debounced search-as-you-type; ignore obsolete responses when the user types again. */
    private fun scheduleSuggestions(immediate: Boolean = false) {
        val query = searchInput.text.toString().trim()
        val version = ++addressRevision
        suggestionTask?.let(ui::removeCallbacks)
        suggestionTask = null
        results.removeAllViews()
        if (query.length < 3) {
            if (query.isNotEmpty()) add(results, txt("Escribe al menos 3 caracteres para ver sugerencias", 12f, color = secondary))
            return
        }
        val runnable = Runnable {
            if (version != addressRevision || isFinishing || isDestroyed) return@Runnable
            results.removeAllViews()
            add(results, txt("Buscando sugerencias…", 13f, color = secondary))
            addressWorker.execute {
                if (version != addressRevision) return@execute
                val response = runCatching { search.find(query) }
                ui.post {
                    if (version != addressRevision || isFinishing || isDestroyed) return@post
                    results.removeAllViews()
                    response.onFailure { err ->
                        add(results, txt("No se pudieron obtener sugerencias: ${err.message}", 12f, color = alert))
                    }.onSuccess { places ->
                        if (places.isEmpty()) add(results, txt("Sin resultados. Prueba con una ciudad o código postal.", 13f, color = secondary))
                        places.forEach { place ->
                            val item = button("⌖  ${place.name}") {
                                addressRevision++
                                suggestionTask?.let(ui::removeCallbacks)
                                suggestionTask = null
                                updateDestination(GeoPoint(place.lat, place.lon), place.name, true)
                                results.removeAllViews()
                                add(results, txt("✓ Dirección seleccionada", 12f, color = accent))
                                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                    .hideSoftInputFromWindow(searchInput.windowToken, 0)
                                searchInput.clearFocus()
                            }
                            item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            item.maxLines = 2
                            item.ellipsize = android.text.TextUtils.TruncateAt.END
                            add(results, item, top = 5)
                        }
                    }
                }
            }
        }
        suggestionTask = runnable
        ui.postDelayed(runnable, if (immediate) 0L else 700L)
    }

    private fun updateDestination(point: GeoPoint, label: String, center: Boolean) {
        // Avoid displaying directions for an old destination after a new selection.
        if (service?.navigationActive == true) service?.stopRoute()
        chosenPoint = point; chosenName = label
        changingSearchProgrammatically = true
        searchInput.setText(label)
        searchInput.setSelection(searchInput.length())
        changingSearchProgrammatically = false
        selectedAddress.text = label
        routeButton.isEnabled = true
        destinationMarker.position = point
        destinationMarker.title = label
        if (center) map.controller.animateTo(point)
        map.invalidate()
    }
    private fun launchRoute() {
        val point = chosenPoint ?: run { toast("Selecciona primero un destino"); return }
        if (service?.gps == null) { toast("Esperando la posición GPS; mantén activada la ubicación"); return }
        service?.planRoute(point)
        toast("Calculando recorrido. Cuando termine, pulsa «Iniciar navegación».")
    }
    private fun centerOnPosition() {
        val gps = service?.gps ?: run { toast("Esperando GPS"); return }
        map.controller.animateTo(GeoPoint(gps.latitude, gps.longitude))
        map.controller.setZoom(16.0)
    }
    private fun scanBike() {
        val ble = service?.ble ?: run { toast("Iniciando Bluetooth…"); return }
        seenDevices.clear(); devices.removeAllViews()
        ble.scan()
    }
    private fun openReleases() {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
            "https://github.com/AlixerVolkov/Bafang-Companion-Public/releases")))
    }

    private fun requiredPermissions(): Array<String> {
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        return required.toTypedArray()
    }
    private fun requestPermissionsAndLaunch() {
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionsRequest)
        else launchService()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == permissionsRequest) {
            val essential = requiredPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
            if (essential.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) launchService()
            else toast("Necesitamos permisos Bluetooth y GPS para navegar")
        }
    }
    private fun launchService() {
        try {
            val i = Intent(this, RideService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            connectUi()
        } catch (e: Exception) { toast("Error al iniciar la aplicación: ${e.message}") }
    }
    private fun connectUi() {
        val svc = RideService.active
        if (svc == null) { ui.postDelayed({ if (!isFinishing) connectUi() }, 250); return }
        service = svc
        svc.observer = this
        onState()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDevice(device: BluetoothDevice, label: String) = runOnUiThread {
        if (seenDevices.add(device.address)) add(devices, button("Conectar: $label", true) {
            service?.ble?.connect(device)
        }, top = 6)
    }
    override fun onLog(message: String) = runOnUiThread {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logLines.addLast("$stamp  $message")
        while (logLines.size > 120) logLines.removeFirst()
        logs.text = logLines.joinToString("\n")
    }
    override fun onState() {
        if (!initialized) return
        runOnUiThread {
            val svc = service ?: return@runOnUiThread
            val connected = svc.ble.isAuthenticated
            val gps = svc.gpsSpeedKmh
            speedMain.text = gps?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            speedOrigin.text = if (gps == null) "Esperando velocidad GPS fiable" else "Velocidad obtenida del GPS del teléfono"
            statusTop.text = if (connected) "●  ${svc.ble.deviceName} · conectado" else "○  Bicicleta sin conectar"
            statusTop.setTextColor(if (connected) accent else alert)
            bikeStatus.text = svc.status
            bikeStatus.setTextColor(if (connected) accent else alert)
            batteryMain.text = svc.b02ncBatteryCandidate?.let { "$it %" } ?: "— %"
            pasMain.text = if (connected) "PAS ${svc.ble.assistLevel}" else "PAS —"
            tripMain.text = svc.b02ncTripKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "— km"
            avgMain.text = svc.b02ncAvgCalculatedKmh?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
            navigation.text = svc.navigationText
            val preview = svc.route
            val matchesDestination = preview != null && chosenPoint?.let { p ->
                kotlin.math.abs(p.latitude-preview.target.latitude)<0.00005 &&
                    kotlin.math.abs(p.longitude-preview.target.longitude)<0.00005
            } == true
            routeButton.isEnabled = chosenPoint != null && !svc.routingBusy
            routeButton.text = if (svc.routingBusy) "Calculando recorrido…" else "1 · Calcular y previsualizar ruta"
            startNavButton.isEnabled = matchesDestination && !svc.routingBusy && !svc.navigationActive
            startNavButton.text = if (svc.navigationActive) "✓  Navegación activa en B02NC"
                else "2 · Iniciar navegación en B02NC"
            routeGuidanceStatus.text = when {
                svc.routingBusy -> "Buscando ruta ciclista y maniobras…"
                svc.navigationActive -> "●  ${svc.navigationText}"
                matchesDestination -> "✓  Ruta preparada: ${preview!!.meters.roundToInt()} m · ${preview.turns.size} maniobras. Pulsa Iniciar para activar la pantalla."
                preview != null -> "Hay una ruta anterior. Calcula el recorrido al nuevo destino antes de iniciarlo."
                else -> "Elige una dirección, calcula la ruta y después inicia las instrucciones en B02NC."
            }
            assistLabel.text = if (!connected) "PAS desconectado" else if (svc.ble.pendingAssistLevel != null)
                "PAS ${svc.ble.assistLevel} / 5 · pendiente" else "PAS ${svc.ble.assistLevel} / 5"
            val selectable = listOf(assistMinus, assistPlus, lightToggle) + assistButtons + brightnessButtons
            selectable.forEach { it.isEnabled = connected; it.alpha = if (connected) 1f else .4f }
            updateLightButton(svc.ble.lightsOnForUi, connected)
            assistButtons.forEachIndexed { level, btn ->
                val active = connected && svc.ble.assistLevel == level
                btn.background = ripple(if (active) accent else surfaceColor)
                btn.setTextColor(if (active) bg else ink)
            }
            val requestedBrightness = svc.ble.brightnessLevel
            val brightnessFromBle = svc.b02ncBrightnessCandidate
            brightnessLabel.text = when {
                requestedBrightness != null && brightnessFromBle == requestedBrightness -> "Brillo $requestedBrightness / 5 · BLE coincide"
                requestedBrightness != null -> "Brillo solicitado: $requestedBrightness / 5"
                brightnessFromBle != null -> "Brillo BLE: $brightnessFromBle / 5 (provisional)"
                else -> "Brillo · elige un nivel"
            }
            brightnessButtons.forEachIndexed { index, btn ->
                val active = connected && (requestedBrightness ?: brightnessFromBle) == index + 1
                btn.background = ripple(if (active) accent else surfaceColor)
                btn.setTextColor(if (active) bg else ink)
            }
            bikeMetrics.text = buildString {
                append("Batería "); append(svc.b02ncBatteryCandidate?.let { "$it % (provisional)" } ?: "sin lectura")
                append("\nTRIP: "); append(svc.b02ncTripKm?.let { String.format(Locale.US, "%.2f km", it) } ?: "—")
                append("\nODO: "); append(svc.b02ncOdometerKm?.let { String.format(Locale.US, "%.2f km", it) } ?: "—")
                append("\nAVG: "); append(svc.b02ncAvgCalculatedKmh?.let { String.format(Locale.US, "%.2f km/h", it) } ?: "—")
                append("\nRecorrido GPS: "); append(String.format(Locale.US, "%.2f km", svc.trackMeters / 1000))
            }
            val loc = svc.gps
            if (loc != null) {
                val here = GeoPoint(loc.latitude, loc.longitude)
                positionMarker.position = here
                if (!mapCentered) { map.controller.setCenter(here); mapCentered = true }
            }
            val r = svc.route
            if (r !== oldRoute) {
                oldRoute = r
                routeLine.setPoints(r?.line ?: emptyList())
            }
            traceLine.setPoints(svc.track.takeLast(2000).map { GeoPoint(it.latitude, it.longitude) })
            if (pageIndex == 1) map.invalidate()
        }
    }
    private fun updateLightButton(requestedOn: Boolean?, connected: Boolean) {
        lightToggle.text = when {
            !connected -> "◉  Luces · desconectadas"
            requestedOn == true -> "◉  Apagar luces"
            else -> "◉  Encender luces"
        }
        lightToggle.background = ripple(if (connected && requestedOn != true) accent else surfaceColor)
        lightToggle.setTextColor(if (connected && requestedOn != true) bg else ink)
    }

    private fun showWalkHelp() {
        AlertDialog.Builder(this)
            .setTitle("Ayuda a la marcha · B02NC")
            .setMessage("Mantén pulsado el botón − del mando físico para la asistencia a pie (según manual EKD01); suéltalo para detener.\n\nLa orden Bluetooth exclusiva todavía no está confirmada para B02NC_SMK_BF_N06. Por seguridad no enviamos órdenes de motor desconocidas.")
            .setPositiveButton("Entendido", null).show()
    }
    private fun saveGpx() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/gpx+xml"
            putExtra(Intent.EXTRA_TITLE, "bafang-${System.currentTimeMillis()}.gpx")
        }
        startActivityForResult(intent, saveGpxRequest)
    }
    @Deprecated("Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == saveGpxRequest && resultCode == RESULT_OK) {
            try {
                val uri = data?.data ?: return
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(service?.exportGpx() ?: "") }
                toast("GPX guardado")
            } catch (e: Exception) { toast("Error al guardar GPX: ${e.message}") }
        }
    }
    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logLines.joinToString("\n"))
            putExtra(Intent.EXTRA_SUBJECT, "Bafang Companion BLE")
        }
        startActivity(Intent.createChooser(i, "Compartir registro"))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("active_tab", pageIndex)
        chosenPoint?.let { outState.putDouble("destination_lat", it.latitude); outState.putDouble("destination_lon", it.longitude) }
        outState.putString("destination_name", chosenName)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (initialized) map.onResume()
        if (service == null && initialized) connectUi() else service?.observer = this
        if (::updater.isInitialized) { updater.onResume(); updater.checkOnOpen() }
    }
    override fun onPause() { if (initialized) map.onPause(); super.onPause() }
    override fun onDestroy() {
        if (service?.observer === this) service?.observer = null
        suggestionTask?.let(ui::removeCallbacks)
        addressRevision++
        addressWorker.shutdownNow()
        super.onDestroy()
    }
}