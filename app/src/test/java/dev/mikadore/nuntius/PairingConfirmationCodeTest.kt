package dev.mikadore.nuntius

import dev.mikadore.nuntius.lib.generatePairingConfirmationCode
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingConfirmationCodeTest {
    @Test
    fun generatesSixDigitDecimalCodes() {
        repeat(100) {
            assertTrue(generatePairingConfirmationCode().matches(Regex("^[0-9]{6}$")))
        }
    }
}
