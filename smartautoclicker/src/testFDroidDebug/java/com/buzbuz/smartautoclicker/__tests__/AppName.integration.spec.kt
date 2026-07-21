/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.__tests__

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Node

class AppNameIntegrationSpec {

    @Test
    fun `debug app label identifies development build`() {
        val stringsDocument = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/debug/res/values/strings.xml"))
        val appNameNode = XPathFactory.newInstance().newXPath().evaluate(
            "/resources/string[@name='app_name']",
            stringsDocument,
            XPathConstants.NODE,
        ) as Node

        assertEquals("Klick´r dev", appNameNode.textContent)
    }
}
