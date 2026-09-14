package com.example.feature.customer

import com.example.api.ApiRoutes
import com.example.api.common.FieldLimits
import com.example.api.common.PageQuery
import com.example.api.customer.CustomerAddress
import com.example.api.customer.CustomerRequest
import com.example.api.customer.CustomerStatus
import com.example.common.BodyExample
import com.example.common.INVALID_CURSOR
import com.example.common.ValidationException
import com.example.common.badRequest
import com.example.common.conflict
import com.example.common.internalError
import com.example.common.jsonBody
import com.example.common.notFound
import com.example.common.pageCursor
import com.example.common.pageLimit
import com.example.common.payloadTooLarge
import com.example.common.requireUserId
import com.example.common.unauthorized
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ExperimentalKtorApi
import org.koin.ktor.ext.inject
import java.util.UUID

private const val CUSTOMERS_TAG = "Customers"

private val STATUS_KEYS = CustomerStatus.entries.joinToString { "`${it.key}`" }

private fun customerExamples() =
    arrayOf(
        BodyExample(
            "minimal",
            "A first name and a status are all that is required",
            CustomerRequest(firstName = "Ada", status = CustomerStatus.LEAD),
        ),
        BodyExample(
            "full",
            "Every field",
            CustomerRequest(
                firstName = "Ada",
                lastName = "Lovelace",
                companyName = "Analytical Engines Ltd",
                email = "ada@example.com",
                phone = "+447700900123",
                address =
                    CustomerAddress(
                        line1 = "12 St James's Square",
                        line2 = "Floor 2",
                        city = "London",
                        region = "Greater London",
                        postalCode = "SW1Y 4JH",
                        countryCode = "GB",
                    ),
                notes = "Prefers email. Renewal due in March.",
                status = CustomerStatus.ACTIVE,
            ),
        ),
    )

/**
 * A customer belongs to the account that created it, and nothing here reads across accounts. Every
 * route is behind a token, and the owner is always the token's subject.
 */
@OptIn(ExperimentalKtorApi::class)
fun Application.customerRoutes() {
    val customerService by inject<CustomerService>()

    routing {
        authenticate("auth-jwt") {
            post(ApiRoutes.Customers.PATH) {
                val request = call.receive<CustomerRequest>()
                val customer = customerService.create(call.requireUserId(), request.toDetails())
                call.response.headers.append(HttpHeaders.Location, ApiRoutes.Customers.byId(customer.id.toString()))
                call.respond(HttpStatusCode.Created, customer.toResponse())
            }.describe {
                tag(CUSTOMERS_TAG)
                operationId = "createCustomer"
                summary = "Create a customer"
                requestBody { jsonBody(*customerExamples()) }
                description =
                    """
                    Creates a customer owned by the signed-in account and returns it. There is no field for an
                    owner: the customer always belongs to the token's subject.

                    `email` is trimmed and lowercased before it is stored, and must be unique among your own
                    live customers. Another account holding the same address makes no difference.
                    """.trimIndent()
                responses {
                    HttpStatusCode.Created {
                        description = "The customer was created."
                        headers {
                            header("Location") {
                                description = "Path of the customer that was created."
                                required = true
                            }
                        }
                    }
                    badRequest()
                    unauthorized()
                    conflict("You already have a customer with that email address.")
                    payloadTooLarge()
                    internalError()
                }
            }

            get(ApiRoutes.Customers.PATH) {
                val page = customerService.list(call.requireUserId(), call.customerListQuery())
                call.respond(HttpStatusCode.OK, page.toResponse())
            }.describe {
                tag(CUSTOMERS_TAG)
                operationId = "listCustomers"
                summary = "List the signed-in account's customers"
                description =
                    """
                    Newest first. To read the next page, send `nextCursor` back as `?cursor=`, keeping every
                    other parameter the same; `nextCursor` is `null` on the last page. A page boundary never
                    skips or repeats a customer, even while others are being added.

                    Deleted customers are never listed.
                    """.trimIndent()
                parameters {
                    query(PageQuery.LIMIT) {
                        description =
                            "Customers per page, from 1 to ${FieldLimits.MAX_PAGE_SIZE}. Defaults to ${FieldLimits.DEFAULT_PAGE_SIZE}."
                        schema = jsonSchema<Int>()
                    }
                    query(PageQuery.CURSOR) {
                        description = "The `nextCursor` from the previous page. Omit it for the first page."
                        schema = jsonSchema<String>()
                    }
                    query(ApiRoutes.Customers.STATUS) {
                        description = "Only customers with this status: one of $STATUS_KEYS."
                        schema = jsonSchema<String>()
                    }
                    query(ApiRoutes.Customers.SEARCH) {
                        description =
                            """
                            Case-insensitive prefix of the first name, last name, company name or email, at most
                            ${FieldLimits.MAX_CUSTOMER_SEARCH_LENGTH} characters. `ada` finds Ada Lovelace; `love`
                            finds her too, through the last name; `ovelace` finds nobody.
                            """.trimIndent()
                        schema = jsonSchema<String>()
                    }
                }
                responses {
                    HttpStatusCode.OK { description = "One page of customers." }
                    badRequest("`limit` is out of range, `status` is unknown, `q` is too long, or `cursor` is not one we issued.")
                    unauthorized()
                    internalError()
                }
            }

            get(ApiRoutes.Customers.BY_ID) {
                val customer = customerService.customerOf(call.requireUserId(), call.customerIdPathParameter())
                call.respond(HttpStatusCode.OK, customer.toResponse())
            }.describe {
                tag(CUSTOMERS_TAG)
                operationId = "getCustomer"
                summary = "Read a customer"
                description =
                    """
                    The address `POST /api/v1/customers` returns in its `Location` header. A customer that
                    belongs to another account answers `404` rather than `403`, exactly like one that never
                    existed, so an id cannot be used to learn what other accounts hold.
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK { description = "The customer." }
                    badRequest("The id is not a UUID.")
                    unauthorized()
                    notFound("No such customer among yours, or it was deleted.")
                    internalError()
                }
            }

            put(ApiRoutes.Customers.BY_ID) {
                val customerId = call.customerIdPathParameter()
                val request = call.receive<CustomerRequest>()
                val customer = customerService.update(call.requireUserId(), customerId, request.toDetails())
                call.respond(HttpStatusCode.OK, customer.toResponse())
            }.describe {
                tag(CUSTOMERS_TAG)
                operationId = "replaceCustomer"
                summary = "Replace a customer"
                requestBody { jsonBody(*customerExamples()) }
                description =
                    """
                    Writes every field exactly as it arrives, so an optional field left out or sent as `null`
                    clears the stored value rather than leaving it alone. Read the customer, change what you
                    need, and send the whole thing back.

                    `status` is required here too: a missing one is refused rather than defaulted, because
                    defaulting would quietly reactivate a customer somebody had marked inactive.
                    """.trimIndent()
                responses {
                    HttpStatusCode.OK { description = "The customer as it now stands." }
                    badRequest()
                    unauthorized()
                    notFound("No such customer among yours, or it was deleted.")
                    conflict("Another of your customers already has that email address.")
                    payloadTooLarge()
                    internalError()
                }
            }

            delete(ApiRoutes.Customers.BY_ID) {
                customerService.delete(call.requireUserId(), call.customerIdPathParameter())
                call.respond(HttpStatusCode.NoContent)
            }.describe {
                tag(CUSTOMERS_TAG)
                operationId = "deleteCustomer"
                summary = "Delete a customer"
                description =
                    """
                    The customer stops appearing anywhere in the API at once, and its email can be given to a new
                    customer straight away. The record is kept server side rather than erased.

                    Deleting a customer that is already deleted answers `404`.
                    """.trimIndent()
                responses {
                    HttpStatusCode.NoContent { description = "Deleted. No body." }
                    badRequest("The id is not a UUID.")
                    unauthorized()
                    notFound("No such customer among yours, or it was already deleted.")
                    internalError()
                }
            }
        }
    }
}

/**
 * Read from the query string alone. Everything is checked here, before the service sees it, and an
 * unknown status is refused rather than ignored: silently listing every status would look like a
 * filter that worked.
 */
private fun ApplicationCall.customerListQuery(): CustomerListQuery {
    val query = request.queryParameters

    val status =
        query[ApiRoutes.Customers.STATUS]?.let { raw ->
            CustomerStatus.fromKey(raw)
                ?: throw ValidationException("The status must be one of ${CustomerStatus.entries.joinToString { it.key }}.")
        }

    val search = query[ApiRoutes.Customers.SEARCH]?.trim()?.takeIf { it.isNotEmpty() }
    if (search != null && search.length > FieldLimits.MAX_CUSTOMER_SEARCH_LENGTH) {
        throw ValidationException("The search must be at most ${FieldLimits.MAX_CUSTOMER_SEARCH_LENGTH} characters.")
    }

    return CustomerListQuery(
        limit = pageLimit(),
        after = pageCursor()?.let { CustomerCursor.decode(it) ?: throw ValidationException(INVALID_CURSOR) },
        status = status,
        search = search,
    )
}

/**
 * Read from the path and nowhere else: [RoutingCall.parameters] merges the query string in, so a
 * `?customerId=` could otherwise stand in for the segment the route matched.
 */
private fun RoutingCall.customerIdPathParameter(): UUID {
    val raw = pathParameters[ApiRoutes.Customers.CUSTOMER_ID] ?: throw ValidationException("A customer id is required.")
    return runCatching { UUID.fromString(raw) }
        .getOrElse { throw ValidationException("A customer id must be a UUID.") }
}
