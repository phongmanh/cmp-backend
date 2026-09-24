package com.example.feature.customer

import com.example.api.common.ErrorCode
import com.example.common.BusinessRuleException
import com.example.common.ConflictException
import com.example.common.ResourceNotFoundException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The owner is always the caller: every [UUID] named `ownerId` here comes from the verified token,
 * never from a body, a query parameter or a header.
 */
class CustomerService(
    private val customerRepository: CustomerRepository,
) {
    private val logger = LoggerFactory.getLogger(CustomerService::class.java)

    /**
     * With an [idempotencyKey], a retry of a create that already happened returns that customer
     * rather than making another. The lookup first answers the ordinary retry without a write; the
     * key's primary key settles the one where both attempts arrive together.
     */
    suspend fun create(
        ownerId: UUID,
        details: CustomerDetails,
        idempotencyKey: UUID?,
    ): Customer {
        val normalized = details.normalized()
        val idempotency = idempotencyKey?.let { IdempotentCreate(it, normalized.requestHash()) }

        if (idempotency != null) {
            customerRepository.findIdempotencyRecord(ownerId, idempotency.key)?.let { return replay(ownerId, it, idempotency) }
        }

        val customer =
            try {
                customerRepository.create(ownerId, normalized, idempotency)
            } catch (_: DuplicateCustomerEmailException) {
                throw ConflictException(EMAIL_TAKEN)
            } catch (duplicate: DuplicateIdempotencyKeyException) {
                // The racing create that claimed the key has committed by now, or this would not have failed.
                val retry = idempotency ?: throw duplicate
                val claimed = customerRepository.findIdempotencyRecord(ownerId, retry.key) ?: throw duplicate
                return replay(ownerId, claimed, retry)
            }

        // Identifiers only. A customer's name and contact details are personal data.
        logger.info("Created customer {} for user {}", customer.id, ownerId)
        return customer
    }

    /** Answers with the customer as it stands now, which a later replace may have changed. */
    private suspend fun replay(
        ownerId: UUID,
        earlier: IdempotencyRecord,
        retry: IdempotentCreate,
    ): Customer {
        if (earlier.requestHash != retry.requestHash) {
            throw BusinessRuleException(ErrorCode.IDEMPOTENCY_KEY_REUSED, KEY_REUSED)
        }
        val customer = customerRepository.findById(ownerId, earlier.customerId) ?: throw ConflictException(CREATED_THEN_DELETED)
        logger.info("Replayed create of customer {} for user {}", customer.id, ownerId)
        return customer
    }

    /**
     * Another account's customer answers exactly like one that was never created, so an id cannot be
     * used to learn that a customer exists.
     */
    suspend fun customerOf(
        ownerId: UUID,
        customerId: UUID,
    ): Customer = customerRepository.findById(ownerId, customerId) ?: throw ResourceNotFoundException(NOT_FOUND)

    suspend fun list(
        ownerId: UUID,
        query: CustomerListQuery,
    ): CustomerPage = customerRepository.findPage(ownerId, query)

    /** Every field is replaced, so a null clears the stored value. */
    suspend fun update(
        ownerId: UUID,
        customerId: UUID,
        details: CustomerDetails,
    ): Customer {
        val customer =
            try {
                customerRepository.update(ownerId, customerId, details.normalized())
            } catch (_: DuplicateCustomerEmailException) {
                throw ConflictException(EMAIL_TAKEN)
            } ?: throw ResourceNotFoundException(NOT_FOUND)

        logger.info("Updated customer {} for user {}", customer.id, ownerId)
        return customer
    }

    /**
     * Soft, so a customer deleted by mistake can be recovered and the record stays auditable. Their
     * email is free for a new customer straight away.
     */
    suspend fun delete(
        ownerId: UUID,
        customerId: UUID,
    ) {
        if (!customerRepository.softDelete(ownerId, customerId)) {
            throw ResourceNotFoundException(NOT_FOUND)
        }
        logger.info("Deleted customer {} for user {}", customerId, ownerId)
    }

    private companion object {
        const val NOT_FOUND = "Customer not found."
        const val EMAIL_TAKEN = "You already have a customer with that email address."
        const val KEY_REUSED = "That Idempotency-Key was already used for a different customer. Send a new key."
        const val CREATED_THEN_DELETED = "The customer that Idempotency-Key created has since been deleted."
    }
}

/**
 * Every field, each written as its length and then its value, so no two different requests can
 * spell the same string: a null reads as `-`, which no length does, and a value cannot bleed into
 * the next field. Hashed after [normalized], so a retry that only differs in case or spacing of
 * the email still matches.
 */
private fun CustomerDetails.requestHash(): String {
    val fields =
        listOf(
            firstName,
            lastName,
            companyName,
            email,
            phone,
            address?.line1,
            address?.line2,
            address?.city,
            address?.region,
            address?.postalCode,
            address?.countryCode,
            notes,
            status.key,
        )
    val canonical = fields.joinToString("") { field -> field?.let { "${it.length}:$it" } ?: "-" }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(digest)
}

/**
 * Validation has already refused blank values, so trimming cannot turn a present field into an
 * empty one. The email is lowercased because the unique index compares it as stored.
 */
private fun CustomerDetails.normalized(): CustomerDetails =
    copy(
        firstName = firstName.trim(),
        lastName = lastName?.trim(),
        companyName = companyName?.trim(),
        email = email?.trim()?.lowercase(),
        phone = phone?.trim(),
        address =
            address?.let {
                it.copy(
                    line1 = it.line1.trim(),
                    line2 = it.line2?.trim(),
                    city = it.city.trim(),
                    region = it.region?.trim(),
                    postalCode = it.postalCode?.trim(),
                    countryCode = it.countryCode.trim(),
                )
            },
        notes = notes?.trim(),
    )
