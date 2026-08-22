package com.example.common

/**
 * Personal data in logs is masked: `user@example.com` becomes `u***@example.com`.
 */
fun String.maskEmail(): String {
    val at = indexOf('@')
    if (at <= 0) return "***"
    return "${first()}***${substring(at)}"
}
