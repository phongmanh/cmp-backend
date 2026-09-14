package com.example.feature.customer

import com.example.api.common.PageResponse
import com.example.api.customer.CustomerAddress
import com.example.api.customer.CustomerRequest
import com.example.api.customer.CustomerResponse

/**
 * The crossings between the wire and the internal [Customer]. The owner id and the soft delete
 * column have no field to land in on the way out, so they cannot leak by accident.
 */
fun CustomerRequest.toDetails(): CustomerDetails =
    CustomerDetails(
        firstName = firstName,
        lastName = lastName,
        companyName = companyName,
        email = email,
        phone = phone,
        address = address?.let { PostalAddress(it.line1, it.line2, it.city, it.region, it.postalCode, it.countryCode) },
        notes = notes,
        status = status,
    )

fun Customer.toResponse(): CustomerResponse =
    CustomerResponse(
        id = id.toString(),
        firstName = details.firstName,
        lastName = details.lastName,
        companyName = details.companyName,
        email = details.email,
        phone = details.phone,
        address = details.address?.let { CustomerAddress(it.line1, it.line2, it.city, it.region, it.postalCode, it.countryCode) },
        notes = details.notes,
        status = details.status.key,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

fun CustomerPage.toResponse(): PageResponse<CustomerResponse> =
    PageResponse(
        items = customers.map { it.toResponse() },
        nextCursor = next?.encode(),
    )
