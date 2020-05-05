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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.PropertyException;
import javax.xml.bind.Unmarshaller;

import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.Records;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config parser class. This class parses the xml configuration file converts it
 * into a list of requests
 *
 * @author Peter Kreutzer
 */
public class ConfigParser {

    private static final Logger logger = LoggerFactory.getLogger(ConfigParser.class);

    public ConfigParser() {
    }

    /**
     * This method saves List of Recorddefinition objects into xml file
     *
     * @param records
     *            object saved
     * @param xmlFileLocation
     *            file object to save the object into
     */
    @SuppressWarnings("resource")
    public void marshal(List<RecordDefinition> records, File xmlFileLocation) throws StiebelHeatPumpException {
        JAXBContext context;
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(xmlFileLocation), "UTF-8"));
        } catch (IOException e) {
            throw new StiebelHeatPumpException(e.toString());
        }
        try {
            context = JAXBContext.newInstance(Records.class);
        } catch (JAXBException e) {
            throw new StiebelHeatPumpException(e.toString());
        }
        Marshaller m;
        try {
            m = context.createMarshaller();
        } catch (JAXBException e) {
            throw new StiebelHeatPumpException(e.toString());
        }
        try {
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        } catch (PropertyException e) {
            throw new StiebelHeatPumpException(e.toString());
        }
        try {
            m.marshal(new Records(records), writer);
        } catch (JAXBException e) {
            throw new StiebelHeatPumpException(e.toString());
        }
        try {
            writer.close();
        } catch (IOException e) {
            throw new StiebelHeatPumpException(e.toString());
        }
    }

    /**
     * This method loads a List of Request objects from xml file
     *
     * @param importFile
     *            file object to load the object from
     * @return List of Requests
     */
    public List<RecordDefinition> unmarshal(File importFile) throws StiebelHeatPumpException {
        Records records = new Records();

        JAXBContext context;
        try {
            context = JAXBContext.newInstance(Records.class);
            Unmarshaller um = context.createUnmarshaller();
            records = (Records) um.unmarshal(importFile);
        } catch (JAXBException e) {
            throw new StiebelHeatPumpException(e.toString(), e);
        }

        return records.getRecords();
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
        try {
            JAXBContext context = JAXBContext.newInstance(Records.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);
            Records configuration = (Records) unmarshaller.unmarshal(stream);
            List<RecordDefinition> records = configuration.getRecords();
            return records;
        } catch (JAXBException e) {
            logger.debug("Parsing  failed {}. " + e.toString(), fileName);
            throw new RuntimeException(e);
        }
    }
}
