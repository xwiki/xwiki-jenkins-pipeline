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

import groovy.lang.Closure;
import groovy.lang.Script;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code pnpmAudit} pipeline library step.
 *
 * @version $Id$
 */
class PnpmAuditTest extends BasePipelineTest
{
    private static final String PLUGIN = "com.github.eirslett:frontend-maven-plugin";

    private final List<String> shellCommands = new ArrayList<>();

    private final List<String> messages = new ArrayList<>();

    private final List<String> mails = new ArrayList<>();

    private final List<String> lockFiles = new ArrayList<>();

    private final Map<String, String> pomProperties = new HashMap<>();

    private final Map<String, Integer> statuses = new HashMap<>();

    private Map<String, Object> currentBuild;

    @BeforeEach
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        this.shellCommands.clear();
        this.messages.clear();
        this.mails.clear();
        this.lockFiles.clear();
        this.statuses.clear();
        this.pomProperties.clear();
        this.pomProperties.put("node.version", "v24.21.0");
        this.pomProperties.put("pnpm.version", "11.28.4");
        this.currentBuild = new HashMap<>();
        this.currentBuild.put("result", "SUCCESS");
        getBinding().setVariable("currentBuild", this.currentBuild);

        getHelper().registerAllowedMethod(method("echoXWiki", String.class),
            (Object message) -> this.messages.add(String.valueOf(message)));
        getHelper().registerAllowedMethod(method("pwd"), new Closure<String>(this)
        {
            public String doCall()
            {
                return "/ws";
            }
        });
        getHelper().registerAllowedMethod(method("pwd", Map.class), (Object arg) -> "/ws@tmp");
        getHelper().registerAllowedMethod(method("error", String.class), (Object message) -> {
            throw new IllegalStateException(String.valueOf(message));
        });
        getHelper().registerAllowedMethod(method("findFiles", Map.class), (Object arg) -> {
            List<Map<String, String>> foundFiles = new ArrayList<>();
            this.lockFiles.forEach(path -> foundFiles.add(Map.of("path", path)));
            return foundFiles;
        });
        getHelper().registerAllowedMethod(method("withMaven", Map.class, Closure.class), new Closure<Void>(this)
        {
            public void doCall(Map<?, ?> arguments, Closure<?> body)
            {
                body.call();
            }
        });
        for (String publisher : List.of("artifactsPublisher", "dependenciesFingerprintPublisher", "invokerPublisher",
            "junitPublisher")) {
            getHelper().registerAllowedMethod(method(publisher, Map.class), (Object arg) -> publisher);
        }
        getHelper().registerAllowedMethod(method("sendMail", String.class, String.class), new Closure<Void>(this)
        {
            public void doCall(String status, String name)
            {
                PnpmAuditTest.this.mails.add(status + ":" + name);
            }
        });
        getHelper().registerAllowedMethod(method("sh", Map.class), (Object arg) -> {
            Map<?, ?> parameters = (Map<?, ?>) arg;
            String command = String.valueOf(parameters.get("script"));
            this.shellCommands.add(command);
            if (parameters.get("returnStdout") == Boolean.TRUE) {
                return this.pomProperties.getOrDefault(command.replaceAll(".*-Dexpression=(\\S+) .*", "$1"),
                    "null object or invalid expression");
            }
            return this.statuses.entrySet().stream().filter(entry -> command.contains(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(0);
        });
    }

    @Test
    void noLockFile()
    {
        pnpmAudit(new HashMap<>());

        assertTrue(this.shellCommands.isEmpty(), this.shellCommands.toString());
        assertEquals("SUCCESS", this.currentBuild.get("result"));
        assertTrue(this.mails.isEmpty());
    }

    @Test
    void oneLockFile()
    {
        this.lockFiles.add("pnpm-lock.yaml");

        pnpmAudit(new HashMap<>());

        assertEquals(4, this.shellCommands.size(), this.shellCommands.toString());
        assertEquals("mvn -f pom.xml -N help:evaluate -Dexpression=node.version -q -DforceStdout",
            this.shellCommands.get(0));
        assertEquals("mvn -f pom.xml -N help:evaluate -Dexpression=pnpm.version -q -DforceStdout",
            this.shellCommands.get(1));
        assertEquals("mvn -f pom.xml -N --no-transfer-progress " + PLUGIN + ":install-node-and-pnpm "
            + "-DinstallDirectory=/ws@tmp/pnpm-audit -DnodeVersion=v24.21.0 -DpnpmVersion=11.28.4",
            this.shellCommands.get(2));
        assertEquals("mvn -f pom.xml -N --no-transfer-progress " + PLUGIN + ":pnpm "
            + "-DinstallDirectory=/ws@tmp/pnpm-audit -DworkingDirectory=/ws/. "
            + "'-Dfrontend.pnpm.arguments=audit --audit-level=high'", this.shellCommands.get(3));
        assertEquals("SUCCESS", this.currentBuild.get("result"));
        assertTrue(this.mails.isEmpty());
    }

    @Test
    void severalLockFiles()
    {
        this.lockFiles.add("pnpm-lock.yaml");
        this.lockFiles.add("module/sub/pnpm-lock.yaml");

        pnpmAudit(new HashMap<>());

        // The versions are read once, Node and pnpm are installed once, and each lockfile is audited from its own
        // directory.
        assertEquals(5, this.shellCommands.size(), this.shellCommands.toString());
        assertEquals(1, this.shellCommands.stream().filter(command -> command.contains(":install-node")).count());
        assertTrue(this.shellCommands.get(3).contains("-DworkingDirectory=/ws/. "));
        assertTrue(this.shellCommands.get(4).contains("-DworkingDirectory=/ws/module/sub "));
        assertEquals("SUCCESS", this.currentBuild.get("result"));
    }

    @Test
    void customPomFile()
    {
        this.lockFiles.add("pnpm-lock.yaml");

        Map<String, Object> config = new HashMap<>();
        config.put("pom", "other/pom.xml");
        pnpmAudit(config);

        assertTrue(this.shellCommands.stream().allMatch(command -> command.startsWith("mvn -f other/pom.xml ")),
            this.shellCommands.toString());
    }

    @Test
    void auditFailure()
    {
        this.lockFiles.add("pnpm-lock.yaml");
        this.lockFiles.add("module/pnpm-lock.yaml");
        this.statuses.put("-DworkingDirectory=/ws/module ", 1);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> pnpmAudit(new HashMap<>()));

        // The other lockfile is still audited before the pipeline is stopped.
        assertEquals("pnpm audit failed: pnpm audit failed for module/pnpm-lock.yaml (exit code 1), see the audit "
            + "output above", error.getMessage());
        assertEquals(5, this.shellCommands.size(), this.shellCommands.toString());
        assertEquals("FAILURE", this.currentBuild.get("result"));
        assertEquals(List.of("ERROR:Default"), this.mails);
        assertTrue(this.messages.contains("ERROR: pnpm audit failed for module/pnpm-lock.yaml (exit code 1), see the "
            + "audit output above"), this.messages.toString());
    }

    @Test
    void auditFailureWithSkipMail()
    {
        this.lockFiles.add("pnpm-lock.yaml");
        this.statuses.put(":pnpm ", 1);

        Map<String, Object> config = new HashMap<>();
        config.put("skipMail", true);
        assertThrows(IllegalStateException.class, () -> pnpmAudit(config));

        assertEquals("FAILURE", this.currentBuild.get("result"));
        assertTrue(this.mails.isEmpty());
    }

    @Test
    void installFailure()
    {
        this.lockFiles.add("pnpm-lock.yaml");
        this.statuses.put(":install-node-and-pnpm ", 1);

        assertThrows(IllegalStateException.class, () -> pnpmAudit(new HashMap<>()));

        assertEquals(3, this.shellCommands.size(), this.shellCommands.toString());
        assertEquals("FAILURE", this.currentBuild.get("result"));
        assertEquals(List.of("ERROR:Default"), this.mails);
    }

    @Test
    void missingVersionProperties()
    {
        this.lockFiles.add("pnpm-lock.yaml");
        this.pomProperties.remove("pnpm.version");

        assertThrows(IllegalStateException.class, () -> pnpmAudit(new HashMap<>()));

        // No guessed version: nothing is installed or audited.
        assertTrue(this.shellCommands.stream().noneMatch(command -> command.contains(PLUGIN)),
            this.shellCommands.toString());
        assertEquals("FAILURE", this.currentBuild.get("result"));
        assertEquals(List.of("ERROR:Default"), this.mails);
        assertTrue(this.messages.contains("ERROR: Cannot run the pnpm audit: the node.version [v24.21.0] and "
            + "pnpm.version [null] properties must be defined in pom.xml"), this.messages.toString());
    }

    private void pnpmAudit(Map<String, Object> config)
    {
        Map<String, Object> javaMavenConfig = new HashMap<>();
        javaMavenConfig.put("jdk", "java21");
        javaMavenConfig.put("mavenOpts", "-Xmx1920m");
        Script script = loadScript("vars/pnpmAudit.groovy");
        script.invokeMethod("call", new Object[] { config, javaMavenConfig, "Default" });
    }
}
