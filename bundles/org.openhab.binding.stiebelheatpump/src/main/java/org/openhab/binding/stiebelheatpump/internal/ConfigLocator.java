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

import java.util.List;

import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;

/**
 * Config locator class. This class located the configuration file in the
 * resources and converts it into a list of requests
 *
 * @author Peter Kreutzer
 */
public class ConfigLocator {

    private String file;
    private ConfigParser configParser = new ConfigParser();

    public ConfigLocator() {
    }

    /**
     * @param file
     *            that shall be located in the resources\thing the file shall contain
     *            the configuration of the specific request to the firmware
     *            version naming convention shall be "thingtypenid.xml" , e.g.
     *            LWZ_THZ303_2_06.xml
     */
    public ConfigLocator(String file) {
        this.file = file;
    }

    /**
     * Searches for the given files in the class path.
     *
     * @return All found Configurations
     */
    public List<RecordDefinition> getConfig() {
        List<RecordDefinition> config = configParser.parseConfig(file);
        return config;
    }
}
