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

import org.apache.commons.lang3.StringUtils

// Computes the full Clover TPC for the XWiki project, taking into account all tests located in various repos:
// xwiki-commons, xwiki-rendering and xwiki-platform.
// This script should be loaded by a standard Jenkins Pipeline job, using the "Pipeline script from SCM" option.
// This script also performs an analysis of the repory by comparing it to a previous report and generating an email
// if some modules have a TPC lower than before.
node() {
    def mvnHome
    def localRepository
    def cloverDir
    def shortDateString = new Date().format("yyyyMMdd")
    def dateString = new Date().format("yyyyMMdd-HHmm")
    def workspace = pwd()
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
    ["xwiki-commons", "xwiki-rendering", "xwiki-platform"].each() { repoName ->
        stage("Cloverify ${repoName}") {
            dir (repoName) {
                git "https://github.com/xwiki/${repoName}.git"
                runCloverAndGenerateReport(mvnHome, localRepository, cloverDir)
            }
        }
    }
    stage("Analyze Results") {
        // Find the Clover report to compare with. We compare against the latest version in which all modules have
        // a higher TPC than before.
        def latestReport = getLatestReport()
        def (date, time) = latestReport.tokenize('-')

        // Read the Clover XML report and extract data
        def tpcs1 = scrapeData(
          "http://maven.xwiki.org/site/clover/${date}/clover-commons+rendering+platform-${latestReport}/clover.xml"
          .toURL().newReader())

        // Read the current generated Clover XML report from the file system
        def cloverReportLocation = "${workspace}/xwiki-platform/target/site/clover"
        // Important note: using "new File()" will refer to files on the master and not on the slave,
        // see https://stackoverflow.com/a/50503979/153102
        def cloverXMLLocation = readFile "${cloverReportLocation}/clover.xml"
        def tpcs2 = scrapeData(new StringReader(cloverXMLLocation))

        // Compute the TPCs for each module
        def map1 = computeTPC(tpcs1.modules).sort()
        def map2 = computeTPC(tpcs2.modules).sort()

        // Compute a diff map that we use to both test for TPC failures and for for generating the HTML report in such
        // a case.
        def map = computeDisplayMap(map1, map2)

        // Get the HTML for the report + mail sending (if need be)
        def htmlContent = displayResultsInHTML(latestReport, dateString, "Module", map)

        // Save the report
        writeFile file: "${cloverReportLocation}/XWikiReport.html", text: "${htmlContent}"
        sh "ssh maven@maven.xwiki.org mkdir -p public_html/site/clover/${shortDateString}"
        sh "scp ${cloverReportLocation}/XWikiReport.html maven@maven.xwiki.org:public_html/site/clover/${shortDateString}/XWikiReport-${latestReport}-${dateString}.html"

        // Send mail or update latest.txt file when no failures
        if (hasFailures(map)) {
            // Send the mail to notify about failures
            sendMail(latestReport, dateString, htmlContent)
        } else {
            // Update the latest.txt file
            writeFile file: "${cloverReportLocation}/latest.txt", text: "${dateString}"
            sh "scp ${cloverReportLocation}/latest.txt maven@maven.xwiki.org:public_html/site/clover/latest.txt"
        }
    }
    stage("Publish Clover Reports") {
        def prefix = "clover-"
        ["commons", "rendering", "platform"].each() { repoName ->
            dir ("xwiki-${repoName}/target/site") {
                if (repoName != 'commons') {
                    prefix = "${prefix}+${repoName}"
                } else {
                    prefix = "${prefix}${repoName}"
                }
                sh "tar cvf ${prefix}-${dateString}.tar clover"
                sh "gzip ${prefix}-${dateString}.tar"
                sh "scp ${prefix}-${dateString}.tar.gz maven@maven.xwiki.org:public_html/site/clover/${shortDateString}/"
                sh "rm ${prefix}-${dateString}.tar.gz"
                sh "ssh maven@maven.xwiki.org 'cd public_html/site/clover/${shortDateString}; gunzip ${prefix}-${dateString}.tar.gz; tar xvf ${prefix}-${dateString}.tar; mv clover ${prefix}-${dateString};rm ${prefix}-${dateString}.tar'"
            }
        }
    }
}
def runCloverAndGenerateReport(def mvnHome, def localRepository, def cloverDir)
{
    wrap([$class: 'Xvnc']) {
        withEnv(["PATH+MAVEN=${mvnHome}/bin", 'MAVEN_OPTS=-Xmx4096m']) {
            sh "mvn -Dmaven.repo.local='${localRepository}' clean clover:setup install -Pclover,integration-tests,flavor-integration-tests,distribution -Dmaven.clover.cloverDatabase=${cloverDir}/clover.db -Dmaven.test.failure.ignore=true -Dxwiki.revapi.skip=true"
            sh "mvn -Dmaven.repo.local='${localRepository}' clover:clover -N -Dmaven.clover.cloverDatabase=${cloverDir}/clover.db"
        }
    }
}
def sendMail(def oldDateString, def newDateString, def htmlContent)
{
    def (oldDate, oldTime) = oldReportDateString.tokenize('-')
    def oldCloverURL =
        "http://maven.xwiki.org/site/clover/${oldDate}/clover-commons+rendering+platform-${oldReportDateString}/dashboard.html"
    def (newDate, newTime) = newDateString.tokenize('-')
    def newCloverURL =
        "http://maven.xwiki.org/site/clover/${newDate}/clover-commons+rendering+platform-${newDateString}/dashboard.html"
    emailext (
        subject: "Global Coverage Failure - Build # ${env.BUILD_NUMBER}",
        body: """
At least one module got a TPC lower than in the new <a href="${newCloverURL}">${newDateString}</a> report when compared with the old <a href="${oldCloverURL}">${oldDateString}</a> one.

Please fix all elements in red in the report below.

${htmlContent}
""",
        mimeType: 'text/html',
        to: 'notifications@xwiki.org'
    )
}
def getLatestReport()
{
    try {
        return new URL ("http://maven.xwiki.org/site/clover/latest.txt").getText()
    } catch (all) {
        // When no file exist, default to a fixed value we define.
        return "20171222-1835"
    }
}
// Example input: "/home/hudsonagent/jenkins_root/workspace/Clover/xwiki-commons/xwiki-commons-core/
//   xwiki-commons-stability/src/main/java/org/xwiki/stability/Unstable.java"
// Returns "xwiki-commons-stability"
def extractModuleName(def path)
{
    def before = StringUtils.substringBefore(path, '/src/')
    return StringUtils.substringAfterLast(before, '/')
}
def emptyMetrics()
{
    def map = [:]
    map.coveredconditionals = 0
    map.coveredstatements = 0
    map.coveredmethods = 0
    map.conditionals = 0
    map.statements = 0
    map.methods = 0
    map.coveredelements = 0
    return map
}
void addMetrics(def metricXMLElement, def map, def key)
{
    def innerMap = map.get(key)
    if (!innerMap) {
        innerMap = emptyMetrics()
        map.put(key, innerMap)
    }
    innerMap.coveredelements += metricXMLElement.@coveredelements.toDouble()
    innerMap.coveredconditionals += metricXMLElement.@coveredconditionals.toDouble()
    innerMap.coveredstatements += metricXMLElement.@coveredstatements.toDouble()
    innerMap.coveredmethods += metricXMLElement.@coveredmethods.toDouble()
    innerMap.conditionals += metricXMLElement.@conditionals.toDouble()
    innerMap.statements += metricXMLElement.@statements.toDouble()
    innerMap.methods += metricXMLElement.@methods.toDouble()
}
def scrapeData(def reader)
{
    def packageMap = [:]
    def moduleMap = [:]
    def root = new XmlSlurper().parse(reader)
    root.project.package.each() { packageElement ->
        def packageName = packageElement.@name.text()
        packageElement.file.each() { fileElement ->
            def filePath = fileElement.@path.text()
            // Iterate over all the files to remove test classes in order to harmonize TPC
            if (!(filePath.contains('/test/') || filePath =~ /xwiki-.*-test/)) {
                // Save metrics for packages
                addMetrics(fileElement.metrics, packageMap, packageName)
                // Save metrics for modules
                addMetrics(fileElement.metrics, moduleMap, extractModuleName(filePath))
                if (filePath.contains('test')) {
                    echo "File that should maybe not be counted: ${filePath}"
                }
            }
        }
    }
    return ['packages' : packageMap, 'modules' : moduleMap]
}
def computeTPC(def map)
{
    def totalcoveredconditionals = 0
    def totalcoveredstatements = 0
    def totalcoveredmethods = 0
    def totalconditionals = 0
    def totalstatements = 0
    def totalmethods = 0

    map.reverseEach() { key, metrics ->
        def coveredconditionals = metrics.get('coveredconditionals')
        totalcoveredconditionals += coveredconditionals
        def coveredstatements = metrics.get('coveredstatements')
        totalcoveredstatements += coveredstatements
        def coveredmethods = metrics.get('coveredmethods')
        totalcoveredmethods += coveredmethods
        def conditionals = metrics.get('conditionals')
        totalconditionals += conditionals
        def statements = metrics.get('statements')
        totalstatements += statements
        def methods = metrics.get('methods')
        totalmethods += methods
        def elementsCount = conditionals + statements + methods
        def tpc
        if (elementsCount == 0) {
            tpc = 0
        } else {
            def coveredElements = coveredconditionals + coveredstatements + coveredmethods
            def elements = conditionals + statements + methods
            tpc = (coveredElements/elements) * 100
        }
        metrics.put('tpc', tpc)
    }
    def totalCoveredElements = totalcoveredconditionals + totalcoveredstatements + totalcoveredmethods
    def totalElements = totalconditionals + totalstatements + totalmethods
    def totalTPC = (totalCoveredElements/totalElements) * 100
    map.put('ALL', [
            'tpc': totalTPC,
            'coveredconditionals': totalcoveredconditionals,
            'coveredstatements': totalcoveredstatements,
            'coveredmethods': totalcoveredmethods,
            'conditionals': totalconditionals,
            'statements': totalstatements,
            'methods': totalmethods
    ])
    return map
}
def getDiffValue(key, all, metric1, metric2)
{
    def value = all.get(key)
    if (metric2) {
        value -= (metric2.get(key) - metric1.get(key))
    } else {
        value -= metric1.get(key)
    }
    return value
}
def computeTPCWithout(all, metric1, metric2)
{
    def conditionalsDiff = getDiffValue('conditionals', all, metric1, metric2)
    def statementsDiff = getDiffValue('statements', all, metric1, metric2)
    def methodsDiff = getDiffValue('methods', all, metric1, metric2)
    def elementsCount = conditionalsDiff + statementsDiff + methodsDiff
    if (elementsCount == 0) {
        return 0
    } else {
        def coveredconditionalsDiff = getDiffValue('coveredconditionals', all, metric1, metric2)
        def coveredstatementsDiff = getDiffValue('coveredstatements', all, metric1, metric2)
        def coveredmethodsDiff = getDiffValue('coveredmethods', all, metric1, metric2)
        def coveredElementsCount = coveredconditionalsDiff + coveredstatementsDiff + coveredmethodsDiff
        return (coveredElementsCount/elementsCount) * 100
    }
}
def round(number)
{
    return number.toDouble().trunc(4)
}
def computeDisplayMap(def map1, def map2)
{
    def newAll = map2.get('ALL')
    def map = [:]

    // Process the new added modules + the modified ones
    map2.each() { key, metrics ->
        // New modules
        if (!map1.containsKey(key)) {
            def contribution = newAll.tpc - computeTPCWithout(newAll, metrics, null)
            metrics.put('contrib', contribution)
            metrics.put('newtpc', metrics.tpc)
            map.put(key, metrics)
        } else {
            // Modified modules
            def oldtpc = map1.get(key)?.tpc
            def tpc = metrics.tpc
            if (oldtpc && tpc != oldtpc) {
                def diff = tpc - oldtpc
                metrics.put('oldtpc', oldtpc)
                metrics.put('difftpc', diff)
                metrics.put('newtpc', tpc)
                def contribution = newAll.tpc - computeTPCWithout(newAll, map1.get(key), metrics)
                metrics.put('contrib', contribution)
                map.put(key, metrics)
            }
        }
    }
    // Process removed modules
    map1.each() { key, metrics ->
        if (!map2.containsKey(key)) {
            def contribution = computeTPCWithout(newAll, metrics, null) - newAll.tpc
            metrics.put('contrib', contribution)
            metrics.put('oldtpc', metrics.tpc)
            map.put(key, metrics)
        }
    }

    map = sortMap(map)

    return map
}
/**
 * We need @NonCPS as otherwise the map sort returns a singe BigDecimal instead of returning a sorted Map.
 */
@NonCPS
def sortMap(def map)
{
    return map.sort {it.value.contrib}
}
def hasFailures(def map)
{
    // Using a for so that we can exit the loop.
    for (e in map) {
        if (e.value.oldtpc != null && e.value.newtpc != null && round(e.value.newtpc - e.value.oldtpc) < 0) {
            return true
        }
    }
    return false
}
// Note: Not used but leaving as an example FTM. Useful if we want to run the code in a wiki page in XWiki.
def displayResultsInWikiSyntax(def topic, def map)
{
    def content = ""
    content += "|=${topic}|=TPC Old|=TPC New|=TPC Diff|=Global TPC Contribution\n"
    content += "|ALL|${round(map.ALL.oldtpc)}|${round(map.ALL.newtpc)}|${round(map.ALL.newtpc - map.ALL.oldtpc)}|N/A\n"
    map.each() { key, value ->
        if (key == 'ALL') return
        def css = value.contrib < 0 ? '(% style="color:red;" %)' : '(% style="color:green;" %)'
        def oldtpc = value.oldtpc != null ? round(value.oldtpc) : 'N/A'
        def newtpc = value.newtpc != null ? round(value.newtpc) : 'N/A'
        def difftpc = value.oldtpc != null && value.newtpc != null ? round(value.newtpc - value.oldtpc) : 'N/A'
        def cssdiff = value.contrib < 0 || (value.oldtpc != null && value.newtpc != null
            && value.newtpc - value.oldtpc < 0) ? '(% style="color:red;" %)' : '(% style="color:green;" %)'
        content += "|${key}|${css}${oldtpc}|${css}${newtpc}|${cssdiff}${difftpc}|${css}${round(value.contrib)}\n"
    }
    return content
}
def displayResultsInHTML(def oldDateString, def newDateString, def topic, def map)
{
    def content = "<h1>Report - ${oldDateString} -> ${newDateString}</h1>"
    content += "<table><thead><tr>"
    content += "<th>${topic}</th><th>TPC Old</th><th>TPC New</th><th>TPC Diff</th><th>Global TPC Contribution</th>"
    content += "</tr></thead><tbody>"
    content += "<tr><td>ALL</td><td>${round(map.ALL.oldtpc)}</td><td>${round(map.ALL.newtpc)}</td>"
    content += "<td>${round(map.ALL.newtpc - map.ALL.oldtpc)}</td><td>N/A</td></tr>"
    map.each() { key, value ->
        if (key == 'ALL') return
        def css = value.contrib < 0 ? 'style="color:red;"' : 'style="color:green;"'
        def oldtpc = value.oldtpc != null ? round(value.oldtpc) : 'N/A'
        def newtpc = value.newtpc != null ? round(value.newtpc) : 'N/A'
        def difftpc = value.oldtpc != null && value.newtpc != null ? round(value.newtpc - value.oldtpc) : 'N/A'
        def cssdiff = value.contrib < 0 || (value.oldtpc != null && value.newtpc != null
            && value.newtpc - value.oldtpc < 0) ? 'style="color:red;"' : 'style="color:green;"'
        content += "<tr><td>${key}</td><td ${css}>${oldtpc}</td><td ${css}>${newtpc}</td>"
        content += "<td ${cssdiff}>${difftpc}</td><td ${css}>${round(value.contrib)}</td></tr>"
    }
    content += "</tbody></table>"
    return content
}
/**
 * Echo text with a special character prefix to make it stand out in the pipeline logs.
 */
def echoXWiki(string)
{
    echo "\u27A1 ${string}"
}
