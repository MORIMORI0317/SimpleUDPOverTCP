package net.morimori0317.simpleudpovertcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean

class UDPConnection(
    private val channel: DatagramChannel,
    private val receiveListener: (ByteArray, Int, InetSocketAddress) -> Unit,
    private val closeListener: () -> Unit
) {

    companion object {
        const val UDP_PAYLOAD_SIZE = 65507
    }

    private val destroy = AtomicBoolean(false)

    private val receiveThread = ReceiveThread()

    private val coroutineScope: CoroutineScope
    private val sendFlow = MutableSharedFlow<Triple<BufferPool.Entry, Int, SocketAddress>>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    private val sendBufferPool = BufferPool(UDP_PAYLOAD_SIZE)

    init {
        // コルーチン作成
        val exHandler = CoroutineExceptionHandler { _, ex ->
            ex.printStackTrace()
            channel.close()
        }
        this.coroutineScope = CoroutineScope(Dispatchers.Default + CoroutineName("UDP Connection") + exHandler)

        // サブスクライバ登録
        coroutineScope.launch {
            val buf = ByteBuffer.allocate(UDP_PAYLOAD_SIZE)

            sendFlow.collect { (buffer, len, addr) ->
                // 送信データを書き込む
                buf.put(buffer.content, 0, len)
                buf.flip()

                // 送信
                with(Dispatchers.IO) {
                    channel.send(buf, addr)
                }

                buf.clear()

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

    fun send(data: ByteArray, len: Int, socketAddress: SocketAddress) {
        if (destroy.get()) {
            return
        }

        val buffer = sendBufferPool.obtain()
        data.copyInto(buffer.content, 0, 0, len)

        runBlocking {
            sendFlow.emit(Triple(buffer, len, socketAddress))
        }
    }

    fun dispose() {
        if (destroy.getAndSet(true)) {
            return
        }

        coroutineScope.cancel()
        channel.close()
    }

    private inner class ReceiveThread : Thread() {

        init {
            isDaemon = true
            name = "UDP Connection Receive Thread"
        }

        override fun run() {
            val buf = ByteBuffer.allocate(UDP_PAYLOAD_SIZE)

            try {
                while (true) {
                    // UDPのパケット読み取り
                    val socketAddr = channel.receive(buf) as InetSocketAddress
                    receiveListener(buf.array(), buf.position(), socketAddr)
                    buf.clear()
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                closeListener()
            }
        }
    }
}