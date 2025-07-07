package net.morimori0317.simpleudpovertcp

import org.apache.commons.cli.*
import java.net.InetSocketAddress


fun main(args: Array<String>) {
    // コマンドライン引数解析
    val options = Options()

    val typeOption = Option.builder("t")
        .argName("type")
        .required()
        .desc("udp2tcp or tcp2udp")
        .hasArg()
        .build()
    options.addOption(typeOption)

    val listenAddrOption = Option.builder("l")
        .argName("listen")
        .required()
        .desc("Listen Address")
        .hasArg()
        .build()
    options.addOption(listenAddrOption)

    val destAddrOption = Option.builder("d")
        .argName("dest")
        .required()
        .desc("Destination Address")
        .hasArg()
        .build()
    options.addOption(destAddrOption)

    val parser = DefaultParser()
    val cmd = try {
        parser.parse(options, args)
    } catch (_: ParseException) {
        val helpFormatter = HelpFormatter()
        helpFormatter.printHelp("[Options]", options)
        return
    }

    val listenAddr = parseAddress(cmd.getOptionValue("l"))
    val destAddr = parseAddress(cmd.getOptionValue("d"))

    // 開始
    when (cmd.getOptionValue("t")) {
        "udp2tcp" -> {
            val udpToTcp = UDPToTCP(listenAddr, destAddr)
            udpToTcp.start()
        }

        "tcp2udp" -> {
            val tcpToUdp = TCPToUDP(listenAddr, destAddr)
            tcpToUdp.start()
        }

        else -> throw IllegalArgumentException("Invalid type: ${cmd.getOptionValue("t")}")
    }
}

private fun parseAddress(text: String): InetSocketAddress {
    val parts = text.split(":")

    if (parts.size != 2) {
        throw IllegalArgumentException("Invalid address format: $text")
    }

    val host = parts[0]
    val port = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid port number: ${parts[1]}")
    return InetSocketAddress(host, port)
}
