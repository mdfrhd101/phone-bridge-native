package com.phonerelay.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the trickiest logic: version comparison and OTP extraction.
 * (Crypto and JSON codecs use Android framework classes and are exercised on-device.)
 */
class LogicTest {

    // ---- AppUpdater.isNewerVersion ----

    @Test fun newerVersion_detectsHigherPatch() {
        assertTrue(AppUpdater.isNewerVersion("1.3.13", "1.3.12"))
    }

    @Test fun newerVersion_detectsHigherMinor() {
        assertTrue(AppUpdater.isNewerVersion("1.4.0", "1.3.99"))
    }

    @Test fun newerVersion_equalIsNotNewer() {
        assertFalse(AppUpdater.isNewerVersion("1.3.12", "1.3.12"))
    }

    @Test fun newerVersion_olderIsNotNewer() {
        assertFalse(AppUpdater.isNewerVersion("1.3.11", "1.3.12"))
    }

    @Test fun newerVersion_toleratesVPrefixAndWhitespace() {
        assertTrue(AppUpdater.isNewerVersion(" v1.3.13 ", "1.3.12"))
        assertFalse(AppUpdater.isNewerVersion("v1.3.12", " 1.3.12"))
    }

    // ---- EventCodec.extractOtp (accuracy-first) ----

    @Test fun otp_extractsFromRealOtpMessage() {
        assertEquals("482910", EventCodec.extractOtp("Your OTP for card transaction is 482910. Do not share it."))
    }

    @Test fun otp_extractsWithBengaliVerificationWord() {
        assertEquals("5821", EventCodec.extractOtp("Your verification code is 5821"))
    }

    @Test fun otp_ignoresPlainNumberWithoutKeyword() {
        // A phone number / amount / address in an ordinary SMS must NOT be treated as an OTP.
        assertNull(EventCodec.extractOtp("Meeting at 1234 Main Street tomorrow"))
        assertNull(EventCodec.extractOtp("You have received Tk 2500 from a friend"))
    }

    @Test fun otp_ignoresEmptyOrBlank() {
        assertNull(EventCodec.extractOtp(""))
        assertNull(EventCodec.extractOtp(null))
    }

    @Test fun otp_bkashKeywordCountsAsOtpContext() {
        assertEquals("7391", EventCodec.extractOtp("bKash: your one time password is 7391"))
    }
}
