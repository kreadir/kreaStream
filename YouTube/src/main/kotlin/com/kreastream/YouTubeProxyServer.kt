package com.kreastream

import android.util.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object YouTubeProxyServer {
    private var server: ServerSocket? = null
    private var port: Int = 0
    private val streamMap = ConcurrentHashMap<String, StreamPair>()
    private var isRunning = false
    
    data class StreamPair(val videoUrl: String, val audioUrl: String)
    
    fun start(): Int {
        if (isRunning) return port
        
        try {
            server = ServerSocket(0) // Use any available port
            port = server!!.localPort
            isRunning = true
            
            Log.d("YouTubeProxyServer", "Started proxy server on port $port")
            
            // Start accepting connections
            GlobalScope.launch(Dispatchers.IO) {
                acceptConnections()
            }
            
            return port
        } catch (e: Exception) {
            Log.e("YouTubeProxyServer", "Failed to start proxy server: ${e.message}")
            return -1
        }
    }
    
    fun addStream(videoUrl: String, audioUrl: String): String {
        val streamId = Random.nextInt(10000, 99999).toString()
        streamMap[streamId] = StreamPair(videoUrl, audioUrl)
        return "http://127.0.0.1:$port/merge/$streamId"
    }
    
    private suspend fun acceptConnections() {
        while (isRunning && server != null && !server!!.isClosed) {
            try {
                val clientSocket = server!!.accept()
                GlobalScope.launch(Dispatchers.IO) {
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("YouTubeProxyServer", "Error accepting connection: ${e.message}")
                }
            }
        }
    }
    
    private suspend fun handleClient(clientSocket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val output = clientSocket.getOutputStream()
            
            // Read the full HTTP request
            val requestLine = input.readLine() ?: return
            Log.d("YouTubeProxyServer", "Request: $requestLine")
            
            // Read headers until empty line
            var line: String?
            do {
                line = input.readLine()
            } while (!line.isNullOrEmpty())
            
            // Parse request
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                send404(output)
                return
            }
            
            val path = parts[1]
            if (path.startsWith("/merge/")) {
                val streamId = path.substring(7)
                val streamPair = streamMap[streamId]
                
                if (streamPair != null) {
                    handleMergeRequest(output, streamPair)
                } else {
                    Log.w("YouTubeProxyServer", "Stream not found: $streamId")
                    send404(output)
                }
            } else {
                send404(output)
            }
            
        } catch (e: Exception) {
            Log.e("YouTubeProxyServer", "Error handling client: ${e.message}")
            try {
                send500(clientSocket.getOutputStream())
            } catch (e2: Exception) {
                // Ignore
            }
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    private suspend fun handleMergeRequest(output: OutputStream, streamPair: StreamPair) {
        try {
            // Create simple HLS playlist instead of DASH
            val hlsPlaylist = createHlsPlaylist(streamPair.videoUrl, streamPair.audioUrl)
            
            val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${hlsPlaylist.length}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    hlsPlaylist
            
            output.write(response.toByteArray())
            output.flush()
            
        } catch (e: Exception) {
            Log.e("YouTubeProxyServer", "Error creating HLS response: ${e.message}")
            send500(output)
        }
    }
    
    private fun createHlsPlaylist(videoUrl: String, audioUrl: String): String {
        return """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
$videoUrl
#EXT-X-STREAM-INF:BANDWIDTH=128000
$audioUrl"""
    }
    
    private fun send404(output: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }
    
    private fun send500(output: OutputStream) {
        val response = "HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }
    
    fun stop() {
        isRunning = false
        try {
            server?.close()
        } catch (e: Exception) {
            Log.e("YouTubeProxyServer", "Error stopping server: ${e.message}")
        }
        streamMap.clear()
    }
}