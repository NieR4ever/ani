package me.him188.ani.app.data.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.media.resolver.TorrentVideoSourceResolver
import me.him188.ani.app.torrent.FilePriority
import me.him188.ani.app.torrent.TorrentDownloadSession
import me.him188.ani.app.torrent.TorrentDownloader
import me.him188.ani.app.torrent.TorrentFileEntry
import me.him188.ani.app.torrent.TorrentFileHandle
import me.him188.ani.app.torrent.model.EncodedTorrentData
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.core.cache.InternalMediaCacheStorageApi
import me.him188.ani.datasources.core.cache.MediaCache
import me.him188.ani.datasources.core.cache.MediaCacheEngine
import me.him188.ani.datasources.core.cache.MediaStats
import me.him188.ani.utils.coroutines.SuspendLazy
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.nio.file.Paths
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

private const val EXTRA_TORRENT_DATA = "torrentData"
private const val EXTRA_TORRENT_CACHE_DIR = "torrentCacheDir" // 种子的缓存目录, 注意, 一个 MediaCache 可能只对应该种子资源的其中一个文件
//private const val EXTRA_TORRENT_CACHE_FILE =
//    "torrentCacheFile" // MediaCache 所对应的视频文件. 该文件一定是 [EXTRA_TORRENT_CACHE_DIR] 目录中的文件 (的其中一个)

class TorrentMediaCacheEngine(
    private val mediaSourceId: String,
    getTorrentDownloader: suspend () -> TorrentDownloader,
) : MediaCacheEngine {
    private companion object {
        val logger = logger<TorrentMediaCacheEngine>()
    }

    private val downloader = SuspendLazy {
        getTorrentDownloader()
    }

    class LazyFileHandle(
        val scope: CoroutineScope,
        val state: SharedFlow<State?>,
    ) {
        val handle = state.map { it?.handle }
        val entry = state.map { it?.entry }

        class State(
            val session: TorrentDownloadSession,
            val entry: TorrentFileEntry?,
            val handle: TorrentFileHandle?,
        )
    }

    private inner class TorrentMediaCache(
        override val origin: Media,
        /**
         * Required:
         * @see EXTRA_TORRENT_CACHE_DIR
         * @see EXTRA_TORRENT_DATA
         */
        override val metadata: MediaCacheMetadata, // 注意, 我们不能写 check 检查这些属性, 因为可能会有旧版本的数据
        val lazyFileHandle: LazyFileHandle
    ) : MediaCache {
        override suspend fun getCachedMedia(): CachedMedia {
            val file = lazyFileHandle.handle.first()
            val finished = file?.entry?.stats?.isFinished?.first()
            if (finished == true) {
                val filePath = file.entry.resolveFile()
                if (!filePath.exists()) {
                    error("TorrentFileHandle has finished but file does not exist: $filePath")
                }
                logger.info { "getCachedMedia: Torrent has already finished, returning file $filePath" }
                return CachedMedia(
                    origin,
                    mediaSourceId,
                    download = ResourceLocation.LocalFile(filePath.toString())
                )
            } else {
                logger.info { "getCachedMedia: Torrent has not yet finished, returning torrent" }
                return CachedMedia(
                    origin,
                    mediaSourceId,
                    download = origin.download,
//                download = ResourceLocation.LocalFile(session.first().filePath().toString())
                )
            }
        }

        override fun isValid(): Boolean {
            return metadata.extra[EXTRA_TORRENT_CACHE_DIR]?.let {
                Paths.get(it).exists()
            } ?: false
        }

        private val entry get() = lazyFileHandle.entry

        override val downloadSpeed: Flow<FileSize>
            get() = entry.flatMapLatest { session ->
                session?.stats?.downloadRate?.map {
                    it?.bytes ?: FileSize.Unspecified
                } ?: flowOf(FileSize.Unspecified)
            }

        override val uploadSpeed: Flow<FileSize>
            get() = entry.flatMapLatest { session ->
                session?.stats?.uploadRate?.map {
                    it?.bytes ?: FileSize.Unspecified
                } ?: flowOf(FileSize.Unspecified)
            }

        override val progress: Flow<Float>
            get() = entry.filterNotNull().flatMapLatest { it.stats.progress }

        override val finished: Flow<Boolean>
            get() = entry.filterNotNull().flatMapLatest { it.stats.isFinished }

        override val totalSize: Flow<FileSize>
            get() = entry.flatMapLatest { entry ->
                entry?.stats?.totalBytes?.map { it.bytes } ?: flowOf(0.bytes)
            }

        @Volatile
        private var deleted = false

        override suspend fun pause() {
            if (deleted) return
            lazyFileHandle.handle.first()?.pause()
        }

        override suspend fun resume() {
            if (deleted) return
            val file = lazyFileHandle.handle.first()
            logger.info { "Resuming file: $file" }
            file?.resume(FilePriority.NORMAL)
        }

        @InternalMediaCacheStorageApi
        override suspend fun deleteFiles() {
            if (deleted) return
            synchronized(this) {
                if (deleted) return
                deleted = true
            }
            val handle = lazyFileHandle.handle.first() ?: return // did not even selected a file
            val file = handle.entry.resolveFile()
            handle.close()
            if (file.exists()) {
                logger.info { "Deleting torrent cache: $file" }
                file.deleteIfExists()
            } else {
                logger.info { "Torrent cache does not exist, ignoring: $file" }
            }
            lazyFileHandle.scope.cancel()
        }
    }

    override val stats: MediaStats = object : MediaStats {
        override val uploaded: Flow<FileSize> =
            flow { emit(downloader.get()) }
                .flatMapLatest { it.totalUploaded }
                .map { it.bytes }
        override val downloaded: Flow<FileSize> =
            flow { emit(downloader.get()) }
                .flatMapLatest { it.totalDownloaded }
                .map { it.bytes }

        override val uploadRate: Flow<FileSize> =
            flow { emit(downloader.get()) }
                .flatMapLatest { it.totalUploadRate }
                .map { it.bytes }
        override val downloadRate: Flow<FileSize> =
            flow { emit(downloader.get()) }
                .flatMapLatest { it.totalDownloadRate }
                .map { it.bytes }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext
    ): MediaCache? {
        val data = metadata.extra[EXTRA_TORRENT_DATA]?.hexToByteArray() ?: return null
        return TorrentMediaCache(
            origin = origin,
            metadata = metadata,
            lazyFileHandle = getLazyFileHandle(EncodedTorrentData(data), metadata, parentContext)
        )
    }

    private fun getLazyFileHandle(
        encoded: EncodedTorrentData,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext
    ): LazyFileHandle {
        val scope = CoroutineScope(parentContext + Job(parentContext[Job]))

        // lazy
        val state = flow {
            val session = downloader.get().startDownload(encoded, parentContext)

            val selectedFile = TorrentVideoSourceResolver.selectVideoFileEntry(
                session.getFiles(),
                listOf(metadata.episodeName),
                metadata.episodeSort,
            )

            val handle = selectedFile?.createHandle()
            if (handle == null) {
                session.closeIfNotInUse()
            }
            emit(LazyFileHandle.State(session, selectedFile, handle))
        }
        return LazyFileHandle(scope, state.shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1))
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun createCache(
        origin: Media,
        request: MediaCacheMetadata,
        parentContext: CoroutineContext
    ): MediaCache {
        val downloader = downloader.get()
        val data = downloader.fetchTorrent(origin.download.uri)
        val metadata = request.withExtra(
            mapOf(
                EXTRA_TORRENT_DATA to data.data.toHexString(),
                EXTRA_TORRENT_CACHE_DIR to downloader.getSaveDir(data).absolutePath,
            )
        )

        return TorrentMediaCache(
            origin = origin,
            metadata = metadata,
            lazyFileHandle = getLazyFileHandle(data, metadata, parentContext),
        )
    }

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {
        val allowed = all.mapNotNull { it.metadata.extra[EXTRA_TORRENT_CACHE_DIR] }

        val downloader = downloader.get()
        withContext(Dispatchers.IO) {
            val saves = downloader.listSaves()
            for (save in saves) {
                if (save.absolutePath !in allowed) {
                    val totalLength = save.walk().sumOf { it.length() }
                    logger.warn { "本地种子缓存文件未找到匹配的 MediaCache, 已释放 ${totalLength.bytes}: ${save.absolutePath}" }
                    save.deleteRecursively()
                }
            }
        }
    }
}