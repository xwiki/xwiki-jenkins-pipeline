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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.lesfurets.jenkins.unit.BasePipelineTest;

import groovy.lang.Closure;
import groovy.lang.Script;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@code xwikiFlavorUpgradeBuild} pipeline library step.
 *
 * @version $Id$
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class XWikiFlavorUpgradeBuildTest extends BasePipelineTest
{
    private static final String UPGRADE_PATH = "prefix/xwiki-platform-distribution-flavor-test-upgrade";

    // The configuration of each xwikiBuild call, by build name.
    private final Map<String, Map<String, Object>> buildConfigurations = new LinkedHashMap<>();

    @BeforeEach
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        this.buildConfigurations.clear();
        Script isMasterBranch = loadScript("vars/isMasterBranch.groovy");
        getHelper().registerAllowedMethod(method("isMasterBranch", String.class),
            (Object branchName) -> isMasterBranch.invokeMethod("call", new Object[] { branchName }));
        getHelper().registerAllowedMethod(method("getFlavorTestPrefix"), (Object arg) -> "prefix");
        getHelper().registerAllowedMethod(method("echoXWiki", String.class), (Object message) -> { });
        getHelper().registerAllowedMethod(method("xwikiBuild", String.class, Closure.class),
            new Closure<Void>(this)
            {
                public void doCall(String name, Closure<?> body)
                {
                    Map<String, Object> config = new HashMap<>();
                    body.setResolveStrategy(Closure.DELEGATE_FIRST);
                    body.setDelegate(config);
                    body.call();
                    buildConfigurations.put(name, config);
                }
            });
    }

    @ParameterizedTest
    @CsvSource({ "master, 30", "stable-18.4.x, ", "PR-1234, " })
    void call(String branchName, String expectedDaysToKeep)
    {
        getBinding().setVariable("env", new HashMap<>(Map.of("BRANCH_NAME", branchName)));

        loadScript("vars/xwikiFlavorUpgradeBuild.groovy").invokeMethod("call", new Object[] {
            new Closure<Void>(this)
            {
                public void doCall()
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> config = (Map<String, Object>) getDelegate();
                    config.put("modules", List.of(UPGRADE_PATH + "/upgrade-84/pom.xml",
                        UPGRADE_PATH + "/upgrade-911/pom.xml", UPGRADE_PATH + "/upgrade-1011/pom.xml"));
                }
            }
        });

        // The modules are split into the 2 default batches, selected from the upgrade aggregator.
        assertEquals(List.of("Flavor Test - Upgrade #1 for upgrade-84, upgrade-911",
            "Flavor Test - Upgrade #2 for upgrade-1011"), List.copyOf(this.buildConfigurations.keySet()));
        Map<String, Object> firstBatch = this.buildConfigurations.values().iterator().next();
        assertEquals(UPGRADE_PATH + "/pom.xml", String.valueOf(firstBatch.get("pom")));
        assertEquals("--projects upgrade-84,upgrade-911 -e -U --fail-at-end",
            String.valueOf(firstBatch.get("mavenFlags")));

        // The builds are kept as long as the docker builds that run in the same environment tests job.
        this.buildConfigurations.values()
            .forEach(config -> assertEquals(expectedDaysToKeep, config.get("daysToKeepStr")));
    }
}
