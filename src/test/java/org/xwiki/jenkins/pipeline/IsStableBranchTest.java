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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.lesfurets.jenkins.unit.BasePipelineTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the {@code isStableBranch} pipeline library step.
 *
 * @version $Id$
 */
class IsStableBranchTest extends BasePipelineTest
{
    @BeforeEach
    @Override
    public void setUp() throws Exception
    {
        super.setUp();
    }

    @ParameterizedTest
    @CsvSource({ "stable-17.10.x, true", "stable-18.x, true", "master, false", "PR-1234, false",
        "feature-stable-something, false" })
    void isStableBranch(String branchName, boolean expected)
    {
        assertEquals(expected,
            loadScript("vars/isStableBranch.groovy").invokeMethod("call", new Object[] { branchName }));
    }
}
