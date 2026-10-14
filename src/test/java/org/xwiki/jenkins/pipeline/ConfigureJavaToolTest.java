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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Test
    void addHeapDumpAndGCLockerOptionsForJava17()
    {
        Map<String, Object> results = configureJavaTool("java17", null);

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertTrue(mavenOpts.startsWith("-Xmx1920m -Xms256m "));
        assertTrue(mavenOpts.contains(HEAP_DUMP_OPTIONS));
        assertTrue(mavenOpts.contains(GCLOCKER_OPTIONS));
        assertEquals(HEAP_DUMP_DIRECTORY, String.valueOf(results.get("heapDumpDirectory")));
        // The JVM only writes the heap dump when its directory exists.
        assertEquals(List.of("rm -rf " + HEAP_DUMP_DIRECTORY + " && mkdir -p " + HEAP_DUMP_DIRECTORY),
            this.shellCommands);
    }

    @Test
    void addOptionsToOverriddenMavenOpts()
    {
        Map<String, Object> results = configureJavaTool("java21", "-Xmx2048m -Xms512m");

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertTrue(mavenOpts.startsWith("-Xmx2048m -Xms512m "));
        assertTrue(mavenOpts.contains(HEAP_DUMP_OPTIONS));
        assertTrue(mavenOpts.contains(GCLOCKER_OPTIONS));
    }

    @Test
    void noGCLockerOptionForJava25()
    {
        // Java 25 refuses to start when -XX:GCLockerRetryAllocationCount is set.
        Map<String, Object> results = configureJavaTool("java25", null);

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertTrue(mavenOpts.contains(HEAP_DUMP_OPTIONS));
        assertFalse(mavenOpts.contains("GCLockerRetryAllocationCount"));
    }

    @Test
    void keepExistingHeapDumpOptions()
    {
        Map<String, Object> results =
            configureJavaTool("java17", "-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/custom");

        String mavenOpts = String.valueOf(results.get("mavenOpts"));
        assertTrue(mavenOpts.contains("-XX:HeapDumpPath=/custom"));
        assertFalse(mavenOpts.contains(HEAP_DUMP_DIRECTORY));
        // No directory to archive the dumps from since they're not written in the expected place.
        assertNull(results.get("heapDumpDirectory"));
        assertTrue(this.shellCommands.isEmpty());
    }

    private Map<String, Object> configureJavaTool(String javaTool, String mavenOpts)
    {
        Map<String, Object> config = new HashMap<>();
        config.put("javaTool", javaTool);
        config.put("mavenOpts", mavenOpts);
        Script script = loadScript("vars/configureJavaTool.groovy");
        return (Map<String, Object>) script.invokeMethod("call", new Object[] { config });
    }
}
