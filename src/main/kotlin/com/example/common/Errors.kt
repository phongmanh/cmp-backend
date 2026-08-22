package com.example.common

import com.example.api.common.ErrorCode

/**
 * Every failure the client is allowed to see. The message is written for the caller, so it must
 * never carry a stack trace, SQL text or anything about the internals of the server.
 *
 * [code] is drawn from [ErrorCode], the same list the app branches on.
 */
sealed class AppException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

class ValidationException(
    message: String,
) : AppException(ErrorCode.VALIDATION_ERROR, message)

class AuthenticationException(
    message: String,
) : AppException(ErrorCode.UNAUTHENTICATED, message)

class AccessDeniedException(
    message: String,
) : AppException(ErrorCode.FORBIDDEN, message)

class ResourceNotFoundException(
    message: String,
) : AppException(ErrorCode.NOT_FOUND, message)

class ConflictException(
    message: String,
) : AppException(ErrorCode.CONFLICT, message)

class PayloadTooLargeException(
    message: String,
) : AppException(ErrorCode.PAYLOAD_TOO_LARGE, message)

class BusinessRuleException(
    code: String,
    message: String,
) : AppException(code, message)
