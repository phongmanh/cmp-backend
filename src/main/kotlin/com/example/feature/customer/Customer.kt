package com.example.feature.customer

import com.example.api.customer.CustomerStatus
import java.time.Instant
import java.util.UUID

data class Customer(
    val id: UUID,
    val ownerId: UUID,
    val details: CustomerDetails,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Everything about a customer the caller writes, as opposed to what the server assigns. */
data class CustomerDetails(
    val firstName: String,
    val lastName: String?,
    val companyName: String?,
    val email: String?,
    val phone: String?,
    val address: PostalAddress?,
    val notes: String?,
    val status: CustomerStatus,
)

data class PostalAddress(
    val line1: String,
    val line2: String?,
    val city: String,
    val region: String?,
    val postalCode: String?,
    val countryCode: String,
)

/** At most [limit] of one owner's live customers, newest first, starting after [after]. */
data class CustomerListQuery(
    val limit: Int,
    val after: CustomerCursor?,
    val status: CustomerStatus?,
    /** Already trimmed, and never blank: a blank search is no search. */
    val search: String?,
)

data class CustomerPage(
    val customers: List<Customer>,
    /** Where the next page starts, or null when this one reached the end. */
    val next: CustomerCursor?,
)

/**
 * What an `Idempotency-Key` stands for: the create it was first sent with, reduced to [requestHash]
 * so a retry can be told apart from a different request that reused the key.
 */
data class IdempotentCreate(
    val key: UUID,
    val requestHash: String,
)

/** The customer an earlier create made under a key, and the hash of the request that made it. */
data class IdempotencyRecord(
    val customerId: UUID,
    val requestHash: String,
)

/** The repository's way of saying another request already claimed that owner's key. */
class DuplicateIdempotencyKeyException : RuntimeException("The owner already used that idempotency key.")

/**
 * The repository's way of saying the owner already has a live customer with that email. Kept free
 * of any HTTP meaning: the service decides that it is a conflict and what to tell the caller.
 */
class DuplicateCustomerEmailException : RuntimeException("The owner already has a customer with that email.")
