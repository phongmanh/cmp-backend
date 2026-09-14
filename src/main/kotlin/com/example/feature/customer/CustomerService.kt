package com.example.feature.customer

import com.example.common.ConflictException
import com.example.common.ResourceNotFoundException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The owner is always the caller: every [UUID] named `ownerId` here comes from the verified token,
 * never from a body, a query parameter or a header.
 */
class CustomerService(
    private val customerRepository: CustomerRepository,
) {
    private val logger = LoggerFactory.getLogger(CustomerService::class.java)

    suspend fun create(
        ownerId: UUID,
        details: CustomerDetails,
    ): Customer {
        val customer =
            try {
                customerRepository.create(ownerId, details.normalized())
            } catch (_: DuplicateCustomerEmailException) {
                throw ConflictException(EMAIL_TAKEN)
            }

        // Identifiers only. A customer's name and contact details are personal data.
        logger.info("Created customer {} for user {}", customer.id, ownerId)
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
    }
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
