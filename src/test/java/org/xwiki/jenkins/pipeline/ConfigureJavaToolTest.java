/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.jenkins.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.lesfurets.jenkins.unit.BasePipelineTest;

import groovy.lang.Script;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code configureJavaTool} pipeline library step.
 *
 * @version $Id$
 */
class ConfigureJavaToolTest extends BasePipelineTest
{
    private static final String HEAP_DUMP_DIRECTORY = "/ws@tmp/maven-heap-dumps";

    private static final String HEAP_DUMP_OPTIONS =
        "-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=" + HEAP_DUMP_DIRECTORY;

    private static final String GCLOCKER_OPTIONS =
        "-XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=100";

    private final List<String> shellCommands = new ArrayList<>();

    @BeforeEach
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        this.shellCommands.clear();
        getHelper().registerAllowedMethod(method("echoXWiki", String.class), (Object arg) -> { });
        getHelper().registerAllowedMethod(method("pwd", Map.class), (Object arg) -> "/ws@tmp");
        getHelper().registerAllowedMethod(method("sh", String.class), (Object command) -> {
            this.shellCommands.add(String.valueOf(command));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "java8", "java11", "java17", "java21" })
    void addHeapDumpAndGCLockerOptions(String javaTool)
    {
        Map<?, ?> results = configureJavaTool(javaTool, null);

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertTrue(mavenOpts.startsWith("-Xmx1920m -Xms256m "));
        assertEquals(1, countOccurrences(mavenOpts, HEAP_DUMP_OPTIONS));
        assertEquals(1, countOccurrences(mavenOpts, GCLOCKER_OPTIONS));
        assertEquals(HEAP_DUMP_DIRECTORY, String.valueOf(results.get("heapDumpDirectory")));
        // The JVM only writes the heap dump when its directory exists.
        assertEquals(List.of("rm -rf " + HEAP_DUMP_DIRECTORY + " && mkdir -p " + HEAP_DUMP_DIRECTORY),
            this.shellCommands);
    }

    @Test
    void addOptionsToOverriddenMavenOpts()
    {
        Map<?, ?> results = configureJavaTool("java21", "-Xmx2048m -Xms512m");

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertTrue(mavenOpts.startsWith("-Xmx2048m -Xms512m "));
        assertEquals(1, countOccurrences(mavenOpts, HEAP_DUMP_OPTIONS));
        assertEquals(1, countOccurrences(mavenOpts, GCLOCKER_OPTIONS));
    }

    @ParameterizedTest
    @ValueSource(strings = { "java7", "java25", "official" })
    void noGCLockerOption(String javaTool)
    {
        // Java 7 doesn't know -XX:GCLockerRetryAllocationCount and Java 25 refuses to start when it's set. The version
        // of the "official" Java tool isn't known.
        Map<?, ?> results = configureJavaTool(javaTool, null);

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertEquals(1, countOccurrences(mavenOpts, HEAP_DUMP_OPTIONS));
        assertFalse(mavenOpts.contains("GCLockerRetryAllocationCount"));
    }

    @Test
    void keepExistingGCLockerOption()
    {
        Map<?, ?> results =
            configureJavaTool("java17", "-Xmx2048m -XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=50");

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertEquals(1, countOccurrences(mavenOpts, "GCLockerRetryAllocationCount"));
        assertTrue(mavenOpts.contains("-XX:GCLockerRetryAllocationCount=50"));
    }

    @Test
    void keepExistingHeapDumpOptions()
    {
        Map<?, ?> results =
            configureJavaTool("java17", "-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/custom");

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertEquals(1, countOccurrences(mavenOpts, "HeapDumpOnOutOfMemoryError"));
        assertTrue(mavenOpts.contains("-XX:HeapDumpPath=/custom"));
        assertFalse(mavenOpts.contains(HEAP_DUMP_DIRECTORY));
        // No directory to archive the dumps from since they're not written in the expected place.
        assertNull(results.get("heapDumpDirectory"));
        assertTrue(this.shellCommands.isEmpty());
    }

    @Test
    void useJava11ForSonarWhenNoJavaVersionInPOM()
    {
        getHelper().registerAllowedMethod(method("getPOMFile", Map.class), (Object arg) -> "pom.xml");
        // No xwiki.java.version property in the effective pom.
        getHelper().registerAllowedMethod(method("sh", Map.class), (Object arg) -> "null object or invalid expression");

        Map<String, Object> config = new HashMap<>();
        config.put("sonar", true);
        Map<?, ?> results = configureJavaTool(config);

        assertEquals("java11", results.get("jdk"));
    }

    private Map<?, ?> configureJavaTool(String javaTool, String mavenOpts)
    {
        Map<String, Object> config = new HashMap<>();
        config.put("javaTool", javaTool);
        config.put("mavenOpts", mavenOpts);
        return configureJavaTool(config);
    }

    private Map<?, ?> configureJavaTool(Map<String, Object> config)
    {
        Script script = loadScript("vars/configureJavaTool.groovy");
        return (Map<?, ?>) script.invokeMethod("call", new Object[] { config });
    }

    private int countOccurrences(String text, String searched)
    {
        return text.split(Pattern.quote(searched), -1).length - 1;
    }
}
