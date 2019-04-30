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
 * @return the list of module paths having Docker-based tests, found in the current directory and sub
 * directories. For example will return {@code xwiki-platform-menu} if there's a
 * {@code xwiki-platform-menu-test/xwiki-platform-menu-test-docker} module.
 */
def call()
{
    def modules = []
    def dockerModuleFiles = findFiles(glob: '**/*-test-docker/pom.xml')
    // Note: don't use each() since it leads to unserializable exceptions
    for (def it in dockerModuleFiles) {
        // Skip 'xwiki-platform-test-docker' since it matches the glob pattern but isn't a test module.
        if (!it.path.contains('xwiki-platform-test-docker')) {
            // Find grand parent module, e.g. return the path to xwiki-platform-menu when
            // xwiki-platform-menu-test-docker is found.
            modules.add(getParentPath(getParentPath(getParentPath(it.path))))
        }
    }
    return modules
}

private def getParentPath(def path)
{
    return path.substring(0, path.lastIndexOf('/'))
}
