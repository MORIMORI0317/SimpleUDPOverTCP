package net.morimori0317.simpleudpovertcp

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.DatagramChannel
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class TCPToUDP(
    private val listenAddr: InetSocketAddress,
    private val destAddr: InetSocketAddress
) {

    companion object {
        private const val TCP_TIME_OUT = 1000L * 15L
        private const val TCP_TIME_OUT_CHECK_INTERVAL = 1000L * 10L
        private var CONNECT_COUNTER = 0
    }

    private val connectInstanceList = mutableListOf<ConnectInstance>()
    private val connectInstanceLock = Object()

    private val connectCheckTimer = Timer(false)

    fun start() {
        println("TCP to UDP started with listenAddr: $listenAddr, destAddr: $destAddr")

        // 接続確認タスク開始
        connectCheckTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    connectCheck()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }, 0L, TCP_TIME_OUT_CHECK_INTERVAL)

        val serverSocket = ServerSocket(listenAddr.port, 50, listenAddr.address)
        while (true) {
            val clientSocket = serverSocket.accept()
            clientSocket.keepAlive = true

            synchronized(connectInstanceLock) {
                val connectInstance = ConnectInstance(clientSocket)
                connectInstanceList.add(connectInstance)
                connectInstance.start()
            }
        }
    }

    private fun connectCheck() {
        val removeConnectInstanceSet = mutableSetOf<ConnectInstance>()

        // タイムアウトチェック
        synchronized(connectInstanceLock) {
            val currentTime = System.currentTimeMillis()
            connectInstanceList
                .filter { (currentTime - it.lastTime) >= TCP_TIME_OUT }
                .forEach { removeConnectInstanceSet.add(it) }
        }

        removeConnectInstanceSet.forEach { it.dispose() }
    }

    private inner class ConnectInstance(
        socket: Socket,
    ) {
        private val id = ++CONNECT_COUNTER
        private val destroy = AtomicBoolean(false)

        var lastTime = System.currentTimeMillis()

        private val tcpConnection = TCPConnection(socket, ::receiveTCP, ::dispose)
        private lateinit var udpConnection: UDPConnection

        fun start() {
            println("Connection Start: $id")

            // UDP接続開始
            val channel = DatagramChannel.open()
            channel.socket().bind(null)
            udpConnection = UDPConnection(channel, { data, len, _ -> receiveUDP(data, len) }, ::dispose)

            tcpConnection.start()
            udpConnection.start()
        }

        private fun receiveTCP(data: ByteArray, len: Int) {
            lastTime = System.currentTimeMillis()
            udpConnection.send(data, len, destAddr)
        }

        private fun receiveUDP(data: ByteArray, len: Int) {
            lastTime = System.currentTimeMillis()
            tcpConnection.send(data, len)
        }

        fun dispose() {
            if (destroy.getAndSet(true)) {
                return
            }
            println("Connection Dispose: $id")

            tcpConnection.dispose()
            udpConnection.dispose()

            synchronized(connectInstanceLock) {
                connectInstanceList.remove(this)
            }
        }
    }
}