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

def call(firefoxVersion = '32.0.1')
{
    // Example of running "which firefox":
    // - on agents not having FF installed with apt: "/home/hudsonagent/firefox//firefox"
    // - on agents having FF installed with apt (KS4 for ex): "/usr/bin/firefox"
    // - inside the xwiki jenkins docker image "/usr/bin/firefox"
    // We expect that the other firefox version will be available at "<prefix>/firefox-<firefoxVersion>/firefox",
    // where "<prefix>" is the path before "/firefox" in the result of "which firefox" (see above).
    def ffpath = sh script: 'which firefox', returnStdout: true
    def newffpath = "${ffpath.substring(0, ffpath.indexOf('/firefox') + 8)}-${firefoxVersion}/firefox"
    echoXWiki "Using Firefox [${newffpath}]"
    return "-Dwebdriver.firefox.bin=${newffpath}"
}
