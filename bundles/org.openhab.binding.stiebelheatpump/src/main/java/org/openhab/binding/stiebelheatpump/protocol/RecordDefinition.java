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

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlValue;

/**
 * Record definition class for Stiebel heat pump requests.
 *
 * @author Peter Kreutzer
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "", propOrder = { "value" })
@XmlRootElement(name = "recordDefinition")
public class RecordDefinition {

    public static enum Type {
        Sensor,
        Status,
        Settings;
    }

    @XmlValue
    protected String value;
    @XmlAttribute(name = "channelid")
    private String channelid;

    @XmlAttribute(name = "requestByte")
    private byte requestByte;

    @XmlAttribute(name = "DataType", required = true)
    private Type dataType;

    @XmlAttribute(name = "Position")
    private int position;

    @XmlAttribute(name = "Length")
    private int length;

    @XmlAttribute(name = "Scale")
    private double scale;

    @XmlAttribute(name = "BitPosition")
    private int bitPosition;

    private int min;

    private int max;

    private double step;

    private String unit;

    public RecordDefinition() {
    }

    /**
     * Constructor of record definition used for status and sensor values
     *
     * @param channelid
     *            of record
     * @param position
     *            of the value in the byte array
     * @param lenght
     *            of byte representing the value
     * @param scale
     *            to apply to the byte value
     * @param dataType
     *            of the record, see enums
     * @param unit
     *            of the value
     */
    public RecordDefinition(String channelid, byte requestByte, int position, int lenght, double scale, Type dataType,
            String unit) {
        this.channelid = channelid;
        this.requestByte = requestByte;
        this.position = position;
        this.length = lenght;
        this.scale = scale;
        this.dataType = dataType;
        this.unit = unit;
    }

    /**
     * Constructor of record definition used for setting programs with week days
     * encoding
     *
     * @param channelid
     *            of record
     * @param position
     *            of the value in the byte array
     * @param lenght
     *            of byte representing the value
     * @param scale
     *            to apply to the byte value
     * @param dataType
     *            of the record, see enums
     * @param min
     *            values for a setting
     * @param max
     *            values for a setting
     * @param step
     *            in which setting can be changed
     * @param bitPosition
     *            of the bit in the byte representing the value
     * @param unit
     *            of the value
     */
    public RecordDefinition(String channelid, byte requestByte, int position, int lenght, double scale, Type dataType,
            int min, int max, double step, int bitPosition, String unit) {
        this.channelid = channelid;
        this.requestByte = requestByte;
        this.position = position;
        this.length = lenght;
        this.scale = scale;
        this.dataType = dataType;
        this.min = min;
        this.max = max;
        this.step = step;
        this.bitPosition = bitPosition;
        this.unit = unit;
    }

    /**
     * Constructor of record definition used for settings that can be changed
     *
     * @param channelid
     *            of record
     * @param position
     *            of the value in the byte array
     * @param lenght
     *            of byte representing the value
     * @param scale
     *            to apply to the byte value
     * @param dataType
     *            of the record, see enums
     * @param min
     *            values for a setting
     * @param max
     *            values for a setting
     * @param step
     *            in which setting can be changed
     * @param unit
     *            of the value
     */
    public RecordDefinition(String channelid, byte requestByte, int position, int lenght, double scale, Type dataType,
            int min, int max, double step, String unit) {
        this.channelid = channelid;
        this.requestByte = requestByte;
        this.position = position;
        this.length = lenght;
        this.scale = scale;
        this.dataType = dataType;
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit;
    }

    /**
     * Gets the value of the value property.
     *
     * @return
     *         possible object is
     *         {@link String }
     *
     */
    public String getValue() {
        return value;
    }

    /**
     * Sets the value of the value property.
     *
     * @param value
     *            allowed object is
     *            {@link String }
     *
     */
    public void setValue(String value) {
        this.value = value;
    }

    public String getChannelid() {
        return channelid;
    }

    public void setChannelid(String channelid) {
        this.channelid = channelid;
    }

    public byte getRequestByte() {
        return requestByte;
    }

    public void setRequestByte(byte requestByte) {
        this.requestByte = requestByte;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public double getScale() {
        return scale;
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public Type getDataType() {
        return dataType;
    }

    public void setDataType(Type dataType) {
        this.dataType = dataType;
    }

    public int getMin() {
        return min;
    }

    public void setMin(int min) {
        this.min = min;
    }

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = max;
    }

    public double getStep() {
        return step;
    }

    public void setStep(double step) {
        this.step = step;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public int getBitPosition() {
        return bitPosition;
    }

    public void setBitPosition(int bitPosition) {
        this.bitPosition = bitPosition;
    }
}
