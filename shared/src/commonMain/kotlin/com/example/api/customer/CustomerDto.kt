package com.example.api.customer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a customer stands with the account that keeps them. Unknown values are rejected by the
 * deserializer rather than defaulted to one of these.
 */
@Serializable
enum class CustomerStatus {
    @SerialName("lead")
    LEAD,

    @SerialName("active")
    ACTIVE,

    @SerialName("inactive")
    INACTIVE,
    ;

    /** The wire form, which is also the value that appears in [CustomerResponse.status]. */
    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(key: String): CustomerStatus? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A postal address. Either the whole object is absent or [line1], [city] and [countryCode] are
 * there: half an address cannot be posted to, so the server does not accept one.
 */
@Serializable
data class CustomerAddress(
    val line1: String,
    val line2: String? = null,
    val city: String,
    /** State, province, county or prefecture — whatever the country subdivides into. */
    val region: String? = null,
    val postalCode: String? = null,
    /** ISO 3166-1 alpha-2, upper case: `GB`, `US`, `VN`. */
    val countryCode: String,
)

/**
 * Sent to `POST /api/v1/customers` to create a customer, and to `PUT /api/v1/customers/{customerId}`
 * to replace one.
 *
 * Replace semantics, not a patch: every field is written exactly as it arrives, so an omitted
 * optional field clears the stored value. [status] has no default for the same reason an unknown
 * value is refused — a client that forgot to send it would otherwise quietly reactivate a customer
 * somebody had marked inactive.
 */
@Serializable
data class CustomerRequest(
    val firstName: String,
    val lastName: String? = null,
    val companyName: String? = null,
    /** Stored trimmed and lowercased, and unique among the caller's customers. */
    val email: String? = null,
    /** E.164: a `+`, the country code, then the number, with no spaces — `+447700900123`. */
    val phone: String? = null,
    val address: CustomerAddress? = null,
    val notes: String? = null,
    val status: CustomerStatus,
)

/**
 * The only shape a customer is ever published in. The owning account and the soft delete column
 * stay behind.
 */
@Serializable
data class CustomerResponse(
    val id: String,
    val firstName: String,
    val lastName: String?,
    val companyName: String?,
    val email: String?,
    val phone: String?,
    val address: CustomerAddress?,
    val notes: String?,
    /**
     * A [CustomerStatus.key]. A string rather than the enum so that a client built today still
     * decodes a response after a status is added.
     */
    val status: String,
    /** ISO-8601 in UTC. */
    val createdAt: String,
)
