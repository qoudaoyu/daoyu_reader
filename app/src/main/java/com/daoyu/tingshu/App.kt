package com.daoyu.tingshu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.daoyu.tingshu.data.AppDatabase
import com.daoyu.tingshu.data.BookImporter
import com.daoyu.tingshu.data.BookStorage
import com.daoyu.tingshu.data.SecureKeys
import com.daoyu.tingshu.data.SettingsStore
import com.daoyu.tingshu.source.SourceDownloads
import com.daoyu.tingshu.source.SourceRepo
import com.daoyu.tingshu.tts.TtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var db: AppDatabase; private set
    lateinit var storage: BookStorage; private set
    lateinit var importer: BookImporter; private set
    lateinit var settings: SettingsStore; private set
    lateinit var keys: SecureKeys; private set
    lateinit var tts: TtsController; private set
    val sources by lazy { SourceRepo(this) }
    val sourceDownloads by lazy { SourceDownloads(appScope, importer, storage) }
    val director by lazy { com.daoyu.tingshu.drama.DramaDirector() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.create(this)
        storage = BookStorage(this)
        importer = BookImporter(this, db, storage)
        settings = SettingsStore(this, appScope)
        keys = SecureKeys(this)
        tts = TtsController(this)
        appScope.launch(Dispatchers.IO) { db.books().deleteUnfinished() }
        // 1.0.3 shipped "Elias" (a female voice) as the default narrator labelled male; move users to Vincent once
        appScope.launch(Dispatchers.IO) {
            if (!settings.tts.value.directorChecked) settings.updateTts {
                if (it.directorModel == "qwen-plus") it.copy(directorModel = "qwen3-max", directorInPrice = 2.5f, directorOutPrice = 10f, directorChecked = true)
                else it.copy(directorChecked = true)
            }
            if (!settings.tts.value.qwenVoiceChecked) settings.updateTts {
                it.copy(qwenVoice = if (it.qwenVoice == "Elias") "Vincent" else it.qwenVoice, qwenVoiceChecked = true)
            }
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "听书播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "听书时在通知栏和锁屏显示播放控制"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        lateinit var instance: App; private set
    }
}
