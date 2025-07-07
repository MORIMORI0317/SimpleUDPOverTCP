package net.morimori0317.simpleudpovertcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class TCPConnection(
    private val socket: Socket,
    private val receiveListener: (ByteArray, Int) -> Unit,
    private val closeListener: () -> Unit
) {

    private val destroy = AtomicBoolean(false)

    private val inputStream = socket.inputStream
    private val outputStream = socket.outputStream

    private val receiveThread = ReceiveThread()

    private val coroutineScope: CoroutineScope
    private val sendFlow = MutableSharedFlow<Pair<BufferPool.Entry, Int>>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private val sendBufferPool = BufferPool(UDPConnection.UDP_PAYLOAD_SIZE)

    init {

        // コルーチン作成
        val exHandler = CoroutineExceptionHandler { _, ex ->
            ex.printStackTrace()
            socket.close()
        }
        this.coroutineScope =
            CoroutineScope(Dispatchers.Default + CoroutineName("TCP Connection ${socket.remoteSocketAddress}") + exHandler)

        // サブスクライバ登録
        coroutineScope.launch {
            val packetSizeBuf = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)

            sendFlow.collect { (buffer, len) ->
                with(Dispatchers.IO) {
                    // パケットサイズ書き込み
                    packetSizeBuf.putInt(len)
                    packetSizeBuf.flip()
                    outputStream.write(packetSizeBuf.array())

                    // パケット本体書き込み
                    outputStream.write(buffer.content, 0, len)
                    outputStream.flush()
                }

                sendBufferPool.free(buffer)
            }
        }
    }

    fun start() {
        if (destroy.get()) {
            return
        }

        receiveThread.start()
    }

    fun send(data: ByteArray, len: Int) {
        if (destroy.get()) {
            return
        }

        val buffer = sendBufferPool.obtain()
        data.copyInto(buffer.content, 0, 0, len)

        runBlocking {
            sendFlow.emit(Pair(buffer, len))
        }
    }

    fun dispose() {
        if (destroy.getAndSet(true)) {
            return
        }

        coroutineScope.cancel()

        socket.shutdownInput()
        socket.shutdownOutput()
        socket.close()
    }

    private inner class ReceiveThread : Thread() {

        init {
            isDaemon = true
            name = "TCP Connection Receive Thread"
        }

        override fun run() {
            try {
                val buffer = ByteArray(UDPConnection.UDP_PAYLOAD_SIZE)

                val packetSizeBufArray = ByteArray(Int.SIZE_BYTES)
                val packetSizeBuf = ByteBuffer.wrap(packetSizeBufArray).order(ByteOrder.BIG_ENDIAN)

                inputStream.use {
                    while (true) {
                        // パケットサイズ読み取り
                        readFullNByte(it, packetSizeBufArray, Int.SIZE_BYTES)
                        val packetSize = packetSizeBuf.int
                        packetSizeBuf.clear()

                        // パケット本体読み取り
                        readFullNByte(it, buffer, packetSize)

                        receiveListener(buffer, packetSize)
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                closeListener()
            }
        }
    }

    private fun readFullNByte(stream: InputStream, data: ByteArray, len: Int) {
        var remaining = len
        while (remaining > 0) {
            val read = stream.readNBytes(data, len - remaining, remaining)
            check(remaining != -1)
            remaining -= read
        }
    }

}
