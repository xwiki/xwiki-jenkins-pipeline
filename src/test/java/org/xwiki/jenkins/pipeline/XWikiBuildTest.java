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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.lesfurets.jenkins.unit.BasePipelineTest;
import com.lesfurets.jenkins.unit.MethodCall;

import groovy.lang.Script;
import hudson.FilePath;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pipeline library steps.
 *
 * @version $Id$
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class XWikiBuildTest extends BasePipelineTest
{
    private static final String HEAP_DUMP_DIRECTORY = "/ws@tmp/maven-heap-dumps";

    @BeforeAll
    public void setUp() throws Exception
    {
        super.setUp();
    }

    @Test
    void getKnownFlickeringTests()
    {
        Script script = loadScript("vars/xwikiBuild.groovy");
        Map<String, String> flickers =
            (Map<String, String>) script.invokeMethod("getKnownFlickeringTests", new Object[] {});
        assertNotNull(flickers);
        // Verify we get results (note that this will fail in case we fix all the flickering tests, yeah one can dream)
        assertTrue(flickers.size() > 0);
    }

    @Test
    void computeArchivedArtifactPath()
    {
        Script script = loadScript("vars/xwikiBuild.groovy");

        // A screenshot saved in a "screenshots" directory of a Maven build directory is archived by the pipeline, so its
        // path relative to the directory the archiving was done from is returned (the URL encoding is done by
        // computeArchivedArtifactUrl, this path is also used as an archiving pattern).
        assertEquals("module/target/config/screenshots/db-firefox-org.xwiki.AllIT$NestedIT-verify1.png",
            computeArchivedArtifactPath(script,
                "/ws/module/target/config/screenshots/db-firefox-org.xwiki.AllIT$NestedIT-verify1.png", "/ws"));

        // Videos are archived from anywhere inside a Maven build directory.
        assertEquals("module/target/screenshots/db-firefox-org.xwiki.AllIT-verify.flv", computeArchivedArtifactPath(
            script, "/ws/module/target/screenshots/db-firefox-org.xwiki.AllIT-verify.flv", "/ws"));

        // A trailing separator on the archiving directory is supported.
        assertEquals("module/target/screenshots/test.png",
            computeArchivedArtifactPath(script, "/ws/module/target/screenshots/test.png", "/ws/"));

        // Not archived, and thus no path: a file outside the directory the archiving was done from (e.g. a screenshot
        // saved in the temporary directory)...
        assertNull(computeArchivedArtifactPath(script, "/tmp/db-firefox-org.xwiki.AllIT-verify.png", "/ws"));
        // ... a screenshot outside a "screenshots" directory (e.g. in the legacy "selenium-screenshots" one)...
        assertNull(computeArchivedArtifactPath(script, "/ws/module/target/selenium-screenshots/test.png", "/ws"));
        // ... a file outside a Maven build directory...
        assertNull(computeArchivedArtifactPath(script, "/ws/module/screenshots/test.png", "/ws"));
        // ... and a file inside a "node_modules" directory, which the archiving excludes.
        assertNull(computeArchivedArtifactPath(script, "/ws/m/node_modules/n/target/screenshots/test.png", "/ws"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "master", "main", "stable-17.10.x" })
    void archiveMavenHeapDumps(String branchName)
    {
        List<String> shellCommands = registerHeapDumpMethods(0);
        Script script = loadScript("vars/xwikiBuild.groovy");

        script.invokeMethod("archiveMavenHeapDumps", new Object[] { HEAP_DUMP_DIRECTORY, branchName });

        // The dumps are compressed and moved to the workspace since archiveArtifacts only takes files from there.
        assertEquals(List.of("gzip /ws@tmp/maven-heap-dumps/*.hprof && rm -rf maven-heap-dumps && mkdir "
            + "maven-heap-dumps && mv /ws@tmp/maven-heap-dumps/*.hprof.gz maven-heap-dumps/"), shellCommands);
        List<MethodCall> archiveCalls = getMethodCalls("archiveArtifacts");
        assertEquals(1, archiveCalls.size());
        assertEquals("maven-heap-dumps/*.hprof.gz", ((Map<?, ?>) archiveCalls.get(0).getArgs()[0]).get("artifacts"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "PR-1234", "feature-deploy-something" })
    void archiveMavenHeapDumpsOnlyForMasterAndStableBranches(String branchName)
    {
        // There are dumps but they're not archived, to limit the disk space used.
        List<String> shellCommands = registerHeapDumpMethods(0);
        Script script = loadScript("vars/xwikiBuild.groovy");

        script.invokeMethod("archiveMavenHeapDumps", new Object[] { HEAP_DUMP_DIRECTORY, branchName });

        assertTrue(shellCommands.isEmpty());
        assertTrue(getMethodCalls("archiveArtifacts").isEmpty());
    }

    @Test
    void archiveMavenHeapDumpsWhenNoDump()
    {
        // The "ls" command fails when there's no dump.
        List<String> shellCommands = registerHeapDumpMethods(2);
        Script script = loadScript("vars/xwikiBuild.groovy");

        script.invokeMethod("archiveMavenHeapDumps", new Object[] { HEAP_DUMP_DIRECTORY, "master" });
        // No heap dump directory when the job configures its own heap dump options (see configureJavaTool).
        script.invokeMethod("archiveMavenHeapDumps", new Object[] { null, "master" });

        assertTrue(shellCommands.isEmpty());
        assertTrue(getMethodCalls("archiveArtifacts").isEmpty());
        assertEquals(1, getMethodCalls("sh").size());
    }

    private List<String> registerHeapDumpMethods(int lsExitCode)
    {
        getHelper().getCallStack().clear();
        List<String> shellCommands = new ArrayList<>();
        getHelper().registerAllowedMethod(method("echoXWiki", String.class), (Object arg) -> { });
        getHelper().registerAllowedMethod(method("sh", Map.class), (Object arg) -> lsExitCode);
        getHelper().registerAllowedMethod(method("sh", String.class), (Object command) -> {
            shellCommands.add(String.valueOf(command));
        });
        getHelper().registerAllowedMethod(method("archiveArtifacts", Map.class), (Object arg) -> { });
        // Same logic as the isMasterBranch library step, which isn't loaded by this test.
        getHelper().registerAllowedMethod(method("isMasterBranch", String.class),
            (Object branchName) -> "master".equals(branchName) || "main".equals(branchName));
        return shellCommands;
    }

    private List<MethodCall> getMethodCalls(String methodName)
    {
        return getHelper().getCallStack().stream().filter(call -> call.getMethodName().equals(methodName))
            .collect(Collectors.toList());
    }

    private Object computeArchivedArtifactPath(Script script, String filePath, String archivingDirectory)
    {
        return script.invokeMethod("computeArchivedArtifactPath",
            new Object[] { new FilePath(new File(filePath)), archivingDirectory });
    }
}