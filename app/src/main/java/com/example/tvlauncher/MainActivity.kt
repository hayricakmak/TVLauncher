package com.example.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AppInfo(val label: String, val packageName: String, val icon: ImageBitmap)

// ---------- Durum ve kalıcı ayarlar ----------
class LauncherState(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private fun readList(k: String) = (sp.getString(k, "") ?: "").split(",").filter { it.isNotEmpty() }
    private fun writeList(k: String, v: List<String>) = sp.edit().putString(k, v.joinToString(",")).apply()

    var apps by mutableStateOf<List<AppInfo>>(emptyList())
    var iconSize by mutableStateOf(sp.getInt("iconSize", 0))
    var sort by mutableStateOf(sp.getInt("sort", 0))
    var wallpaper by mutableStateOf(sp.getInt("wallpaper", 0))
    var customBg by mutableStateOf<ImageBitmap?>(null)
    var favorites by mutableStateOf(readList("fav"))
    var recents by mutableStateOf(readList("rec"))
    var hidden by mutableStateOf(readList("hid"))
    var counts by mutableStateOf<Map<String, Int>>(
        sp.all.filterKeys { it.startsWith("c_") }
            .mapKeys { it.key.removePrefix("c_") }
            .mapValues { (it.value as? Int) ?: 0 }
    )
    var menuApp by mutableStateOf<AppInfo?>(null)
    var showSettings by mutableStateOf(false)

    fun setIconSize(v: Int) { iconSize = v; sp.edit().putInt("iconSize", v).apply() }
    fun setSort(v: Int) { sort = v; sp.edit().putInt("sort", v).apply() }
    fun setWallpaper(v: Int) { wallpaper = v; sp.edit().putInt("wallpaper", v).apply() }

    fun launched(pkg: String) {
        recents = (listOf(pkg) + recents.filter { it != pkg }).take(12)
        writeList("rec", recents)
        val n = (counts[pkg] ?: 0) + 1
        counts = counts + (pkg to n)
        sp.edit().putInt("c_$pkg", n).apply()
    }

    fun toggleFav(pkg: String) {
        favorites = if (pkg in favorites) favorites - pkg else favorites + pkg
        writeList("fav", favorites)
    }

    fun toggleHidden(pkg: String) {
        hidden = if (pkg in hidden) hidden - pkg else hidden + pkg
        writeList("hid", hidden)
    }

    fun unhideAll() { hidden = emptyList(); writeList("hid", hidden) }

    fun visible(): List<AppInfo> {
        val v = apps.filter { it.packageName !in hidden }
        return when (sort) {
            1 -> v.sortedByDescending { it.label.lowercase() }
            2 -> v.sortedWith(
                compareByDescending<AppInfo> { counts[it.packageName] ?: 0 }.thenBy { it.label.lowercase() }
            )
            else -> v.sortedBy { it.label.lowercase() }
        }
    }

    fun loadCustom() {
        val u = sp.getString("wpUri", null) ?: return
        Thread {
            val b = decodeBg(ctx, u)
            Handler(Looper.getMainLooper()).post { customBg = b }
        }.start()
    }

    fun setCustom(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        sp.edit().putString("wpUri", uri.toString()).apply()
        setWallpaper(5)
        loadCustom()
    }
}

fun decodeBg(ctx: Context, uriStr: String): ImageBitmap? = try {
    val uri = Uri.parse(uriStr)
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / s > 1920) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }?.asImageBitmap()
} catch (e: Exception) { null }

fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(normal, 0))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            val d = it.activityInfo.loadBanner(pm) ?: it.loadIcon(pm)
            AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, d.toBitmap(320, 180).asImageBitmap())
        }
        .sortedBy { it.label.lowercase() }
}

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    private val state by lazy { LauncherState(applicationContext) }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.setCustom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state.loadCustom()
        onBackPressedDispatcher.addCallback(this) {
            if (state.showSettings) state.showSettings = false
        }
        setContent {
            LauncherRoot(
                state,
                onLaunch = { launchApp(it) },
                pickImage = { try { picker.launch(arrayOf("image/*")) } catch (e: Exception) { } }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val list = loadApps(this)
            runOnUiThread { state.apps = list }
        }.start()
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            state.launched(pkg)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}

// ---------- Arayüz ----------
val presets = listOf(
    Color(0xFF1B2540) to Color(0xFF0A0D18),
    Color(0xFF3B1D5E) to Color(0xFF120A22),
    Color(0xFF0F3D3A) to Color(0xFF06161A),
    Color(0xFF5E2A3B) to Color(0xFF1A0E1F),
    Color(0xFF151515) to Color(0xFF000000)
)

@Composable
fun LauncherRoot(st: LauncherState, onLaunch: (String) -> Unit, pickImage: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val bg = st.customBg
        if (st.wallpaper == 5 && bg != null) {
            Image(bitmap = bg, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color(0x77000000)))
        } else {
            val c = presets[st.wallpaper.coerceIn(0, 4)]
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.first, c.second))))
        }
        if (st.showSettings) SettingsScreen(st, pickImage) else HomeScreen(st, onLaunch)
        st.menuApp?.let { MenuDialog(st, it, onLaunch) }
    }
}

@Composable
fun HomeScreen(st: LauncherState, onLaunch: (String) -> Unit) {
    val w = listOf(110, 140, 180)[st.iconSize.coerceIn(0, 2)].dp
    val perRow = listOf(6, 5, 4)[st.iconSize.coerceIn(0, 2)]
    val all = st.visible()
    val recents = st.recents.mapNotNull { p -> all.find { it.packageName == p } }
    val favs = st.favorites.mapNotNull { p -> all.find { it.packageName == p } }

    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TopBar(st) }
        if (recents.isNotEmpty()) {
            item { SectionTitle("Son Kullanılanlar") }
            item { AppRow(recents, w, st, onLaunch) }
        }
        if (favs.isNotEmpty()) {
            item { SectionTitle("Favoriler") }
            item { AppRow(favs, w, st, onLaunch) }
        }
        item { SectionTitle("Tüm Uygulamalar") }
        items(all.chunked(perRow)) { row -> AppRow(row, w, st, onLaunch) }
    }
}

@Composable
fun TopBar(st: LauncherState) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(15_000) } }
    val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    val date = now.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("tr")))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(time, style = TextStyle(Color.White, 36.sp, FontWeight.Light))
        Spacer(Modifier.width(16.dp))
        BasicText(date, style = TextStyle(Color(0xB3FFFFFF), 16.sp))
        Spacer(Modifier.weight(1f))
        Chip("Ayarlar", false, { st.showSettings = true })
    }
}

@Composable
fun SectionTitle(text: String) {
    BasicText(
        text,
        style = TextStyle(Color(0xCCFFFFFF), 18.sp, FontWeight.SemiBold),
        modifier = Modifier.padding(start = 48.dp, top = 12.dp, bottom = 0.dp)
    )
}

@Composable
fun AppRow(list: List<AppInfo>, w: androidx.compose.ui.unit.Dp, st: LauncherState, onLaunch: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(list, key = { it.packageName }) { app ->
            AppCard(app, w, { onLaunch(app.packageName) }, { st.menuApp = app })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCard(app: AppInfo, w: androidx.compose.ui.unit.Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val longFired = remember { booleanArrayOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "scale")
    Column(Modifier.width(w), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(w).height(w * 9 / 16)
                .scale(scale)
                .shadow(if (focused) 20.dp else 0.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .border(BorderStroke(if (focused) 3.dp else 0.dp, Color.White), RoundedCornerShape(14.dp))
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    val center = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    if (center && e.type == KeyEventType.KeyDown) {
                        if (k.repeatCount == 0) longFired[0] = false
                        else if (k.repeatCount == 1) { longFired[0] = true; onLongClick() }
                    }
                    false
                }
                .combinedClickable(
                    onClick = { if (longFired[0]) longFired[0] = false else onClick() },
                    onLongClick = onLongClick
                )
        )
        Spacer(Modifier.height(10.dp))
        BasicText(
            app.label,
            maxLines = 1,
            style = TextStyle(Color.White, 13.sp, FontWeight.Medium),
            modifier = Modifier.alpha(if (focused) 1f else 0.55f)
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (f) Color.White else if (selected) Color(0xFF3A5BFF) else Color(0x33FFFFFF))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        BasicText(text, style = TextStyle(if (f) Color.Black else Color.White, 15.sp))
    }
}

@Composable
fun MenuDialog(st: LauncherState, app: AppInfo, onLaunch: (String) -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val pkg = app.packageName
    fun close() { st.menuApp = null }
    fun go(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { ctx.startActivity(i) } }
    Dialog(onDismissRequest = { close() }) {
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(20.dp))
                .background(Color(0xF01E2233)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
            BasicText(app.label, maxLines = 1, style = TextStyle(Color.White, 20.sp, FontWeight.SemiBold))
            Chip("Aç", false, { close(); onLaunch(pkg) }, Modifier.fillMaxWidth().focusRequester(fr))
            Chip(if (pkg in st.favorites) "Favorilerden çıkar" else "Favorilere ekle", false,
                { st.toggleFav(pkg); close() }, Modifier.fillMaxWidth())
            Chip("Gizle", false, { st.toggleHidden(pkg); close() }, Modifier.fillMaxWidth())
            Chip("Uygulama bilgisi", false, {
                close()
                go(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }, Modifier.fillMaxWidth())
            Chip("Kaldır", false, {
                close()
                go(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun SettingRow(title: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(title, style = TextStyle(Color(0xCCFFFFFF), 16.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SettingsScreen(st: LauncherState, pickImage: () -> Unit) {
    val fr = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
        BasicText("Launcher Ayarları", style = TextStyle(Color.White, 30.sp, FontWeight.Light))

        SettingRow("Simge boyutu") {
            listOf("Küçük", "Orta", "Büyük").forEachIndexed { i, n ->
                Chip(n, st.iconSize == i, { st.setIconSize(i) }, if (i == 0) Modifier.focusRequester(fr) else Modifier)
            }
        }
        SettingRow("Uygulama sıralaması") {
            listOf("A-Z", "Z-A", "En çok kullanılan").forEachIndexed { i, n ->
                Chip(n, st.sort == i, { st.setSort(i) })
            }
        }
        SettingRow("Duvar kağıdı") {
            listOf("Mavi", "Mor", "Yeşil", "Kızıl", "Siyah").forEachIndexed { i, n ->
                Chip(n, st.wallpaper == i, { st.setWallpaper(i) })
            }
            Chip("Özel resim seç", st.wallpaper == 5, { pickImage() })
        }
        SettingRow("Gizli uygulamalar") {
            Chip("Hepsini göster (${st.hidden.size})", false, { st.unhideAll() })
        }
        SettingRow("Favoriler ve gizleme") {
            BasicText(
                "Bir uygulamanın üzerinde OK tuşuna basılı tutun: favori, gizle, bilgi, kaldır menüsü açılır.",
                style = TextStyle(Color(0x99FFFFFF), 14.sp)
            )
        }
        Chip("Kapat", false, { st.showSettings = false })
    }
}
