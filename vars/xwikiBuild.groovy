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
import com.jenkinsci.plugins.badge.action.BadgeAction
import com.cloudbees.groovy.cps.NonCPS

// Example usage:
// parallel(
//     "standard": {
//         node {
//             xwikiBuild {
//                 goals = "clean install"
//             }
//         }
//     },
//     "test": {
//         node {
//             xwikiBuild {
//                 goals = "clean deploy"
//             }
//         }
//     }
// )

// The following options are available:
//     goals = 'clean install' (default is 'clean deploy' for 'master' and 'stable-*' branches and 'clean install'
//         for the rest)
//     profiles = 'quality' (default is 'quality,legacy,integration-tests,jetty,hsqldb,firefox')
//     mavenOpts = '-Xmx1024m'
//         (default is '-Xmx1536m -Xms256m' for java8 and '-Xmx1536m -Xms256m -XX:MaxPermSize=512m' for java7)
//     mavenTool = 'Maven 3' (default is 'Maven')
//     properties = '-Dparam1=value1 -Dparam2value2' (default is empty)
//     javaTool = 'java7' (default is 'official')
//     timeout = 60 (default is 240 minutes)
//     disabled = true (allows disabling a build, defaults to true)
//     xvnc = false (disable running xvnc, useful when running on a local Jenkins, defaults to true)
//     pom = 'some/other/pom.xml' (defaults to 'pom.xml')
//     archiveArtifacts = true (defaults to false since we don't need that as we push to a maven repo)
//     fingerprintDependencies = false (default to true since it's required by the withMaven dependency graph feature
//         in order to have builds trigger automatically other builds when dependencies are built)
//     skipCheckout = true (default is false). If true then don't perform a SCM checkout by default. This is useful to
//         be able to use this library for simple pipeline jobs (without a Jenkinsfile). In this case the pipeline
//         would do the checkout.
//     mavenFlags = '--projects ... -amd -U -e' (default is '-U -e')
//     cron = '@midnight' (default is '@monthly'). Sets the minimal time-based trigger for the job. Use 'none' to
//         not override the cron setting for the job.
//     skipMail = true (default is false). If true then don't send emails when the job or tests fail.
//
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
def call(String name = 'Default', body)
{
    echoXWiki "Calling Jenkinsfile..."
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Only keep the 10 most recent builds & disable concurrent builds to avoid rebuilding whenever a new commit is
    // made. The commits will accumulate till the previous build is finished before starting a new one.
    // Note 1: this is limiting concurrency per branch only.
    // Note 2: This needs to be one of the first code executed which is why it's the first step we execute.
    // See https://thepracticalsysadmin.com/limit-jenkins-multibranch-pipeline-builds/ for details.
    echoXWiki "Only keep the 10 most recent builds + disable concurrent builds"
    def projectProperties = [
            [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', daysToKeepStr: '7']],
            disableConcurrentBuilds()
    ]

    // By default make sure projects are built at least once a month because SNAPSHOT older than one month are deleted
    // by a Nexus scheduler.
    def cronValue = config.cron ?: '@monthly'
    if (cronValue != 'none') {
        echoXWiki "Setting cron to [${cronValue}]"
        projectProperties.add(pipelineTriggers([cron("${cronValue}")]))
    }
    properties(projectProperties)

    // Initialize a variable that hold past failing tests. We need this because we can't get access to the failing
    // tests for a given Maven run (we can only get them globally).
    // See https://groups.google.com/d/msg/jenkinsci-users/dDDPC486JWE/9vtojUOoAwAJ
    // Note1: we save TesResult objects in this list and they are serializable.
    // Note2: This strategy is not working and cannot work because of parallel() executions, see
    //        https://issues.jenkins-ci.org/browse/JENKINS-49339. The consequence is that we can get several emails
    //        sent for the same test errors (no past failing test when the various maven build start in // and as
    //        soon as one has a failing test they'll all report is as a new failing test).
    def savedFailingTests = getFailingTests()
    echoXWiki "Past failing tests: ${savedFailingTests.collect { "${it.getClassName()}#${it.getName()}" }}"

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
            checkout scm
        }

        // Configure the version of Java to use
        def pom = readMavenPom file: 'pom.xml'
        configureJavaTool(config, pom)

        // Display some environmental information that can be useful to debug some failures
        // Note: if the executables don't exist, this won't fail the step thanks to "returnStatus: true".
        sh script: 'ps -ef', returnStatus: true
        sh script: 'netstat -nltp', returnStatus: true
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
            echoXWiki "Using Java: ${env.JAVA_HOME}"
            echoXWiki "Using Maven tool: ${mavenTool}"
            echoXWiki "Using Maven options: ${env.MAVEN_OPTS}"
            def archiveArtifacts = config.archiveArtifacts == null ? false : config.archiveArtifacts
            echoXWiki "Artifact archiving: ${archiveArtifacts}"
            def fingerprintDependencies = config.fingerprintDependencies == null ? true :
                config.fingerprintDependencies
            echoXWiki "Dependencies fingerprinting: ${fingerprintDependencies}"
            // Note: We're not passing "mavenOpts" voluntarily, see configureJavaTool()
            withMaven(maven: mavenTool, options: [artifactsPublisher(disabled: !archiveArtifacts),
                dependenciesFingerprintPublisher(disabled: !fingerprintDependencies)])
            {
                try {
                    def goals = computeMavenGoals(config)
                    echoXWiki "Using Maven goals: ${goals}"
                    def profiles = config.profiles ?: 'quality,legacy,integration-tests,jetty,hsqldb,firefox'
                    echoXWiki "Using Maven profiles: ${profiles}"
                    def properties = config.properties ?: ''
                    echoXWiki "Using Maven properties: ${properties}"
                    def timeoutThreshold = config.timeout ?: 240
                    echoXWiki "Using timeout: ${timeoutThreshold}"
                    // Display the java version for information (in case it's useful to debug some specific issue)
                    sh script: 'java -version', returnStatus: true
                    // Abort the build if it takes more than the timeout threshold (in minutes).
                    timeout(timeoutThreshold) {
                        def pom = config.pom ?: 'pom.xml'
                        echoXWiki "Using POM file: ${pom}"
                        // Note: We use -Dmaven.test.failure.ignore so that the maven build continues till the
                        // end and is not stopped by the first failing test. This allows to get more info from the
                        // build (see all failing tests for all modules built). Also note that the build is marked
                        // unstable when there are failing tests by the JUnit Archiver executed during the
                        // 'Post Build' stage below.
                        def fullProperties = "-Dmaven.test.failure.ignore ${properties}"
                        // Set Maven flags to use
                        def mavenFlags = config.mavenFlags ?: '-U -e'
                        sh "mvn -f ${pom} ${goals} -P${profiles} ${mavenFlags} ${fullProperties}"
                    }
                } catch (Exception e) {
                    // - If this line is reached it means the build has failed (other than for failing tests) or has
                    //   been aborted (because we told maven above to not stop on test failures!)
                    // - We stop the build by throwing the exception.
                    // - Note that withMaven() doesn't set any build result in this case but we don't need to set any
                    //   since we're stopping the build!
                    // - Don't send emails for aborts! We discover aborts by checking for exit code 143.
                    if (!e.getMessage().contains('exit code 143') && !config.skipMail) {
                        notifyByMail('ERROR', name)
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

        echoXWiki "Current build status after withMaven execution: ${currentBuild.result}"

        // For each failed test, find if there's a screenshot for it taken by the XWiki selenium tests and if so
        // embed it in the failed test's description.
        if (currentBuild.result != 'SUCCESS') {
            def failingTests = getFailingTestsSinceLastMavenExecution(savedFailingTests)
            echoXWiki "New failing tests: ${failingTests.collect { "${it.getClassName()}#${it.getName()}" }}"
            if (!failingTests.isEmpty()) {
                echoXWiki "Attaching screenshots to test result pages (if any)..."
                attachScreenshotToFailingTests(failingTests)

                // Check for false positives & Flickers.
                echoXWiki "Checking for false positives and flickers in build results..."
                def containsFalsePositivesOrOnlyFlickers = checkForFalsePositivesAndFlickers(failingTests)

                // Also send a mail notification when the job is not successful.
                echoXWiki "Checking if email should be sent or not"
                if (!containsFalsePositivesOrOnlyFlickers && !config.skipMail) {
                    notifyByMail(currentBuild.result, name)
                } else {
                    echoXWiki "No email sent even if some tests failed because they contain only flickering tests!"
                    echoXWiki "Considering job as successful!"
                    currentBuild.result = 'SUCCESS'
                }
            }
        }
    }
}

def wrapInXvnc(config, closure)
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

def notifyByMail(String buildStatus, String name)
{

    echoXWiki "Build has failed, sending mails to concerned parties"

    // Sending mails to:
    // - culprits: list of users who committed a change since the last non-broken build till now
    // - developers: anyone who checked in code for the last build
    // - requester: whoever triggered the build manually

    // TODO: Sending some simple mail content FTM since it seems that parsing large logs fails and hangs the job.
    sendSimpleMail(buildStatus, name)
}

def sendFullMail(String buildStatus, String name)
{
    emailext (
            subject: "${env.JOB_NAME} - [${name}] - Build # ${env.BUILD_NUMBER} - ${buildStatus}",
            body: '''
Check console output at $BUILD_URL to view the results.

Failed tests:

${FAILED_TESTS}

Cause of error:

${BUILD_LOG_REGEX, regex = ".*BUILD FAILURE.*", linesBefore = 250, linesAfter = 0}

Maven error reported:

${BUILD_LOG_REGEX, regex = ".*Re-run Maven using the -X switch to enable full debug logging*", linesBefore = 100, linesAfter = 0}
''',
            mimeType: 'text/plain',
            recipientProviders: [
                    [$class: 'CulpritsRecipientProvider'],
                    [$class: 'DevelopersRecipientProvider'],
                    [$class: 'RequesterRecipientProvider']
            ]
    )
}

def sendSimpleMail(String buildStatus, String name)
{
    emailext (
            subject: "${env.JOB_NAME} - [${name}] - Build # ${env.BUILD_NUMBER} - ${buildStatus}",
            body: '''
Check console output at $BUILD_URL to view the results.

Failed tests:

${FAILED_TESTS}
''',
            mimeType: 'text/plain',
            recipientProviders: [
                    [$class: 'CulpritsRecipientProvider'],
                    [$class: 'DevelopersRecipientProvider'],
                    [$class: 'RequesterRecipientProvider']
            ]
    )
}

/**
 * Echo text with a special character prefix to make it stand out in the pipeline logs.
 */
def echoXWiki(string)
{
    echo "\u27A1 ${string}"
}

/**
 * Configure which Java version to use by Maven and which Java memory options to use when the {@code javaTool} and
 * {@code mavenOpts} config parameter weren't specified.
 */
def configureJavaTool(def config, def pom)
{
    def javaTool = config.javaTool
    if (!javaTool) {
        javaTool = getJavaTool(pom)
    }
    // NOTE: The Java tool Needs to be configured in the Jenkins global configuration.
    env.JAVA_HOME="${tool javaTool}"
    env.PATH="${env.JAVA_HOME}/bin:${env.PATH}"

    // Configure MAVEN_OPTS based on the java version found and whether users have configured the mavenOpts or not
    echoXWiki "Found overridden Maven options: ${config.mavenOpts}"
    def mavenOpts = config.mavenOpts
    if (!mavenOpts) {
        mavenOpts = '-Xmx1920m -Xms256m'
        if (javaTool == 'java7') {
            mavenOpts = "${mavenOpts} -XX:MaxPermSize=512m"
        }
    }
    // Note: withMaven is concatenating any passed "mavenOpts" with env.MAVEN_OPTS. Thus in order to fully
    // control the maven options used we only set env.MAVEN_OPTS and don't pass "mavenOpts" when using withMaven.
    // See http://bit.ly/2zwl4IU
    env.MAVEN_OPTS = mavenOpts
}

/**
 * Read the parent pom to try to guess the java tool to use based on the parent pom version.
 * XWiki versions < 8 should use Java 7.
 */
def getJavaTool(def pom)
{
    def parent = pom.parent
    def groupId
    def artifactId
    def version
    if (parent != null) {
        groupId = parent.groupId
        artifactId = parent.artifactId
        version = parent.version
    } else {
        // We're on the top pom (no parent)
        groupId = pom.groupId
        artifactId = pom.artifactId
        version = pom.version

    }
    if (isKnownParent(groupId, artifactId)) {
        // If version < 8 then use Java7, otherwise official
        def major = version.substring(0, version.indexOf('.'))
        if (major.toInteger() < 8) {
            return 'java7'
        }
    }
    return 'official'
}

def computeMavenGoals(config)
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
 * Since we're trying to guess the Java version to use based on the parent POM version, we need to ensure that the
 * parent POM points to an XWiki core module (there are several possible) so that we can compare with the version 8.
 */
def isKnownParent(parentGroupId, parentArtifactId)
{
    return (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-platform') ||
        (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-commons') ||
        (parentGroupId == 'org.xwiki.contrib' && parentArtifactId == 'parent-rendering') ||
        (parentGroupId == 'org.xwiki.commons' && parentArtifactId == 'xwiki-commons-pom') ||
        (parentGroupId == 'org.xwiki.rendering' && parentArtifactId == 'xwiki-rendering') ||
        (parentGroupId == 'org.xwiki.platform' && parentArtifactId == 'xwiki-platform') ||
        (parentGroupId == 'org.xwiki.platform' && parentArtifactId == 'xwiki-platform-distribution')
}

/**
 * Create a FilePath instance that points either to a file on the master node or a file on a remote agent node.
 */
def createFilePath(String path)
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
 * Attach the screenshot of a failing XWiki Selenium test to the failed test's description.
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
def attachScreenshotToFailingTests(def failingTests)
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
        // The screenshot can have 2 possible file names and locations, we have to look for both.
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

            // Set the description to the failing test and save it to disk.
            testResultAction.setDescription(failedTest, description)
            // Clear potentially problematic non-serializable object reference, after we've used it.
            // See https://github.com/jenkinsci/pipeline-plugin/blob/master/TUTORIAL.md#serializing-local-variables
            testResultAction = null
            currentBuild.rawBuild.save()
        } else {
            def locationText = "[${imageAbsolutePath1}], [${imageAbsolutePath2}] or [${imageAbsolutePath3}]"
            echo "No screenshot found for test [${testClass}#${testExample}] in ${locationText}"
        }
    }
}

/**
 * Check for false positives for known cases of failures not related to code + check for test flickers.
 *
 * @return true if the build has false positives or if there are only flickering tests
 */
def checkForFalsePositivesAndFlickers(def failingTests)
{
    // Step 1: Check for false positives
    def containsFalsePositives = checkForFalsePositives()

    // Step 2: Check for flickers
    def containsOnlyFlickers = checkForFlickers(failingTests)

    return containsFalsePositives || containsOnlyFlickers
}

/**
 * Check for false positives for known cases of failures not related to code.
 *
 * @return true if false positives have been detected
 */
def checkForFalsePositives()
{
    def messages = [
        [".*A fatal error has been detected by the Java Runtime Environment.*", "JVM Crash", "A JVM crash happened!"],
        [".*Error: cannot open display: :1.0.*", "VNC not running", "VNC connection issue!"],
        [".*java.lang.NoClassDefFoundError: Could not initialize class sun.awt.X11GraphicsEnvironment.*", "VNC issue",
             "VNC connection issue!"],
        [".*hudson.plugins.git.GitException: Could not fetch from any repository.*", "Git issue",
             "Git fetching issue!"],
        [".*Error communicating with the remote browser. It may have died..*", "Browser issue",
             "Connection to Browser has died!"],
        [".*Failed to start XWiki in .* seconds.*", "XWiki Start", "Failed to start XWiki fast enough!"],
        [".*Failed to transfer file.*nexus.*Return code is:.*ReasonPhrase:Service Temporarily Unavailable.",
             "Nexus down", "Nexus is down!"],
        [".*com.jcraft.jsch.JSchException: java.net.UnknownHostException: maven.xwiki.org.*",
             "maven.xwiki.org unavailable", "maven.xwiki.org is not reachable!"],
        [".*Fatal Error: Unable to find package java.lang in classpath or bootclasspath.*", "Compilation issue",
             "Compilation issue!"],
        [".*hudson.plugins.git.GitException: Command.*", "Git issue", "Git issue!"],
        [".*Caused by: org.openqa.selenium.WebDriverException: Failed to connect to binary FirefoxBinary.*",
             "Browser issue", "Browser setup is wrong somehow!"],
        [".*java.net.SocketTimeoutException: Read timed out.*", "Unknown connection issue",
             "Unknown connection issue!"],
        [".*Can't connect to X11 window server.*", "VNC not running", "VNC connection issue!"],
        [".*The forked VM terminated without saying properly goodbye.*", "Surefire Forked VM crash",
             "Surefire Forked VM issue!"],
        [".*java.lang.RuntimeException: Unexpected exception deserializing from disk cache.*", "GWT building issue",
             "GWT building issue!"],
        [".*Unable to bind to locking port 7054.*", "Selenium bind issue with browser", "Selenium issue!"],
        [".*Error binding monitor port 8079: java.net.BindException: Cannot assign requested address.*",
             "XWiki instance already running", "XWiki stop issue!"],
        [".*Caused by: java.util.zip.ZipException: invalid block type.*", "Maven build error",
             "Maven generated some invalid Zip file"],
        [".*java.lang.ClassNotFoundException: org.jvnet.hudson.maven3.listeners.MavenProjectInfo.*", "Jenkins error",
             "Unknown Jenkins error!"],
        [".*Failed to execute goal org.codehaus.mojo:sonar-maven-plugin.*No route to host.*", "Sonar error",
             "Error connecting to Sonar!"]
    ]

    messages.each { message ->
        if (manager.logContains(message.get(0))) {
            manager.addWarningBadge(message.get(1))
            manager.createSummary("warning.gif").appendText("<h1>${message.get(2)}</h1>", false, false, false, "red")
            manager.buildUnstable()
            echoXWiki "False positive detected [${message.get(2)}] ..."
            return true
        }
    }

    return false
}

/**
 * Check for test flickers, and modify test result descriptions for tests that are identified as flicker. A test is
 * a flicker if there's a JIRA issue having the "Flickering Test" custom field containing the FQN of the test in the
 * format "<java package name>#<test name>".
 *
 * @return true if the failing tests only contain flickering tests
 */
def checkForFlickers(def failingTests)
{
    def knownFlickers = getKnownFlickeringTests()
    echoXWiki "Known flickering tests: ${knownFlickers}"

    // For each failed test, check if it's in the known flicker list.
    def containsAtLeastOneFlicker = false
    boolean containsOnlyFlickers = true
    failingTests.each() { testResult ->
        // Format of a Test Result id is "junit/<package name>/<test class name>/<test method name>"
        // Example: "junit/org.xwiki.test.ui.repository/RepositoryTest/validateAllFeatures"
        // => testName = "org.xwiki.test.ui.repository.RepositoryTest#validateAllFeatures"
        def parts = testResult.getId().split('/')
        def testName = "${parts[1]}.${parts[2]}#${parts[3]}".toString()
        echoXWiki "Analyzing test [${testName}] for flicker..."
        if (knownFlickers.contains(testName)) {
            // Add the information that the test is a flicker to the test's description. Only display this
            // once (a Jenkinsfile can contain several builds and this this code can be called several times
            // for the same tests).
            def flickeringText = 'This is a flickering test'
            if (testResult.getDescription() == null || !testResult.getDescription().contains(flickeringText)) {
                testResult.setDescription(
                    "<h1 style='color:red'>${flickeringText}</h1>${testResult.getDescription() ?: ''}")
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
        def badgeFound = isBadgeFound(
            currentBuild.getRawBuild().getActions(BadgeAction.class), badgeText)
        if (!badgeFound) {
            manager.addWarningBadge(badgeText)
            manager.createSummary("warning.gif").appendText("<h1>${badgeText}</h1>", false, false, false, "red")
        }
    }

    return containsOnlyFlickers
}

@NonCPS
def isBadgeFound(def badgeActionItems, def badgeText)
{
    badgeActionItems.each() {
        if (it.getText().contains(badgeText)) {
            return true
        }
    }
    return false
}

/**
 * @return all known flickering tests from JIRA in the format
 *         {@code org.xwiki.test.ui.repository.RepositoryTest#validateAllFeatures}
 */
@NonCPS
def getKnownFlickeringTests()
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

def getFailingTests()
{
    def failingTests
    AbstractTestResultAction testResultAction = currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    if (testResultAction != null) {
        failingTests = testResultAction.getResult().getFailedTests()
    } else {
        // No tests were run in this build, nothing left to do.
        failingTests = []
    }
    return failingTests
}

def getFailingTestsSinceLastMavenExecution(savedFailingTests)
{
    def failingTestsSinceLastExecution = []
    def fullFailingTests = getFailingTests()
    def savedFailingTestsAsStringList = savedFailingTests.collect { "${it.getClassName()}#${it.getName()}" }
    echo "All failing tests at this point: ${fullFailingTests.collect { "${it.getClassName()}#${it.getName()}" }}"
    fullFailingTests.each() {
        def fullTestName = "${it.getClassName()}#${it.getName()}"
        if (!savedFailingTestsAsStringList.contains(fullTestName)) {
            failingTestsSinceLastExecution.add(it)
        }
    }
    return failingTestsSinceLastExecution
}
