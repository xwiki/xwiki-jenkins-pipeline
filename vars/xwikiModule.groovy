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
import hudson.tasks.junit.TestResultAction
import hudson.util.IOUtils
import javax.xml.bind.DatatypeConverter

// Example usage:
//   xwikiModule {
//     goals = 'clean install' (default is 'clean deploy')
//     profiles = 'quality' (default is 'quality,legacy,integration-tests,jetty,hsqldb,firefox')
//     mavenOpts = '-Xmx1024m'
//         (default is '-Xmx1536m -Xms256m' for java8 and '-Xmx1536m -Xms256m -XX:MaxPermSize=512m' for java7)
//     mavenTool = 'Maven 3' (default is 'Maven')
//     properties = '-Dparam1=value1 -Dparam2value2' (default is empty)
//     javaTool = 'java7' (default is 'official')
//     timeout = 60 (default is 240 minutes)
//  }

def call(body)
{
    // Evaluate the body block, and collect configuration into the config object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    node {
        def mvnHome
        stage('Preparation') {
            // Get the Maven tool.
            // NOTE: The Maven tool Needs to be configured in the Jenkins global configuration.
            def mavenTool = config.mavenTool ?: 'Maven'
            mvnHome = tool mavenTool
            echoXWiki "Using Maven: ${mvnHome}"
        }
        stage('Build') {
            checkout scm
            // Configure the version of Java to use
            def mavenOpts = configureJavaTool(config)
            // Execute the XVNC plugin (useful for integration-tests)
            wrap([$class: 'Xvnc']) {
                withEnv(["PATH+MAVEN=${mvnHome}/bin", "MAVEN_OPTS=${mavenOpts}"]) {
                    try {
                        def goals = config.goals ?: 'clean deploy'
                        echoXWiki "Using Maven goals: ${goals}"
                        def profiles = config.profiles ?: 'quality,legacy,integration-tests,jetty,hsqldb,firefox'
                        echoXWiki "Using Maven profiles: ${profiles}"
                        def properties = config.properties ?: ''
                        echoXWiki "Using Maven properties: ${properties}"
                        def timeoutThreshold = config.timeout ?: 240
                        echoXWiki "Using timeout: ${timeoutThreshold}"
                        // Abort the build if it takes more than the timeout threshold (in minutes).
                        timeout(timeoutThreshold) {
                            // Note: We use -Dmaven.test.failure.ignore so that the maven build continues till the
                            // end and is not stopped by the first failing test. This allows to get more info from the
                            // build (see all failing tests for all modules built). Also note that the build is marked
                            // unstable when there are failing tests by the JUnit Archiver executed during the
                            // 'Post Build' stage below.
                            def fullProperties = "-Dmaven.test.failure.ignore ${properties}"
                            sh "mvn ${goals} jacoco:report -P${profiles} -U -e ${fullProperties}"
                        }
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        notifyByMail(currentBuild.result)
                        throw e
                    }
                }
            }
        }
        stage('Post Build') {
            // Save the JUnit test report
            junit testResults: '**/target/surefire-reports/TEST-*.xml', allowEmptyResults: true

            // For each failed test, find if there's a screenshot for it taken by the XWiki selenium tests and if so
            // embed it in the failed test's description.
            echoXWiki "Attaching screenshots to test result pages (if any)..."
            attachScreenshotToFailingTests()

            // Also send a mail notification when the job has failed tests.
            // The JUnit archiver above will mark the build as UNSTABLE when there are failed tests
            if (currentBuild.result == 'UNSTABLE') {
                notifyByMail(currentBuild.result)
            }
        }
    }
}

def notifyByMail(String buildStatus)
{
    // TODO: Handle false positives as we used to do in http://ci.xwiki.org/scriptler/editScript?id=postbuild.groovy
    // We need to convert this script to a Groovy pipeline script
    emailext (
        subject: "${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
        body: """<p>${buildStatus}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
        <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>""",
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
def configureJavaTool(config)
{
    def javaTool = config.javaTool
    if (!javaTool) {
        javaTool = getJavaTool()
    }
    // NOTE: The Java tool Needs to be configured in the Jenkins global configuration.
    env.JAVA_HOME="${tool javaTool}"
    env.PATH="${env.JAVA_HOME}/bin:${env.PATH}"
    echoXWiki "Using Java: ${env.JAVA_HOME}"

    // Configure MAVEN_OPTS based on the java version found and whether uses have configured the mavenOpts or not
    def mavenOpts = config.mavenOpts
    if (!mavenOpts) {
        mavenOpts = '-Xmx1536m -Xms256m'
        if (javaTool == 'java7') {
            mavenOpts = "${mavenOpts} -XX:MaxPermSize=512m"
        }
    }
    echoXWiki "Using Maven options: ${mavenOpts}"

    return mavenOpts
}

/**
 * Read the parent pom to try to guess the java tool to use based on the parent pom version.
 * XWiki versions < 8 should use Java 7.
 */
def getJavaTool()
{
    def pom = readMavenPom file: 'pom.xml'
    def parent = pom.parent
    def parentGroupId = parent.groupId
    def parentArtifactId = parent.artifactId
    def parentVersion = parent.version
    if (isKnownParent(parentGroupId, parentArtifactId)) {
        // If version < 8 then use Java7, otherwise official
        def major = parentVersion.substring(0, parentVersion.indexOf('.'))
        if (major.toInteger() < 8) {
            return 'java7'
        }
    }
    return 'official'
}

/**
 * Since we're trying to guess the Java version to use based on the parent POM version, we need to ensure that the
 * parent POM points to an XWiki core module (there are several possible) so that we can compare with the version 8.
 */
boolean isKnownParent(parentGroupId, parentArtifactId)
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
def createFilePath(String path) {
    if (env['NODE_NAME'] == null) {
        error "envvar NODE_NAME is not set, probably not inside an node {} or running an older version of Jenkins!";
    } else if (env['NODE_NAME'].equals("master")) {
        return new FilePath(new File(path));
    } else {
        return new FilePath(Jenkins.getInstance().getComputer(env['NODE_NAME']).getChannel(), path);
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
 *   <li>The following security exceptions must be added to http://<jenkins server ip>/scriptApproval/:
 *       <pre><code>
 *         field hudson.tasks.test.AbstractTestResultAction owner
 *         method hudson.FilePath exists
 *         method hudson.FilePath getParent
 *         method hudson.FilePath read
 *         method hudson.model.Actionable getAction java.lang.Class
 *         method hudson.model.Saveable save
 *         method hudson.tasks.junit.CaseResult getClassName
 *         method hudson.tasks.junit.CaseResult getSimpleName
 *         method hudson.tasks.junit.CaseResult getSuiteResult
 *         method hudson.tasks.junit.SuiteResult getFile
 *         method hudson.tasks.junit.TestObject getName
 *         method hudson.tasks.test.AbstractTestResultAction getFailedTests
 *         method hudson.tasks.test.AbstractTestResultAction getResult
 *         method hudson.tasks.test.AbstractTestResultAction setDescription hudson.tasks.test.TestObject java.lang.String
 *         method hudson.tasks.test.TestResult getParentAction
 *         method org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper getRawBuild
 *         method org.jvnet.hudson.plugins.groovypostbuild.GroovyPostbuildRecorder$BadgeManager getBuild
 *         new hudson.FilePath hudson.FilePath java.lang.String
 *         new hudson.FilePath java.io.File
 *         new java.io.File java.lang.String
 *         staticMethod hudson.util.IOUtils toByteArray java.io.InputStream
 *         staticMethod javax.xml.bind.DatatypeConverter printBase64Binary byte[]
 *       </code></pre>
 *     </li>
 *     <li>Install the <a href="https://wiki.jenkins-ci.org/display/JENKINS/PegDown+Formatter+Plugin">Pegdown Formatter
 *       plugin</a> and set the description syntax to be Pegdown in the Global Security configuration
 *       (http://<jenkins server ip>/configureSecurity).</li>
 *  </ul>
 */
def attachScreenshotToFailingTests() {
    def testResults = manager.build.getAction(TestResultAction.class)
    if (testResults == null) {
        // No tests were run in this build, nothing left to do.
        return
    }

    // Go through each failed test in the current build.
    def failedTests = testResults.getFailedTests()
    for (def failedTest : failedTests) {
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
        echo "Image path 1 (selenium 1) [${imageAbsolutePath1}], Exists: [${imageAbsolutePath1.exists()}]"
        echo "Image path 2 (selenium 2) [${imageAbsolutePath2}], Exists: [${imageAbsolutePath2.exists()}]"
        echo "Image path 3 (tmp) [${imageAbsolutePath3}], Exists: [${imageAbsolutePath3.exists()}]"
        def imageAbsolutePath = imageAbsolutePath1.exists() ?
            imageAbsolutePath1 : (imageAbsolutePath2.exists() ? imageAbsolutePath2 :
                (imageAbsolutePath3.exists() ? imageAbsolutePath3 : null))

        echo "Attaching screenshot to description: [${imageAbsolutePath}]"

        // If the screenshot exists...
        if (imageAbsolutePath != null) {
            // Build a base64 string of the image's content.
            def imageDataStream = imageAbsolutePath.read()
            byte[] imageData = IOUtils.toByteArray(imageDataStream)
            def imageDataString = "data:image/png;base64," + DatatypeConverter.printBase64Binary(imageData)

            def testResultAction = failedTest.getParentAction()

            // Build a description HTML to be set for the failing test that includes the image in Data URI format.
            def description = """
               <h3>Screenshot</h3>
               <a href="${imageDataString}"><img style="width: 800px" src="${imageDataString}" /></a>
            """

            // Set the description to the failing test and save it to disk.
            testResultAction.setDescription(failedTest, description)
            currentBuild.rawBuild.save()
        }
    }
}