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
 * build unchecked. Every package of the app carries it, in its {@code package-info.java}, and so does every package
 * of its tests, including those that exist only in the tests. The ArchUnit fixtures live outside the app's root
 * package on purpose, and are left out.
 */
class NullMarkedPackagesTest {

    private static final String ROOT = "io.github.ghostwritertje.seedkeeper";

    @Test
    void everyProductionPackageIsNullMarked() throws IOException, URISyntaxException {
        Map<String, String> classByPackage = classByPackage(SeedkeeperConfig.class);
        assertTrue(classByPackage.containsKey(ROOT + ".decide"), classByPackage::toString);

        assertEquals(List.of(), unmarked(classByPackage), "packages without @NullMarked in package-info.java");
    }

    @Test
    void everyTestPackageIsNullMarked() throws IOException, URISyntaxException {
        Map<String, String> classByPackage = classByPackage(NullMarkedPackagesTest.class);
        assertTrue(classByPackage.containsKey(ROOT + ".startup"), classByPackage::toString);

        assertEquals(List.of(), unmarked(classByPackage), "test packages without @NullMarked in package-info.java");
    }

    private static List<String> unmarked(Map<String, String> classByPackage) {
        return classByPackage.entrySet().stream()
                .filter(entry -> !isNullMarked(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
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

    /** One compiled class per package under {@link #ROOT}, from the directory {@code loadedFrom} was loaded from. */
    private static Map<String, String> classByPackage(Class<?> loadedFrom) throws IOException, URISyntaxException {
        Path classes = Path.of(
                loadedFrom.getProtectionDomain().getCodeSource().getLocation().toURI());
        String separator = classes.getFileSystem().getSeparator();
        Map<String, String> classByPackage = new TreeMap<>();
        try (Stream<Path> files = Files.walk(classes)) {
            files.map(file -> classes.relativize(file).toString())
                    .filter(name -> name.endsWith(".class") && name.contains(separator))
                    .map(name ->
                            name.substring(0, name.length() - ".class".length()).replace(separator, "."))
                    .filter(name -> name.startsWith(ROOT + "."))
                    .forEach(name -> classByPackage.putIfAbsent(name.substring(0, name.lastIndexOf('.')), name));
        }
        return classByPackage;
    }
}
