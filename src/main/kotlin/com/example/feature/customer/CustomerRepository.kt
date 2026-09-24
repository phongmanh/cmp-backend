package com.example.feature.customer

import com.example.api.customer.CustomerStatus
import com.example.common.dbQuery
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.LikePattern
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.compoundOr
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.update
import org.postgresql.util.PSQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Every statement here is scoped to one owner and to live rows. There is no method that reaches a
 * customer by id alone, so a caller cannot be handed somebody else's by passing the wrong id.
 */
class CustomerRepository {
    /**
     * With [idempotency], the key is claimed before the customer is written and in the same
     * transaction, so a racing retry blocks on the key and fails on it, never on the email, and a
     * create that fails for any other reason leaves the key free to try again.
     */
    suspend fun create(
        ownerId: UUID,
        details: CustomerDetails,
        idempotency: IdempotentCreate?,
    ): Customer =
        translatingUniqueViolations {
            dbQuery {
                val now = now()
                val id = UUID.randomUUID()
                if (idempotency != null) {
                    CustomerIdempotencyKeyTable.insert {
                        it[CustomerIdempotencyKeyTable.ownerId] = ownerId
                        it[idempotencyKey] = idempotency.key
                        it[requestHash] = idempotency.requestHash
                        it[customerId] = id
                        it[createdAt] = now
                    }
                }
                CustomerTable.insert {
                    it[CustomerTable.id] = id
                    it[CustomerTable.ownerId] = ownerId
                    it.write(details)
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                Customer(id, ownerId, details, createdAt = now, updatedAt = now)
            }
        }

    suspend fun findIdempotencyRecord(
        ownerId: UUID,
        key: UUID,
    ): IdempotencyRecord? =
        dbQuery {
            CustomerIdempotencyKeyTable
                .selectAll()
                .where {
                    (CustomerIdempotencyKeyTable.ownerId eq ownerId) and (CustomerIdempotencyKeyTable.idempotencyKey eq key)
                }.singleOrNull()
                ?.let {
                    IdempotencyRecord(
                        customerId = it[CustomerIdempotencyKeyTable.customerId].value,
                        requestHash = it[CustomerIdempotencyKeyTable.requestHash],
                    )
                }
        }

    suspend fun findById(
        ownerId: UUID,
        customerId: UUID,
    ): Customer? =
        dbQuery {
            CustomerTable
                .selectAll()
                .where { (CustomerTable.id eq customerId) and liveFor(ownerId) }
                .singleOrNull()
                ?.toCustomer()
        }

    /**
     * Reads one row past the page to learn whether another page exists, which costs nothing next to
     * a `COUNT(*)` over every matching row.
     */
    suspend fun findPage(
        ownerId: UUID,
        query: CustomerListQuery,
    ): CustomerPage =
        dbQuery {
            val rows =
                CustomerTable
                    .selectAll()
                    .where { query.conditionsFor(ownerId) }
                    .orderBy(CustomerTable.createdAt to SortOrder.DESC, CustomerTable.id to SortOrder.DESC)
                    .limit(query.limit + 1)
                    .map { it.toCustomer() }

            val page = rows.take(query.limit)
            val next = if (rows.size > query.limit) page.lastOrNull()?.let { CustomerCursor(it.createdAt, it.id) } else null
            CustomerPage(page, next)
        }

    /**
     * Writes and reads back inside one transaction, so the response can never describe a customer a
     * concurrent write has already moved past. A missing, deleted or foreign row reports as null.
     */
    suspend fun update(
        ownerId: UUID,
        customerId: UUID,
        details: CustomerDetails,
    ): Customer? =
        translatingUniqueViolations {
            dbQuery {
                val rowsWritten =
                    CustomerTable.update({ (CustomerTable.id eq customerId) and liveFor(ownerId) }) {
                        it.write(details)
                        it[updatedAt] = now()
                    }
                if (rowsWritten == 0) return@dbQuery null

                CustomerTable
                    .selectAll()
                    .where { CustomerTable.id eq customerId }
                    .singleOrNull()
                    ?.toCustomer()
            }
        }

    /** Reports back whether there was a live customer of that owner to delete. */
    suspend fun softDelete(
        ownerId: UUID,
        customerId: UUID,
    ): Boolean =
        dbQuery {
            val now = now()
            val rowsWritten =
                CustomerTable.update({ (CustomerTable.id eq customerId) and liveFor(ownerId) }) {
                    it[deletedAt] = now
                    it[updatedAt] = now
                }
            rowsWritten > 0
        }
}

/**
 * Truncated to what a Postgres `TIMESTAMP` holds. Without it the instant returned from a create
 * could carry nanoseconds the stored row does not, and a cursor built from it would sit a fraction
 * of a microsecond away from the row it names.
 */
private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

private fun liveFor(ownerId: UUID): Op<Boolean> = (CustomerTable.ownerId eq ownerId) and CustomerTable.deletedAt.isNull()

private fun CustomerListQuery.conditionsFor(ownerId: UUID): Op<Boolean> =
    listOfNotNull(
        liveFor(ownerId),
        status?.let { CustomerTable.status eq it.key },
        search?.let { matchesPrefix(it) },
        after?.let { startsAfter(it) },
    ).compoundAnd()

/**
 * A prefix match rather than a substring one, because a btree can answer `LIKE 'abc%'` and nothing
 * short of a trigram index can answer `LIKE '%abc%'`. The search text is escaped, so a `%` or `_`
 * a user types is looked for literally rather than read as a wildcard.
 */
private fun matchesPrefix(search: String): Op<Boolean> {
    val pattern = LikePattern.ofLiteral(search.lowercase()) + "%"
    return listOf(
        CustomerTable.firstName.lowerCase() like pattern,
        CustomerTable.lastName.lowerCase() like pattern,
        CustomerTable.companyName.lowerCase() like pattern,
        CustomerTable.email like pattern,
    ).compoundOr()
}

/**
 * Rows strictly after [cursor] in `created_at DESC, id DESC` order. The leading `<=` is redundant
 * with the rest but gives the planner a range it can walk the index with.
 */
private fun startsAfter(cursor: CustomerCursor): Op<Boolean> =
    (CustomerTable.createdAt lessEq cursor.createdAt) and
        ((CustomerTable.createdAt less cursor.createdAt) or (CustomerTable.id less cursor.id))

/**
 * A unique index is the only thing that can settle two writes racing for one address or one
 * idempotency key, so its violation is the signal rather than a lookup beforehand, which would leave
 * a gap between the check and the write for a second request to land in.
 *
 * Caught outside `dbQuery` on purpose: once a statement fails, Postgres refuses everything else in
 * that transaction, so there is nothing useful left to do inside it.
 */
private suspend fun <T> translatingUniqueViolations(block: suspend () -> T): T =
    try {
        block()
    } catch (cause: ExposedSQLException) {
        val violated =
            generateSequence<Throwable>(cause) { it.cause }
                .filterIsInstance<PSQLException>()
                .firstOrNull()
                ?.serverErrorMessage
                ?.constraint
        when (violated) {
            CUSTOMER_EMAIL_UNIQUE_INDEX -> throw DuplicateCustomerEmailException()
            CUSTOMER_IDEMPOTENCY_KEY_PRIMARY_KEY -> throw DuplicateIdempotencyKeyException()
            else -> throw cause
        }
    }

private fun UpdateBuilder<*>.write(details: CustomerDetails) {
    this[CustomerTable.firstName] = details.firstName
    this[CustomerTable.lastName] = details.lastName
    this[CustomerTable.companyName] = details.companyName
    this[CustomerTable.email] = details.email
    this[CustomerTable.phone] = details.phone
    this[CustomerTable.addressLine1] = details.address?.line1
    this[CustomerTable.addressLine2] = details.address?.line2
    this[CustomerTable.city] = details.address?.city
    this[CustomerTable.region] = details.address?.region
    this[CustomerTable.postalCode] = details.address?.postalCode
    this[CustomerTable.countryCode] = details.address?.countryCode
    this[CustomerTable.notes] = details.notes
    this[CustomerTable.status] = details.status.key
}

private fun ResultRow.toCustomer(): Customer =
    Customer(
        id = this[CustomerTable.id].value,
        ownerId = this[CustomerTable.ownerId].value,
        details =
            CustomerDetails(
                firstName = this[CustomerTable.firstName],
                lastName = this[CustomerTable.lastName],
                companyName = this[CustomerTable.companyName],
                email = this[CustomerTable.email],
                phone = this[CustomerTable.phone],
                address = toPostalAddress(),
                notes = this[CustomerTable.notes],
                status =
                    CustomerStatus.fromKey(this[CustomerTable.status])
                        ?: error("Customer ${this[CustomerTable.id].value} has a status the application does not know."),
            ),
        createdAt = this[CustomerTable.createdAt],
        updatedAt = this[CustomerTable.updatedAt],
    )

/** The migration's check constraint guarantees these three are set together or not at all. */
private fun ResultRow.toPostalAddress(): PostalAddress? {
    val line1 = this[CustomerTable.addressLine1] ?: return null
    val city = this[CustomerTable.city] ?: return null
    val countryCode = this[CustomerTable.countryCode] ?: return null
    return PostalAddress(
        line1 = line1,
        line2 = this[CustomerTable.addressLine2],
        city = city,
        region = this[CustomerTable.region],
        postalCode = this[CustomerTable.postalCode],
        countryCode = countryCode,
    )
}
