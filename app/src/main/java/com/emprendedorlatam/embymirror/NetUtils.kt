package com.emprendedorlatam.embymirror

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    fun firstIpv4Address(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }
}
