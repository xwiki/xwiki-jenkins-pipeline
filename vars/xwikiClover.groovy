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
import groovy.json.*

void call(baselineDefinitions)
{
    def mvnHome
    def localRepository
    def cloverDir
    def shortDateString = new Date().format("yyyyMMdd")
    def dateString = new Date().format("yyyyMMdd-HHmm")
    def workspace = pwd()
    def currentPOMVersion
    def cloverReportPrefixURL = "http://maven.xwiki.org/site/clover"

    stage('Preparation') {
        localRepository = "${workspace}/maven-repository"
        // Make sure that the special Maven local repository exists
        sh "mkdir -p ${localRepository}"
        // Remove all XWiki artifacts from it
        sh "rm -Rf ${localRepository}/org/xwiki"
        sh "rm -Rf ${localRepository}/com/xpn"
        // Make sure that the directory where clover will store its data exists in
        // the workspace and that it's clean
        cloverDir = "${workspace}/clover-data"
        sh "rm -Rf ${cloverDir}"
        sh "mkdir -p ${cloverDir}"
        // Get the Maven tool.
        // NOTE: Needs to be configured in the global configuration.
        mvnHome = tool 'Maven'
    }
    ['xwiki-commons', 'xwiki-rendering', 'xwiki-platform'].each() { repoName ->
        stage("Clover for ${repoName}") {
            dir (repoName) {
                git "https://github.com/xwiki/${repoName}.git"
                if (repoName == 'xwiki-commons') {
                    def pom = readMavenPom file: 'pom.xml'
                    currentPOMVersion = pom.version
                    echoXWiki("Current version = ${currentPOMVersion}")
                }
                runCloverAndGenerateReport(mvnHome, localRepository, cloverDir)
            }
        }
    }
    stage("Analyze Results") {
        // for each baseline definition, generate a report
        baselineDefinitions.each() { baselineDefinition ->
            def baselineDefinitionData = getBaselineDefinitionData(baselineDefinition.baseline, cloverReportPrefixURL)
            def baseline = baselineDefinitionData.baseline
            def version = baselineDefinitionData.version
            def failReport = baselineDefinition.fail
            def (date, time) = baseline.tokenize('-')

            // Location of the new clover XML file from the file system.
            def cloverReportDirectory = "${workspace}/xwiki-platform/target/site/clover"
            def cloverXMLReport = "${cloverReportDirectory}/clover.xml"

            // Generate the Diff Report using Maven
            def oldReport = "${cloverReportPrefixURL}/${date}/clover-commons+rendering+platform-${baseline}/clover.xml"
            def reportProperties = getSystemPropertiesAsString([
                    'oldCloverXMLReport' : oldReport,
                    'oldReportId' : baseline,
                    'newCloverXMLReport' : cloverXMLReport,
                    'newReportId' : dateString,
                    'diffReportOutputDirectory': cloverReportDirectory
            ])
            dir ("xwiki-platform") {
                withEnv(["PATH+MAVEN=${mvnHome}/bin", 'MAVEN_OPTS=-Xmx2048m']) {
                    sh "nice -n 5 mvn -N org.xwiki.clover:xwiki-clover-maven:1.1:report ${reportProperties}"
                }
            }

            // Publish the report remotely
            sh "ssh maven@maven.xwiki.org mkdir -p public_html/site/clover/${shortDateString}"
            def diffHTMLReportName = "XWikiReport-${baseline}-${dateString}.html"
            def diffHTMLReport = "${cloverReportDirectory}/${diffHTMLReportName}"
            def targetCloverDir = "maven@maven.xwiki.org:public_html/site/clover"
            def targetFile = "${targetCloverDir}/${shortDateString}/${diffHTMLReportName}"
            sh "scp ${diffHTMLReport} ${targetFile}"

            // Find if the global TPC has been lowered in the Diff Report and if so, send the HTML by email
            def cloverReportsURL = "${cloverReportPrefixURL}/${shortDateString}"
            def htmlContent = readFile diffHTMLReport
            if (failReport && htmlContent.contains('ERROR')) {
                // Send the mail to notify about failures
                sendMail(htmlContent)
                // Mark the build as failed so that we notice it!
                currentBuild.result = 'FAILURE'
                // Add a badge to indicate the reason of the failure
                def badgeText = 'Global test coverage has been reduced!'
                manager.addErrorBadge(badgeText)
                // Add some HTML to link to the report showing the failure
                def summaryText = "<h1>${badgeText} See <a href='${cloverReportsURL}'>report</a></h1>"
                manager.createSummary('red.gif').appendText(summaryText, false, false, false, 'red')
                // Persist changes
                currentBuild.rawBuild.save()
            } else {
                // Add a badge to indicate all ok
                def badgeText = 'Global test coverage is similar or increased!'
                manager.addInfoBadge(badgeText)
                // Add some HTML to link to the report
                def summaryText = "<h1>${badgeText} See <a href='${cloverReportsURL}'>report</a></h1>"
                manager.createSummary('green.gif').appendText(summaryText, false, false, false, 'green')
                // Persist changes
                currentBuild.rawBuild.save()
            }

            // Update the latest.json file if the version has changed and a version was specified.
            if (version && currentPOMVersion != version) {
                // Generate new JSON file in the local workspace
                def newData = [:]
                newData.baseline = dateString
                newData.version = currentPOMVersion
                writeFile file: "latest.json", text: JsonOutput.toJson(newData)
                // Upload it and overwrite any previous one
                sh "scp latest.json maven@maven.xwiki.org:public_html/site/clover/latest.json"
            }
        }
    }
    // Note 1: We run this stage after the Analyze results stage so that we can have the custom XWiki report even if
    // the publishing of clover reports fail (The custom XWiki report is the most important).
    // Note 2: We upload the Clover reports only after having built all the repositories with Maven since we only want
    // to get a new directory created at http://maven.xwiki.org/site/clover/ if we've been able to generate Clover
    // reports for all repositories (otherwise it would just clutter the hard drive for no value).
    stage("Publish Clover Reports") {
        def prefix = "clover-"
        ["commons", "rendering", "platform"].each() { repoName ->
            dir("xwiki-${repoName}/target/site") {
                if (repoName != 'commons') {
                    prefix = "${prefix}+${repoName}"
                } else {
                    prefix = "${prefix}${repoName}"
                }
                sh "tar cvf ${prefix}-${dateString}.tar clover"
                sh "gzip ${prefix}-${dateString}.tar"
                def cloverTarget = "maven@maven.xwiki.org:public_html/site/clover"
                sh "scp ${prefix}-${dateString}.tar.gz ${cloverTarget}/${shortDateString}/"
                sh "rm ${prefix}-${dateString}.tar.gz"

                def cdCommand = "cd public_html/site/clover/${shortDateString}"
                def gunzipCommand = "gunzip ${prefix}-${dateString}.tar.gz"
                def tarCommand = "tar xvf ${prefix}-${dateString}.tar"
                def mvCommand = "mv clover ${prefix}-${dateString}"
                def rmCommand = "rm ${prefix}-${dateString}.tar"
                def commands = "${cdCommand}; ${gunzipCommand}; ${tarCommand}; ${mvCommand}; ${rmCommand}"
                sh "ssh maven@maven.xwiki.org '${commands}'"
            }
        }
    }
}

private void runCloverAndGenerateReport(def mvnHome, def localRepository, def cloverDir)
{
    // Generate Clover Report locally
    wrap([$class: 'Xvnc']) {
        // Note: With 2048m we got a OOM.
        withEnv(["PATH+MAVEN=${mvnHome}/bin", 'MAVEN_OPTS=-Xmx4096m']) {
            def commonPropertiesString = getSystemPropertiesAsString([
                    'maven.repo.local' : "'${localRepository}'",
                    'maven.clover.cloverDatabase' : "${cloverDir}/clover.db"
            ])
            // Skip the maximum number of checks to speed up the build
            def propertiesString = getSystemPropertiesAsString([
                    'xwiki.revapi.skip' : 'true',
                    'xwiki.checkstyle.skip' : 'true',
                    'xwiki.enforcer.skip' : 'true',
                    'xwiki.license.skip' : 'true',
                    'maven.test.failure.ignore' : 'true'
            ])
            def profiles = "-Pclover,integration-tests,flavor-integration-tests,distribution,docker"
            // Use "nice" to reduce priority of the Maven process so that Jenkins stays as responsive as possible during
            // the build.
            sh "nice -n 5 mvn clean clover:setup install ${profiles} ${commonPropertiesString} ${propertiesString}"

            // Check that there are no false positives. If there are then stop the build.
            def containsFalsePositives = checkForFalsePositives()
            if (containsFalsePositives) {
                throw new RuntimeException(
                        "The build contains at least one false positive which can skew the global TPC result, stopping job")
            }

            // Note: Clover reporting requires a display. Even though we're inside XVNC and thus have a display, let's
            // still configure the execution to be headless.
            sh "nice -n 5 mvn clover:clover -N ${commonPropertiesString} -Djava.awt.headless=true"
        }
    }
}

private def getSystemPropertiesAsString(def systemPropertiesAsMap)
{
    return systemPropertiesAsMap.inject([]) { result, entry ->
        result << "-D${entry.key}=${entry.value}"
    }.join(' ')
}

private void sendMail(def htmlContent)
{
    emailext (
            subject: "Global Coverage Failure - Build # ${env.BUILD_NUMBER}",
            body: htmlContent,
            mimeType: 'text/html',
            to: 'notifications@xwiki.org'
    )
}

/**
 * @param originalBaseline can be either a date and time value representing the last report id
 *        (e.g. {@code 20190101-2330}) at http://maven.xwiki.org/site/clover, or {@code latest}, in which case the data
 *        is read from a {@code latest.json} file at http://maven.xwiki.org/site/clover/latest.json. In the former case
 *        no version is returned. The version is used to perform a check to decide whether to  update the
 *        {@code latest.json} file or not.
 *
 * Example of latest.json content:
 * <code><pre>
 *     {
 *         'baseline' : '20190101-2330',
 *         'version' : '11.2-SNAPSHOT'
 *     }
 * </pre></code>
 */
private def getBaselineDefinitionData(def originalBaseline, def cloverReportPrefixURL)
{
    def result
    if (originalBaseline == 'latest') {
        try {
            // Read it from the file named latest.json on maven.xwiki.org
            result = new JsonSlurperClassic().parse("${cloverReportPrefixURL}/latest.json".toURL())
        } catch(all) {
            // No latest.json file exist, fill with default data
            result = [ baseline: '20190101-2330', version: '11.0-SNAPSHOT' ]
        }
    } else {
        result = [ baseline: originalBaseline ]
    }
    return result
}
