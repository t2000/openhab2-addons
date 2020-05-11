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
package org.openhab.binding.stiebelheatpump.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * request class for Stiebel heat pump.
 *
 * @author Peter Kreutzer
 */
public class Request {

    private String name;

    private String description;

    private Byte requestByte;

    private List<RecordDefinition> recordDefinitions = new ArrayList<RecordDefinition>();

    public Request() {
    }

    public Request(String name, String description, byte requestByte) {
        this.name = name;
        this.description = description;
        this.requestByte = requestByte;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public byte getRequestByte() {
        return requestByte;
    }

    public void setRequestByte(byte requestByte) {
        this.requestByte = requestByte;
    }

    public List<RecordDefinition> getRecordDefinitions() {
        return recordDefinitions;
    }

    public void setRecordDefinitions(List<RecordDefinition> recordDefinitions) {
        this.recordDefinitions = recordDefinitions;
    }
}
