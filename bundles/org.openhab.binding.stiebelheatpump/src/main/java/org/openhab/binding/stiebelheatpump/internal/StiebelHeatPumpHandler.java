/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.stiebelheatpump.internal;

import static org.openhab.binding.stiebelheatpump.internal.StiebelHeatPumpBindingConstants.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.thing.type.ThingType;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.io.transport.serial.SerialPortIdentifier;
import org.eclipse.smarthome.io.transport.serial.SerialPortManager;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition.Type;
import org.openhab.binding.stiebelheatpump.protocol.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link StiebelHeatPumpHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Peter Kreutzer - Initial contribution
 */
public class StiebelHeatPumpHandler extends BaseThingHandler {

    private Logger logger = LoggerFactory.getLogger(StiebelHeatPumpHandler.class);
    private final SerialPortManager serialPortManager;
    private SerialPortIdentifier portId;

    /** port of interface to heat pump */
    private String port;
    /** baudRate of interface to heat pump */
    private int baudRate;
    /** waiting time between requests */
    private int waitingTime;
    /** refresh interval */
    private BigDecimal refresh = new BigDecimal(0);
    // ** indicates if the communication is currently in use by a call
    boolean communicationInUse = false;

    /** heat pump request definition */
    private List<Request> heatPumpConfiguration = new ArrayList<Request>();
    private List<Request> heatPumpSensorConfiguration = new ArrayList<Request>();
    private List<Request> heatPumpSettingConfiguration = new ArrayList<Request>();
    private List<Request> heatPumpStatusConfiguration = new ArrayList<Request>();
    private List<Request> heatPumpRefresh = new ArrayList<Request>();
    private Request versionRequest;
    private Request timeRequest;

    /** cyclic pooling of data from heat pump */
    ScheduledFuture<?> refreshJob;
    /** cyclic update of time in the heat pump */
    ScheduledFuture<?> timeRefreshJob;

    private ThingType thingType;

    public StiebelHeatPumpHandler(Thing thing, ThingType thingType, final SerialPortManager serialPortManager) {
        super(thing);
        this.thingType = thingType;
        this.serialPortManager = serialPortManager;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Received command {} for channelUID {}", command, channelUID);

        if (command instanceof RefreshType) {
            // refresh is handled with scheduled polling of data
            return;
        }

        int retry = 0;
        while (communicationInUse & (retry < MAXRETRY)) {
            try {
                Thread.sleep(waitingTime);
            } catch (InterruptedException e) {
                logger.debug("Could not get access to heatpump ! : {}", e.toString());
            }
            retry++;
        }
        if (communicationInUse) {
            return;
        }
        communicationInUse = true;

        CommunicationService communicationService = null;
        try {
            communicationService = new CommunicationService(serialPortManager, heatPumpConfiguration, port, baudRate,
                    waitingTime);
            Map<String, String> data = new HashMap<String, String>();

            switch (channelUID.getId()) {
                case CHANNEL_SETTIME:
                    data = communicationService.setTime(timeRequest);
                    updateState(channelUID, OnOffType.OFF);
                    break;
                case CHANNEL_DUMPRESPONSE:
                    for (byte requestByte : DEBUGBYTES) {
                        communicationService.dumpResponse(requestByte);
                        Thread.sleep(waitingTime);
                    }
                    updateState(channelUID, OnOffType.OFF);
                    break;
                default:
                    String value = command.toString();
                    if (command instanceof OnOffType) {
                        // the command come from a switch type , we need to map ON and OFF to 0 and 1 values
                        if (command.equals(OnOffType.ON)) {
                            value = "1";
                        }
                        if (command.equals(OnOffType.OFF)) {
                            value = "0";
                        }
                    }

                    String channelType = getThing().getChannel(channelUID.getId()).getChannelTypeUID().toString();
                    if (channelType.equalsIgnoreCase(CHANNELTYPE_TIMESETTING)) {
                        int hours = 0;
                        int minutes = 0;
                        String[] parts = command.toString().split(Pattern.quote(":"), 2);
                        if (parts.length < 2) {
                            parts = new String[] { "0", "0" };
                        }
                        hours = Integer.parseInt(parts[0]);
                        minutes = Integer.parseInt(parts[1]);
                        if (hours > 23) {
                            hours = 23;
                            minutes = 59;
                        }
                        if (hours < 0) {
                            hours = 0;
                            minutes = 0;
                        }
                        if (minutes > 59 || hours < 0) {
                            minutes = 59;
                        }
                        value = String.valueOf(hours) + String.format("%02d", minutes);
                    }

                    communicationService.setData(value, channelUID.getId(), heatPumpRefresh);
                    break;
            }

            updateCannels(data);

        } catch (Exception e) {
            logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
        } finally {
            if (communicationService != null) {
                communicationService.finalizer();
                communicationInUse = false;
            }
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        String channelId = channelUID.getId();
        String[] parts = channelId.split(Pattern.quote(StiebelHeatPumpBindingConstants.CHANNELGROUPSEPERATOR), 2);
        if (parts.length != 2) {
            logger.debug("Channel {} to link has invalid structure ChannelGroup#recordChannelId", channelId);
            return;
        }
        channelId = parts[parts.length - 1];

        // verify if channel is already in considered refresh request
        for (Request request : heatPumpRefresh) {
            for (RecordDefinition record : request.getRecordDefinitions()) {
                if (record.getChannelid().equalsIgnoreCase(channelId)) {
                    logger.debug("Found valid record definitionrequest in refresh for:{}", channelId);
                    return;
                }
            }
        }

        // record is currently not in the refresh list, add the request
        for (Request request : heatPumpConfiguration) {
            for (RecordDefinition record : request.getRecordDefinitions()) {
                if (record.getChannelid().equalsIgnoreCase(channelId)) {
                    logger.debug("Found valid record definition in request with ChannelID:{}", record.getChannelid());
                    heatPumpRefresh.add(request);
                    break;
                }
            }
        }
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        String channelId = channelUID.getId();
        String[] parts = channelId.split(Pattern.quote(StiebelHeatPumpBindingConstants.CHANNELGROUPSEPERATOR));
        if (parts.length != 2) {
            logger.debug("Channel {} to unlink has invalid structure channelgroup#channel", channelId);
        }
        channelId = parts[parts.length - 1];

        requestsLoop: for (Request request : heatPumpRefresh) {
            String requestByte = DatatypeConverter.printHexBinary(new byte[] { request.getRequestByte() });
            requestLoop: for (RecordDefinition record : request.getRecordDefinitions()) {
                if (record.getChannelid().equalsIgnoreCase(channelId)) {
                    logger.debug("Found valid request {} in refresh for:{}", requestByte, channelId);
                    List<Channel> channels = getThing().getChannels();
                    for (Channel channel : channels) {
                        if (!this.isLinked(channel.getUID())) {
                            continue;
                        }
                        String tmpChannelUID = channel.getUID().toString();
                        for (RecordDefinition searchRecord : request.getRecordDefinitions()) {
                            if (record.equals(searchRecord)) {
                                continue;
                            }
                            if (tmpChannelUID.endsWith(searchRecord.getChannelid())) {
                                // there is still a channel link in the thing which will require updates
                                logger.debug("Request {} will remain in refresh list as channel {} is still linked",
                                        requestByte, searchRecord.getChannelid());
                                break requestLoop;
                            }
                        }

                    }
                    // no channel found which belongs to same request, remove request
                    heatPumpRefresh.remove(request);
                    logger.debug("Request {} removed in refresh list no additionl channel from request linked",
                            requestByte);
                    break requestsLoop;
                }
            }
        }
    }

    @Override
    public void initialize() {

        String msg = "Error occurred while initializing stiebel heat pump handler! ";
        try {
            Configuration config = getThing().getConfiguration();
            port = (String) config.get(PROPERTY_PORT);
            baudRate = ((BigDecimal) config.get(PROPERTY_BAUDRATE)).intValueExact();
            refresh = (BigDecimal) config.get(PROPERTY_REFRESH);
            waitingTime = ((BigDecimal) config.get(PROPERTY_WAITINGTIME)).intValueExact();

            logger.debug(
                    "Initializing stiebel heat pump handler '{}' with configuration: port '{}', baudRate {}, refresh {}.",
                    getThing().getUID().toString(), port, baudRate, refresh);

            portId = serialPortManager.getIdentifier(port);
            if (portId == null) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port is not known!");
                return;
            }
            boolean success = getInitialHeatPumpSettings();
            if (success) {
                updateStatus(ThingStatus.ONLINE);
                startTimeRefresh();
                startAutomaticRefresh();
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
            }
        } catch (Exception ex) {
            msg = msg + " : " + ex.getMessage();
            logger.error(msg, ex);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, msg);
        }
    }

    @Override
    public void dispose() {
        if (refreshJob != null) {
            refreshJob.cancel(true);
        }
    }

    /**
     * This method pools the meter data and updates the channels on a scheduler
     * once per refresh time defined in the thing properties
     */
    private void startAutomaticRefresh() {
        refreshJob = scheduler.scheduleWithFixedDelay(() -> {

            if (heatPumpRefresh.isEmpty()) {
                logger.info("nothing to update, refresh list is empty");
                return;
            }

            if (communicationInUse) {
                return;
            }
            communicationInUse = true;

            CommunicationService communicationService = null;
            try {
                communicationService = new CommunicationService(serialPortManager, heatPumpConfiguration, port,
                        baudRate, waitingTime);
                Map<String, String> data = communicationService.getRequestData(heatPumpRefresh);
                updateCannels(data);
            } catch (Exception e) {
                logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            } finally {
                if (communicationService != null) {
                    communicationService.finalizer();
                    communicationInUse = false;
                }
            }
        }, 2, refresh.intValue(), TimeUnit.SECONDS);
    }

    /**
     * This method set the time in the heat pump to system time on a scheduler
     * once a week
     */
    private void startTimeRefresh() {
        timeRefreshJob = scheduler.scheduleAtFixedRate(() -> {
            if (communicationInUse) {
                return;
            }
            communicationInUse = true;

            CommunicationService communicationService = null;
            try {
                communicationService = new CommunicationService(serialPortManager, heatPumpConfiguration, port,
                        baudRate, waitingTime);
                Map<String, String> time = communicationService.setTime(timeRequest);
                updateCannels(time);

            } catch (Exception e) {
                logger.debug(e.getMessage());
            } finally {
                if (communicationService != null) {
                    communicationService.finalizer();
                    communicationInUse = false;
                }
            }
        }, 1, 7, TimeUnit.DAYS);
    }

    /**
     * This method reads initial information from the heat pump. It reads
     * the configuration file and loads all defined record definitions of sensor
     * data, status information , actual time settings and setting parameter
     * values for the thing type definition.
     *
     * @return true if heat pump information could be successfully connected and read
     */
    private boolean getInitialHeatPumpSettings() {
        String thingVersion = getThing().getProperties().get("firmware");
        String version = "";

        if (heatPumpConfiguration.isEmpty()) {

            // get the records from the thing-type configuration file
            String configFile = thingType.getUID().getId();
            ConfigLocator configLocator = new ConfigLocator(configFile + ".xml");
            heatPumpConfiguration = configLocator.getRequests().getRequests();
        }

        categorizeHeatPumpConfiguration();

        // get version information from the heat pump
        CommunicationService communicationService = null;
        try {

            communicationService = new CommunicationService(serialPortManager, heatPumpConfiguration, port, baudRate,
                    waitingTime);
            version = communicationService.getversion(versionRequest);
        } catch (Exception e) {
            logger.debug(e.getMessage());
            return false;
        } finally {
            if (communicationService != null) {
                communicationService.finalizer();
            }
        }

        logger.info("Heat pump has version {}", version);
        ChannelUID versionChannelUID = new ChannelUID(getThing().getUID(), CHANNEL_VERSION);
        updateState(versionChannelUID, new StringType(version));

        if (!thingVersion.equals(version)) {
            logger.error("Thingtype version of heatpump {} is not the same as the heatpump version {}", thingVersion,
                    version);
            return false;
        }

        return true;
    }

    /**
     * This method updates the query data to the channels
     *
     * @param data
     *            Map<String, String> of data coming from heat pump
     */
    private void updateCannels(Map<String, String> data) {

        if (data.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry : data.entrySet()) {
            logger.debug("Data {} has value {}", entry.getKey(), entry.getValue());
            String channelId = entry.getKey().toString();

            List<@NonNull Channel> channels = getThing().getChannels();
            for (Channel ch : channels) {
                ChannelUID channelUID = ch.getUID();
                if (ch.getUID().toString().endsWith(channelId)) {
                    ChannelTypeUID channelTypeUID = ch.getChannelTypeUID();
                    String channelType = channelTypeUID.toString();

                    if (channelType.equalsIgnoreCase(CHANNELTYPE_TIMESETTING)) {
                        String newTime = String.format("%04d", Integer.parseInt(entry.getValue()));
                        newTime = new StringBuilder(newTime).insert(newTime.length() - 2, ":").toString();
                        updateState(channelUID, new StringType(newTime));
                        continue;
                    }

                    if (channelType.equalsIgnoreCase(CHANNELTYPE_SWITCHSETTING)) {
                        switch (entry.getValue()) {
                            case "0":
                                updateState(channelUID, OnOffType.OFF);
                                break;
                            default:
                                updateState(channelUID, OnOffType.ON);
                                break;
                        }
                    }

                    updateState(channelUID, new DecimalType(entry.getValue()));
                }
            }
        }

        updateStatus(ThingStatus.ONLINE);
    }

    /**
     * This method categorize the heat pump configuration into setting, sensor
     * and status
     *
     * @return true if heat pump configuration for version could be found and
     *         loaded
     */
    private boolean categorizeHeatPumpConfiguration() {
        for (Request request : heatPumpConfiguration) {
            String requestByte = DatatypeConverter.printHexBinary(new byte[] { request.getRequestByte() });
            logger.debug("Request : RequestByte -> {}", requestByte);

            // group requests in different categories
            for (RecordDefinition record : request.getRecordDefinitions()) {

                if (record.getRequestByte() == REQUEST_VERSION) {
                    versionRequest = request;
                    logger.debug("set version request : " + requestByte);
                    continue;
                }

                if (record.getRequestByte() == REQUEST_TIME) {
                    timeRequest = request;
                    logger.debug("set time request : " + requestByte);
                    continue;
                }

                if (record.getDataType() == Type.Settings && !heatPumpSettingConfiguration.contains(request)) {
                    heatPumpSettingConfiguration.add(request);
                }
                if (record.getDataType() == Type.Status && !heatPumpStatusConfiguration.contains(request)) {
                    heatPumpStatusConfiguration.add(request);
                }
                if (record.getDataType() == Type.Sensor && !heatPumpSensorConfiguration.contains(request)) {
                    heatPumpSensorConfiguration.add(request);
                }
            }
        }

        if (versionRequest == null || timeRequest == null) {
            logger.debug("version or time request could not be found in configuration");
            return false;
        }
        return true;
    }
}
