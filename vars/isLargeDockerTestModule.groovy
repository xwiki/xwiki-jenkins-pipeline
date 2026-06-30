#!/usr/bin/env groovy

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

/**
 * The Docker-based test modules that take a long time to execute and that we therefore want to run on their own Jenkins
 * agent instead of batching them with other modules. Batching several modules together (see {@code xwikiITBuild} and
 * {@code xwikiDockerBuild}) reduces the number of agents used and lets Maven reuse its local repository (downloaded
 * dependencies) across the batched builds. However the duration of a batch is the sum of the durations of the modules it
 * contains, so we keep these large modules out of the batches to avoid increasing the minimum build duration too much.
 *
 * Note: the entries below are matched against the individual path segments of the passed module path so that the same
 * list works both with the module paths used by {@code xwikiITBuild} (which point to the {@code *-test-docker} /
 * {@code *-test-tests} module, e.g.
 * {@code xwiki-platform-core/xwiki-platform-administration/xwiki-platform-administration-test/xwiki-platform-administration-test-docker})
 * and with those used by {@code xwikiDockerBuild} (which point to the top module, e.g.
 * {@code xwiki-platform-core/xwiki-platform-administration}).
 *
 * @param modulePath the path of the module to be tested
 * @return true if the passed module is a large module that must be executed on its own agent
 */
boolean call(String modulePath)
{
    def largeModuleNames = [
        'xwiki-platform-administration',
        'xwiki-platform-flamingo-skin'
    ]
    def segments = modulePath.tokenize('/')
    return largeModuleNames.any { segments.contains(it) }
}
