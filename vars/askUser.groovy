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
 * Ask the user what to build.
 *
 * @param buildMap A map consisting of build configuration. See the Jenkinsfile for xwiki-platform to see how to use it
 * @return the id of the item selected in the passed buildMap
 */
def call(def buildMap)
{
    def selection

    // If a user is manually triggering this job, then ask what to build
    if (currentBuild.rawBuild.getCauses()[0].toString().contains('UserIdCause')) {
        echo "Build triggered by user, asking question..."
        try {
            timeout(time: 60, unit: 'SECONDS') {
                def choices = buildMap.collect { k,v -> "$k" }.join('\n')
                selection = input(id: 'selection', message: 'Select what to build', parameters: [
                        choice(choices: choices, description: 'Choose with build to execute', name: 'build')
                ])
            }
        } catch(err) {
            def user = err.getCauses()[0].getUser()
            if ('SYSTEM' == user.toString()) { // SYSTEM means timeout.
                selection = 'All'
            } else {
                // Aborted by user
                throw err
            }
        }
    } else {
        echo "Build triggered automatically, building all..."
        selection = 'All'
    }

    return selection
}