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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.lesfurets.jenkins.unit.BasePipelineTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@code dockerConfigurations} pipeline library step.
 *
 * @version $Id$
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DockerConfigurationsTest extends BasePipelineTest
{
    @BeforeAll
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
    }

    @ParameterizedTest
    @CsvSource({
        "17.10.0, 17.10, true",
        "17.10.1-SNAPSHOT, 17.10, true",
        // The development versions of the minimal version are considered as at least that version.
        "17.10.0-SNAPSHOT, 17.10, true",
        "17.10.0-rc-1, 17.10, true",
        "17.0.0-SNAPSHOT, 17.0, true",
        "18.0.0-SNAPSHOT, 17.10, true",
        "17.9.0, 17.10, false",
        "17.9.1-SNAPSHOT, 17.10, false",
        "16.10.5, 17.0, false"
    })
    void isXWikiVersionAtLeast(String version, String minimalVersion, boolean expected)
    {
        assertEquals(expected, loadScript("vars/dockerConfigurations.groovy").invokeMethod("isXWikiVersionAtLeast",
            new Object[] { version, minimalVersion }));
    }
}
