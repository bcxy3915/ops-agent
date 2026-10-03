package com.example.opsaiagent;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageImportCheckTest {

    @Test
    void noJavaxValidationImports() throws IOException {
        Path srcDir = Paths.get("src/main/java");
        try (Stream<Path> files = Files.walk(srcDir)) {
            List<String> badFiles = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            return content.contains("javax.validation");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();

            assertTrue(badFiles.isEmpty(),
                    "以下文件使用了 javax.validation，Spring Boot 3.x 应该用 jakarta.validation:\n"
                            + String.join("\n", badFiles));
        }
    }
}