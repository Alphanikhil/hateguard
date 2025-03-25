package com.example.nikkuisgoodboy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nikkuisgoodboy.R
import com.example.nikkuisgoodboy.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean

class ContentFilterVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var vpnThread: Thread? = null
    private var dnsServerJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        preferenceManager = PreferenceManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_CONNECT -> {
                if (!isRunning.get()) {
                    // Start VPN service
                    startVpn()
                }
            }
            ACTION_DISCONNECT -> {
                if (isRunning.get()) {
                    // Stop VPN service
                    stopVpn()
                }
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startVpn() {
        // Create notification channel
        createNotificationChannel()

        // Start as foreground service with notification
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Configure and establish VPN connection
        val builder = createVpnInterface()
        vpnInterface = builder.establish()

        if (vpnInterface != null) {
            isRunning.set(true)

            // Start processing VPN traffic
            vpnThread = Thread {
                processVpnTraffic()
            }
            vpnThread?.start()

            // Start DNS server for content filtering
            startDnsServer()
        } else {
            Log.e(TAG, "Failed to establish VPN connection")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Content Filter VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for filtering network requests"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun createVpnInterface(): Builder {
        return Builder().apply {
            // Set VPN address and routes
            addAddress("10.0.0.2", 32)
            addRoute("0.0.0.0", 0)

            // Add DNS server (our local DNS proxy)
            addDnsServer("10.0.0.1")

            // Exclude apps if needed
            for (packageName in preferenceManager.getWhitelistedApps()) {
                try {
                    addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add disallowed app: $packageName", e)
                }
            }

            // Set session name and MTU
            setSession(getString(R.string.vpn_service_name))
            setMtu(1500)

            // Allow traffic to bypass VPN when screen is off (optional)
            allowBypass()

            // Allow family traffic for basic operation
            allowFamily(1) // 1 represents IPv4 family
        }
    }

    private fun processVpnTraffic() {
        val fileDescriptor = vpnInterface?.fileDescriptor ?: return

        val inputStream = FileInputStream(fileDescriptor)
        val outputStream = FileOutputStream(fileDescriptor)

        val buffer = ByteBuffer.allocate(32767)

        try {
            while (isRunning.get()) {
                // Read incoming packets
                buffer.clear()
                val length = inputStream.channel.read(buffer)

                if (length > 0) {
                    buffer.flip()

                    // TODO: Implement packet inspection and filtering
                    // For now, we'll just forward all traffic

                    // For advanced implementation:
                    // 1. Inspect DNS requests to check for NSFW domains
                    // 2. Inspect HTTP/HTTPS requests to check for NSFW URLs or content-type
                    // 3. Block or allow based on the inspection result

                    // Write outgoing packets
                    outputStream.channel.write(buffer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing VPN traffic", e)
        } finally {
            try {
                inputStream.close()
                outputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing streams", e)
            }
        }
    }

    private fun startDnsServer() {
        dnsServerJob = serviceScope.launch {
            try {
                val channel = DatagramChannel.open()
                channel.socket().bind(InetSocketAddress(5353))

                val buffer = ByteBuffer.allocate(1024)

                while (isRunning.get()) {
                    buffer.clear()
                    val clientAddress = channel.receive(buffer)

                    if (clientAddress != null) {
                        buffer.flip()

                        // TODO: Implement DNS request inspection and filtering
                        // For advanced implementation:
                        // 1. Parse DNS query
                        // 2. Check if the domain is potentially NSFW
                        // 3. If NSFW, return blocked IP or redirect
                        // 4. If not NSFW, forward to a real DNS server and return the response

                        // For now, we'll use Google's DNS as a fallback
                        forwardDnsRequest(buffer, channel, clientAddress as InetSocketAddress)
                    }
                }

                channel.close()
            } catch (e: Exception) {
                Log.e(TAG, "DNS server error", e)
            }
        }
    }

    private suspend fun forwardDnsRequest(
        buffer: ByteBuffer,
        localChannel: DatagramChannel,
        clientAddress: InetSocketAddress
    ) {
        try {
            // Send to Google DNS (8.8.8.8)
            val forwardChannel = DatagramChannel.open()
            forwardChannel.connect(InetSocketAddress("8.8.8.8", 53))

            // Send DNS query
            buffer.position(0)
            forwardChannel.write(buffer)

            // Get response
            buffer.clear()
            forwardChannel.read(buffer)

            // Send response back to client
            buffer.flip()
            localChannel.send(buffer, clientAddress)

            forwardChannel.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding DNS request", e)
        }
    }

    private fun stopVpn() {
        isRunning.set(false)

        dnsServerJob?.cancel()
        dnsServerJob = null

        vpnThread?.interrupt()
        vpnThread = null

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    companion object {
        private const val TAG = "ContentFilterVpnService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "hateguard_vpn_service"

        const val ACTION_CONNECT = "com.example.nikkuisgoodboy.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.nikkuisgoodboy.action.DISCONNECT"
    }
}