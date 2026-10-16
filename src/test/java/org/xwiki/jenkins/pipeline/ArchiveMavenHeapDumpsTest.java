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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.lesfurets.jenkins.unit.BasePipelineTest;
import com.lesfurets.jenkins.unit.MethodCall;

import groovy.lang.Script;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code archiveMavenHeapDumps} pipeline library step.
 *
 * @version $Id$
 */
class ArchiveMavenHeapDumpsTest extends BasePipelineTest
{
    private static final String HEAP_DUMP_DIRECTORY = "/ws@tmp/maven-heap-dumps";

    private final List<String> shellCommands = new ArrayList<>();

    private final List<String> messages = new ArrayList<>();

    private int lsExitCode;

    @BeforeEach
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        this.shellCommands.clear();
        this.messages.clear();
        this.lsExitCode = 0;
        getHelper().registerAllowedMethod(method("echoXWiki", String.class), (Object message) -> {
            this.messages.add(String.valueOf(message));
        });
        // The "ls" command fails when there's no dump.
        getHelper().registerAllowedMethod(method("sh", Map.class), (Object arg) -> this.lsExitCode);
        getHelper().registerAllowedMethod(method("sh", String.class), (Object command) -> {
            this.shellCommands.add(String.valueOf(command));
        });
        getHelper().registerAllowedMethod(method("archiveArtifacts", Map.class), (Object arg) -> { });
        // Use the actual library steps.
        registerStep("isMasterBranch");
        registerStep("isStableBranch");
    }

    @ParameterizedTest
    @ValueSource(strings = { "master", "main", "stable-17.10.x" })
    void archive(String branchName)
    {
        archiveMavenHeapDumps(HEAP_DUMP_DIRECTORY, branchName, "Flavor Test - UI");

        // The dumps are compressed and moved to the workspace since archiveArtifacts only takes files from there, in a
        // directory named after the build.
        assertEquals(List.of("gzip /ws@tmp/maven-heap-dumps/*.hprof && rm -rf maven-heap-dumps/Flavor_Test_-_UI "
            + "&& mkdir -p maven-heap-dumps/Flavor_Test_-_UI && mv /ws@tmp/maven-heap-dumps/*.hprof.gz "
            + "maven-heap-dumps/Flavor_Test_-_UI/"), this.shellCommands);
        List<MethodCall> archiveCalls = getMethodCalls("archiveArtifacts");
        assertEquals(1, archiveCalls.size());
        assertEquals("maven-heap-dumps/Flavor_Test_-_UI/*.hprof.gz",
            String.valueOf(((Map<?, ?>) archiveCalls.get(0).getArgs()[0]).get("artifacts")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "PR-1234", "feature-deploy-something" })
    void archiveOnlyForMasterAndStableBranches(String branchName)
    {
        // There are dumps but they're not archived, to limit the disk space used, and they're deleted to free the disk
        // space of the agent.
        archiveMavenHeapDumps(HEAP_DUMP_DIRECTORY, branchName, "Main");

        assertEquals(List.of("rm -f /ws@tmp/maven-heap-dumps/*.hprof"), this.shellCommands);
        assertTrue(getMethodCalls("archiveArtifacts").isEmpty());
        assertEquals(List.of("Deleting the Maven heap dumps found in /ws@tmp/maven-heap-dumps since they're only "
            + "archived for the master and stable branches"), this.messages);
    }

    @Test
    void archiveWhenNoDump()
    {
        this.lsExitCode = 2;

        archiveMavenHeapDumps(HEAP_DUMP_DIRECTORY, "master", "Main");

        assertTrue(this.shellCommands.isEmpty());
        assertTrue(getMethodCalls("archiveArtifacts").isEmpty());
        assertTrue(this.messages.isEmpty());
    }

    @Test
    void archiveWhenNoHeapDumpDirectory()
    {
        // No heap dump directory when the job configures its own heap dump options (see configureJavaTool), in which
        // case the dumps aren't even looked for.
        archiveMavenHeapDumps(null, "master", "Main");

        assertTrue(getMethodCalls("sh").isEmpty());
        assertTrue(getMethodCalls("archiveArtifacts").isEmpty());
    }

    @Test
    void archiveWhenFailing()
    {
        getHelper().registerAllowedMethod(method("sh", String.class), (Object command) -> {
            throw new IllegalStateException("gzip: No space left on device");
        });

        // The failure is only logged, so that it doesn't replace the failure of the build or prevent the processing
        // of its results.
        archiveMavenHeapDumps(HEAP_DUMP_DIRECTORY, "master", "Main");

        assertTrue(getMethodCalls("archiveArtifacts").isEmpty());
        assertEquals("Failed to archive the Maven heap dumps found in /ws@tmp/maven-heap-dumps: gzip: No space left "
            + "on device", this.messages.get(this.messages.size() - 1));
    }

    private void archiveMavenHeapDumps(String heapDumpDirectory, String branchName, String buildName)
    {
        Script script = loadScript("vars/archiveMavenHeapDumps.groovy");
        script.invokeMethod("call", new Object[] { heapDumpDirectory, branchName, buildName });
    }

    private void registerStep(String stepName)
    {
        Script step = loadScript(String.format("vars/%s.groovy", stepName));
        getHelper().registerAllowedMethod(method(stepName, String.class),
            (Object arg) -> step.invokeMethod("call", new Object[] { arg }));
    }

    private List<MethodCall> getMethodCalls(String methodName)
    {
        return getHelper().getCallStack().stream().filter(call -> call.getMethodName().equals(methodName))
            .collect(Collectors.toList());
    }
}
