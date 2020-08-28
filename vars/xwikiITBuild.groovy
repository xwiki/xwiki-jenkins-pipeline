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

    // Run integrations tests on all passed modules
    def builds = [:]
    config.modules.each() { modulePath ->
        echoXWiki "Module name: ${modulePath}"
        def profiles = 'docker,legacy,integration-tests,snapshotModules'
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
