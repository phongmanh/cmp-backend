package com.example.api.common

import kotlinx.serialization.Serializable

/**
 * One page of a list. Every list this API returns is bounded, so a client walks a long one by
 * sending [nextCursor] back as `?cursor=` until it comes back null.
 */
@Serializable
data class PageResponse<T>(
    val items: List<T>,
    /** Opaque. Send it back unchanged; what is inside may change between releases. */
    val nextCursor: String?,
)

/** The query parameters every list endpoint reads. */
object PageQuery {
    const val LIMIT = "limit"
    const val CURSOR = "cursor"
}
