package org.jrd.backend.data;

import org.jrd.backend.core.AgentRequestAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

import static org.jrd.backend.data.Cli.*;
import static org.jrd.backend.data.Help.*;
import static org.jrd.frontend.frame.main.VmDecompilerInformationController.createRequest;
import static org.jrd.frontend.frame.main.VmDecompilerInformationController.submitRequest;
import static org.junit.jupiter.api.Assertions.*;


public class CliTest {
    private Model model;
    private String[] args;
    private Cli cli;
    private TestingDummyHelper dummy;

    private final StreamWrappers streams = new StreamWrappers();

    private static final String UNKNOWN_FLAG = "--zyxwvutsrqponmlqjihgfedcba";

    @BeforeEach
    void setup() {
        streams.captureStreams(true);

        try {
            dummy = new TestingDummyHelper()
                    .write()
                    .compile()
                    .execute();
        } catch (TestingDummyHelper.TestingDummyException e) {
            fail(e);
        }
        assertTrue(dummy.isAlive());

        model = new Model(); // must be below dummy process execution to be aware of it during VmManager instantiation
    }

    @AfterEach
    void cleanup() throws InterruptedException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        streams.captureStreams(false);

        assertTrue(dummy.isAlive());
        // halt agent, otherwise an open socket prevents termination of dummy process
        AgentRequestAction request = createRequest(cli.getVmInfo(String.valueOf(dummy.getPid())), AgentRequestAction.RequestAction.HALT, "");
        String response = submitRequest(model.getVmManager(), request);
        assertEquals("ok", response);

        assertTrue(dummy.isAlive());
        dummy.terminate();
        Thread.sleep(200); // termination does not propagate fast enough
        assertFalse(dummy.isAlive(), "Pid is " + dummy.getPid());
    }

    private String prependSlashes(String original, int count) {
        return "-".repeat(count) + original;
    }

    private String cleanSlashes(String original) {
        return original.replace("-", "");
    }

    private String oneSlash(String flag) {
        return prependSlashes(cleanSlashes(flag), 1);
    }

    private String twoSlashes(String flag) {
        return prependSlashes(cleanSlashes(flag), 2);
    }

    @Test
    void testShouldBeVerbose() {
        // gui verbose
        args = new String[] {VERBOSE};
        cli = new Cli(args, model);
        assertTrue(cli.shouldBeVerbose());

        // gui not verbose
        args = new String[] {};
        cli = new Cli(args, model);
        assertFalse(cli.shouldBeVerbose());

        // cli verbose
        args = new String[] {VERBOSE, UNKNOWN_FLAG};
        cli = new Cli(args, model);
        assertTrue(cli.shouldBeVerbose());

        // cli not verbose
        args = new String[] {UNKNOWN_FLAG};
        cli = new Cli(args, model);
        assertFalse(cli.shouldBeVerbose());
    }

    @Test
    void testHelp() throws Exception {
        args = new String[] {HELP};
        cli = new Cli(args, model);

        cli.consumeCli();
        String cliResult = streams.getOut();

        printHelpText();
        String methodResult = streams.getOut();

        assertEquals(cliResult, methodResult);
    }

    @Test
    void testVersion() throws Exception {
        args = new String[]{VERSION};
        cli = new Cli(args, model);

        cli.consumeCli();
        String cliResult = streams.getOut();

        assertEquals(cliResult.trim(), MetadataProperties.getInstance().toString());
    }

    private String processFormatDefault(String original) {
        return processFormat(
                original,
                String.valueOf(dummy.getPid()),
                new String[]{TestingDummyHelper.CLASS_REGEX},
                TestingDummyHelper.CLASS_NAME,
                TestingDummyHelper.DOT_CLASS_PATH,
                "javap"
        );
    }

    private static String processFormat(String original, String puc, String[] classRegex, String className, String classFile, String plugin) {
        String result = original
                .replace("...", "")
                .replace("<PUC>", puc)
                .replace("<PLUGIN>", plugin);

        if (classRegex != null) {
            result = result.replace("<CLASS REGEX>", String.join(" ", classRegex));
        }
        if (className != null) {
            result = result.replace("<CLASS NAME>", className);
        }
        if (classFile != null) {
            result = result.replace("<CLASS FILE>", classFile);
        }

        return result
                .replaceAll("\\[.*?<.*?>.*?]", "") //remove unused optional arguments
                .replaceAll("\\[(.*?)]", "$1"); //remove brackets around used optional arguments
    }

    private String[] processArgs(String operation) {
        if (operation.contains(" ")) {
            return processFormatDefault(operation).split(" ");
        }

        return new String[]{operation};
    }

    //temporarily missing COMPILE
    @ParameterizedTest
    @ValueSource(strings = {H, HELP, VERBOSE, VERSION, LIST_JVMS, LIST_PLUGINS, LIST_CLASSES_FORMAT, BYTES_FORMAT, BASE64_FORMAT, DECOMPILE_FORMAT, OVERWRITE_FORMAT})
    void testValidOperation(String operation) {
        args = processArgs(operation);
        cli = new Cli(args, model);

        assertDoesNotThrow(cli::consumeCli);
    }

    @ParameterizedTest
    @ValueSource(strings = {H, HELP, VERBOSE, VERSION, LIST_JVMS, LIST_PLUGINS, LIST_CLASSES_FORMAT, BYTES_FORMAT, BASE64_FORMAT, DECOMPILE_FORMAT, OVERWRITE_FORMAT})
    void testValidOperationTwoSlashes(String operation) {
        args = processArgs(operation);
        args[0] = twoSlashes(args[0]);
        cli = new Cli(args, model);

        assertDoesNotThrow(cli::consumeCli);
    }

    @ParameterizedTest
    @ValueSource(strings = {VERBOSE, LIST_JVMS, LIST_PLUGINS, OVERWRITE_FORMAT})
    void testTooManyArguments(String operation) {
        if (operation.contains(" ")) {
            args = processFormatDefault(operation + " " + UNKNOWN_FLAG).split(" ");
        } else {
            args = new String[]{operation, UNKNOWN_FLAG};
        }
        cli = new Cli(args, model);

        assertThrows(RuntimeException.class, () -> cli.consumeCli());
    }

    @Test
    void testInvalidOperation() {
        args = new String[]{UNKNOWN_FLAG};
        cli = new Cli(args, model);

        assertThrows(RuntimeException.class, () -> cli.consumeCli());
    }

    @Test
    void testListJvms() throws Exception {
        args = new String[]{LIST_JVMS};
        cli = new Cli(args, model);

        cli.consumeCli();
        String jvms = streams.getOut();

        List<String> result = new ArrayList<>();
        Matcher m = TestingDummyHelper.JVM_LIST_REGEX.matcher(jvms);
        while (m.find()) {
            result.add(m.group(1));
        }

        assertTrue(result.contains(String.valueOf(dummy.getPid())));
    }

    @Test
    void testListClasses() throws Exception {
        // no regex
        args = new String[]{VERBOSE, LIST_CLASSES, String.valueOf(dummy.getPid())};
        cli = new Cli(args, model);

        cli.consumeCli();
        String allClassesDefault = streams.getOut();

        Matcher m = TestingDummyHelper.EXACT_CLASS_REGEX.matcher(allClassesDefault);
        if (!m.find()) {
            fail("Class " + TestingDummyHelper.CLASS_NAME + " not found when listing all classes.");
        }

        // all regex
        args = new String[]{LIST_CLASSES, String.valueOf(dummy.getPid()), ".*"};
        cli = new Cli(args, model);

        cli.consumeCli();
        String allClassesRegex = streams.getOut();

        m = TestingDummyHelper.EXACT_CLASS_REGEX.matcher(allClassesRegex);
        if (!m.find()) {
            fail("Class " + TestingDummyHelper.CLASS_NAME + " not found when listing all classes via .* regex.");
        }

        assertEqualsWithTolerance(
                Arrays.asList(allClassesDefault.split("\n")),
                Arrays.asList(allClassesRegex.split("\n")),
                0.9
        ); // exact class list differs between dummy process executions

        // specific regex
        args = new String[]{LIST_CLASSES, String.valueOf(dummy.getPid()), TestingDummyHelper.CLASS_NAME};
        cli = new Cli(args, model);

        cli.consumeCli();
        String ourClass = streams.getOut();

        m = TestingDummyHelper.EXACT_CLASS_REGEX.matcher(ourClass);
        if (!m.find()) {
            fail("Class " + TestingDummyHelper.CLASS_NAME + " not found when listing all classes via specific regex.");
        }

        assertEquals(ourClass.lines().count(), 1);
    }

    // until CLI plugin importing is added, this is the only certain output
    @Test
    void testListPlugins() throws Exception {
        args = new String[]{LIST_PLUGINS};
        cli = new Cli(args, model);

        cli.consumeCli();
        String plugins = streams.getOut();

        assertTrue(plugins.contains("javap Internal/valid - null"));
        assertTrue(plugins.contains("javap -v Internal/valid - null"));
    }

    @Test
    void testBytes() throws Exception {
        args = new String[]{BYTES, String.valueOf(dummy.getPid()), TestingDummyHelper.CLASS_REGEX};
        cli = new Cli(args, model);

        cli.consumeCli();

        byte[] bytes = streams.getOutBytes();
        byte[] fileContents = Files.readAllBytes(Path.of(TestingDummyHelper.DOT_CLASS_PATH));

        assertArrayEquals(fileContents, bytes);
    }

    @Test
    void testBase64Bytes() throws Exception {
        args = new String[]{BASE64, String.valueOf(dummy.getPid()), TestingDummyHelper.CLASS_REGEX};
        cli = new Cli(args, model);

        cli.consumeCli();

        byte[] base64Bytes = streams.getOut().trim().getBytes(StandardCharsets.UTF_8);
        byte[] fileContents = Files.readAllBytes(Path.of(TestingDummyHelper.DOT_CLASS_PATH));
        byte[] encoded = Base64.getEncoder().encode(fileContents);

        assertArrayEquals(encoded, base64Bytes);
    }

    @Test
    void testBytesAndBase64BytesEqual() throws Exception {
        args = new String[]{BYTES, String.valueOf(dummy.getPid()), TestingDummyHelper.CLASS_REGEX};
        cli = new Cli(args, model);

        cli.consumeCli();
        byte[] bytes = streams.getOutBytes();

        args = new String[]{BASE64, String.valueOf(dummy.getPid()), TestingDummyHelper.CLASS_REGEX};
        cli = new Cli(args, model);

        cli.consumeCli();
        String base64 = streams.getOut().trim();
        byte[] decoded = Base64.getDecoder().decode(base64);

        assertArrayEquals(bytes, decoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-v"})
    void testDecompileJavap(String option) throws Exception {
        args = new String[]{DECOMPILE, String.valueOf(dummy.getPid()), "javap" + option , TestingDummyHelper.CLASS_REGEX};
        cli = new Cli(args, model);

        cli.consumeCli();
        String jrdDisassembled = streams.getOut();
        String javapDisassembled = TestingDummyHelper.executeJavaP(option);

        assertEqualsWithTolerance(jrdDisassembled, javapDisassembled, 0.8); // JRD javap has additional debug comment lines + header is different
    }

    @SuppressWarnings("unchecked") // field reflection
    @Test
    void testArgumentCleaning() throws Exception {
        Field field = Cli.class.getDeclaredField("filteredArgs"); // changing access modifier of filteredArgs for this test is not justifiable
        field.setAccessible(true);

        // nothing gets cleaned
        args = new String[]{LIST_CLASSES, String.valueOf(dummy.getPid()), "--"}; // weird regex, but should not get cleaned
        cli = new Cli(args, model);
        cli.consumeCli();

        String[] filteredArgs = ((List<String>) field.get(cli)).toArray(new String[0]);
        assertArrayEquals(args, filteredArgs);

        // only operation gets cleaned, but is still valid
        args = new String[]{twoSlashes(LIST_CLASSES), String.valueOf(dummy.getPid()), "--"};
        cli = new Cli(args, model);
        cli.consumeCli();

        filteredArgs = ((List<String>) field.get(cli)).toArray(new String[0]);
        assertArrayEquals(args, filteredArgs);
    }

    private static boolean isDifferenceTolerable(double samenessPercentage, int actualChanges, int totalSize) {
        assert samenessPercentage >= 0 && samenessPercentage <= 1.0;

        double changesAllowed = (1.0 - samenessPercentage) * totalSize;
        return actualChanges <= changesAllowed;
    }

    static void assertEqualsWithTolerance(String s1, String s2, double samenessPercentage) {
        assertTrue(isDifferenceTolerable(
                samenessPercentage,
                LevenshteinDistance.calculate(s1, s2),
                Math.max(s1.length(), s2.length())
        ));
    }

    static void assertEqualsWithTolerance(List<String> l1, List<String> l2, double samenessPercentage) {
        // symmetric difference
        Set<String> intersection = new HashSet<>(l1);
        intersection.retainAll(l2);

        Set<String> difference = new HashSet<>();
        difference.addAll(l1);
        difference.addAll(l2);
        difference.removeAll(intersection);

        assertTrue(isDifferenceTolerable(samenessPercentage, difference.size(), Math.max(l1.size(), l2.size())));
    }

    private static final class LevenshteinDistance {
        /**
         * Calculates the Levenshtein distance between two strings.<br/>
         * Uses a 2D array to represent individual changes, therefore the time complexity is quadratic
         * (in reference to the strings' length).
         * @param str1 the first string
         * @param str2 the second string
         * @return an integer representing the amount of atomic changes between {@code str1} and {@code str2}
         */
        public static int calculate(String str1, String str2) {
            int[][] matrix = new int[str1.length() + 1][str2.length() + 1];

            for (int i = 0; i <= str1.length(); i++) {
                for (int j = 0; j <= str2.length(); j++) {
                    if (i == 0) { // distance between "" and str2 == how long str2 is
                        matrix[i][j] = j;
                    } else if (j == 0) { // distance between str1 and "" == how long str1 is
                        matrix[i][j] = i;
                    } else {
                        int substitution = matrix[i - 1][j - 1] + substitutionCost(str1.charAt(i - 1), str2.charAt(j - 1));
                        int insertion = matrix[i][j - 1] + 1;
                        int deletion = matrix[i - 1][j] + 1;

                        matrix[i][j] = min3(substitution, insertion, deletion);
                    }
                }
            }

            return matrix[str1.length()][str2.length()]; // result is in the bottom-right corner
        }

        private static int substitutionCost(char a, char b) {
            return (a == b) ? 0 : 1;
        }

        private static int min3(int a, int b, int c) {
            return Math.min(a, Math.min(b, c));
        }
    }


    private static class StreamWrappers {
        private final ByteArrayOutputStream out;
        private final ByteArrayOutputStream err;

        private final PrintStream originalOut;
        private final PrintStream originalErr;

        public StreamWrappers() {
            out = new ByteArrayOutputStream();
            err = new ByteArrayOutputStream();
            originalOut = System.out;
            originalErr = System.err;
        }

        public void captureStreams(boolean capture) {
            System.setOut(capture ? new PrintStream(out, true, StandardCharsets.UTF_8) : originalOut);
            System.setErr(capture ? new PrintStream(err, true, StandardCharsets.UTF_8) : originalErr);
        }

        private String get(ByteArrayOutputStream which) {
            String string = which.toString(StandardCharsets.UTF_8);
            which.reset();

            return string;
        }

        public String getOut() {
            return get(out);
        }

        public byte[] getOutBytes() {
            byte[] bytes = out.toByteArray();
            out.reset();

            return bytes;
        }

        public String getErr() {
            return get(err);
        }
    }
}
