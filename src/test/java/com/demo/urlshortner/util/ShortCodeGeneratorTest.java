package com.demo.urlshortner.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void generatesBase62CodeOfFixedLength() {
        String code = generator.generate();

        assertThat(code).hasSize(ShortCodeGenerator.CODE_LENGTH).matches("[0-9A-Za-z]+");
    }

    @Test
    void generatesDistinctCodes() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(generator.generate());
        }

        assertThat(codes).hasSize(1000);
    }
}
