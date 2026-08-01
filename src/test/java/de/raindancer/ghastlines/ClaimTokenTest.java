package de.raindancer.ghastlines;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a name tag into a word somebody can type.
 *
 * <h2>Why this needs pinning</h2>
 * A name tag is the one thing in this plugin a player can write absolutely anything into — spaces, colour
 * codes, emoji, nothing at all — and the result has to be a single command argument. Every one of these cases
 * would otherwise be a ghast that exists, is listed, and cannot be summoned.
 */
class ClaimTokenTest {

    private static final UUID GHAST =
            UUID.fromString("0123abcd-0000-0000-0000-000000000000");

    @Test
    @DisplayName("an ordinary name becomes itself, in lower case")
    void ordinaryName() {
        assertThat(Claims.tokenOf("Bessie", GHAST)).isEqualTo("bessie");
    }

    @Test
    @DisplayName("spaces and punctuation become underscores, collapsed and trimmed")
    void spacesBecomeUnderscores() {
        assertThat(Claims.tokenOf("Bus 12", GHAST)).isEqualTo("bus_12");
        assertThat(Claims.tokenOf("  The   #1 Bus!  ", GHAST)).isEqualTo("the_1_bus");
    }

    @Test
    @DisplayName("a ghast with no name answers to the first eight characters of its id")
    void unnamedFallsBackToTheId() {
        assertThat(Claims.tokenOf("", GHAST)).isEqualTo("0123abcd");
        assertThat(Claims.tokenOf(null, GHAST)).isEqualTo("0123abcd");
        assertThat(Claims.tokenOf(Claims.UNNAMED, GHAST)).isEqualTo("0123abcd");
    }

    @Test
    @DisplayName("a name with nothing typeable in it falls back to the id rather than to an underscore")
    void unusableNameFallsBackToo() {
        assertThat(Claims.tokenOf("★ ✦ ★", GHAST)).isEqualTo("0123abcd");
    }

    @Test
    @DisplayName("a very long name is cut to something that can be typed")
    void longNamesAreCut() {
        assertThat(Claims.tokenOf("a".repeat(100), GHAST)).hasSize(24);
    }

    @Test
    @DisplayName("the id form is stable and eight characters")
    void shortIdIsStable() {
        assertThat(Claims.shortId(GHAST)).isEqualTo("0123abcd");
        assertThat(Claims.shortId(new GhastClaim(GHAST, UUID.randomUUID(), "x", "world", 0, 0, 0, 0L)))
                .isEqualTo("0123abcd");
    }
}
