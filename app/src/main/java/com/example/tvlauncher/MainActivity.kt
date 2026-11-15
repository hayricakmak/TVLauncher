package com.example.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AppInfo(val label: String, val packageName: String, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {
    private var apps by mutableStateOf<List<AppInfo>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launcher'da geri tuşu hiçbir şey yapmasın
        onBackPressedDispatcher.addCallback(this) { }
        setContent { LauncherScreen(apps) { launchApp(it) } }
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val list = loadApps(this)
            runOnUiThread { apps = list }
        }.start()
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { startActivity(it) }
    }
}

fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return (pm.queryIntentActivities(leanback, 0) + pm.queryIntentActivities(normal, 0))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            val d: Drawable = it.activityInfo.loadBanner(pm) ?: it.loadIcon(pm)
            AppInfo(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName,
                icon = d.toBitmap(448, 252).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun LauncherScreen(apps: List<AppInfo>, onLaunch: (String) -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(15_000) }
    }
    val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    val date = now.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("tr")))

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1B2540), Color(0xFF0A0D18)))
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 32.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                BasicText(time, style = TextStyle(Color.White, 44.sp, FontWeight.Light))
                Spacer(Modifier.width(20.dp))
                BasicText(date, style = TextStyle(Color(0xB3FFFFFF), 20.sp))
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 24.dp)) {
                items(apps.chunked(5)) { row ->
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(row, key = { it.packageName }) { app ->
                            AppCard(app) { onLaunch(app.packageName) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppCard(app: AppInfo, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "scale")
    Column(Modifier.width(224.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(224.dp).height(126.dp)
                .scale(scale)
                .shadow(if (focused) 24.dp else 0.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .border(
                    BorderStroke(if (focused) 3.dp else 0.dp, Color.White),
                    RoundedCornerShape(18.dp)
                )
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick)
        )
        Spacer(Modifier.height(14.dp))
        BasicText(
            app.label,
            maxLines = 1,
            style = TextStyle(Color.White, 16.sp, FontWeight.Medium),
            modifier = Modifier.alpha(if (focused) 1f else 0.55f)
        )
    }
}
