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

    // Run integrations tests on all passed modules, grouping several modules per build so that they are executed on the
    // same agent. This amortizes the cost of acquiring an agent, checking out the sources and pulling Docker images over
    // several modules, and lets Maven reuse its local repository across the modules of a batch.
    def builds = [:]
    def profiles = 'docker,legacy,integration-tests,snapshotModules,distribution,flavor-integration-tests'
    config.modules.collate(config.batchSize ?: 4).eachWithIndex() { modulePaths, i ->
        def buildName = "IT #${i + 1} for ${getModuleNames(modulePaths).join(', ')}"
        echoXWiki "Build name: ${buildName}"
        // We pass --fail-at-end so that a failure while building one module doesn't prevent the other modules of the
        // batch from being built and tested (this is a no-op when there's a single module in the batch).
        builds[buildName] = {
            build(
                name: buildName,
                profiles: profiles,
                properties: "${getSystemProperties().join(' ')}",
                mavenFlags: "--projects ${modulePaths.join(',')} -e -U --fail-at-end",
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

// Return the last segment (the Maven module name) of each of the passed module paths, used to build short and readable
// build/step names.
private def getModuleNames(modulePaths)
{
    return modulePaths.collect { it.substring(it.lastIndexOf('/') + 1) }
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
