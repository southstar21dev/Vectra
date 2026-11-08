package dev.southstar21.vectra.features.auth

import dev.southstar21.vectra.core.network.PocketBaseException

/** Validation rules and limits for the account settings. */
object AccountLogic {
    const val MIN_PASSWORD_LENGTH = 8
    const val FULL_NAME_MAX_LENGTH = 100
    const val DELETE_ACCOUNT_CONFIRM_TEXT = "DELETE"
    private const val PB_AUTH_FAILED = "Failed to authenticate."

    private val FieldLabels = mapOf(
        "password" to "Password",
        "passwordConfirm" to "Password confirmation",
        "email" to "Email",
        "full_name" to "Nickname",
    )

    fun isValidEmail(email: String) = Regex("""^\S+@\S+\.\S+$""").matches(email)

    fun validatePassword(password: String): String? = when {
        password.isEmpty() -> "Password is required"
        password.length < MIN_PASSWORD_LENGTH -> "Password must be at least $MIN_PASSWORD_LENGTH characters"
        else -> null
    }

    fun validateConfirmPassword(password: String, confirm: String): String? = when {
        confirm.isEmpty() -> "Please confirm your new password"
        password != confirm -> "Passwords do not match"
        else -> null
    }

    fun loginErrorMessage(err: Throwable?): String {
        val message = err?.message.orEmpty()
        if (message == PB_AUTH_FAILED) return "Incorrect email or password."
        return message.ifEmpty { "Could not sign in. Try again." }
    }

    /** Why a password-reset request failed, worded for the person waiting on the email. */
    fun resetErrorMessage(err: Throwable?): String = when ((err as? PocketBaseException)?.status) {
        0 -> "Couldn't reach Vectra. Check your connection and try again."
        429 -> "Too many attempts. Wait a minute and try again."
        else -> fieldErrorMessage(err) ?: "Couldn't send the reset email. Try again later."
    }

    /** "Email: The email is invalid or already in use." style message for the first field error. */
    fun fieldErrorMessage(err: Throwable?): String? {
        val (field, message) = (err as? PocketBaseException)?.fieldErrors?.entries?.firstOrNull() ?: return null
        return FieldLabels[field]?.let { "$it: $message" } ?: message
    }

    fun deleteConfirmationMatches(input: String) = input.trim().uppercase() == DELETE_ACCOUNT_CONFIRM_TEXT
}
