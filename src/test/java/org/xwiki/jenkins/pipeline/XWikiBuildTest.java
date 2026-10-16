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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.lesfurets.jenkins.unit.BasePipelineTest;

import groovy.lang.Script;
import hudson.FilePath;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void isMonthlyTriggerEnabled()
    {
        Script script = loadScript("vars/xwikiBuild.groovy");

        Map<String, Object> config = new HashMap<>();
        assertTrue((Boolean) script.invokeMethod("isMonthlyTriggerEnabled", new Object[] { config }));
        config.put("monthlyTrigger", true);
        assertTrue((Boolean) script.invokeMethod("isMonthlyTriggerEnabled", new Object[] { config }));
        config.put("monthlyTrigger", false);
        assertFalse((Boolean) script.invokeMethod("isMonthlyTriggerEnabled", new Object[] { config }));
    }

    @Test
    void computeMavenGoals()
    {
        getHelper().registerAllowedMethod(method("isMasterBranch", String.class), (Object branch) -> false);
        getHelper().registerAllowedMethod(method("isStableBranch", String.class), (Object branch) -> false);
        getHelper().registerAllowedMethod(method("isFeatureDeploymentBranch", String.class), (Object branch) -> false);
        Script script = loadScript("vars/xwikiBuild.groovy");
        getBinding().setVariable("env", new HashMap<>(Map.of("BRANCH_NAME", "feature")));

        Map<String, Object> config = new HashMap<>();
        assertEquals("clean install", computeMavenGoals(script, config));
        config.put("goals", "clean deploy");
        assertEquals("clean deploy", computeMavenGoals(script, config));

        // Asking for a SonarQube analysis adds the jacoco:report and sonar:sonar goals, to the default goals or to the
        // configured ones...
        config.put("sonar", true);
        assertEquals("clean deploy jacoco:report sonar:sonar", computeMavenGoals(script, config));
        config.remove("goals");
        assertEquals("clean install jacoco:report sonar:sonar", computeMavenGoals(script, config));
        // ... with the jacoco:report goal executed before the sonar:sonar one...
        config.put("goals", "clean sonar:sonar install");
        assertEquals("clean jacoco:report sonar:sonar install", computeMavenGoals(script, config));
        // ... and without duplicating the goals already present.
        config.put("goals", "jacoco:report sonar:sonar");
        assertEquals("jacoco:report sonar:sonar", computeMavenGoals(script, config));
    }

    @Test
    void setPackagesVersion()
    {
        Map<String, String> files = new HashMap<>();
        files.put("package.json", "{\"name\":\"root\",\"version\":\"1.0.0\"}");
        files.put("module/package.json", "{\"name\":\"module\",\"version\":\"1.0.0\"}");
        files.put("private/package.json", "{\"name\":\"private\",\"version\":\"1.0.0\",\"private\":true}");
        Map<String, String> writtenFiles = new HashMap<>();
        List<String> messages = new ArrayList<>();
        getHelper().registerAllowedMethod(method("findFiles", Map.class), (Object arg) -> {
            List<Map<String, String>> foundFiles = new ArrayList<>();
            files.keySet().forEach(path -> foundFiles.add(Map.of("path", path)));
            return foundFiles;
        });
        getHelper().registerAllowedMethod(method("readFile", String.class), (Object path) -> files.get(path));
        getHelper().registerAllowedMethod(method("writeFile", Map.class), (Object arg) -> {
            Map<?, ?> parameters = (Map<?, ?>) arg;
            writtenFiles.put(String.valueOf(parameters.get("file")), String.valueOf(parameters.get("text")));
        });
        getHelper().registerAllowedMethod(method("echoXWiki", String.class), (Object message) -> {
            messages.add(String.valueOf(message));
        });

        Script script = loadScript("vars/xwikiBuild.groovy");
        script.invokeMethod("setPackagesVersion", new Object[] { "17.10.0-feature-SNAPSHOT" });

        // Only the non-private packages located below the workspace root are modified.
        assertEquals(List.of("module/package.json"), new ArrayList<>(writtenFiles.keySet()));
        String moduleContent = writtenFiles.get("module/package.json");
        assertTrue(moduleContent.contains("\"version\": \"17.10.0-feature-SNAPSHOT\""), moduleContent);
        assertTrue(moduleContent.contains("\"name\": \"module\""), moduleContent);
        assertTrue(messages.isEmpty(), messages.toString());
    }

    @ParameterizedTest
    @CsvSource({
        "org.xwiki.menu.test.ui.MenuIT, true",
        "org.xwiki.menu.test.ui.AllIT$NestedMenuIT, true",
        // A nested class of a functional test is a functional test too.
        "org.xwiki.menu.test.ui.AllIT$NestedTest, true",
        // The test suite of the legacy functional tests.
        "org.xwiki.test.ui.AllITs, true",
        "org.xwiki.menu.MenuTest, false",
        // Class names containing "IT" without being functional tests.
        "org.xwiki.pdf.ITextRendererTest, false",
        "org.xwiki.ITest, false",
        "org.xwiki.it.SomeTest, false"
    })
    void isFunctionalTestClass(String className, boolean expected)
    {
        assertEquals(expected,
            loadScript("vars/xwikiBuild.groovy").invokeMethod("isFunctionalTestClass", new Object[] { className }));
    }

    @Test
    void attachMavenCommandToFailingTests()
    {
        List<String> savedDescriptions = new ArrayList<>();
        FakeCaseResult test = new FakeCaseResult("org.xwiki.MenuIT", "verifyMenu", "<h3>Screenshot</h3>");
        getHelper().registerAllowedMethod(method("saveCurrentBuildChanges"),
            (Object arg) -> savedDescriptions.add(test.getDescription()));
        getHelper().registerAllowedMethod(method("echoXWiki", String.class), (Object message) -> { });

        Script script = loadScript("vars/xwikiBuild.groovy");
        Object[] arguments = new Object[] { List.of(test), "Main", "mvn clean install" };
        script.invokeMethod("attachMavenCommandToFailingTests", arguments);
        // Attaching the command again doesn't change the description.
        script.invokeMethod("attachMavenCommandToFailingTests", arguments);

        // The command is appended to the existing description, which is saved once.
        assertEquals(List.of("<h3>Screenshot</h3><h3>Maven command (build <tt>Main</tt>)</h3>"
            + "<pre style='white-space: pre-wrap; word-wrap: break-word'>mvn clean install</pre>"), savedDescriptions);
    }

    private String computeMavenGoals(Script script, Map<String, Object> config)
    {
        return String.valueOf(script.invokeMethod("computeMavenGoals", new Object[] { config }));
    }

    private Object computeArchivedArtifactPath(Script script, String filePath, String archivingDirectory)
    {
        return script.invokeMethod("computeArchivedArtifactPath",
            new Object[] { new FilePath(new File(filePath)), archivingDirectory });
    }

    /**
     * Exposes the {@code hudson.tasks.junit.CaseResult} methods used by the pipeline to read and set test descriptions.
     */
    public static class FakeCaseResult
    {
        private final String className;

        private final String name;

        private String description;

        FakeCaseResult(String className, String name, String description)
        {
            this.className = className;
            this.name = name;
            this.description = description;
        }

        public String getClassName()
        {
            return this.className;
        }

        public String getName()
        {
            return this.name;
        }

        public String getDescription()
        {
            return this.description;
        }

        public void setDescription(String description)
        {
            this.description = description;
        }
    }
}
