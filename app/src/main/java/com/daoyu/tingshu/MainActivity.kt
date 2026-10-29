package com.daoyu.tingshu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.daoyu.tingshu.data.BookImporter
import com.daoyu.tingshu.reader.ReaderScreen
import com.daoyu.tingshu.settings.SettingsScreen
import com.daoyu.tingshu.shelf.ShelfScreen
import com.daoyu.tingshu.transfer.TransferScreen
import com.daoyu.tingshu.ui.DaoyuTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Volume keys → page turns while the reader is visible. */
object KeyEvents {
    @Volatile var interceptVolume = false
    val volume = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)   // true = forward
}

class MainActivity : ComponentActivity() {
    private val openBook = MutableSharedFlow<Long>(extraBufferCapacity = 2)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            DaoyuTheme {
                val nav = rememberNavController()
                LaunchedEffect(Unit) { openBook.collect { id -> openReader(nav, id) } }
                NavHost(
                    nav, startDestination = "shelf",
                    enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
                    exitTransition = { fadeOut() },
                    popEnterTransition = { fadeIn() },
                    popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
                ) {
                    composable("shelf") {
                        ShelfScreen(
                            onOpen = { openReader(nav, it) },
                            onTransfer = { nav.navigate("transfer") },
                            onSearch = { nav.navigate("search") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("reader/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                        ReaderScreen(
                            bookId = e.arguments!!.getLong("id"),
                            onBack = { nav.popBackStack() },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenCast = { nav.navigate("cast/${e.arguments!!.getLong("id")}") },
                        )
                    }
                    composable("cast/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                        com.daoyu.tingshu.drama.CastScreen(bookId = e.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
                    }
                    composable("transfer") { TransferScreen(onBack = { nav.popBackStack() }) }
                    composable("search") {
                        com.daoyu.tingshu.source.SearchScreen(onBack = { nav.popBackStack() }, onSources = { nav.navigate("sources") }, onOpenBook = { openReader(nav, it) })
                    }
                    composable("sources") {
                        com.daoyu.tingshu.source.SourcesScreen(onBack = { nav.popBackStack() }, onEdit = { url -> nav.navigate("sourceEdit?url=" + android.net.Uri.encode(url ?: "")) })
                    }
                    composable("sourceEdit?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })) { e ->
                        com.daoyu.tingshu.source.SourceEditScreen(url = e.arguments?.getString("url").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
        handleIntent(intent)
    }

    private fun openReader(nav: NavHostController, id: Long) {
        if (id < 0) return
        val route = "reader/$id"
        if (nav.currentBackStackEntry?.destination?.route == "reader/{id}" &&
            nav.currentBackStackEntry?.arguments?.getLong("id") == id) return
        nav.navigate(route) { popUpTo("shelf") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra("openListening", false)) {
            intent.removeExtra("openListening")
            openBook.tryEmit(App.instance.tts.state.value.bookId)
            return
        }
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        intent.action = null
        lifecycleScope.launch {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book.txt"
            val res = try {
                contentResolver.openInputStream(uri)?.let { App.instance.importer.import(name, it) } ?: BookImporter.Result.Fail("无法读取文件")
            } catch (e: Exception) { BookImporter.Result.Fail(e.message ?: "导入失败") }
            when (res) {
                is BookImporter.Result.Ok -> openBook.tryEmit(res.book.id)
                is BookImporter.Result.Fail -> Toast.makeText(this@MainActivity, res.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyEvents.interceptVolume && (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            // while listening, volume keys keep their normal job
            if (App.instance.tts.state.value.playing) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) KeyEvents.volume.tryEmit(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
