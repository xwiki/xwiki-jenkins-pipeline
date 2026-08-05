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

import com.cloudbees.groovy.cps.NonCPS

/**
 * Build and run the flavor upgrade functional tests. Each "upgrade from version X" sub-module of the flavor upgrade
 * aggregator is discovered by the caller and passed in through {@code modules} (a list of {@code pom.xml} paths). The
 * sub-modules are split into {@code batches} groups that run in parallel across agents; within a group the versions
 * run sequentially in a single reactor (each starts XWiki on the same fixed port via {@code XWikiExecutor(0)}, so
 * there is no in-reactor parallelism).
 *
 * Config parameters (set inside the closure body):
 * <ul>
 *   <li>{@code modules} (required): list of flavor-upgrade sub-module {@code pom.xml} paths.</li>
 *   <li>{@code batches} (optional, default 2): number of parallel groups to split the sub-modules into.</li>
 *   <li>{@code jobProperties} (optional): job properties to (re)apply, e.g. to protect a cron trigger.</li>
 *   <li>{@code label} (optional, default any agent): node label to run the builds on.</li>
 * </ul>
 */
void call(body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def modulePaths = config.modules ?: []
    if (!modulePaths) {
        echoXWiki 'No flavor upgrade test modules to build, nothing to do'
        return
    }

    def upgradePom =
        "${getFlavorTestPrefix()}/xwiki-platform-distribution-flavor-test-upgrade/pom.xml"

    // Split the independent "upgrade from version X" sub-modules into `batches` groups (the groups run in parallel).
    int batches = config.batches ?: 2
    int perBatch = Math.ceil(modulePaths.size() / (double) batches) as int

    def builds = [:]
    modulePaths.collate(perBatch).eachWithIndex() { batch, i ->
        def selectors = batch.collect { getLeafModuleName(it) }
        def buildName = "Flavor Test - Upgrade #${i + 1} for ${selectors.join(', ')}"
        builds[buildName] = {
            // Default to any agent (no label): the standard agents provide Firefox + Xvnc, and using them keeps load
            // off a compute-constrained pool such as the one running the docker matrix.
            node(config.label ?: '') {
                xwikiBuild(buildName) {
                    // Use the same heap as the other flavor functional tests, plus heap-dump options for post-mortem
                    // debugging of OOMs.
                    mavenOpts = "-Xmx2048m -Xms512m ${getOOMHeapDumpMavenOpts()}".trim()
                    // Javadoc execution is on by default but we don't need it for the functional tests.
                    javadoc = false
                    goals = 'clean verify'
                    profiles = 'legacy,integration-tests,jetty,hsqldb,firefox'
                    pom = upgradePom
                    // --projects selects this group's children of the upgrade aggregator. --fail-at-end so that one
                    // version's failure doesn't skip the rest of the group.
                    mavenFlags = "--projects ${selectors.join(',')} -e -U --fail-at-end"
                    xvnc = true
                    skipChangeLog = true
                    // Reapply the passed job properties so that a racing properties() call from another build can't
                    // wipe them (e.g. the environment-test cron trigger).
                    if (config.jobProperties != null) {
                        jobProperties = config.jobProperties
                    }
                }
            }
        }
    }
    parallel builds
}

// Return the sub-module directory name (== its artifactId, used as the --projects selector) for a discovered pom path.
@NonCPS
private static String getLeafModuleName(String pomPath)
{
    def parts = pomPath.split('/')
    return parts[parts.length - 2]
}
