package com.example.feature.customer

import com.example.api.ApiRoutes
import com.example.api.auth.TokenResponse
import com.example.api.common.ErrorCode
import com.example.api.common.ErrorResponse
import com.example.api.common.FieldLimits
import com.example.api.common.PageQuery
import com.example.api.common.PageResponse
import com.example.api.customer.CustomerAddress
import com.example.api.customer.CustomerRequest
import com.example.api.customer.CustomerResponse
import com.example.api.customer.CustomerStatus
import com.example.feature.auth.register
import com.example.support.authTestApplication
import com.example.support.jsonClient
import com.example.support.uniqueEmail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VALID_PASSWORD = "a-long-enough-password"
private const val MISSING_ID = "11111111-1111-1111-1111-111111111111"

private val LONDON =
    CustomerAddress(
        line1 = "12 St James's Square",
        line2 = "Floor 2",
        city = "London",
        region = "Greater London",
        postalCode = "SW1Y 4JH",
        countryCode = "GB",
    )

private fun fullRequest(email: String = "ada@example.com") =
    CustomerRequest(
        firstName = "Ada",
        lastName = "Lovelace",
        companyName = "Analytical Engines Ltd",
        email = email,
        phone = "+447700900123",
        address = LONDON,
        notes = "Prefers email.",
        status = CustomerStatus.ACTIVE,
    )

class CustomerRoutesTest {
    @Test
    fun `creates a customer and says where to read it`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()

            val created = client.createCustomer(token, fullRequest(email = "  Ada@Example.COM "))

            assertEquals(HttpStatusCode.Created, created.status)
            val body = created.body<CustomerResponse>()
            assertEquals("Ada", body.firstName)
            assertEquals("Lovelace", body.lastName)
            assertEquals("Analytical Engines Ltd", body.companyName)
            assertEquals("ada@example.com", body.email, "the email is trimmed and lowercased")
            assertEquals("+447700900123", body.phone)
            assertEquals(LONDON, body.address)
            assertEquals("Prefers email.", body.notes)
            assertEquals("active", body.status)
            Instant.parse(body.createdAt)
            assertEquals(body.createdAt, body.updatedAt, "a customer nobody has replaced was last updated when it was created")

            val location = assertNotNull(created.headers[HttpHeaders.Location], "create must say where the customer can be read")
            val reread = client.get(location) { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, reread.status)
            assertEquals(body, reread.body<CustomerResponse>())
        }

    @Test
    fun `a first name and a status are enough`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()

            val response = client.createCustomer(token, CustomerRequest(firstName = "Ada", status = CustomerStatus.LEAD))

            assertEquals(HttpStatusCode.Created, response.status)
            val body = response.body<CustomerResponse>()
            assertNull(body.email)
            assertNull(body.address)
            assertEquals("lead", body.status)
        }

    @Test
    fun `returns 401 when creating without a token`() =
        authTestApplication {
            val response =
                jsonClient().post(ApiRoutes.Customers.PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(fullRequest())
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("UNAUTHENTICATED", response.body<ErrorResponse>().code)
        }

    @Test
    fun `returns 401 on every customer route without a token`() =
        authTestApplication {
            val client = jsonClient()
            val byId = ApiRoutes.Customers.byId(MISSING_ID)

            val responses =
                listOf(
                    client.get(ApiRoutes.Customers.PATH),
                    client.get(byId),
                    client.put(byId) {
                        contentType(ContentType.Application.Json)
                        setBody(fullRequest())
                    },
                    client.delete(byId),
                )

            responses.forEach {
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    it.status,
                    "${it.call.request.method.value} ${it.call.request.url}",
                )
            }
        }

    @Test
    fun `rejects a blank first name`() =
        authTestApplication {
            val client = jsonClient()
            val response = client.createCustomer(client.signedIn(), fullRequest().copy(firstName = "   "))

            assertValidationError(response)
        }

    @Test
    fun `rejects a first name longer than the column holds`() =
        authTestApplication {
            val client = jsonClient()
            val tooLong = "a".repeat(FieldLimits.MAX_CUSTOMER_NAME_LENGTH + 1)

            val response = client.createCustomer(client.signedIn(), fullRequest().copy(firstName = tooLong))

            assertValidationError(response)
        }

    @Test
    fun `rejects an email that is not an address`() =
        authTestApplication {
            val client = jsonClient()
            val response = client.createCustomer(client.signedIn(), fullRequest(email = "not-an-email"))

            assertValidationError(response)
        }

    @Test
    fun `rejects a phone number that is not E164`() =
        authTestApplication {
            val client = jsonClient()
            val response = client.createCustomer(client.signedIn(), fullRequest().copy(phone = "07700 900123"))

            assertValidationError(response)
        }

    @Test
    fun `rejects a country code that does not exist`() =
        authTestApplication {
            val client = jsonClient()
            val response =
                client.createCustomer(client.signedIn(), fullRequest().copy(address = LONDON.copy(countryCode = "ZZ")))

            assertValidationError(response)
        }

    @Test
    fun `rejects an address with a blank city`() =
        authTestApplication {
            val client = jsonClient()
            val response =
                client.createCustomer(client.signedIn(), fullRequest().copy(address = LONDON.copy(city = "")))

            assertValidationError(response)
        }

    @Test
    fun `rejects a status it does not know rather than defaulting it`() =
        authTestApplication {
            val client = jsonClient()
            val response =
                client.post(ApiRoutes.Customers.PATH) {
                    bearerAuth(client.signedIn())
                    contentType(ContentType.Application.Json)
                    setBody("""{"firstName":"Ada","status":"vip"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_REQUEST", response.body<ErrorResponse>().code)
        }

    @Test
    fun `rejects a request that leaves the status out`() =
        authTestApplication {
            val client = jsonClient()
            val response =
                client.post(ApiRoutes.Customers.PATH) {
                    bearerAuth(client.signedIn())
                    contentType(ContentType.Application.Json)
                    setBody("""{"firstName":"Ada"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("INVALID_REQUEST", response.body<ErrorResponse>().code)
        }

    @Test
    fun `another account's customer reads, replaces and deletes as missing`() =
        authTestApplication {
            val client = jsonClient()
            val theirs = client.signedIn()
            val customer = client.createCustomer(theirs, fullRequest()).body<CustomerResponse>()
            val mine = client.signedIn()
            val path = ApiRoutes.Customers.byId(customer.id)

            val read = client.get(path) { bearerAuth(mine) }
            val replaced = client.replaceCustomer(mine, customer.id, fullRequest().copy(firstName = "Mallory"))
            val deleted = client.delete(path) { bearerAuth(mine) }

            listOf(read, replaced, deleted).forEach { response ->
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("NOT_FOUND", response.body<ErrorResponse>().code)
            }
            val untouched = client.get(path) { bearerAuth(theirs) }
            assertEquals(HttpStatusCode.OK, untouched.status)
            assertEquals("Ada", untouched.body<CustomerResponse>().firstName)
        }

    @Test
    fun `lists only the caller's own customers`() =
        authTestApplication {
            val client = jsonClient()
            val mine = client.signedIn()
            val theirs = client.signedIn()
            val own = client.createCustomer(mine, fullRequest()).body<CustomerResponse>()
            client.createCustomer(theirs, fullRequest())

            val page = client.listCustomers(mine)

            assertEquals(listOf(own.id), page.items.map { it.id })
            assertNull(page.nextCursor)
        }

    @Test
    fun `pages through every customer exactly once, newest first`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val created =
                (1..5).map { n ->
                    client
                        .createCustomer(
                            token,
                            CustomerRequest(firstName = "Customer $n", status = CustomerStatus.ACTIVE),
                        ).body<CustomerResponse>()
                }

            val pages = mutableListOf<PageResponse<CustomerResponse>>()
            var cursor: String? = null
            do {
                val page = client.listCustomers(token, PageQuery.LIMIT to "2", PageQuery.CURSOR to cursor)
                pages += page
                cursor = page.nextCursor
            } while (cursor != null)

            assertEquals(listOf(2, 2, 1), pages.map { it.items.size })
            assertEquals(created.reversed().map { it.id }, pages.flatMap { it.items }.map { it.id })
        }

    @Test
    fun `a page that ends exactly at the last customer has no next cursor`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            repeat(2) { client.createCustomer(token, CustomerRequest(firstName = "Ada", status = CustomerStatus.ACTIVE)) }

            val page = client.listCustomers(token, PageQuery.LIMIT to "2")

            assertEquals(2, page.items.size)
            assertNull(page.nextCursor)
        }

    @Test
    fun `rejects a limit outside the allowed range`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()

            listOf("0", "${FieldLimits.MAX_PAGE_SIZE + 1}", "ten").forEach { limit ->
                val response =
                    client.get(ApiRoutes.Customers.PATH) {
                        bearerAuth(token)
                        parameter(PageQuery.LIMIT, limit)
                    }
                assertValidationError(response)
            }
        }

    @Test
    fun `rejects a cursor it did not issue`() =
        authTestApplication {
            val client = jsonClient()
            val response =
                client.get(ApiRoutes.Customers.PATH) {
                    bearerAuth(client.signedIn())
                    parameter(PageQuery.CURSOR, "made-up")
                }

            assertValidationError(response)
        }

    @Test
    fun `filters by status`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val lead =
                client
                    .createCustomer(
                        token,
                        CustomerRequest(firstName = "Lead", status = CustomerStatus.LEAD),
                    ).body<CustomerResponse>()
            client.createCustomer(token, CustomerRequest(firstName = "Active", status = CustomerStatus.ACTIVE))

            val page = client.listCustomers(token, ApiRoutes.Customers.STATUS to "lead")

            assertEquals(listOf(lead.id), page.items.map { it.id })
        }

    @Test
    fun `rejects a status filter it does not know`() =
        authTestApplication {
            val client = jsonClient()
            val response =
                client.get(ApiRoutes.Customers.PATH) {
                    bearerAuth(client.signedIn())
                    parameter(ApiRoutes.Customers.STATUS, "vip")
                }

            assertValidationError(response)
        }

    @Test
    fun `searches names, company and email by case-insensitive prefix`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val ada = client.createCustomer(token, fullRequest()).body<CustomerResponse>()
            client.createCustomer(
                token,
                CustomerRequest(firstName = "Grace", lastName = "Hopper", email = "grace@navy.example", status = CustomerStatus.ACTIVE),
            )

            listOf("ADA", "love", "analytical", "ada@").forEach { term ->
                val found = client.listCustomers(token, ApiRoutes.Customers.SEARCH to term)
                assertEquals(listOf(ada.id), found.items.map { it.id }, "searching for '$term'")
            }
            assertTrue(
                client.listCustomers(token, ApiRoutes.Customers.SEARCH to "ovelace").items.isEmpty(),
                "a search matches prefixes only",
            )
        }

    @Test
    fun `a wildcard in the search is matched literally`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            client.createCustomer(token, fullRequest())

            val page = client.listCustomers(token, ApiRoutes.Customers.SEARCH to "%")

            assertTrue(page.items.isEmpty(), "a % must not match every customer")
        }

    @Test
    fun `replaces every field and clears the ones left out`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val customer = client.createCustomer(token, fullRequest()).body<CustomerResponse>()

            val response =
                client.replaceCustomer(token, customer.id, CustomerRequest(firstName = "Augusta", status = CustomerStatus.INACTIVE))

            assertEquals(HttpStatusCode.OK, response.status)
            val replaced = response.body<CustomerResponse>()
            assertEquals(customer.id, replaced.id)
            assertEquals(customer.createdAt, replaced.createdAt)
            assertEquals("Augusta", replaced.firstName)
            assertEquals("inactive", replaced.status)
            assertNull(replaced.lastName)
            assertNull(replaced.email)
            assertNull(replaced.address)
            assertEquals(replaced, client.get(ApiRoutes.Customers.byId(customer.id)) { bearerAuth(token) }.body<CustomerResponse>())
        }

    @Test
    fun `a replacement moves updatedAt forward and leaves createdAt alone`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val customer = client.createCustomer(token, fullRequest()).body<CustomerResponse>()

            val replaced = client.replaceCustomer(token, customer.id, fullRequest().copy(notes = "Renewal due.")).body<CustomerResponse>()

            assertEquals(customer.createdAt, replaced.createdAt)
            assertTrue(
                Instant.parse(replaced.updatedAt).isAfter(Instant.parse(customer.updatedAt)),
                "updatedAt went from ${customer.updatedAt} to ${replaced.updatedAt}",
            )
        }

    @Test
    fun `a rejected replacement leaves updatedAt alone`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            client.createCustomer(token, fullRequest(email = "ada@example.com"))
            val grace = client.createCustomer(token, fullRequest(email = "grace@example.com")).body<CustomerResponse>()

            client.replaceCustomer(token, grace.id, fullRequest(email = "ada@example.com"))
            val reread = client.get(ApiRoutes.Customers.byId(grace.id)) { bearerAuth(token) }.body<CustomerResponse>()

            assertEquals(grace.updatedAt, reread.updatedAt)
        }

    @Test
    fun `returns 404 when replacing a customer that does not exist`() =
        authTestApplication {
            val client = jsonClient()
            val response = client.replaceCustomer(client.signedIn(), MISSING_ID, fullRequest())

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `rejects an invalid replacement`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val customer = client.createCustomer(token, fullRequest()).body<CustomerResponse>()

            val response = client.replaceCustomer(token, customer.id, fullRequest().copy(phone = "not a phone"))

            assertValidationError(response)
        }

    @Test
    fun `refuses a second customer with the same email, whatever its case`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            client.createCustomer(token, fullRequest(email = "ada@example.com"))

            val response = client.createCustomer(token, fullRequest(email = "ADA@example.com"))

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("CONFLICT", response.body<ErrorResponse>().code)
        }

    @Test
    fun `a retry with the same idempotency key returns the first customer instead of making another`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val key = UUID.randomUUID().toString()
            // No email, so the unique index cannot catch the duplicate: only the key can.
            val request = CustomerRequest(firstName = "Ada", status = CustomerStatus.LEAD)

            val first = client.createCustomer(token, request, key)
            val retry = client.createCustomer(token, request, key)

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.Created, retry.status)
            assertEquals(first.body<CustomerResponse>(), retry.body<CustomerResponse>())
            assertEquals(first.headers[HttpHeaders.Location], retry.headers[HttpHeaders.Location])
            assertEquals(1, client.listCustomers(token).items.size)
        }

    @Test
    fun `a retry that carries an email is replayed rather than refused as a conflict`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val key = UUID.randomUUID().toString()
            val first = client.createCustomer(token, fullRequest(), key).body<CustomerResponse>()

            val retry = client.createCustomer(token, fullRequest(email = " ADA@example.com"), key)

            assertEquals(HttpStatusCode.Created, retry.status, "a retry differing only in email case or spacing is the same request")
            assertEquals(first.id, retry.body<CustomerResponse>().id)
        }

    @Test
    fun `without an idempotency key a repeated create makes a second customer`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val request = CustomerRequest(firstName = "Ada", status = CustomerStatus.LEAD)

            client.createCustomer(token, request)
            client.createCustomer(token, request)

            assertEquals(2, client.listCustomers(token).items.size)
        }

    @Test
    fun `concurrent creates with one idempotency key make one customer`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val key = UUID.randomUUID().toString()

            val responses =
                coroutineScope {
                    List(5) { async { client.createCustomer(token, fullRequest(), key) } }.awaitAll()
                }

            assertTrue(responses.all { it.status == HttpStatusCode.Created }, "statuses: ${responses.map { it.status }}")
            assertEquals(1, responses.map { it.body<CustomerResponse>().id }.toSet().size)
            assertEquals(1, client.listCustomers(token).items.size)
        }

    @Test
    fun `refuses an idempotency key sent again with a different body`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val key = UUID.randomUUID().toString()
            client.createCustomer(token, fullRequest(), key)

            val response = client.createCustomer(token, fullRequest().copy(firstName = "Grace"), key)

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, response.body<ErrorResponse>().code)
            assertEquals(1, client.listCustomers(token).items.size)
        }

    @Test
    fun `rejects an idempotency key that is not a canonical uuid`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()

            assertValidationError(client.createCustomer(token, fullRequest(), "not-a-uuid"))
            assertValidationError(client.createCustomer(token, fullRequest(), "1-1-1-1-1"))
            assertTrue(client.listCustomers(token).items.isEmpty())
        }

    @Test
    fun `another account's identical idempotency key creates that account's own customer`() =
        authTestApplication {
            val client = jsonClient()
            val key = UUID.randomUUID().toString()
            val mine = client.createCustomer(client.signedIn(), fullRequest(), key).body<CustomerResponse>()

            val theirs = client.createCustomer(client.signedIn(), fullRequest(), key)

            assertEquals(HttpStatusCode.Created, theirs.status)
            assertNotEquals(mine.id, theirs.body<CustomerResponse>().id)
        }

    @Test
    fun `a create that fails leaves its idempotency key free for the retry`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val key = UUID.randomUUID().toString()
            val holder = client.createCustomer(token, fullRequest()).body<CustomerResponse>()
            assertEquals(HttpStatusCode.Conflict, client.createCustomer(token, fullRequest(), key).status)
            client.delete(ApiRoutes.Customers.byId(holder.id)) { bearerAuth(token) }

            val retry = client.createCustomer(token, fullRequest(), key)

            assertEquals(HttpStatusCode.Created, retry.status)
        }

    @Test
    fun `replaying a key whose customer was deleted is a conflict`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val key = UUID.randomUUID().toString()
            val customer = client.createCustomer(token, fullRequest(), key).body<CustomerResponse>()
            client.delete(ApiRoutes.Customers.byId(customer.id)) { bearerAuth(token) }

            val retry = client.createCustomer(token, fullRequest(), key)

            assertEquals(HttpStatusCode.Conflict, retry.status)
            assertTrue(client.listCustomers(token).items.isEmpty(), "a replay must not bring the customer back")
        }

    @Test
    fun `another account may keep a customer with the same email`() =
        authTestApplication {
            val client = jsonClient()
            client.createCustomer(client.signedIn(), fullRequest(email = "ada@example.com"))

            val response = client.createCustomer(client.signedIn(), fullRequest(email = "ada@example.com"))

            assertEquals(HttpStatusCode.Created, response.status)
        }

    @Test
    fun `a replacement that takes another customer's email is a conflict`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            client.createCustomer(token, fullRequest(email = "ada@example.com"))
            val grace = client.createCustomer(token, fullRequest(email = "grace@example.com")).body<CustomerResponse>()

            val response = client.replaceCustomer(token, grace.id, fullRequest(email = "ada@example.com"))

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `deletes a customer so it can no longer be read or listed`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val customer = client.createCustomer(token, fullRequest()).body<CustomerResponse>()
            val path = ApiRoutes.Customers.byId(customer.id)

            val deleted = client.delete(path) { bearerAuth(token) }

            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete(path) { bearerAuth(token) }.status)
            assertTrue(client.listCustomers(token).items.isEmpty())
        }

    @Test
    fun `a deleted customer's email is free for a new one`() =
        authTestApplication {
            val client = jsonClient()
            val token = client.signedIn()
            val first = client.createCustomer(token, fullRequest()).body<CustomerResponse>()
            client.delete(ApiRoutes.Customers.byId(first.id)) { bearerAuth(token) }

            val response = client.createCustomer(token, fullRequest())

            assertEquals(HttpStatusCode.Created, response.status)
        }

    @Test
    fun `rejects an id that is not a uuid`() =
        authTestApplication {
            val client = jsonClient()
            val response = client.get(ApiRoutes.Customers.byId("not-a-uuid")) { bearerAuth(client.signedIn()) }

            assertValidationError(response)
        }

    @Test
    fun `answers 405 for a method the customer collection does not take`() =
        authTestApplication {
            val client = jsonClient()
            val response = client.patch(ApiRoutes.Customers.PATH) { bearerAuth(client.signedIn()) }

            assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
            assertEquals("METHOD_NOT_ALLOWED", response.body<ErrorResponse>().code)
        }
}

private suspend fun HttpClient.signedIn(): String = register(uniqueEmail(), VALID_PASSWORD).body<TokenResponse>().accessToken

private suspend fun HttpClient.createCustomer(
    accessToken: String,
    request: CustomerRequest,
    idempotencyKey: String? = null,
): HttpResponse =
    post(ApiRoutes.Customers.PATH) {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        idempotencyKey?.let { header(ApiRoutes.Customers.IDEMPOTENCY_KEY, it) }
        setBody(request)
    }

private suspend fun HttpClient.replaceCustomer(
    accessToken: String,
    customerId: String,
    request: CustomerRequest,
): HttpResponse =
    put(ApiRoutes.Customers.byId(customerId)) {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

private suspend fun HttpClient.listCustomers(
    accessToken: String,
    vararg query: Pair<String, String?>,
): PageResponse<CustomerResponse> {
    val response =
        get(ApiRoutes.Customers.PATH) {
            bearerAuth(accessToken)
            query.forEach { (name, value) -> value?.let { parameter(name, it) } }
        }
    assertEquals(HttpStatusCode.OK, response.status)
    return response.body()
}

private suspend fun assertValidationError(response: HttpResponse) {
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals("VALIDATION_ERROR", response.body<ErrorResponse>().code)
}
