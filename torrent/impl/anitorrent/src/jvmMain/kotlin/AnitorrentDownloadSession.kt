package me.him188.ani.app.torrent.anitorrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import me.him188.ani.app.torrent.anitorrent.AnitorrentDownloadSession.AnitorrentEntry.EntryHandle
import me.him188.ani.app.torrent.anitorrent.binding.session_t
import me.him188.ani.app.torrent.anitorrent.binding.torrent_handle_t
import me.him188.ani.app.torrent.anitorrent.binding.torrent_info_t
import me.him188.ani.app.torrent.anitorrent.binding.torrent_resume_data_t
import me.him188.ani.app.torrent.anitorrent.binding.torrent_stats_t
import me.him188.ani.app.torrent.api.TorrentDownloadSession
import me.him188.ani.app.torrent.api.TorrentDownloadState
import me.him188.ani.app.torrent.api.files.AbstractTorrentFileEntry
import me.him188.ani.app.torrent.api.files.DownloadStats
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.PieceState
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.files.TorrentFilePieceMatcher
import me.him188.ani.app.torrent.api.pieces.Piece
import me.him188.ani.app.torrent.api.pieces.PiecePriorities
import me.him188.ani.app.torrent.api.pieces.TorrentDownloadController
import me.him188.ani.app.torrent.api.pieces.lastIndex
import me.him188.ani.app.torrent.api.pieces.startIndex
import me.him188.ani.app.torrent.io.TorrentInput
import me.him188.ani.utils.io.SeekableInput
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.toFile
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.io.RandomAccessFile
import kotlin.coroutines.CoroutineContext

class AnitorrentDownloadSession(
    private val session: session_t, // we should hold session to prevent it from being GCed
    private val handle: torrent_handle_t,
    override val saveDirectory: SystemPath,
    val fastResumeFile: SystemPath,
    private val onClose: (AnitorrentDownloadSession) -> Unit,
    private val onDelete: (AnitorrentDownloadSession) -> Unit,
    parentCoroutineContext: CoroutineContext,
) : TorrentDownloadSession {
    val logger = logger(this::class)
    val handleId = handle.id // 内存地址, 不可持久

    private val scope =
        CoroutineScope(
            parentCoroutineContext + Dispatchers.IO + SupervisorJob(parentCoroutineContext[Job]),
        )

    init {
        scope.launch {
            while (isActive) {
                if (!handle.is_valid) {
                    return@launch
                }
                handle.post_status_updates()
                delay(1000)
            }
        }
    }

    override val state: MutableStateFlow<TorrentDownloadState> =
        MutableStateFlow(TorrentDownloadState.Starting)

    override val overallStats: MutableDownloadStats = MutableDownloadStats()

    private val openFiles = mutableListOf<EntryHandle>()
    private val prioritizer = createPiecePriorities()

    inner class AnitorrentEntry(
        override val pieces: List<Piece>,
        index: Int,
        val offset: Long,
        length: Long, saveDirectory: SystemPath, relativePath: String,
        isDebug: Boolean, parentCoroutineContext: CoroutineContext,
        initialDownloadedBytes: Long,
    ) : AbstractTorrentFileEntry(
        index, length, saveDirectory, relativePath, handleId.toString(), isDebug,
        parentCoroutineContext,
    ) {
        override val supportsStreaming: Boolean get() = true
        val pieceRange = if (pieces.isEmpty()) LongRange.EMPTY else pieces.first().startIndex..pieces.last().lastIndex

        val controller: TorrentDownloadController = TorrentDownloadController(
            pieces,
            prioritizer,
            // libtorrent 可能会平均地请求整个 window, 所以不能太大
            windowSize = (8 * 1024 * 1024 / (pieces.firstOrNull()?.size ?: 1024L)).toInt().coerceIn(2, 64),
            headerSize = 2 * 1024 * 1024,
            footerSize = (0.5 * 1024 * 1024).toLong(),
            possibleFooterSize = 8 * 1024 * 1024,
        )

        inner class EntryHandle : AbstractTorrentFileHandle() {
            override val entry get() = this@AnitorrentEntry

            override fun closeImpl() {
                openFiles.remove(this)
                closeIfNotInUse()
            }

            override fun resumeImpl(priority: FilePriority) {
                controller.onTorrentResumed()
                handle.resume()
            }

            override fun closeAndDelete() {
                close()
                deleteEntireTorrentIfNotInUse()
            }
        }

        override fun updatePriority() {
            logger.info { "[$handleId] Set file priority to $requestingPriority: $relativePath" }
            handle.set_file_priority(index, requestingPriority.toLibtorrentValue())
        }

        val downloadedBytes: MutableStateFlow<Long> = MutableStateFlow(initialDownloadedBytes)
        override val stats: DownloadStats = object : DownloadStats() {
            override val totalSize: MutableStateFlow<Long> = MutableStateFlow(length)
            override val downloadedBytes: MutableStateFlow<Long> get() = this@AnitorrentEntry.downloadedBytes
            override val uploadRate: MutableStateFlow<Long> get() = this@AnitorrentDownloadSession.overallStats.uploadRate
            override val progress: Flow<Float> =
                combine(totalSize, downloadedBytes) { total, downloaded ->
                    if (total == 0L) return@combine 0f
                    downloaded.toFloat() / total.toFloat()
                }
        }

        override fun createHandle(): TorrentFileHandle {
            return EntryHandle().also {
                openFiles.add(it)
            }
        }

        override suspend fun createInput(): SeekableInput {
            val input = resolveFileOrNull() ?: resolveDownloadingFile()
            return TorrentInput(
                runInterruptible(Dispatchers.IO) {
                    RandomAccessFile(input.toFile(), "r")
                },
                this.pieces,
                logicalStartOffset = offset,
                onWait = { piece ->
                    updatePieceDeadlinesForSeek(piece)
                },
                size = length,
            )
        }

        private fun updatePieceDeadlinesForSeek(requested: Piece) {
            if (!controller.isDownloading(requested.pieceIndex)) {
                logger.info { "[TorrentDownloadControl] $torrentId: Resetting deadlines to download ${requested.pieceIndex}" }
                handle.clear_piece_deadlines()
                controller.onSeek(requested.pieceIndex) // will request further pieces
            } else {
                logger.info { "[TorrentDownloadControl] $torrentId: Requested piece ${requested.pieceIndex} is already downloading" }
                return
            }
        }
    }

    /**
     * 延迟获取的具体文件信息. 因为如果是添加磁力链的话, 文件信息需要经过耗时的网络查询后才能得到.
     * @see useTorrentInfoOrLaunch
     */
    inner class TorrentInfo(
        val allPiecesInTorrent: List<Piece>,
        val pieceLength: Int,
        val entries: List<AnitorrentEntry>,
    ) {
        init {
            check(allPiecesInTorrent is RandomAccess)
        }

        override fun toString(): String {
            return "TorrentInfo(numPieces=${allPiecesInTorrent.size}, entries.size=${entries.size})"
        }
    }

    private val actualTorrentInfo = CompletableDeferred<TorrentInfo>()

    /**
     * 当 [actualTorrentInfo] 还未完成时的任务队列, 用于延迟执行需要 [TorrentInfo] 的任务.
     *
     * 这是因为 [onTorrentFinished] 和 [onPieceDownloading] 可能会早于 [onTorrentChecked] 调用.
     * 而且 [onPieceDownloading] 会非常频繁调用, 不能为它启动过多协程
     */
    private val taskQueue: DisposableTaskQueue<AnitorrentDownloadSession> = DisposableTaskQueue(this).apply {
        scope.launch {
            actualTorrentInfo.await()
            runAndDispose()
        }
    }

    // 回调可能会早于 [actualTorrentInfo] 计算完成之前调用, 所以需要考虑延迟的情况
    private inline fun useTorrentInfoOrLaunch(
        // receiver 是为了让 lambda 无需捕获 this 对象. 因为 [onPieceDownloading] 可能会调用数万次
        // will be inlined multiple times!
        crossinline block: AnitorrentDownloadSession.(TorrentInfo) -> Unit
    ) {
        if (actualTorrentInfo.isCompleted) {
            block(actualTorrentInfo.getCompleted())
        } else {
            val added = taskQueue.add {
                block(actualTorrentInfo.getCompleted())
            }
            if (!added) {
                // taskQueue disposed, then actualTorrentInfo must have completed now
                check(actualTorrentInfo.isCompleted) {
                    "taskQueue disposed however actualTorrentInfo is not completed yet"
                }
                block(actualTorrentInfo.getCompleted())
            }
        }
    }

    private fun initializeTorrentInfo(info: torrent_info_t) {
        check(this.actualTorrentInfo.isActive) {
            "actualTorrentInfo has already been completed or closed"
        }
        val allPiecesInTorrent =
            Piece.buildPieces(info.num_pieces) {
                if (it == info.num_pieces - 1) {
                    info.last_piece_size.toUInt().toLong()
                } else info.piece_length.toUInt().toLong()
            }

        val entries: List<AnitorrentEntry> = kotlin.run {
            val numFiles = info.fileSequence.toList()

            var currentOffset = 0L
            val list = numFiles.mapIndexed { index, file ->
                val size = file.size
                val path = file.path.takeIf { it.isNotBlank() } ?: file.name
                val list = TorrentFilePieceMatcher.matchPiecesForFile(
                    allPiecesInTorrent,
                    currentOffset,
                    size,
                ).also { pieces ->
                    logPieces(pieces, path)
                }
                val filePieces = if (list is RandomAccess) {
                    list
                } else {
                    ArrayList(list)
                }
                AnitorrentEntry(
                    pieces = filePieces,
                    index = index,
                    offset = currentOffset,
                    length = size,
                    saveDirectory = saveDirectory,
                    relativePath = path,
                    isDebug = false,
                    parentCoroutineContext = Dispatchers.IO,
                    initialDownloadedBytes = calculateTotalFinishedSize(filePieces).coerceAtMost(size),
                ).also {
                    currentOffset += size
                }
            }
            list
        }
        val value = TorrentInfo(allPiecesInTorrent, pieceLength = info.piece_length, entries)
        logger.info { "[$handleId] Got torrent info: $value" }
        this.overallStats.totalSize.value = entries.sumOf { it.length }
//        handle.ignore_all_files() // no need because we set libtorrent::torrent_flags::default_dont_download in native
        this.actualTorrentInfo.complete(value)
    }

    fun onTorrentChecked() {
        logger.info { "[$handleId] onTorrentChecked" }
        val res = handle.reload_file()
        if (res != torrent_handle_t.reload_file_result_t.kReloadFileSuccess) {
            logger.error { "[$handleId] Reload file result: $res" }
            throw IllegalStateException("Failed to reload file, native returned $res")
        }
        val info = handle.get_info_view()
        if (info != null) {
            initializeTorrentInfo(info)
        } else {
            logger.error { "[$handleId] onTorrentChecked: info is null" }
        }
    }

    fun onPieceDownloading(pieceIndex: Int) {
        useTorrentInfoOrLaunch { info ->
            info.allPiecesInTorrent.getOrNull(pieceIndex)?.state?.compareAndSet(
                PieceState.READY,
                PieceState.DOWNLOADING,
            )
        }
    }

    fun onPieceFinished(pieceIndex: Int) {
        // 注意, 在恢复时, libtorrent 不一定会为所有 piece 发送这个事件
        useTorrentInfoOrLaunch { info ->
            onPieceFinishedImpl(info, pieceIndex) // avoid being inlined multiple times
        }
    }

    private fun onPieceFinishedImpl(
        info: TorrentInfo,
        pieceIndex: Int
    ) {
        info.allPiecesInTorrent.getOrNull(pieceIndex)?.state?.value = PieceState.FINISHED
        for (file in openFiles) {
            if (pieceIndex in file.entry.pieceRange) {
                file.entry.controller.onPieceDownloaded(pieceIndex)
            }
        }
        // TODO: Anitorrent 计算 file 完成度的算法需要优化性能, 这有 n^2 复杂度
        for (entry in info.entries) {
            if (pieceIndex !in entry.pieceRange) continue

            val downloadedBytes = entry.downloadedBytes.value
            if (downloadedBytes == entry.length) {
                // entry already finished
                continue
            }

            entry.downloadedBytes.compareAndSet(
                downloadedBytes,
                calculateTotalFinishedSize(entry.pieces).coerceAtMost(entry.length),
            ) // lazy set, if already finished, don't update
        }
    }

    fun onTorrentFinished() {
        // 注意, 这个事件不一定是所有文件下载完成了. 
        // 在刚刚创建任务的时候所有文件都是完全不下载的状态, libtorrent 会立即广播这个事件.
        logger.info { "[$handleId] onTorrentFinished" }
        handle.post_save_resume()
    }

    fun onStatsUpdate(stats: torrent_stats_t) {
        this.overallStats.downloadRate.value = stats.download_payload_rate.toUInt().toLong()
        this.overallStats.uploadRate.value = stats.upload_payload_rate.toUInt().toLong()
        this.overallStats.progress.value = stats.progress
        this.overallStats.downloadedBytes.value = (this.overallStats.totalSize.value * stats.progress).toLong()
        this.overallStats.uploadedBytes.value = stats.total_payload_upload
        this.overallStats.isFinished.value = stats.progress >= 1f
    }

    fun onFileCompleted(index: Int) {
        useTorrentInfoOrLaunch { info ->
            val entry = info.entries.getOrNull(index) ?: return@useTorrentInfoOrLaunch
            // 没有设置 pieces 状态, 因为假如首尾 pieces 不是精确匹配, 首尾 pieces 可能实际上没有完成
            entry.downloadedBytes.value = entry.length
        }
    }

    fun onSaveResumeData(data: torrent_resume_data_t) {
        logger.info { "[$handleId] saving resume data to: ${fastResumeFile.absolutePath}" }
        data.save_to_file(fastResumeFile.absolutePath)
    }

    override suspend fun getFiles(): List<TorrentFileEntry> = this.actualTorrentInfo.await().entries

    private var closed = false
    override fun close() {
        if (closed) {
            return
        }
        synchronized(this) {
            if (closed) {
                return
            }
            state.value = TorrentDownloadState.Closed
            closed = true
        }
        logger.info { "AnitorrentDownloadSession closing" }
        scope.cancel()
        onClose(this)
    }

    override fun closeIfNotInUse() {
        if (openFiles.isEmpty()) {
            close()
        }
    }

    fun deleteEntireTorrentIfNotInUse() {
        if (openFiles.isEmpty() && closed) {
            saveDirectory.toFile().deleteRecursively()
            onDelete(this)
        }
    }

    private fun createPiecePriorities(): PiecePriorities {
        return object : PiecePriorities {
            //            private val priorities = Array(torrentFile().numPieces()) { Priority.IGNORE }
            override fun downloadOnly(pieceIndexes: List<Int>, possibleFooterRange: IntRange) {
                if (pieceIndexes.isEmpty()) {
                    return
                }
                logger.debug { "[$handleId][TorrentDownloadControl] Prioritizing pieces: $pieceIndexes" }
                val smallestIndex = pieceIndexes.minBy { it }

                // 超高优先下载第一个 piece, 防止它一直请求后面的 (因为一旦有 piece 完成, window 就会往后变大)
                handle.set_piece_deadline(pieceIndexes.first(), -10000)

                for (i in 1 until pieceIndexes.size) {
                    val pieceIndex = pieceIndexes[i]
                    handle.set_piece_deadline(
                        pieceIndex,
                        // 低于现在可以让 libtorrent 更急
                        -5000 + if (pieceIndex in possibleFooterRange) {
                            // 对于视频尾部元数据, 同样需要给予较高的优先级
                            val lastFooter = possibleFooterRange.last()
                            (lastFooter - pieceIndex) * 700
                        } else {
                            // 最高优先级下载第一个. 第一个有可能会是 seek 之后的.
                            (pieceIndex - smallestIndex) * 700
                        },
                    )
                }
            }
        }
    }
}

class MutableDownloadStats : DownloadStats() {
    override val totalSize: MutableStateFlow<Long> = MutableStateFlow(0)
    override val uploadRate: MutableStateFlow<Long> = MutableStateFlow(0)
    override val downloadRate: MutableStateFlow<Long> = MutableStateFlow(0)
    override val progress: MutableStateFlow<Float> = MutableStateFlow(0f)
    override val downloadedBytes: MutableStateFlow<Long> = MutableStateFlow(0)
    val uploadedBytes: MutableStateFlow<Long> = MutableStateFlow(0)
    override val isFinished: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

private fun AnitorrentDownloadSession.logPieces(pieces: List<Piece>, pathInTorrent: String) {
    logger.info {
        val start = pieces.minByOrNull { it.startIndex }
        val end = pieces.maxByOrNull { it.lastIndex }
        if (start == null || end == null) {
            "[$handleId] File '$pathInTorrent' piece initialized, ${pieces.size} pieces, " +
                    "index range: start=$start, end=$end"
        } else {
            "[$handleId] File '$pathInTorrent' piece initialized, ${pieces.size} pieces, " +
                    "index range: ${start.pieceIndex..end.pieceIndex}, " +
                    "offset range: $start..$end"
        }
    }
}

val torrent_info_t.fileSequence
    get() = sequence {
        repeat(file_count().toInt()) {
            val file = file_at(it) ?: return@sequence
            yield(file)
        }
    }

private fun calculateTotalFinishedSize(pieces: List<Piece>): Long =
    pieces.sumOf { if (it.state.value == PieceState.FINISHED) it.size else 0 }

private fun FilePriority.toLibtorrentValue(): Short = when (this) {
    FilePriority.IGNORE -> 0
    FilePriority.LOW -> 1
    FilePriority.NORMAL -> 4
    FilePriority.HIGH -> 7
}