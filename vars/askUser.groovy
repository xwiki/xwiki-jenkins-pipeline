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

def call(choices)
{
    def selection

    // If a user is manually triggering this job, then ask what to build
    if (currentBuild.rawBuild.getCauses()[0].toString().contains('UserIdCause')) {
        echoXWiki "Build triggered by user, asking question..."
        try {
            timeout(time: 60, unit: 'SECONDS') {
                selection = input(id: 'selection', message: 'Select what to build', parameters: [
                    choice(choices: choices, description: 'Choose which build to execute', name: 'build')
                ])
            }
        } catch(err) {
            def sw = new StringWriter()
            def pw = new PrintWriter(sw)
            err.getCauses()[0].printStackTrace(pw)
            echoXWiki sw.toString()
            throw err
            def user = err.getCauses()[0].getUser()
            if ('SYSTEM' == user.toString()) { // SYSTEM means timeout.
                selection = 'All'
            } else {
                // Aborted by user
                throw err
            }
        }
    } else {
        echoXWiki "Build triggered automatically, building 'All'..."
        selection = 'All'
    }

    return selection
}