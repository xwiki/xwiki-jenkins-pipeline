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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.lesfurets.jenkins.unit.BasePipelineTest;

import groovy.lang.Closure;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@code xwikiDockerBuild} pipeline library step.
 *
 * @version $Id$
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class XWikiDockerBuildTest extends BasePipelineTest
{
    private static final String MYSQL_BUILD =
        "MySQL, Tomcat, Firefox - Docker tests #1 for xwiki-platform-a, xwiki-platform-b";

    private static final String POSTGRESQL_BUILD =
        "PostgreSQL, Jetty, Chrome - Docker tests #1 for xwiki-platform-a, xwiki-platform-b";

    private static final String COMMON_PROPERTIES = "-Dxwiki.checkstyle.skip=true "
        + "-Dxwiki.surefire.captureconsole.skip=true -Dxwiki.revapi.skip=true -Dxwiki.spoon.skip=true "
        + "-Dxwiki.enforcer.skip=true";

    // The configuration of each xwikiBuild call, by build name.
    private final Map<String, Map<String, Object>> buildConfigurations = new LinkedHashMap<>();

    @BeforeEach
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        this.buildConfigurations.clear();
        getBinding().setVariable("env", new HashMap<>(Map.of("BRANCH_NAME", "master")));
        getHelper().registerAllowedMethod(method("isMasterBranch", String.class), (Object branchName) -> true);
        getHelper().registerAllowedMethod(method("echoXWiki", String.class), (Object message) -> { });
        getHelper().registerAllowedMethod(method("isBadgeFound", String.class), (Object text) -> true);
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

    @Test
    void call()
    {
        loadScript("vars/xwikiDockerBuild.groovy").invokeMethod("call", new Object[] {
            new Closure<Void>(this)
            {
                public void doCall()
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> config = (Map<String, Object>) getDelegate();
                    Map<String, Map<String, String>> configurations = new LinkedHashMap<>();
                    configurations.put("MySQL, Tomcat, Firefox", orderedMap("database", "mysql", "databaseTag", "9",
                        "servletEngine", "tomcat", "browser", "firefox"));
                    configurations.put("PostgreSQL, Jetty, Chrome",
                        orderedMap("database", "postgresql", "servletEngine", "jetty", "browser", "chrome"));
                    config.put("configurations", configurations);
                    config.put("modules",
                        List.of("xwiki-platform-core/xwiki-platform-a", "xwiki-platform-core/xwiki-platform-b"));
                    config.put("type", "docker-latest");
                }
            }
        });

        // The test framework is built first, then each batch of modules for each configuration.
        assertEquals(List.of("Test Framework", MYSQL_BUILD, POSTGRESQL_BUILD),
            List.copyOf(this.buildConfigurations.keySet()));
        // Each configuration is passed as system properties, and the WCAG tests only run for the first one.
        assertEquals("-Dmaven.build.dir=target/mysql-9-default-tomcat-default-firefox " + COMMON_PROPERTIES
            + " -Dxwiki.test.ui.database=mysql -Dxwiki.test.ui.databaseTag=9 -Dxwiki.test.ui.servletEngine=tomcat"
            + " -Dxwiki.test.ui.browser=firefox -Dxwiki.test.ui.wcag=true",
            String.valueOf(this.buildConfigurations.get(MYSQL_BUILD).get("properties")));
        assertEquals("-Dmaven.build.dir=target/postgresql-default-default-jetty-default-chrome " + COMMON_PROPERTIES
            + " -Dxwiki.test.ui.database=postgresql -Dxwiki.test.ui.servletEngine=jetty"
            + " -Dxwiki.test.ui.browser=chrome",
            String.valueOf(this.buildConfigurations.get(POSTGRESQL_BUILD).get("properties")));
    }

    private static Map<String, String> orderedMap(String... keysAndValues)
    {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
