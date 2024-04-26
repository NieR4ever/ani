package me.him188.ani.app.torrent.api

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import me.him188.ani.app.torrent.PiecesBuilder
import me.him188.ani.app.torrent.api.pieces.Piece
import me.him188.ani.app.torrent.api.pieces.lastIndex
import me.him188.ani.app.torrent.assertCoroutineSuspends
import me.him188.ani.app.torrent.buildPieceList
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

internal class TorrentDownloadSessionTest : TorrentSessionSupport() {
    ///////////////////////////////////////////////////////////////////////////
    // file pieces
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `getFiles suspend until handle ready`() = runTest {
        withSession {
            assertCoroutineSuspends { getFiles() }
            setHandle {
                addFileAndPieces(TestTorrentFile("1.mp4", 1024))
            }
            getFiles().run {
                assertEquals(1, size)
                assertEquals("1.mp4", first().pathInTorrent)
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // resume
    ///////////////////////////////////////////////////////////////////////////

    @Test
    fun `no resume if no file handle created`() = runTest {
        withSession {
            setHandle(object : TestAniTorrentHandle() {
                override fun resume() {
                    fail("Should not resume")
                }

                override fun pause() {
                    fail("Should not resume")
                }
            }) {
                addFileAndPieces(TestTorrentFile("1.mp4", 1024))
            }
        }
    }

    @Test
    fun `resume torrent by handle`() = runTest {
        withSession {
            var resumeCalled = 0
            val handle = setHandle(object : TestAniTorrentHandle() {
                override fun resume() {
                    resumeCalled++
                }

                override fun pause() {
                    fail("Should not resume")
                }
            }) {
                addFileAndPieces(TestTorrentFile("1.mp4", 1024))
            }

            launch {
                yield()
                listener.onUpdate(handle)
            }

            val file = getFiles().single()
            file.createHandle().use {
                it.resume()
            }

            assertEquals(1, resumeCalled)
        }
    }
}

open class TestAniTorrentHandle(
    override val name: String = "test",
) : AniTorrentHandle {
    val trackers = mutableListOf<String>()

    val pieces = mutableListOf<TestPiece>()
    val files = mutableListOf<TorrentFile>()

    override val contents = object : TorrentContents {
        override fun createPieces(): List<Piece> = this@TestAniTorrentHandle.pieces.map { it.piece }
        override val files: List<TorrentFile> get() = this@TestAniTorrentHandle.files
    }

    override fun addTracker(url: String) {
        trackers.add(url)
    }

    var isResumed = false

    override fun resume() {
        isResumed = true
    }

    override fun pause() {
        isResumed = false
    }

    override fun setPieceDeadline(pieceIndex: Int, deadline: Int) {
        check(pieceIndex in this.pieces.map { it.piece.pieceIndex }) { "Piece $pieceIndex not found" }
        this.pieces.first { it.piece.pieceIndex == pieceIndex }.deadline = deadline
    }
}

fun TestAniTorrentHandle.replacePieces(builderAction: PiecesBuilder.() -> Unit) {
    pieces.clear()
    pieces.addAll(buildPieceList(builderAction).map { TestPiece(it) })
}

fun TestAniTorrentHandle.appendPieces(builderAction: PiecesBuilder.() -> Unit) {
    pieces.addAll(buildPieceList(
        initialOffset = pieces.lastOrNull()?.piece?.lastIndex?.plus(1) ?: 0,
        builderAction
    ).map { TestPiece(it) })
}

fun TestAniTorrentHandle.addFileAndPieces(
    file: TestTorrentFile,
) {
    files.add(file)
    appendPieces {
        piece(file.size)
    }
}

class TestPiece(
    var piece: Piece,
    var deadline: Int? = null,
)


class TestTorrentFile(
    override var path: String = "",
    override var size: Long = 0,
    override var priority: FilePriority = FilePriority.NORMAL
) : TorrentFile 