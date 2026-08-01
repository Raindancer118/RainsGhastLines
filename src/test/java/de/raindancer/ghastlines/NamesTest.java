package de.raindancer.ghastlines;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a stop or a route may be called.
 *
 * <p>The interesting cases are the ones that would go wrong somewhere else: a name with a dot writes itself
 * into the wrong place in the YAML file, and a name with a {@code <} becomes markup in a message.
 */
class NamesTest {

    @Test
    @DisplayName("case does not make two different stops")
    void foldsCase() {
        assertThat(Names.normalise("Base")).isEqualTo("base");
        assertThat(Names.normalise("  BASE  ")).isEqualTo("base");
    }

    @Test
    @DisplayName("letters, digits, dash and underscore are allowed")
    void acceptsTheAllowedSet() {
        assertThat(Names.normalise("north_mine-2")).isEqualTo("north_mine-2");
    }

    @Test
    @DisplayName("a name that would break a YAML path is refused")
    void refusesYamlPaths() {
        assertThat(Names.normalise("my.stop")).isNull();
        assertThat(Names.normalise("world:spawn")).isNull();
    }

    @Test
    @DisplayName("a name that would become markup is refused")
    void refusesMarkup() {
        assertThat(Names.normalise("<red>")).isNull();
    }

    @Test
    @DisplayName("a name with a space is refused, because it would be two arguments")
    void refusesSpaces() {
        assertThat(Names.normalise("north mine")).isNull();
    }

    @Test
    @DisplayName("nothing, and too much, are both refused")
    void refusesEmptyAndOverlong() {
        assertThat(Names.normalise("")).isNull();
        assertThat(Names.normalise("   ")).isNull();
        assertThat(Names.normalise(null)).isNull();
        assertThat(Names.normalise("a".repeat(Names.MAX_LENGTH))).isNotNull();
        assertThat(Names.normalise("a".repeat(Names.MAX_LENGTH + 1))).isNull();
    }

    @Test
    @DisplayName("the requirement is phrased for a player and mentions the length")
    void requirementIsUsable() {
        assertThat(Names.requirement()).contains(String.valueOf(Names.MAX_LENGTH));
    }
}
