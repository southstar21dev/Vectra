package dev.southstar21.vectra.features.auth

import dev.southstar21.vectra.core.network.PocketBaseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountLogicTest {
    private fun fieldError(status: Int, field: String, code: String, message: String) = PocketBaseException(
        status,
        "Failed to create record.",
        Json.parseToJsonElement("""{"data":{"$field":{"code":"$code","message":"$message"}}}""") as JsonObject,
    )

    @Test fun anEmptySignupFormSaysWhatIsMissing() {
        assertEquals("Enter a nickname", AccountLogic.validateSignup("", "a@b.co", "password1"))
        assertEquals("Enter your email address", AccountLogic.validateSignup("Ann", " ", "password1"))
        assertEquals("Enter a valid email address", AccountLogic.validateSignup("Ann", "nope", "password1"))
        assertEquals("Password is required", AccountLogic.validateSignup("Ann", "a@b.co", ""))
        assertNull(AccountLogic.validateSignup("Ann", "a@b.co", "password1"))
    }

    @Test fun aTakenEmailIsExplainedInPlainWords() {
        val e = fieldError(400, "email", "validation_not_unique", "Value must be unique.")
        assertEquals("An account with this email already exists. Try signing in instead.", AccountLogic.signupErrorMessage(e))
    }

    @Test fun aBadEmailIsExplainedInPlainWords() {
        val e = fieldError(400, "email", "validation_is_email", "Must be a valid email address.")
        assertEquals("Enter a valid email address.", AccountLogic.signupErrorMessage(e))
    }

    @Test fun offlineAndRateLimitedSignupsAreSaidAsSuch() {
        assertEquals("Couldn't reach Vectra. Check your connection and try again.", AccountLogic.signupErrorMessage(PocketBaseException(0, "x")))
        assertEquals("Too many attempts. Wait a minute and try again.", AccountLogic.signupErrorMessage(PocketBaseException(429, "x")))
    }

    @Test fun otherFieldErrorsKeepTheirLabel() {
        val e = fieldError(400, "password", "validation_length_out_of_range", "The length must be between 8 and 72.")
        assertEquals("Password: The length must be between 8 and 72.", AccountLogic.signupErrorMessage(e))
    }
}
