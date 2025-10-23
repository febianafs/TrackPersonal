package com.example.trackpersonal.mqtt

import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5Connect
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class MqttEngine(
    host: String,
    portWs: Int,
    private val username: String,
    private val password: String,
    clientId: String
) {
    val client: Mqtt5AsyncClient = MqttClient.builder()
        .useMqttVersion5()
        .identifier(clientId)
        .serverHost(host)
        .serverPort(portWs)
        .webSocketConfig() // pakai WS
        .serverPath("/") // sesuaikan jika broker pakai path lain
        .applyWebSocketConfig()
        .automaticReconnect() // auto reconnect dengan backoff default
        .initialDelay(1, TimeUnit.SECONDS)
        .maxDelay(30, TimeUnit.SECONDS)
        .applyAutomaticReconnect()
        .buildAsync()

    fun connectWithLwt(lwtTopic: String, lwtPayload: String) =
        client.connect(
            Mqtt5Connect.builder()
                .keepAlive(30)
                .cleanStart(false)
                .sessionExpiryInterval(7 * 24 * 3600) // 7 hari
                .simpleAuth(
                    Mqtt5SimpleAuth.builder()
                        .username(username)
                        .password(password.toByteArray())
                        .build()
                )
                .willPublish( // Last Will: status offline
                    Mqtt5Publish.builder()
                        .topic(lwtTopic)
                        .qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE) // QoS 1
                        .retain(true)
                        .payload(lwtPayload.toByteArray(StandardCharsets.UTF_8))
                        .build()
                )
                .build()
        )
}
