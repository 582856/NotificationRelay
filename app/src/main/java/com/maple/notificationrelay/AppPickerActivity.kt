package com.maple.notificationrelay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import java.util.concurrent.Executors

class AppPickerActivity : ComponentActivity() {
    private data class AppItem(val app: RelayAppStore.App, val icon: Bitmap?)
    private val worker = Executors.newFixedThreadPool(3)
    private val results = mutableStateListOf<AppItem>()
    private val savedItems = mutableStateListOf<AppItem>()
    private var query by mutableStateOf("")
    private var status by mutableStateOf("在线搜索")
    private var showSearch by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    @Volatile private var generation = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        query = state?.getString("query").orEmpty()
        loadSaved()
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val sheetHeight = maxHeight * 0.75f
                    val sheetWidth = maxWidth
                    LaunchedEffect(Unit) { showSearch = true }
                    WindowBottomSheet(
                        show = showSearch,
                        title = "添加应用",
                        modifier = Modifier.height(sheetHeight),
                        sheetMaxWidth = sheetWidth,
                        outsideMargin = DpSize(0.dp, 0.dp),
                        insideMargin = DpSize(20.dp, 12.dp),
                        allowDismiss = !saving,
                        onDismissRequest = { closeSearch() },
                        onDismissFinished = { finish() },
                        endAction = {
                            IconButton(onClick = { closeSearch() }, enabled = !saving) {
                                Text("×", fontSize = 26.sp)
                            }
                        }
                    ) { SearchContent() }
                    BackHandler(enabled = !showSearch) { if (!saving) finish() }
                }
            }
        }
    }

    @Composable private fun SearchContent() {
        val keyboard = LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InputField(query = query, onQueryChange = { query = it.take(100) },
                    onSearch = { keyboard?.hide(); search() }, expanded = false,
                    onExpandedChange = {}, label = "输入应用名称", enabled = !saving,
                    modifier = Modifier.weight(1f))
                Button(onClick = { keyboard?.hide(); search() }, enabled = !saving,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("搜索") }
            }
            Text(status, fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.65f))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)) {
                items(results, key = { it.app.packageName }) { item -> AppRow(item) }
            }
        }
    }

    @Composable private fun AppRow(item: AppItem) {
        val added = savedItems.any { it.app.packageName == item.app.packageName }
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = item.app.label, summary = item.app.packageName,
                enabled = !saving, onClick = if (added) null else ({ save(item) }),
                startAction = {
                    if (item.icon != null) Image(item.icon.asImageBitmap(), item.app.label,
                        modifier = Modifier.padding(end = 12.dp).size(44.dp).clip(RoundedCornerShape(10.dp)))
                    else Box(Modifier.padding(end = 12.dp).size(44.dp)
                        .clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.background),
                        contentAlignment = Alignment.Center) { Text(item.app.label.take(1)) }
                }, endActions = {
                    Button(onClick = { save(item) }, enabled = !saving && !added,
                        colors = if (added) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary()) {
                        Text(if (added) "已添加" else "选择", fontSize = 13.sp)
                    }
                })
        }
    }

    private fun loadSaved() {
        try {
            RelayAppStore.seed(this)
            savedItems.clear()
            savedItems.addAll(RelayAppStore.read(this).values.map { app ->
                AppItem(app, BitmapFactory.decodeFile(RelayAppStore.iconFile(this, app.packageName).absolutePath))
            })
        } catch (_: Exception) { status = "读取已添加应用失败" }
    }

    private fun closeSearch() {
        if (saving) return
        generation++
        showSearch = false
    }

    private fun search() {
        val text = query.trim()
        if (text.isEmpty() || saving) return
        val request = ++generation
        results.clear()
        status = "正在搜索…"
        worker.execute {
            try {
                val apps = AppLibrary.search(text)
                runOnUiThread {
                    if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                    results.addAll(apps.map { AppItem(it, null) })
                    status = if (apps.isEmpty()) "未找到应用" else "找到 ${apps.size} 个应用 · 点击选择"
                    apps.forEach { app -> worker.execute {
                        if (request != generation) return@execute
                        try {
                            val icon = AppLibrary.icon(app.iconUrl)
                            runOnUiThread {
                                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                                val index = results.indexOfFirst { it.app.packageName == app.packageName }
                                if (index >= 0) results[index] = AppItem(app, icon)
                            }
                        } catch (_: Exception) { }
                    } }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (request == generation && !isFinishing) status = "搜索失败，请检查网络后重试"
                }
            }
        }
    }

    private fun save(item: AppItem) {
        if (saving) return
        saving = true
        status = "正在保存应用和图标…"
        worker.execute {
            try {
                val icon = item.icon ?: AppLibrary.icon(item.app.iconUrl)
                RelayAppStore.save(this, item.app, icon)
                runOnUiThread {
                    saving = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    loadSaved()
                    closeSearch()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    saving = false
                    if (!isFinishing) status = "保存失败，请检查网络后重试"
                }
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("query", query)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }
}
