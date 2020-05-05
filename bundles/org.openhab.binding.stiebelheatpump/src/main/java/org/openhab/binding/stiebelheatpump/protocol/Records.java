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

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

/**
 * Records class for Stiebel heat pump.
 *
 * @author Peter Kreutzer
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = { "recordDefinition" })
@XmlRootElement(name = "records")
public class Records {

    private List<RecordDefinition> records = new ArrayList<RecordDefinition>();

    public Records() {
    }

    public Records(List<RecordDefinition> records) {
        this.records = records;
    }

    public List<RecordDefinition> getRecords() {
        return records;
    }

    @XmlElement
    public void setRecords(List<RecordDefinition> records) {
        this.records = records;
    }

    public static <T> List<T> searchIn(List<T> list, Matcher<T> m) {
        List<T> r = new ArrayList<T>();
        for (T t : list) {
            if (m.matches(t)) {
                r.add(t);
            }
        }
        return r;
    }

    public interface Matcher<T> {
        public boolean matches(T t);
    }
}
