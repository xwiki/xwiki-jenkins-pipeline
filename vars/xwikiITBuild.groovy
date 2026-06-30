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

void call(boolean isParallel = true, body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    echoXWiki "Modules to execute: ${config.modules}"

    def profiles = 'docker,legacy,integration-tests,snapshotModules,distribution,flavor-integration-tests'

    // Partition the modules into the large modules (each executed on its own agent) and the other modules (grouped in
    // batches built several modules per agent). See isLargeDockerTestModule() for the rationale.
    def largeModules = config.modules.findAll { isLargeDockerTestModule(it) }
    def otherModules = config.modules.findAll { !isLargeDockerTestModule(it) }
    echoXWiki "Large modules (one agent each): ${largeModules}"
    echoXWiki "Other modules (batched): ${otherModules}"

    def builds = [:]

    // Build the non-large modules in batches, each batch being built on a single agent. This amortizes the cost of
    // acquiring an agent, checking out the sources and pulling Docker images over several modules, and lets Maven reuse
    // its local repository across all the modules of a batch. We still pass -U so that snapshot dependencies are
    // refreshed from the remote repository on every batch, to avoid stale dependencies and to detect dependencies that
    // are no longer available on Nexus. We pass --fail-at-end so that a failure while building one module of the batch
    // doesn't prevent the other modules of the same batch from being built and tested.
    def batchSize = config.batchSize ?: 4
    otherModules.collate(batchSize).eachWithIndex() { batch, i ->
        def batchName = "IT batch ${i + 1} (${batch.collect { it.substring(it.lastIndexOf('/') + 1) }.join(', ')})"
        builds[batchName] = {
            build(
                name: batchName,
                profiles: profiles,
                properties: "${getSystemProperties().join(' ')}",
                mavenFlags: "--projects ${batch.join(',')} -e -U --fail-at-end",
                xvnc: true,
                goals: 'clean verify',
                skipMail: config.skipMail,
                jobProperties: config.jobProperties,
                label: config.label
            )
        }
    }

    // Build each large module on its own agent (unbatched) since they take a long time to execute and batching them
    // would increase the minimum build duration.
    largeModules.each() { modulePath ->
        builds["IT for ${modulePath}"] = {
            build(
                name: "IT for ${modulePath}",
                profiles: profiles,
                properties: "${getSystemProperties().join(' ')}",
                mavenFlags: "--projects ${modulePath} -e -U",
                xvnc: true,
                goals: 'clean verify',
                skipMail: config.skipMail,
                jobProperties: config.jobProperties,
                label: config.label
            )
        }
    }

    if (isParallel) {
        parallel builds
    } else {
        builds.each() { key, build ->
            build.call()
        }
    }

}

private def getSystemProperties()
{
    return [
        '-Dxwiki.checkstyle.skip=true',
        '-Dxwiki.surefire.captureconsole.skip=true',
        '-Dxwiki.revapi.skip=true',
        '-Dxwiki.spoon.skip=true',
        '-Dxwiki.enforcer.skip=true'
    ]
}

private void build(map)
{
    node(map.label) {
        xwikiBuild(map.name) {
            mavenOpts = map.mavenOpts ?: "-Xmx2048m -Xms512m"
            // Javadoc execution is on by default but we don't need it for the docker tests.
            javadoc = false
            if (map.goals != null) {
                goals = map.goals
            }
            if (map.profiles != null) {
                profiles = map.profiles
            }
            if (map.properties != null) {
                properties = map.properties
            }
            if (map.pom != null) {
                pom = map.pom
            }
            if (map.mavenFlags != null) {
                mavenFlags = map.mavenFlags
            }
            if (map.skipCheckout != null) {
                skipCheckout = map.skipCheckout
            }
            if (map.xvnc != null) {
                xvnc = map.xvnc
            }
            if (map.skipMail != null) {
                skipMail = map.skipMail
            }
            if (map.jobProperties != null) {
                jobProperties = map.jobProperties
            }
        }
    }
}
