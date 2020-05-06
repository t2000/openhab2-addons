/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.stiebelheatpump.internal;

import java.io.InputStream;
import java.util.List;

import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.Records;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

/**
 * Config parser class. This class parses the xml configuration file converts it
 * into a list of requests
 *
 * @author Peter Kreutzer
 */
public class ConfigParser {

    private static final Logger logger = LoggerFactory.getLogger(ConfigParser.class);

    private final XStream xstream;

    public ConfigParser() {
        this.xstream = new XStream(new DomDriver());
    }

    /**
     * This method loads a List of Request objects from xml file
     *
     * @param fileName
     *            file object to load the object from
     * @return List of Requests
     */
    public List<RecordDefinition> parseConfig(String fileName) {
        logger.debug("Parsing  heat pump configuration file {}.", fileName);
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);
        Records records = (Records) xstream.fromXML(stream);
        return records.getRecords();
    }
}
