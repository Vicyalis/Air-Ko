package me.rerere.ai.util

/** 带 HTTP 状态码的供应商错误：用于区分「可重试的限流/服务端错误」和普通失败。 */
class HttpStatusException(
    val code: Int,
    message: String,
) : RuntimeException(message)
