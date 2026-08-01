package de.raindancer.ghastlines;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the settings, including the values nobody meant to write.
 *
 * <p>Clamping rather than refusing is the decision being pinned here: a speed of {@code -5} is a typo, and a
 * plugin that refuses to start over one is worse than a plugin that flies slowly and carries on.
 */
class TransitOptionsTest {

    @Test
    @DisplayName("no configuration at all gives the defaults")
    void missingSectionIsDefaults() {
        assertThat(TransitOptions.from(null)).isEqualTo(TransitOptions.defaults());
        assertThat(TransitOptions.from(new YamlConfiguration())).isEqualTo(TransitOptions.defaults());
    }

    @Test
    @DisplayName("every key is read")
    void readsEveryKey() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("max-per-player", 4);
        config.set("max-stops", 30);
        config.set("max-routes", 9);
        config.set("speed", 20);
        config.set("cruise-clearance", 24);
        config.set("boarding-seconds", 15);
        config.set("summon-cooldown-seconds", 60);
        config.set("max-distance", 5000);
        config.set("allow-cross-world", true);
        config.set("progress-in-boss-bar", false);

        assertThat(TransitOptions.from(config))
                .isEqualTo(new TransitOptions(4, 30, 9, 20, 24, 15, 60, 5000, true, false));
    }

    @Test
    @DisplayName("nonsense is clamped into range rather than refused")
    void clampsNonsense() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("speed", -5);
        config.set("cruise-clearance", 0);
        config.set("boarding-seconds", 0);
        config.set("max-distance", 1);
        config.set("max-per-player", 9999);

        TransitOptions options = TransitOptions.from(config);
        assertThat(options.speed()).isEqualTo(1);
        assertThat(options.clearance()).isEqualTo(4);
        assertThat(options.boardingSeconds()).isEqualTo(1);
        assertThat(options.maxDistance()).isEqualTo(50);
        assertThat(options.maxGhasts()).isEqualTo(20);
    }

    @Test
    @DisplayName("the per-second speed becomes a per-tick one for the flight loop")
    void speedBecomesPerTick() {
        assertThat(TransitOptions.defaults().blocksPerTick())
                .isEqualTo(TransitOptions.defaults().speed() / (double) TransitOptions.TICKS_PER_SECOND);
    }

    @Test
    @DisplayName("zero means off, for the cooldown")
    void zeroCooldownIsNoCooldown() {
        assertThat(TransitOptions.defaults().hasSummonCooldown()).isTrue();
        YamlConfiguration config = new YamlConfiguration();
        config.set("summon-cooldown-seconds", 0);
        assertThat(TransitOptions.from(config).hasSummonCooldown()).isFalse();
    }
}
