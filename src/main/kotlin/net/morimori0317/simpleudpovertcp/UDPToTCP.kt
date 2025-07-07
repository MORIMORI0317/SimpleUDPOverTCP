package net.morimori0317.simpleudpovertcp

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.DatagramChannel
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class UDPToTCP(
    private val listenAddr: InetSocketAddress,
    private val destAddr: InetSocketAddress
) {

    companion object {
        private const val UDP_TIME_OUT = 1000L * 15L
        private const val UDP_TIME_OUT_CHECK_INTERVAL = 1000L * 10L
        private var CONNECT_COUNTER = 0
    }

    private val connectCheckTimer = Timer(false)

    private val waitObj = Object()
    private var closed = false

    private val connectInstanceMap = Long2ObjectOpenHashMap<ConnectInstance>()
    private val connectInstanceLock = Object()
    private lateinit var udpConnection: UDPConnection

    fun start() {
        println("UDP to TCP started with listenAddr: $listenAddr, destAddr: $destAddr")

        // 接続確認タスク開始
        connectCheckTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    connectCheck()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }, 0L, UDP_TIME_OUT_CHECK_INTERVAL)

        // UDP受信開始
        val channel = DatagramChannel.open()
        channel.socket().bind(listenAddr)

        udpConnection = UDPConnection(channel, ::receive) {
            synchronized(waitObj) {
                closed = true
                waitObj.notifyAll()
            }
        }
        udpConnection.start()

        synchronized(waitObj) {
            if (!closed) {
                waitObj.wait()
            }
        }
    }

    private fun receive(data: ByteArray, len: Int, socketAddress: InetSocketAddress) {
        val address = socketAddress.address.address
        val port = socketAddress.port
        val identifier = address[0].toLong() shl 24 or
                (address[1].toLong() shl 16) or
                (address[2].toLong() shl 8) or
                address[3].toLong() or
                (port.toLong() shl 32)

        synchronized(connectInstanceLock) {
            val connectionInstance = connectInstanceMap.computeIfAbsent(identifier) { identifier ->
                val connect = ConnectInstance(identifier, socketAddress)
                connect.start()
                return@computeIfAbsent connect
            }

            if (connectionInstance.error) {
                connectInstanceMap.remove(identifier)
            } else {
                connectionInstance.receiveUDP(data, len)
            }
        }
    }

    private fun connectCheck() {
        val removeConnectInstanceSet = mutableSetOf<ConnectInstance>()

        // タイムアウトチェック
        synchronized(connectInstanceLock) {
            val currentTime = System.currentTimeMillis()
            connectInstanceMap.values
                .filter { (currentTime - it.lastTime) >= UDP_TIME_OUT }
                .forEach { removeConnectInstanceSet.add(it) }
        }

        removeConnectInstanceSet.forEach { it.dispose() }
    }

    private inner class ConnectInstance(
        val identifier: Long,
        val udpAddress: InetSocketAddress
    ) {
        private val id = ++CONNECT_COUNTER
        private val destroy = AtomicBoolean(false)
        var error = false

        var lastTime = System.currentTimeMillis()

        private lateinit var tcpConnection: TCPConnection

        fun start() {
            println("Connection Start: $id, $udpAddress")

            val socket: Socket
            try {
                socket = Socket(destAddr.address, destAddr.port)
                socket.keepAlive = true
            } catch (e: Throwable) {
                error = true
                println("Error: $id, $destAddr, ${e.message}")
                return
            }

            tcpConnection = TCPConnection(socket, ::receiveTCP, ::dispose)
            tcpConnection.start()
        }

        fun receiveUDP(data: ByteArray, len: Int) {
            lastTime = System.currentTimeMillis()
            tcpConnection.send(data, len)
        }

        private fun receiveTCP(data: ByteArray, len: Int) {
            lastTime = System.currentTimeMillis()
            udpConnection.send(data, len, udpAddress)
        }

        fun dispose() {
            if (destroy.getAndSet(true)) {
                return
            }
            println("Connection Dispose: $id")
            tcpConnection.dispose()

            synchronized(connectInstanceLock) {
                connectInstanceMap.remove(identifier)
            }
        }
    }
}