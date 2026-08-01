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
        config.set("speed-percent", 150);
        config.set("cruise-clearance", 24);
        config.set("boarding-seconds", 15);
        config.set("summon-cooldown-seconds", 60);
        config.set("max-distance", 5000);
        config.set("allow-cross-world", true);
        config.set("progress-in-boss-bar", false);
        config.set("keep-loaded", false);

        assertThat(TransitOptions.from(config))
                .isEqualTo(new TransitOptions(4, 30, 9, 150, 24, 15, 60, 5000, true, false, false));
    }

    @Test
    @DisplayName("nonsense is clamped into range rather than refused")
    void clampsNonsense() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("speed-percent", -5);
        config.set("cruise-clearance", 0);
        config.set("boarding-seconds", 0);
        config.set("max-distance", 1);
        config.set("max-per-player", 9999);

        TransitOptions options = TransitOptions.from(config);
        assertThat(options.speedPercent()).isEqualTo(25);
        assertThat(options.clearance()).isEqualTo(4);
        assertThat(options.boardingSeconds()).isEqualTo(1);
        assertThat(options.maxDistance()).isEqualTo(50);
        assertThat(options.maxGhasts()).isEqualTo(20);
    }

    @Test
    @DisplayName("the default flight is short, because a happy ghast is slow")
    void defaultDistanceSuitsTheAnimalsSpeed() {
        // ~3.8 blocks a second: 1200 blocks is about five minutes, which is a journey. 2000 was nine.
        assertThat(TransitOptions.defaults().maxDistance()).isEqualTo(1200);
    }

    @Test
    @DisplayName("the default is the ghast's own speed, unscaled")
    void defaultIsTheGhastsOwnSpeed() {
        assertThat(TransitOptions.defaults().speedPercent())
                .as("100 means 'as fast as a happy ghast flies'; the speed itself comes off the entity")
                .isEqualTo(100);
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
