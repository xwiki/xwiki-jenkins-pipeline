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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lesfurets.jenkins.unit.BasePipelineTest;

import static com.lesfurets.jenkins.unit.MethodSignature.method;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@code itModules} pipeline library step.
 *
 * @version $Id$
 */
class ItModulesTest extends BasePipelineTest
{
    @BeforeEach
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
        getHelper().registerAllowedMethod(method("findFiles", Map.class), (Object arg) -> {
            if ("**/*-test-docker/pom.xml".equals(((Map<?, ?>) arg).get("glob"))) {
                return List.of(Map.of("path", "core/xwiki-platform-menu/xwiki-platform-menu-test/"
                    + "xwiki-platform-menu-test-docker/pom.xml"),
                    // The Docker-based test framework isn't a test module.
                    Map.of("path", "core/xwiki-platform-test/xwiki-platform-test-docker/pom.xml"));
            } else {
                return List.of(Map.of("path", "core/xwiki-platform-administration/"
                    + "xwiki-platform-administration-test/xwiki-platform-administration-test-tests/pom.xml"));
            }
        });
    }

    @Test
    void itModules()
    {
        assertEquals(List.of(
            "core/xwiki-platform-menu/xwiki-platform-menu-test/xwiki-platform-menu-test-docker",
            "core/xwiki-platform-administration/xwiki-platform-administration-test/"
                + "xwiki-platform-administration-test-tests"),
            loadScript("vars/itModules.groovy").invokeMethod("call", new Object[] {}));
    }
}
