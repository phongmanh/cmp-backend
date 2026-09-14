package com.example.common

import com.example.api.common.FieldLimits
import com.example.api.common.PageQuery
import io.ktor.server.application.ApplicationCall

/**
 * `?limit=` for any list endpoint: [FieldLimits.DEFAULT_PAGE_SIZE] when absent.
 *
 * Refused rather than clamped when it is out of range, so a client asking for more than it may have
 * finds out, instead of receiving fewer rows and mistaking the short page for the end of the list.
 */
fun ApplicationCall.pageLimit(): Int {
    val raw = request.queryParameters[PageQuery.LIMIT] ?: return FieldLimits.DEFAULT_PAGE_SIZE
    return raw.toIntOrNull()?.takeIf { it in 1..FieldLimits.MAX_PAGE_SIZE }
        ?: throw ValidationException("The limit must be a whole number from 1 to ${FieldLimits.MAX_PAGE_SIZE}.")
}

/**
 * `?cursor=` as sent, or null for the first page. Only the length is checked here; what a cursor
 * decodes to belongs to the list that issued it.
 */
fun ApplicationCall.pageCursor(): String? {
    val raw = request.queryParameters[PageQuery.CURSOR] ?: return null
    if (raw.isEmpty() || raw.length > FieldLimits.MAX_CURSOR_LENGTH) throw ValidationException(INVALID_CURSOR)
    return raw
}

const val INVALID_CURSOR = "The cursor is not one this server issued. Start again from the first page."
