package com.example.sinkanddestroybattleship.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object ConnectionTest {
    private const val SERVER = "brad-home.ch"
    private const val PORT = 50003
    private const val TIMEOUT = 5000 // 5 seconds

    suspend fun isServerReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SERVER, PORT), TIMEOUT)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getConnectionDetails(): String = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SERVER, PORT), TIMEOUT)
                "Connected to $SERVER:$PORT"
            }
        } catch (e: Exception) {
            "Failed to connect to $SERVER:$PORT - ${e.message}"
        }
    }
} 