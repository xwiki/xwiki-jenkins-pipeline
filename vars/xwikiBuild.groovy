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
import hudson.FilePath
import hudson.util.IOUtils
import javax.xml.bind.DatatypeConverter
import hudson.tasks.test.AbstractTestResultAction
import com.cloudbees.groovy.cps.NonCPS

// If you need to setup a Jenkins instance where the following script will work you'll need to:
//
// - Configure Global tools:
//   - One named 'Maven' for Maven,
//   - One named 'java7' for Java 7
//   - One named 'official' for Java 8
// - Configure a Global Pipeline library
//   - Name: 'XWiki'
//   - Version: 'master'
//   - Enable 'Load implicitly'
//   - Choose modern SCM and then GitHub:
//     - owner: 'xwiki'
//     - repository: 'xwiki-jenkins-pipeline'
// - Have the following plugins installed:
//   - XVnc plugin. You'll also need to have the "vncserver" executable available in the path
//   - Email Extension plugin (provides emailext() API)
//   - Build Timeout plugin (provides timeout() API)
//   - Pipeline Utility Steps plugin (provides readMavenPom() API)
//   - Pipeline Maven Integration plugin (provides withMaven() API)
//   - Groovy Post Build plugin (provides the 'manager' variable)

/**
 * @param name a string representing the current build
 */
void call(name = 'Default', body)
{
    echoXWiki "Calling Jenkinsfile for build [{$name}]"
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
    printConfigurationProperties(config)

    // Does the following:
    // - Only keep builds for the last configured number of days (7 by default)
    // - Disable concurrent builds to avoid rebuilding whenever a new commit is made. The commits will accumulate till
    //   the previous build is finished before starting a new one.
    //   Note 1: this is limiting concurrency per branch only.
    //   Note 2: This needs to be one of the first code executed which is why it's the first step we execute.
    //   See https://thepracticalsysadmin.com/limit-jenkins-multibranch-pipeline-builds/ for details.
    // -  Make sure projects are built at least once a month because SNAPSHOT older than one month are deleted
    //    by the Nexus scheduler.
    def computedDaysToKeepStr = config.daysToKeepStr ?: '7'
    echoXWiki "Only keep the builds for the last $computedDaysToKeepStr days + disable concurrent builds"
    def projectProperties = [
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: computedDaysToKeepStr]],
        disableConcurrentBuilds(),
        pipelineTriggers([cron("@monthly")])
    ]

    // Process job properties overrides.
    def allProperties = []
    // Note: we add the overridden job properties first since the properties() step will honor the values that come
    // first and ignore further ones. This allows the Jenkinsfile to take precedence.
    echoXWiki "Custom job properties: ${config.jobProperties}"
    if (config.jobProperties) {
        allProperties.addAll(config.jobProperties)
    }
    allProperties.addAll(projectProperties)
    echoXWiki "Full job properties: ${allProperties}"
    properties(allProperties)

    def mavenTool
    stage("Preparation for ${name}") {
        // Get the Maven tool.
        // NOTE: The Maven tool Needs to be configured in the Jenkins global configuration.
        mavenTool = config.mavenTool ?: 'Maven'

        // Check if the build should be aborted
        if (config.disabled) {
            currentBuild.result = 'ABORTED'
            error "Aborting build since it's disabled explicitly..."
        }

        if (!config.skipCheckout) {
            echoXWiki "SCM checkout with changelog set to [${config.skipChangeLog}]"
            checkout changelog: config.skipChangeLog ?: false, scm: scm
        }

        // Configure the version of Java to use
        def pom = readMavenPom file: getPOMFile(config)
        configureJavaTool(config, pom)

        // Display some environmental information that can be useful to debug some failures
        // Note: if the executables don't exist, this won't fail the step thanks to "returnStatus: true".
        sh script: 'ps -ef', returnStatus: true
        sh script: 'netstat -nltp', returnStatus: true
        // Note: the "|| true" allows the sh command to fail (when firefox is not installed for example) without
        // failing the job build.
        def firefoxVersion = sh script: 'firefox -version || true', returnStdout: true
        if (firefoxVersion) {
            echoXWiki "Firefox version installed: ${firefoxVersion}"
        }
    }
    stage("Build for ${name}") {
        // Execute the XVNC plugin (useful for integration-tests)
        wrapInXvnc(config) {
            // Execute the Maven build.
            // Note that withMaven() will also perform some post build steps:
            // - Archive and fingerprint generated Maven artifacts and Maven attached artifacts (if archiveArtifacts
            //   is set to true, see above)
            // - Publish JUnit / Surefire reports (if the Jenkins JUnit Plugin is installed)
            // - Publish Findbugs reports (if the Jenkins FindBugs Plugin is installed)
            // - Publish a report of the tasks ("FIXME" and "TODO") found in the java source code
            //   (if the Jenkins Tasks Scanner Plugin is installed)
            echoXWiki "JAVA_HOME: ${env.JAVA_HOME}"
            echoXWiki "Using Maven tool: ${mavenTool}"
            echoXWiki "Using Maven options: ${env.MAVEN_OPTS}"
            def archiveArtifacts = config.archiveArtifacts == null ? false : config.archiveArtifacts
            echoXWiki "Artifact archiving: ${archiveArtifacts}"
            def fingerprintDependencies = config.fingerprintDependencies == null ? false :
                config.fingerprintDependencies
            echoXWiki "Dependencies fingerprinting: ${fingerprintDependencies}"
            // Note: We're not passing "mavenOpts" voluntarily, see configureJavaTool()
            withMaven(maven: mavenTool, options: [artifactsPublisher(disabled: !archiveArtifacts),
                dependenciesFingerprintPublisher(disabled: !fingerprintDependencies)])
            {
                try {
                    def goals = computeMavenGoals(config)
                    echoXWiki "Using Maven goals: ${goals}"
                    def profiles = getMavenProfiles(config, env)
                    echoXWiki "Using Maven profiles: ${profiles}"
                    def properties = getMavenSystemProperties(config, "${NODE_NAME}")
                    echoXWiki "Using Maven properties: ${properties}"
                    def javadoc = ''
                    if (config.javadoc == null || config.javadoc == true) {
                        javadoc = 'javadoc:javadoc -Ddoclint=all'
                        echoXWiki "Enabling javadoc validation"
                    }
                    def timeoutThreshold = config.timeout ?: 240
                    echoXWiki "Using timeout: ${timeoutThreshold}"
                    // Display the java version for information (in case it's useful to debug some specific issue)
                    echoXWiki 'Java version used:'
                    sh script: 'java -version', returnStatus: true
                    // Abort the build if it takes more than the timeout threshold (in minutes).
                    timeout(timeoutThreshold) {
                        def pom = getPOMFile(config)
                        echoXWiki "Using POM file: ${pom}"
                        // Note: We use -Dmaven.test.failure.ignore so that the maven build continues till the
                        // end and is not stopped by the first failing test. This allows to get more info from the
                        // build (see all failing tests for all modules built). Also note that the build is marked
                        // unstable when there are failing tests by the JUnit Archiver executed during the
                        // 'Post Build' stage below.
                        // Note: "--no-transfer-progress" is used to avoid the download progress indicators which do
                        // not display well in a non-interactive shell and which use a lot of console log space.
                        def fullProperties = "--no-transfer-progress -Dmaven.test.failure.ignore ${properties}"
                        // Set Maven flags to use
                        def mavenFlags = config.mavenFlags ?: '-U -e'
                        wrapInSonarQube(config) {
                            sh "mvn -f ${pom} ${goals} -P${profiles} ${mavenFlags} ${fullProperties} ${javadoc}"
                        }
                    }
                } catch (InterruptedException e) {
                    // This can happen when the timeout() step reaches the timeout. We need to let this bubble up so
                    // that Jenkins can coordinate the stopping of all threads & builds that execute in parallel.
                    echoXWiki "XWiki build [${name}] interrupted due to timeout"
                    displayDebugData()
                    Thread.currentThread().interrupt();
                    // Note: Don't send email on an interrupted build.
                } catch (Exception e) {
                    // - If this line is reached it means the build has failed (other than for failing tests) or has
                    //   been aborted (because we told maven above to not stop on test failures!)
                    // - We stop the build by throwing the exception.
                    // - Note that withMaven() doesn't set any build result in this case but we don't need to set any
                    //   since we're stopping the build!
                    // - Don't send emails for aborts! We discover aborts by checking for exit code 143.
                    // - Also don't send emails for process kills since it's an environment issue and not an XWiki
                    //   source problem (this happens when the exit code is 137).
                    displayDebugData()
                    if (!e.getMessage()?.contains('exit code 143') && !e.getMessage()?.contains('exit code 137')
                        && !config.skipMail)
                    {
                        sendMail('ERROR', name)
                    }
                    throw e
                }
            }
        }
    }
    stage("Post Build for ${name}") {
        // If the job made it to here it means the Maven build has either succeeded or some tests have failed.
        // If the build has succeeded, then currentBuild.result is null (since withMaven doesn't set it in this case).
        if (currentBuild.result == null) {
            currentBuild.result = 'SUCCESS'
        }

        // Save videos generated by Docker-based tests, if any.
        // Note: This can generate some not nice stack trace in the logs,
        // see https://issues.jenkins-ci.org/browse/JENKINS-51913
        echoXWiki "Looking for test videos in ${pwd()}"
        archiveArtifacts artifacts: '**/target/**/*.flv', allowEmptyArchive: true

        // Save images generated by functional tests, if any
        // Note: This can generate some not nice stack trace in the logs,
        // see https://issues.jenkins-ci.org/browse/JENKINS-51913
        // Note: we look for screenshots only in the screenshots directory to avoid false positives such as PNG images
        // that would be located in a XWiki distribution located in target/.
        echoXWiki "Looking for test failure images in ${pwd()}"
        archiveArtifacts artifacts: '**/target/**/screenshots/*.png', allowEmptyArchive: true

        echoXWiki "Current build status after withMaven execution: ${currentBuild.result}"

        // For each failed test, find if there's a screenshot for it taken by the XWiki selenium tests and if so
        // embed it in the failed test's description. Also check if failing tests are flickers.
        if (currentBuild.result != 'SUCCESS') {
            def failingTests = getFailingTests()
            echoXWiki "Failing tests: ${failingTests.collect { "${it.getClassName()}#${it.getName()}" }}"
            if (!failingTests.isEmpty()) {
                echoXWiki "Attaching screenshots to test result pages (if any)..."
                attachScreenshotToFailingTests(failingTests)

                // Check for false positives & Flickers.
                echoXWiki "Checking for false positives and flickers in build results..."
                def containsFalsePositivesOrOnlyFlickers = checkForFalsePositivesAndFlickers(failingTests)

                // Also send a mail notification when there are not only false positives or flickering tests.
                // Update 2020-10-31: Temporarily only send mails when there are failing non-functional tests to reduce
                // the number of emails sent, until we fix functional tests stability. See https://bit.ly/34GTVBe
                echoXWiki "Checking if email should be sent or not"
                if (!containsFalsePositivesOrOnlyFlickers && !config.skipMail
                    && containsNonFunctionalFailingTests(failingTests))
                {
                    sendMail(currentBuild.result, name)
                } else {
                    echoXWiki "No email sent even if some tests failed because they contain only flickering tests!"
                    echoXWiki "Considering job as successful!"
                    currentBuild.result = 'SUCCESS'
                }
            }
        }
    }
}

private def containsNonFunctionalFailingTests(def failingTests)
{
    for (failingTest in failingTests) {
        if (!failingTest.className.contains("IT")) {
            return true
        }
    }
    return false
}

private def getPOMFile(def config)
{
    return config.pom ?: 'pom.xml'
}

private def getMavenSystemProperties(config, nodeName)
{
    def properties = config.properties ?: ''

    // Add a system property that represents the agent name so that whenever a test fails, we can display the agent
    // on which it is executed in order to make it easier for debugging (it'll appear in the jenkins page for the
    // failing test (see XWikiDockerExtension which prints it).
    properties = "${properties} -DjenkinsAgentName=\"${nodeName}\""

    // Have functional tests retry twice in case of error. This is done to try to reduce the quantity of flickers.
    // TODO: put back the retry when the build is in a better shape and when Surefirex 3.x doesn't fail anymore in
    // running the tests for xwiki-platform-rendering-macro-python
    //properties = "${properties} -Dfailsafe.rerunFailingTestsCount=2"

    return properties
}

private void printConfigurationProperties(config)
{
    def buffer = new StringBuilder()
    config.each{ k, v -> buffer.append("[${k}] = [${v}]\n") }
    echoXWiki "Passed configuration properties:\n${buffer.toString()}"
}

private def getMavenProfiles(config, env)
{
    def profiles = config.profiles ?: 'quality,legacy,integration-tests,jetty,hsqldb,firefox'
    // If we're on a node supporting docker, also build the docker-based tests (i.e. for the default configuration)
    if (env.NODE_LABELS.split().contains('docker')) {
        profiles = "${profiles},docker"
    }
    return profiles
}

private def wrapInXvnc(config, closure)
{
    def isXvncEnabled = config.xvnc == null ? true : config.xvnc
    if (isXvncEnabled) {
        wrap([$class: 'Xvnc']) {
            closure()
        }
    } else {
        echoXWiki "Xvnc disabled, building without it!"
        closure()
    }
}

private def wrapInSonarQube(config, closure)
{
    if (config.sonar) {
        withSonarQubeEnv('sonar') {
            closure()
        }
    } else {
        closure()
    }
}

private def computeMavenGoals(config)
{
    def goals = config.goals
    if (!goals) {
        // Use "deploy" goal for "master" and "stable-*" branches only and "install" for the rest.
        // This is to avoid having branches with the same version polluting the maven snapshot repo, overwriting one
        // another.
        def branchName = env['BRANCH_NAME']
        if (branchName != null && (branchName.equals("master") || branchName.startsWith('stable-'))) {
            goals = "deploy"
        } else {
            goals = "install"
        }
        goals = "clean ${goals}"
    }
    return goals
}

/**
 * Create a FilePath instance that points either to a file on the master node or a file on a remote agent node.
 */
private def createFilePath(String path)
{
    if (env['NODE_NAME'] == null) {
        error "envvar NODE_NAME is not set, probably not inside an node {} or running an older version of Jenkins!"
    } else if (env['NODE_NAME'].equals("master")) {
        return new FilePath(new File(path))
    } else {
        return new FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), path)
    }
}

/**
 * Attach the screenshot of failing XWiki Selenium tests to failed test descriptions.
 * The screenshot is preserved after the workspace gets cleared by a new build.
 *
 * To make this script works the following needs to be setup on the Jenkins instance:
 * <ul>
 *   <li>Install the <a href="http://wiki.jenkins-ci.org/display/JENKINS/Groovy+Postbuild+Plugin">Groovy Postbuild
 *       plugin</a>. This exposes the "manager" variable needed by the script.</li>
 *   <li>Add the required security exceptions to http://<jenkins server ip>/scriptApproval/ if need be.</li>
 *   <li>Install the <a href="https://wiki.jenkins-ci.org/display/JENKINS/PegDown+Formatter+Plugin">Pegdown Formatter
 *       plugin</a> and set the description syntax to be Pegdown in the Global Security configuration
 *       (http://<jenkins server ip>/configureSecurity).</li>
 * </ul>
 */
private def attachScreenshotToFailingTests(def failingTests)
{
    // Go through each failed test in the current build.
    for (def failedTest : failingTests) {
        // Compute the test's screenshot file name.
        def testClass = failedTest.getClassName()
        def testSimpleClass = failedTest.getSimpleName()
        def testExample = failedTest.getName()

        // Example of value for suiteResultFile (it's a String):
        //   /Users/vmassol/.jenkins/workspace/blog/application-blog-test/application-blog-test-tests/target/
        //     surefire-reports/TEST-org.xwiki.blog.test.ui.AllTests.xml
        def suiteResultFile = failedTest.getSuiteResult().getFile()
        if (suiteResultFile == null) {
            // No results available. Go to the next test.
            continue
        }

        // Compute the screenshot's location on the build agent.
        // Example of target folder path:
        //   /Users/vmassol/.jenkins/workspace/blog/application-blog-test/application-blog-test-tests/target
        def targetFolderPath = createFilePath(suiteResultFile).getParent().getParent()
        // The screenshot can have several possible file names and locations, we check all.
        // Selenium 1 test screenshots.
        def imageAbsolutePath1 = new FilePath(targetFolderPath, "selenium-screenshots/${testClass}-${testExample}.png")
        // Selenium 2 test screenshots.
        def imageAbsolutePath2 = new FilePath(targetFolderPath, "screenshots/${testSimpleClass}-${testExample}.png")
        // If screenshotDirectory system property is not defined we save screenshots in the tmp dir so we must also
        // support this.
        def imageAbsolutePath3 =
            new FilePath(createFilePath(System.getProperty("java.io.tmpdir")), "${testSimpleClass}-${testExample}.png")

        // Determine which one exists, if any.
        def imageAbsolutePath = imageAbsolutePath1.exists() ?
            imageAbsolutePath1 : (imageAbsolutePath2.exists() ? imageAbsolutePath2 :
            (imageAbsolutePath3.exists() ? imageAbsolutePath3 : null))

        // If the screenshot exists...
        if (imageAbsolutePath != null) {
            echo "Attaching screenshot to description: [${imageAbsolutePath}]"

            // Build a base64 string of the image's content.
            def imageDataStream = imageAbsolutePath.read()
            byte[] imageData = IOUtils.toByteArray(imageDataStream)
            def imageDataString = "data:image/png;base64," + DatatypeConverter.printBase64Binary(imageData)

            def testResultAction = failedTest.getParentAction()

            // Build a description HTML to be set for the failing test that includes the image in Data URI format.
            def imgText = """<img style="width: 800px" src="${imageDataString}" />"""
            def description = """<h3>Screenshot</h3><a href="${imageDataString}">${imgText}</a>"""

            // Only modify the description if the test page hasn't been modified already
            if (!description.equals(testResultAction.getDescription(failedTest))) {
                // Set the description to the failing test and save it to disk.
                testResultAction.setDescription(failedTest, description)
                // Clear potentially problematic non-serializable object reference, after we've used it.
                // See https://github.com/jenkinsci/pipeline-plugin/blob/master/TUTORIAL.md#serializing-local-variables
                testResultAction = null
                currentBuild.rawBuild.save()
            }
        } else {
            def locationText = "[${imageAbsolutePath1}], [${imageAbsolutePath2}] or [${imageAbsolutePath3}]"
            echo "No screenshot found for test [${testClass}#${testExample}] in ${locationText}"
            sh script: "ls -alg ${targetFolderPath}", returnStatus: true
        }
    }
}

/**
 * Check for false positives for known cases of failures not related to code + check for test flickers.
 *
 * @return true if the build has false positives or if there are only flickering tests
 */
private def checkForFalsePositivesAndFlickers(def failingTests)
{
    // Step 1: Check for false positives
    def containsFalsePositives = checkForFalsePositives()

    // Step 2: Check for flickers
    def containsOnlyFlickers = checkForFlickers(failingTests)

    return containsFalsePositives || containsOnlyFlickers
}

/**
 * Check for test flickers, and modify test result descriptions for tests that are identified as flicker. A test is
 * a flicker if there's a JIRA issue having the "Flickering Test" custom field containing the FQN of the test in the
 * format "<java package name>#<test name>".
 *
 * @return true if the failing tests only contain flickering tests
 */
private def checkForFlickers(def failingTests)
{
    def knownFlickers = getKnownFlickeringTests()
    echoXWiki "Known flickering tests: ${knownFlickers}"

    // For each failed test, check if it's in the known flicker list.
    def containsAtLeastOneFlicker = false
    boolean containsOnlyFlickers = true
    boolean isModified = false
    failingTests.each() { testResult ->
        // Format of a Test Result id is "junit/<package name>/<test class name>/<test method name>"
        // Example: "junit/org.xwiki.test.ui.repository/RepositoryTest/validateAllFeatures"
        // => testName = "org.xwiki.test.ui.repository.RepositoryTest#validateAllFeatures"
        def parts = testResult.getId().split('/')
        def testName = "${parts[1]}.${parts[2]}#${parts[3]}".toString()
        echoXWiki "Analyzing test [${testName}] for flicker..."
        if (knownFlickers.contains(testName)) {
            // Add the information that the test is a flicker to the test's description. Only display this
            // once (a Jenkinsfile can contain several builds and thus this code can be called several times
            // for the same tests).
            def flickeringText = 'This is a flickering test'
            if (testResult.getDescription() == null || !testResult.getDescription().contains(flickeringText)) {
                testResult.setDescription(
                    "<h1 style='color:red'>${flickeringText}</h1>${testResult.getDescription() ?: ''}")
                isModified = true
            }
            echo "   It's a flicker"
            containsAtLeastOneFlicker = true
        } else {
            echo "   Not a flicker"
            // This is a real failing test, thus we'll need to send the notification email...
            containsOnlyFlickers = false
        }
    }

    if (containsAtLeastOneFlicker) {
        // Only add the badge if none already exist
        def badgeText = 'Contains some flickering tests'
        def badgeFound = isBadgeFound(currentBuild.getRawBuild(), badgeText)
        if (!badgeFound) {
            manager.addWarningBadge(badgeText)
            manager.createSummary('warning.gif').appendText("<h1>${badgeText}</h1>", false, false, false, 'red')
            isModified = true
        }
    }

    if (isModified) {
        // Persist changes
        currentBuild.rawBuild.save()
    }

    return containsOnlyFlickers
}

/**
 * @return all known flickering tests from JIRA in the format
 *         {@code org.xwiki.test.ui.repository.RepositoryTest#validateAllFeatures}
 */
@NonCPS
private def getKnownFlickeringTests()
{
    def knownFlickers = []
    def url = "https://jira.xwiki.org/sr/jira.issueviews:searchrequest-xml/temp/SearchRequest.xml?".concat(
            "jqlQuery=%22Flickering%20Test%22%20is%20not%20empty%20and%20resolution%20=%20Unresolved")
    def root = new XmlSlurper().parseText(url.toURL().text)
    // Note: slurper nodes are not seralizable, hence the @NonCPS annotation above.
    def packageName = ''
    root.channel.item.customfields.customfield.each() { customfield ->
        if (customfield.customfieldname == 'Flickering Test') {
            customfield.customfieldvalues.customfieldvalue.text().split(',').each() {
                // Check if a package is specified and if not use the previously found package name
                // This is an optimization to make it shorter to specify several tests in the same test class.
                // e.g.: "org.xwiki.test.ui.extension.ExtensionTest#testUpgrade,testUninstall"
                def fullName
                int pos = it.indexOf('#')
                if (pos > -1) {
                    packageName = it.substring(0, pos)
                    fullName = it
                } else {
                    fullName = "${packageName}#${it}".toString()
                }
                knownFlickers.add(fullName)
            }
        }
    }

    return knownFlickers
}

/**
 * @return the failing tests for the current build
 */
private def getFailingTests()
{
    def failingTests
    AbstractTestResultAction testResultAction = currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    if (testResultAction != null) {
        failingTests = testResultAction.getResult().getResultInRun(currentBuild.rawBuild).getFailedTests()
    } else {
        // No tests were run in this build, nothing left to do.
        failingTests = []
    }
    return failingTests
}
