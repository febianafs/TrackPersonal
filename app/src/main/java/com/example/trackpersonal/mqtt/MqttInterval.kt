package com.example.trackpersonal.utils

object MqttInterval {
    // Urutan harus match labels() dan secondsAt()
    private val OPTIONS_SECONDS = listOf(10, 50, 60, 120, 180, 240, 300)

    fun labels(): List<String> = listOf(
        "10 seconds",
        "50 seconds",
        "1 minute",
        "2 minute",
        "3 minute",
        "4 minute",
        "5 minute"
    )

    fun allSeconds(): List<Int> = OPTIONS_SECONDS

    fun labelFor(seconds: Int): String {
        val idx = OPTIONS_SECONDS.indexOf(seconds)
        return if (idx >= 0) labels()[idx] else "${seconds}s"
    }

    fun secondsAt(index: Int): Int = OPTIONS_SECONDS.getOrElse(index) { 10 }
}
