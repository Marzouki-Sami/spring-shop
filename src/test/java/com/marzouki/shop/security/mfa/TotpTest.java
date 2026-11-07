package com.marzouki.shop.security.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** The shared secret of the RFC 6238 test vectors ("12345678901234567890"). */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238TestVectors() {
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32RoundTrips() {
        byte[] data = {1, 2, 3, (byte) 250, 99, 0, 7};
        assertThat(Totp.fromBase32(Totp.base32(data))).isEqualTo(data);
        assertThat(Totp.newSecret()).hasSize(32).matches("[A-Z2-7]+");
    }

    @Test
    void acceptsTheNeighbouringStepsButNotOldOrReusedOnes() {
        long now = 1_700_000_000_000L;
        long step = Totp.currentStep(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, null)).isEqualTo(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, null)).isEqualTo(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, null)).isEqualTo(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, step)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "12345", now, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, null, now, null)).isEqualTo(-1);
    }

    @Test
    void spacesInTheCodeAreIgnored() {
        long now = 1_700_000_000_000L;
        String code = Totp.code(RFC_SECRET, Totp.currentStep(now));
        assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now, null)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void theAuthenticatorLinkCarriesTheIssuerAndSecret() {
        assertThat(Totp.uri("Shop", "mo@example.com", "ABC")).startsWith("otpauth://totp/Shop:mo%40example.com?secret=ABC&issuer=Shop");
    }

    @Test
    void codesTypedWithSpacesOrDashesAreAccepted() {
        long now = System.currentTimeMillis();
        long step = now / 30_000;
        String code = Totp.code(RFC_SECRET, step);
        assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now, null)).isEqualTo(step);
        assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + "-" + code.substring(3), now, null)).isEqualTo(step);
    }}
