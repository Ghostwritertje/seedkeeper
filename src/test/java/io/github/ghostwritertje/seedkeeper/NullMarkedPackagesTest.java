package io.github.ghostwritertje.seedkeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

/**
 * NullAway checks only {@code @NullMarked} code, so a package without the annotation would slip past the quality
 * build unchecked. Every production package carries it, in its {@code package-info.java}.
 */
class NullMarkedPackagesTest {

    @Test
    void everyProductionPackageIsNullMarked() throws IOException, URISyntaxException {
        Map<String, String> classByPackage = productionClassByPackage();
        assertTrue(classByPackage.containsKey("io.github.ghostwritertje.seedkeeper.decide"), classByPackage::toString);

        List<String> unmarked = classByPackage.entrySet().stream()
                .filter(entry -> !isNullMarked(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        assertEquals(List.of(), unmarked, "packages without @NullMarked in package-info.java");
    }

    private static boolean isNullMarked(String className) {
        try {
            return Class.forName(className, false, NullMarkedPackagesTest.class.getClassLoader())
                    .getPackage()
                    .isAnnotationPresent(NullMarked.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One compiled production class per package, from the directory {@link SeedkeeperConfig} was loaded from. */
    private static Map<String, String> productionClassByPackage() throws IOException, URISyntaxException {
        Path classes = Path.of(SeedkeeperConfig.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        String separator = classes.getFileSystem().getSeparator();
        Map<String, String> classByPackage = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            files.map(file -> classes.relativize(file).toString())
                    .filter(name -> name.endsWith(".class") && name.contains(separator))
                    .map(name ->
                            name.substring(0, name.length() - ".class".length()).replace(separator, "."))
                    .forEach(name -> classByPackage.putIfAbsent(name.substring(0, name.lastIndexOf('.')), name));
        }
        return classByPackage;
    }
}
