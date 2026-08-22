package com.example.feature.auth

import com.example.api.auth.SocialProvider
import com.example.common.AuthenticationException
import com.example.common.ConflictException
import com.example.common.maskEmail
import com.example.feature.auth.social.SocialIdentity
import com.example.feature.auth.social.SocialVerifierRegistry
import com.example.feature.user.User
import com.example.feature.user.UserRepository
import com.example.feature.user.sanitizedAvatarUrl
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

data class AuthResult(
    val user: User,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val tokenService: TokenService,
    private val passwordHasher: PasswordHasher,
    private val verifiers: SocialVerifierRegistry,
) {
    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        avatarUrl: String?,
    ): AuthResult {
        val normalizedEmail = email.normalizeEmail()
        if (userRepository.findByEmail(normalizedEmail) != null) throw ConflictException(EMAIL_TAKEN)

        val passwordHash = passwordHasher.hash(password)
        val user =
            try {
                userRepository.createWithPassword(normalizedEmail, passwordHash, displayName?.trim(), avatarUrl?.trim())
            } catch (cause: ExposedSQLException) {
                // Two registrations for the same address raced; the unique index settled it.
                logger.info("Registration lost a race for {}", normalizedEmail.maskEmail(), cause)
                throw ConflictException(EMAIL_TAKEN)
            }

        logger.info("Registered user {} ({})", user.id, normalizedEmail.maskEmail())
        return issueTokens(user)
    }

    suspend fun login(
        email: String,
        password: String,
    ): AuthResult {
        val credentials = userRepository.findCredentialsByEmail(email.normalizeEmail())

        // An unknown address and a wrong password must cost the same time and return the same body.
        if (credentials?.passwordHash == null) {
            passwordHasher.burnTime(password)
            throw AuthenticationException(INVALID_CREDENTIALS)
        }
        if (!passwordHasher.verify(password, credentials.passwordHash)) {
            throw AuthenticationException(
                INVALID_CREDENTIALS,
            )
        }
        if (!credentials.isActive) throw AuthenticationException(INVALID_CREDENTIALS)

        val user = userRepository.findById(credentials.userId) ?: throw AuthenticationException(INVALID_CREDENTIALS)
        return issueTokens(user)
    }

    suspend fun signInWithProvider(
        provider: SocialProvider,
        token: String,
    ): AuthResult {
        val identity = verifiers.forProvider(provider).verify(token)

        userRepository.findByIdentity(provider.key, identity.providerUserId)?.let { linked ->
            if (!linked.isActive) throw AuthenticationException(ACCOUNT_DISABLED)
            return issueTokens(linked)
        }

        adoptAccountByEmail(identity)?.let { return issueTokens(it) }

        return issueTokens(createAccountFor(identity))
    }

    /**
     * A provider that vouches for the address proves the person controls that mailbox, which is
     * what lets an existing account absorb the new sign-in method. An address the provider has not
     * verified proves nothing and is never used to reach an existing account.
     */
    private suspend fun adoptAccountByEmail(identity: SocialIdentity): User? {
        if (identity.email == null || !identity.isEmailVerified) return null

        val normalizedEmail = identity.email.normalizeEmail()
        val existing = userRepository.findByEmail(normalizedEmail) ?: return null
        if (!existing.isActive) throw AuthenticationException(ACCOUNT_DISABLED)

        if (!existing.isEmailVerified) {
            userRepository.claimUnverifiedAccount(existing.id)
            refreshTokenRepository.revokeAllForUser(existing.id)
            logger.warn(
                "Account {} was claimed by verified {} sign-in; its unverified password was cleared",
                existing.id,
                identity.provider.key,
            )
        }

        userRepository.linkIdentity(existing.id, identity.provider.key, identity.providerUserId)
        return userRepository.findById(existing.id) ?: existing
    }

    private suspend fun createAccountFor(identity: SocialIdentity): User =
        try {
            userRepository.createWithIdentity(
                // An address the provider has not verified is not stored: it may belong to somebody else.
                email = identity.email?.normalizeEmail()?.takeIf { identity.isEmailVerified },
                displayName = identity.displayName,
                avatarUrl = sanitizedAvatarUrl(identity.avatarUrl),
                isEmailVerified = identity.isEmailVerified && identity.email != null,
                provider = identity.provider.key,
                providerUserId = identity.providerUserId,
            )
        } catch (cause: ExposedSQLException) {
            // Concurrent first sign-ins race on the identity index; the winner's row is the answer.
            logger.info("Social sign-up lost a race for {}", identity.provider.key, cause)
            userRepository.findByIdentity(identity.provider.key, identity.providerUserId)
                ?: throw ConflictException(SIGN_IN_CONFLICT)
        }

    suspend fun refresh(refreshToken: String): AuthResult {
        val stored =
            refreshTokenRepository.findByHash(tokenService.hashRefreshToken(refreshToken))
                ?: throw AuthenticationException(INVALID_REFRESH_TOKEN)

        if (stored.revokedAt != null) {
            // A retired token being presented again means it leaked: drop the whole login.
            logger.warn("Refresh token replay detected for user {}; revoking token family", stored.userId)
            refreshTokenRepository.revokeFamily(stored.familyId)
            throw AuthenticationException(INVALID_REFRESH_TOKEN)
        }
        if (!stored.expiresAt.isAfter(Instant.now())) throw AuthenticationException(INVALID_REFRESH_TOKEN)

        val user =
            userRepository.findById(stored.userId)?.takeIf { it.isActive }
                ?: throw AuthenticationException(INVALID_REFRESH_TOKEN)

        val nextToken = tokenService.generateRefreshToken()
        refreshTokenRepository.rotate(
            currentId = stored.id,
            userId = user.id,
            familyId = stored.familyId,
            newTokenHash = tokenService.hashRefreshToken(nextToken),
            expiresAt = tokenService.refreshTokenExpiry(),
        )

        return AuthResult(user, tokenService.issueAccessToken(user.id), nextToken, tokenService.accessTokenTtlSeconds)
    }

    /** Idempotent and silent: the caller learns nothing about whether the token existed. */
    suspend fun logout(
        userId: UUID,
        refreshToken: String,
    ) {
        val stored = refreshTokenRepository.findByHash(tokenService.hashRefreshToken(refreshToken))
        if (stored != null && stored.userId == userId) {
            refreshTokenRepository.revokeFamily(stored.familyId)
        }
    }

    suspend fun logoutEverywhere(userId: UUID) {
        refreshTokenRepository.revokeAllForUser(userId)
    }

    suspend fun linkProvider(
        userId: UUID,
        provider: SocialProvider,
        token: String,
    ) {
        val identity = verifiers.forProvider(provider).verify(token)
        val owner = userRepository.findByIdentity(provider.key, identity.providerUserId)

        if (owner != null) {
            if (owner.id != userId) throw ConflictException(PROVIDER_LINKED_ELSEWHERE)
            return
        }

        try {
            userRepository.linkIdentity(userId, provider.key, identity.providerUserId)
        } catch (cause: ExposedSQLException) {
            logger.info("Linking {} lost a race for user {}", provider.key, userId, cause)
            throw ConflictException(PROVIDER_LINKED_ELSEWHERE)
        }
    }

    private suspend fun issueTokens(user: User): AuthResult {
        val refreshToken = tokenService.generateRefreshToken()
        refreshTokenRepository.store(
            userId = user.id,
            familyId = UUID.randomUUID(),
            tokenHash = tokenService.hashRefreshToken(refreshToken),
            expiresAt = tokenService.refreshTokenExpiry(),
        )
        return AuthResult(
            user,
            tokenService.issueAccessToken(user.id),
            refreshToken,
            tokenService.accessTokenTtlSeconds,
        )
    }

    private fun String.normalizeEmail(): String = trim().lowercase()

    private companion object {
        const val EMAIL_TAKEN = "An account with that email address already exists."
        const val INVALID_CREDENTIALS = "Email or password is incorrect."
        const val INVALID_REFRESH_TOKEN = "The refresh token is invalid or has expired."
        const val ACCOUNT_DISABLED = "This account is not active."
        const val SIGN_IN_CONFLICT = "Sign-in could not be completed. Please try again."
        const val PROVIDER_LINKED_ELSEWHERE = "That account is already linked to a different user."
    }
}
