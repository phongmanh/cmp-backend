package com.example.feature.customer

import com.example.feature.user.UserTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Shaped by `V5__create_customer_idempotency_keys.sql`. This only describes it for Exposed. */
object CustomerIdempotencyKeyTable : Table("customer_idempotency_keys") {
    val ownerId = reference("owner_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val idempotencyKey = uuid("idempotency_key")
    val requestHash = varchar("request_hash", 64)
    val customerId = reference("customer_id", CustomerTable, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(ownerId, idempotencyKey, name = CUSTOMER_IDEMPOTENCY_KEY_PRIMARY_KEY)
}

/** The constraint a second create with the same key trips, whichever request got there first. */
const val CUSTOMER_IDEMPOTENCY_KEY_PRIMARY_KEY = "customer_idempotency_keys_pkey"
