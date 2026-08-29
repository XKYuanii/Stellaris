package com.stellaris.service.strategy;

import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.ProgramOrderVersion;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProgramOrderContextTest {

    @Test
    void everyDeclaredVersionHasAUniqueRouteKey() {
        Set<String> versionKeys = Arrays.stream(ProgramOrderVersion.values())
                .map(ProgramOrderVersion::getVersion)
                .collect(Collectors.toSet());

        assertThat(versionKeys).hasSize(ProgramOrderVersion.values().length);
    }

    @Test
    void duplicateStrategyVersionFailsDuringStartup() {
        ProgramOrderContext context = new ProgramOrderContext(List.of(
                new TestStrategy("v-test"), new TestStrategy("v-test")));

        assertThatIllegalStateException().isThrownBy(context::init)
                .withMessageContaining("Duplicate program order strategy version 'v-test'");
    }

    private static final class TestStrategy implements ProgramOrderStrategy {
        private final String version;

        private TestStrategy(String version) {
            this.version = version;
        }

        @Override
        public String createOrder(ProgramOrderCreateDto programOrderCreateDto) {
            return "test-order";
        }

        @Override
        public String version() {
            return version;
        }
    }
}
