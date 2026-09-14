package com.example.feature.customer

import com.example.api.common.FieldLimits
import com.example.feature.user.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Shapes are owned by the Flyway migrations. This object only describes the existing schema so
 * Exposed can build statements against it.
 */
object CustomerTable : UUIDTable("customers") {
    val ownerId = reference("owner_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val firstName = varchar("first_name", FieldLimits.MAX_CUSTOMER_NAME_LENGTH)
    val lastName = varchar("last_name", FieldLimits.MAX_CUSTOMER_NAME_LENGTH).nullable()
    val companyName = varchar("company_name", FieldLimits.MAX_COMPANY_NAME_LENGTH).nullable()
    val email = varchar("email", FieldLimits.MAX_EMAIL_LENGTH).nullable()
    val phone = varchar("phone", FieldLimits.MAX_PHONE_LENGTH).nullable()
    val addressLine1 = varchar("address_line1", FieldLimits.MAX_ADDRESS_LINE_LENGTH).nullable()
    val addressLine2 = varchar("address_line2", FieldLimits.MAX_ADDRESS_LINE_LENGTH).nullable()
    val city = varchar("city", FieldLimits.MAX_CITY_LENGTH).nullable()
    val region = varchar("region", FieldLimits.MAX_REGION_LENGTH).nullable()
    val postalCode = varchar("postal_code", FieldLimits.MAX_POSTAL_CODE_LENGTH).nullable()
    val countryCode = varchar("country_code", 2).nullable()
    val notes = varchar("notes", FieldLimits.MAX_CUSTOMER_NOTES_LENGTH).nullable()

    /** A `CustomerStatus.key`, so the stored value survives a rename of the Kotlin constant. */
    val status = varchar("status", 16)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
}

/** The name the migration gives the index that keeps an owner's customer emails unique. */
const val CUSTOMER_EMAIL_UNIQUE_INDEX = "customers_owner_email_unique"
