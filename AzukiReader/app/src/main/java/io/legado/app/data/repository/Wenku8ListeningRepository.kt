package io.legado.app.data.repository

import android.app.Application
import android.media.MediaPlayer
import io.legado.app.domain.gateway.*
import io.legado.app.domain.model.readaloud.*
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.help.readaloud.playback.CloudTtsAudioSynthesizer
import io.legado.app.help.readaloud.playback.validateWenku8SsmlConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.readaloud.ReadAloudBookSession
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerCoordinator
import io.legado.app.utils.GSON
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Android compatibility owner: book/session/service work stays outside Compose and its VM. */
class Wenku8ListeningRepository(
    private val app: Application,
    private val engines: CloudTtsEngineGateway,
    private val voices: ReadAloudVoiceGateway,
    private val settings: ReadAloudSettingsGateway,
    private val sessions: ReadAloudSessionStore,
    private val coordinator: ReadAloudPlayerCoordinator,
) : Wenku8ListeningGateway {
    private val initialization = Mutex()
    private val commands = Mutex()
    private val previewCommands = Mutex()
    private var ready = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val synthesizer = CloudTtsAudioSynthesizer(engines)
    private var preview: MediaPlayer? = null
    private var previewContinuation: CancellableContinuation<Unit>? = null
    private var lastSaved = 0L
    private var segmentsContent: String? = null
    private var segmentsCache: List<ReadAloudSplitUnit> = emptyList()
    @Synchronized private fun segments(content: String): List<ReadAloudSplitUnit> {
        if (segmentsContent != content) {
            segmentsContent = content
            segmentsCache = wenku8SpeechSegments(content)
        }
        return segmentsCache
    }

    override suspend fun initialize() = initialization.withLock {
        if (ready) return@withLock
        if (voices.getVoices().none { it.engineType == ReadAloudVoice.ENGINE_SYSTEM }) {
            voices.upsertVoice(ReadAloudVoice("wenku8-system", ReadAloudVoice.ENGINE_SYSTEM, "", "", "系统离线"))
        }
        if (engines.get(EDGE) == null) engines.upsert(CloudTtsEngine(EDGE, "自然在线", CloudTtsProviderType.Wenku8Edge))
        for ((id, label) in listOf("zh-CN-XiaoxiaoNeural" to "晓晓 · 中文女声", "zh-CN-YunxiNeural" to "云希 · 中文男声")) {
            if (voices.getVoice("$EDGE:$id") == null) voices.upsertVoice(edgeVoice(id,label,"zh-CN"))
        }
        settings.update { current ->
            if (current.wenku8Initialized) current else current.copy(
                wenku8Initialized = true,
                ttsEngine = current.ttsEngine ?: GSON.toJson(ReadAloudEngineSelection(ReadAloudVoice.ENGINE_CLOUD, EDGE, "zh-CN-XiaoxiaoNeural", "晓晓 · 中文女声")),
                keepReadAloudOnExit = if (current.ttsEngine == null) true else current.keepReadAloudOnExit,
                contentSplitMode = if (current.ttsEngine == null) "paragraph" else current.contentSplitMode,
                readAloudByPage = if (current.ttsEngine == null) false else current.readAloudByPage,
                ttsParagraphInterval = if (current.ttsEngine == null) 0 else current.ttsParagraphInterval,
                useMultiSpeaker = if (current.ttsEngine == null) false else current.useMultiSpeaker,
                ttsFollowSys = if (current.ttsEngine == null) false else current.ttsFollowSys,
                mediaButtonPerNext = if (current.ttsEngine == null) true else current.mediaButtonPerNext,
            )
        }
        ready = true
        scope.launch {
            if (ReadAloud.ttsEngine?.contains(EDGE) == true) runCatching { refreshVoices() }
        }
        scope.launch {
            combine(coordinator.state, sessions.state) { source, session -> source to session }.collect { (source, session) ->
                if (ReadAloud.isWenku8Online && source.bookUrl.isNotBlank() && session.status != ReadAloudSessionStatus.Idle &&
                    !session.preparing && (session.status == ReadAloudSessionStatus.Paused || System.currentTimeMillis()-lastSaved >= 5000)) {
                    lastSaved = System.currentTimeMillis()
                    persistCursor(source, session)
                }
            }
        }
        Unit
    }

    override fun observe(reader: Boolean): Flow<Wenku8ListeningState> = combine(
        coordinator.state, sessions.state, settings.settings, ReadBook.snapshot,
    ) { source, session, prefs, _ ->
        // 朗读设置页直接订阅这个 state：任何一处字段异常都不该让整页崩掉，
        // 这里兜底返回一份「未开始听书」的空状态。
        runCatching {
            val requested = if (reader) ReadBook.book else ReadAloud.bookSession.book ?: ReadBook.book
            val own = source.bookUrl == requested?.bookUrl && session.status != ReadAloudSessionStatus.Idle
            val selection = runCatching { GSON.fromJson(ReadAloud.ttsEngine, ReadAloudEngineSelection::class.java) }.getOrNull()
            val selected = source.voices.firstOrNull { it.isSelected }
            val selectedSource = when {
                selection?.engineId == EDGE -> "edge"
                selection?.engineId == CUSTOM -> "custom"
                selected?.engineType == ReadAloudVoice.ENGINE_SYSTEM || prefs.ttsEngine.isNullOrBlank() -> "system"
                else -> "legacy"
            }
            val units = if (ReadAloud.isWenku8Online) segments(source.chapterText) else source.textLines.map { ReadAloudSplitUnit(it.text,it.chapterPosition) }
            val activeIndex = units.indexOfLast { it.chapterPosition <= source.chapterPosition }.coerceAtLeast(0)
            val cursor = savedCursor(requested?.bookUrl.orEmpty())
            Wenku8ListeningState(
                bookName = requested?.name.orEmpty(), bookUrl = requested?.bookUrl.orEmpty(),
                chapterIndex = if (own) source.chapterIndex else ReadBook.durChapterIndex,
                chapterTitle = if (own) source.chapterTitle else ReadBook.readerChapterInputWindow.current?.displayTitle.orEmpty(),
                currentText = if (own) source.playbackText.ifBlank { units.getOrNull(activeIndex)?.text.orEmpty() } else "",
                segmentIndex = if (own) activeIndex else 0, segmentCount = if (own) units.size else 0,
                fraction = if (own) source.chapterPosition.toFloat()/source.chapterLength.coerceAtLeast(1) else 0f,
                active = own, paused = session.status != ReadAloudSessionStatus.Playing,
                preparing = own && session.preparing, otherBookPlaying = !own && session.status != ReadAloudSessionStatus.Idle,
                clipPositionMs = if (own) session.clipPositionMs else 0, clipDurationMs = if (own) session.clipDurationMs else 0,
                error = if (own) session.error else null, skippedSegments = if (own) session.skippedSegments else 0,
                source = selectedSource, voiceLabel = selected?.name ?: selection?.displayName ?: "系统离线",
                speed = if (selectedSource in setOf("edge","custom")) prefs.wenku8Speed else (prefs.ttsSpeechRate+5)/10f,
                follow = session.followReadAloudPosition, timerMinutes = session.timerMinutes,
                sleepAfterChapter = session.timerChapters > 0,
                voices = source.voices.map { ListeningVoice(it.id.orEmpty(),it.name.orEmpty(),it.engineName.orEmpty(),it.isSelected) }.toImmutableList(),
                resumeChapter = cursor?.optInt("chapter")?.takeIf { it >= 0 },
            )
        }.getOrElse { Wenku8ListeningState() }
    }.combine(engines.observeAll()) { state, all ->
        val custom = all.firstOrNull { it.id == CUSTOM }
        runCatching {
            state.copy(custom = custom?.let {
                ListeningCustomConfig(
                    it.baseUrl.orEmpty(), it.model.orEmpty(), it.appId.orEmpty().ifBlank { "zh-CN" },
                    it.name.orEmpty(), it.region.orEmpty(), it.apiKey.orEmpty().isNotEmpty(),
                )
            })
        }.getOrElse { state }
    }.distinctUntilChanged()

    override suspend fun togglePause(reader: Boolean) = commands.withLock {
        initialize()
        if (reader && !ReadAloud.bookSession.owns(ReadBook.book?.bookUrl)) startFromReader(false)
        else coordinator.togglePause()
    }
    override suspend fun stop() {
        if (ReadAloud.isWenku8Online) persistCursor(coordinator.snapshot(), sessions.state.value)
        coordinator.stop()
    }
    override suspend fun moveParagraph(direction: Int) { if (direction < 0) coordinator.previousParagraph() else coordinator.nextParagraph() }
    override suspend fun skipSegment() { sessions.recordSkippedSegment(); coordinator.nextParagraph() }
    override suspend fun seek(fraction: Float) { val snapshot = coordinator.snapshot(); coordinator.seekTo((snapshot.chapterLength*fraction.coerceIn(0f,1f)).roundToInt(),snapshot.chapterLength) }

    override suspend fun startFromReader(resume: Boolean) {
        initialize()
        val capture = ReadAloudBookSession.captureReader() ?: return
        val cursor = if (resume) savedCursor(capture.book?.bookUrl.orEmpty()) else null
        val chapter = cursor?.optInt("chapter",capture.chapterIndex) ?: capture.chapterIndex
        val position = cursor?.optInt("start",capture.chapterPosition) ?: ReadBook.durChapterPos
        val snapshot = if (chapter != capture.chapterIndex) capture.copy(chapterIndex=chapter,input=null,pagination=null,chapterPosition=position) else capture
        val segment = capture.input?.source?.semanticContent?.let(::wenku8SpeechSegments)?.firstOrNull { it.chapterPosition == position }
        val exact = cursor != null && cursor.optString("engine") == ReadAloud.ttsEngine && (chapter != capture.chapterIndex || cursor.optString("hash") == segment?.text?.let(::hash))
        ReadAloud.playRequest(app,snapshot,chapterPosition=position,clipPositionMs=if(exact) cursor?.optLong("clipMs",0) else null, clipTextHash=if(exact) cursor?.optString("hash") else null)
    }
    override suspend fun setSpeed(speed: Float) {
        require(speed.isFinite())
        val value = speed.coerceIn(.5f,3f)
        settings.update { it.copy(wenku8Speed=value,ttsFollowSys=false,ttsSpeechRate=(value*10-5).roundToInt()) }
        ReadAloud.upTtsSpeechRate(app)
    }
    override suspend fun setFollow(follow: Boolean) {
        if (follow) {
            sessions.restoreReadAloudFollow()
            val source=coordinator.snapshot()
            if (ReadBook.book?.bookUrl == source.bookUrl && source.chapterIndex >= 0) {
                ReadBook.syncReadAloudPage(source.chapterIndex, source.chapterPosition)
            }
        } else sessions.detachReadAloudFollow()
    }
    override suspend fun setSleep(minutes: Int, afterChapter: Boolean) {
        settings.update { it.copy(ttsTimer=minutes,timerChapters=if(afterChapter)1 else 0,
            timerMode=if(afterChapter) ReadAloudTimerMode.Chapter.storageValue else ReadAloudTimerMode.Minute.storageValue,
            finishCurrentChapterAfterTimer=false) }
        ReadAloud.setTimer(app,minutes)
        ReadAloud.setTimerChapters(app,if(afterChapter)1 else 0)
    }
    override suspend fun selectVoice(id: String) {
        initialize()
        val actualId = when(id) {
            "source:edge" -> "$EDGE:zh-CN-XiaoxiaoNeural"
            "source:custom" -> voices.getVoices().firstOrNull { it.engineId == CUSTOM }?.id ?: error("请先配置自定义语音接口")
            "source:system" -> voices.getVoices().firstOrNull { it.engineType == ReadAloudVoice.ENGINE_SYSTEM && it.enabled && it.available }?.id ?: error("请先在离线引擎设置中选择系统 TTS")
            else -> id
        }
        val voice = voices.getVoice(actualId) ?: error("音色已不再可用，请刷新列表")
        require(voice.enabled && voice.available && !voice.excluded) { "音色已停用或不可用，请重新选择" }
        settings.update { it.copy(ttsFollowSys = false, useMultiSpeaker = false) }
        if (voice.engineId in setOf(EDGE,CUSTOM)) settings.update { it.copy(useMultiSpeaker=false,bgmAssign=false,multiRoleCast=false,
            contentSplitMode="paragraph",readAloudByPage=false,ttsParagraphInterval=0,ttsFollowSys=false,mediaButtonPerNext=true,keepReadAloudOnExit=true,ignoreAudioFocus=false) }
        coordinator.selectVoice(actualId)
    }
    override suspend fun refreshVoices() {
        initialize()
        for (voice in synthesizer.fetchVoices(EDGE)) voices.upsertVoice(edgeVoice(voice.id,voice.displayName,voice.locale))
        coordinator.refresh()
    }
    override suspend fun saveCustom(config: ListeningCustomConfig, secret: String) {
        initialize()
        val engine = customEngine(config,secret)
        engines.upsert(engine)
        voices.getVoices().filter { it.engineId == CUSTOM && it.speakerId != config.voiceId }.forEach { voices.deleteVoice(it) }
        voices.upsertVoice(ReadAloudVoice("$CUSTOM:${config.voiceId}",ReadAloudVoice.ENGINE_CLOUD,CUSTOM,config.voiceId,config.label,
            traitsJson=GSON.toJson(CloudTtsVoiceConfig(locale=config.locale)),revision=engine.updatedAt))
    }
    override suspend fun deleteCustom() {
        if (ReadAloud.isWenku8Online && settings.currentSettings.ttsEngine?.contains(CUSTOM) == true) selectVoice("$EDGE:zh-CN-XiaoxiaoNeural")
        engines.get(CUSTOM)?.let { engines.delete(it) }
        voices.getVoices().filter { it.engineId == CUSTOM }.forEach { voices.deleteVoice(it) }
    }
    override suspend fun previewCustom(config: ListeningCustomConfig, secret: String, text: String) = previewCommands.withLock {
        require(text.isNotBlank() && text.length <= 240) { "试听文本须为1至240字" }
        val engine = customEngine(config,secret)
        if (sessions.state.value.status == ReadAloudSessionStatus.Playing) ReadAloud.pause(app)
        val file = File(app.cacheDir,"wenku8-listening-preview/${java.util.UUID.randomUUID()}.mp3")
        try {
            synthesizer.synthesize(engine,CloudTtsSynthesisRequest(text,config.voiceId,config.locale),file)
            withContext(Dispatchers.Main.immediate) {
                stopPreview()
                suspendCancellableCoroutine<Unit> { continuation ->
                    previewContinuation=continuation
                    preview=MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { if(continuation.isActive) continuation.resume(Unit) }
                        setOnErrorListener { _,_,_ -> if(continuation.isActive) continuation.resumeWithException(IllegalStateException("试听音频无法播放")); true }
                        setOnPreparedListener { start() }
                        prepareAsync()
                    }
                }
            }
        } finally { withContext(NonCancellable+Dispatchers.Main.immediate) { stopPreview() }; file.delete() }
        Unit
    }
    override fun stopPreview() { preview?.release(); preview=null; previewContinuation?.let { if(it.isActive) it.cancel() }; previewContinuation=null }
    private suspend fun customEngine(config: ListeningCustomConfig, secret: String): CloudTtsEngine {
        val previous=engines.get(CUSTOM)
        val key=secret.ifEmpty { previous?.apiKey.orEmpty() }
        validateWenku8SsmlConfig(config.endpoint,config.voiceId,config.locale,config.label,config.authentication,key)
        return CloudTtsEngine(CUSTOM,config.label,CloudTtsProviderType.Wenku8Ssml,baseUrl=config.endpoint,apiKey=key,region=config.authentication,model=config.voiceId,appId=config.locale)
    }
    private suspend fun persistCursor(source: io.legado.app.ui.book.readaloud.player.ReadAloudPlayerSourceState, session: ReadAloudSessionState) {
        if (source.bookUrl.isBlank() || session.preparing || session.status == ReadAloudSessionStatus.Idle) return
        val cursor = JSONObject().put("chapter",source.chapterIndex).put("position",source.chapterPosition)
        val segment = segments(source.chapterText).lastOrNull { it.chapterPosition <= source.chapterPosition }
        cursor.put("start",segment?.chapterPosition ?: source.chapterPosition)
            .put("clipMs",session.clipPositionMs).put("hash",segment?.text?.let(::hash).orEmpty())
            .put("engine",ReadAloud.ttsEngine.orEmpty())
        settings.update { current ->
            val all=runCatching {JSONObject(current.wenku8Cursors)}.getOrElse {JSONObject()}
            if (all.optJSONObject(source.bookUrl)?.toString() == cursor.toString()) current
            else {all.put(source.bookUrl,cursor);current.copy(wenku8Cursors=all.toString())}
        }
    }
    private fun savedCursor(bookUrl: String) = runCatching { JSONObject(settings.currentSettings.wenku8Cursors).optJSONObject(bookUrl) }.getOrNull()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun edgeVoice(id:String,label:String,locale:String) = ReadAloudVoice("$EDGE:$id",ReadAloudVoice.ENGINE_CLOUD,EDGE,id,label,
        traitsJson=GSON.toJson(CloudTtsVoiceConfig(locale=locale)),managedBy="wenku8")
    companion object { const val EDGE="wenku8-edge"; const val CUSTOM="wenku8-custom" }
}
