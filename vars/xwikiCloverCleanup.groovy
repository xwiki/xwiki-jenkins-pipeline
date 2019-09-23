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

void call()
{
    def sshPrefix = 'ssh maven@maven.xwiki.org'
    def location = 'public_html/site/clover'
    def dirs = sh(script: "${sshPrefix} ls -1t ${location}/", returnStdout: true).split()
    int previousTPC = -1
    dirs.each() {
        if (it.startsWith('2019')) {
            def tpc = sh(script: "${sshPrefix} \"sed -ne 's/.*<td>ALL<\\/td><td>[^<]*<\\/td><td>\\([^<]*\\)<\\/td>.*/\\1/p;q;' ${location}/${it}/XWikiReport*.html\"", returnStdout: true) as Integer
            echoXWiki("TPC = ${tpc}")
            if (previousTPC != -1 && tpc < previousTPC) {
                // Move directory inside toDelete directory
                echoXWiki "TPC in ${it} (${tpc}) lower than previous one (${previousTPC}), moving directory to be deleted"
                sh(script: "${sshPrefix} \"mkdir -p ${location}/toDelete; mv ${location}/${it} ${location}/toDelete/\"", returnStdout: true)
            }
            previousTPC = tpc
        }
    }
}
