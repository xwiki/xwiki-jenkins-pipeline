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
 * Run {@code pnpm audit} on every {@code pnpm-lock.yaml} of the workspace. Node and pnpm are installed in
 * {@code target/pnpm-audit} with the frontend-maven-plugin, using the {@code node.version} and {@code pnpm.version}
 * properties of the pom. A failing audit fails the build and sends a mail, but doesn't stop the pipeline.
 *
 * @param config the configuration map passed to the {@code xwikiBuild} step
 * @param javaMavenConfig the result of {@code configureJavaTool}
 * @param name a string representing the current build
 */
void call(config, javaMavenConfig, name = 'Default')
{
    def lockFiles = findFiles(glob: '**/pnpm-lock.yaml', excludes: '**/node_modules/**,**/target/**')
    if (lockFiles.size() == 0) {
        echoXWiki "No pnpm-lock.yaml file found, skipping the pnpm audit"
        return
    }

    // Same as the webjar-node lifecycle: the version comes from the pluginManagement of the project.
    def plugin = 'com.github.eirslett:frontend-maven-plugin'
    def pomFile = config.pom ?: 'pom.xml'
    def installDirectory = "${pwd()}/target/pnpm-audit"
    def mavenTool = config.mavenTool?.trim() ?: ''
    def failures = []
    try {
        withMaven(maven: mavenTool, jdk: javaMavenConfig.jdk, mavenOpts: javaMavenConfig.mavenOpts, options: [
            artifactsPublisher(disabled: true),
            dependenciesFingerprintPublisher(disabled: true),
            invokerPublisher(disabled: true),
            junitPublisher(disabled: true)
        ])
        {
            def nodeVersion = getPomProperty(pomFile, 'node.version')
            def pnpmVersion = getPomProperty(pomFile, 'pnpm.version')
            if (!nodeVersion || !pnpmVersion) {
                failures.add("Cannot run the pnpm audit: the node.version [${nodeVersion}] and pnpm.version " +
                    "[${pnpmVersion}] properties must be defined in ${pomFile}")
            } else {
                echoXWiki "Installing Node [${nodeVersion}] and pnpm [${pnpmVersion}] in ${installDirectory}"
                def installStatus = sh script: "mvn -f ${pomFile} -N --no-transfer-progress ${plugin}:" +
                    "install-node-and-pnpm -DinstallDirectory=${installDirectory} -DnodeVersion=${nodeVersion} " +
                    "-DpnpmVersion=${pnpmVersion}", returnStatus: true
                if (installStatus != 0) {
                    failures.add("Cannot run the pnpm audit: failed to install Node and pnpm (exit code " +
                        "${installStatus})")
                } else {
                    // Note: don't use each() since it leads to unserializable exceptions
                    for (def lockFile in lockFiles) {
                        def directory = lockFile.path.contains('/') ?
                            lockFile.path.substring(0, lockFile.path.lastIndexOf('/')) : '.'
                        echoXWiki "Running pnpm audit for ${lockFile.path}"
                        def auditStatus = sh script: "mvn -f ${pomFile} -N --no-transfer-progress ${plugin}:pnpm " +
                            "-DinstallDirectory=${installDirectory} -DworkingDirectory=${pwd()}/${directory} " +
                            "'-Dfrontend.pnpm.arguments=audit --audit-level=high'", returnStatus: true
                        if (auditStatus != 0) {
                            failures.add("pnpm audit failed for ${lockFile.path} (exit code ${auditStatus}), see " +
                                "the audit output above")
                        }
                    }
                }
            }
        }
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        failures.add("Cannot run the pnpm audit: ${e.message}")
    }

    if (failures.isEmpty()) {
        echoXWiki "pnpm audit passed for all the pnpm-lock.yaml files"
    } else {
        for (def failure in failures) {
            echoXWiki "ERROR: ${failure}"
        }
        // Note: Jenkins only allows a build result to get worse, so this never improves an aborted build.
        currentBuild.result = 'FAILURE'
        if (!config.skipMail) {
            sendMail('ERROR', name)
        }
    }
}

private def getPomProperty(pomFile, property)
{
    def value = sh(script: "mvn -f ${pomFile} -N help:evaluate -Dexpression=${property} -q -DforceStdout",
        returnStdout: true).trim()
    // Maven prints this message when the property is not defined.
    return value == '' || value.startsWith('null object') ? null : value
}
