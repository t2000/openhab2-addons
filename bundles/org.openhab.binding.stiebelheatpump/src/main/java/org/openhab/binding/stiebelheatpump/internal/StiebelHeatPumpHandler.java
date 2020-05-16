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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Power;
import javax.measure.quantity.Temperature;
import javax.xml.bind.DatatypeConverter;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.library.unit.SmartHomeUnits;
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
import org.joda.time.DateTime;
import org.joda.time.LocalDateTime;
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
    private StiebelHeatPumpConfiguration config;
    CommunicationService communicationService;
    boolean communicationInUse = false;
    public static final String DATE_PATTERN_WITH_TZ = "yyyy-MM-dd HH:mm:ss z";

    /** heat pump request definition */
    private List<Request> heatPumpConfiguration = new ArrayList<>();
    private List<Request> heatPumpSensorConfiguration = new ArrayList<>();
    private List<Request> heatPumpSettingConfiguration = new ArrayList<>();
    private List<Request> heatPumpStatusConfiguration = new ArrayList<>();
    private List<Request> heatPumpRefresh = new ArrayList<>();
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
                Thread.sleep(config.waitingTime);
            } catch (InterruptedException e) {
                logger.debug("Could not get access to heatpump, communication is in use {} !", retry);
                e.printStackTrace();
            }
            retry++;
        }
        if (communicationInUse) {
            logger.debug("Could not get access to heatpump, communication is in use !");
            return;
        }
        communicationInUse = true;
        communicationService.connect();
        try {
            Map<String, String> data = new HashMap<>();

            switch (channelUID.getId()) {
                case CHANNEL_SETTIME:
                    data = communicationService.setTime(timeRequest);
                    updateState(channelUID, OnOffType.OFF);
                    break;
                case CHANNEL_DUMPRESPONSE:
                    for (byte requestByte : DEBUGBYTES) {
                        communicationService.dumpResponse(requestByte);
                        Thread.sleep(config.waitingTime);
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
                    communicationService.setData(value, channelUID.getId(), heatPumpRefresh);
                    break;
            }

            updateCannels(data);

        } catch (Exception e) {
            logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
        } finally {
            communicationService.disconnect();
            communicationInUse = false;
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        String channelId = channelUID.getId();
        String[] parts = channelId.split(Pattern.quote(StiebelHeatPumpBindingConstants.CHANNELGROUPSEPERATOR));
        if (parts.length != 2) {
            logger.debug("Channel {} to unlink has invalid structure channelgroup#channel", channelId);
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

        Request checkRequest = null;
        String requestByte = null;
        // search for the request that contains channelid to be unlinked
        for (Request request : heatPumpRefresh) {
            requestByte = DatatypeConverter.printHexBinary(new byte[] { request.getRequestByte() });
            for (RecordDefinition record : request.getRecordDefinitions()) {
                if (record.getChannelid().equalsIgnoreCase(channelId)) {
                    logger.debug("Found valid request {} in refresh for:{}", requestByte, channelId);
                    checkRequest = request;
                    break;
                }
            }
        }

        if (checkRequest == null) {
            logger.debug("No Request found for channelid {} !", channelId);
            return;
        }

        // verify if there are other channels in the request that are linked
        List<Channel> channels = getThing().getChannels();
        for (Channel channel : channels) {
            if (!this.isLinked(channel.getUID())) {
                continue;
            }
            for (RecordDefinition record : checkRequest.getRecordDefinitions()) {
                if (record.getChannelid().equals(channelId)) {
                    continue;
                }
                if (channel.getUID().toString().endsWith(record.getChannelid())) {
                    // there is still a channel link in the thing which will require updates
                    logger.debug("Request {} will remain in refresh list as channel {} is still linked", requestByte,
                            record.getChannelid());
                    break;
                }
            }
        }
        // no channel found which belongs to same request, remove request
        if (checkRequest != null) {
            heatPumpRefresh.remove(checkRequest);
            logger.debug("Request {} removed in refresh list no additionl channel from request linked", requestByte);
        }
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);

        this.config = getConfigAs(StiebelHeatPumpConfiguration.class);
        if (!validateConfiguration(config)) {
            return;
        }

        String availablePorts = serialPortManager.getIdentifiers().map(id -> id.getName())
                .collect(Collectors.joining(", "));

        logger.debug(
                "Initializing stiebel heat pump handler '{}' with configuration: port '{}', baudRate {}, refresh {}. Available ports are : {}",
                getThing().getUID(), config.port, config.baudRate, config.refresh, availablePorts);

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.HANDLER_CONFIGURATION_PENDING,
                "Waiting for messages from device");

        SerialPortIdentifier portId = serialPortManager.getIdentifier(config.port);
        if (portId == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port is not known!");
            logger.debug("Serial port {} was not found, available ports are : {} ", config.port, availablePorts);
            return;
        }

        communicationService = new CommunicationService(serialPortManager, config.port, config.baudRate,
                config.waitingTime);
        scheduler.schedule(this::getInitialHeatPumpSettings, 1, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        if (refreshJob != null && !refreshJob.isCancelled()) {
            refreshJob.cancel(true);
        }
        refreshJob = null;

        if (timeRefreshJob != null && !timeRefreshJob.isCancelled()) {
            timeRefreshJob.cancel(true);
        }
        timeRefreshJob = null;

        if (communicationService != null) {
            communicationService.finalizer();
            communicationInUse = false;
        }
    }

    private boolean validateConfiguration(StiebelHeatPumpConfiguration config) {
        if (config.port == null || config.port.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port must be set!");
            return false;
        }

        if (config.baudRate < 9600 || config.baudRate > 115200) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "BaudRate must be between 9600 and 115200");
            return false;
        }

        if (config.refresh < 10) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "Refresh rate must be larger than 10");
            return false;
        }

        if (config.waitingTime <= 0) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "Waiting time between requests must be larger than 0");
            return false;
        }

        return true;
    }

    /**
     * This method pools the meter data and updates the channels on a scheduler
     * once per refresh time defined in the thing properties
     */
    private void startAutomaticRefresh() {
        refreshJob = scheduler.scheduleWithFixedDelay(() -> {

            if (heatPumpRefresh.isEmpty()) {
                logger.debug("nothing to update, refresh list is empty");
                return;
            }

            if (communicationInUse) {
                logger.debug("Communication service is in use , skip refresh data task this time.");
                return;
            }
            communicationInUse = true;
            try {
                communicationService.connect();
                Map<String, String> data = communicationService.getRequestData(heatPumpRefresh);
                updateCannels(data);
                LocalDateTime dt = DateTime.now().toLocalDateTime();
                updateState(CHANNEL_LASTUPDATE, new StringType(dt.toString(DATE_PATTERN_WITH_TZ)));
            } catch (Exception e) {
                logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            } finally {
                communicationService.disconnect();
                communicationInUse = false;
            }
        }, 2, config.refresh, TimeUnit.SECONDS);
    }

    /**
     * This method set the time in the heat pump to system time on a scheduler
     * once a week
     */
    private void startTimeRefresh() {
        timeRefreshJob = scheduler.scheduleWithFixedDelay(() -> {
            if (communicationInUse) {
                return;
            }
            communicationInUse = true;
            try {
                communicationService.connect();
                Map<String, String> time = communicationService.setTime(timeRequest);
                updateCannels(time);
            } catch (StiebelHeatPumpException e) {
                logger.debug(e.getMessage());
            } finally {
                communicationService.disconnect();
                communicationInUse = false;
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
    private void getInitialHeatPumpSettings() {
        String thingFirmwareVersion = thingType.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION);

        if (heatPumpConfiguration.isEmpty()) {
            // get the records from the thing-type configuration file
            String configFile = thingType.getUID().getId();
            ConfigLocator configLocator = new ConfigLocator(configFile + ".xml");
            heatPumpConfiguration = configLocator.getRequests().getRequests();
        }

        categorizeHeatPumpConfiguration();

        // get version information from the heat pump
        communicationService.connect();
        try {
            String version = communicationService.getVersion(versionRequest);
            logger.info("Heat pump has version {}", version);
            if (!thingFirmwareVersion.equals(version)) {
                logger.error("Thingtype version of heatpump {} is not the same as the heatpump version {}",
                        thingFirmwareVersion, version);
                return;
            }
        } catch (StiebelHeatPumpException e) {
            logger.debug(e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Communication problem with heatpump");
            communicationService.finalizer();
            return;
        } finally {
            communicationService.disconnect();
        }

        updateStatus(ThingStatus.ONLINE);
        startTimeRefresh();
        updateRefreshRequests();
        startAutomaticRefresh();
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

            for (Channel ch : getThing().getChannels()) {
                ChannelUID channelUID = ch.getUID();
                if (ch.getUID().toString().endsWith(channelId)) {
                    ChannelTypeUID channelTypeUID = ch.getChannelTypeUID();
                    String channelType = channelTypeUID.toString();

                    if (channelType.equalsIgnoreCase(CHANNELTYPE_TIMESETTING)) {
                        updateTimeChannel(entry.getValue(), channelUID);
                        continue;
                    }
                    if (channelType.equalsIgnoreCase(CHANNELTYPE_SWITCHSETTING)) {
                        updateSwitchSettingChannel(entry.getValue(), channelUID);
                        continue;
                    }
                    updateStatus(entry.getValue(), ch, channelUID);
                }
            }
        }

        updateStatus(ThingStatus.ONLINE);
    }

    private String updateStatus(String value, Channel ch, ChannelUID channelUID) {
        String itemType = ch.getAcceptedItemType();

        switch (itemType) {
            case "Number:Temperature":
                QuantityType<Temperature> temperature = new QuantityType<>(Double.valueOf(value), SIUnits.CELSIUS);
                updateState(channelUID, temperature);
                break;
            case "Number:Energy":
                // TODO: how to make this kW as these are coming from heatpump
                QuantityType<Power> energy = new QuantityType<>(Double.valueOf(value), SmartHomeUnits.WATT);
                updateState(channelUID, energy);
                break;
            case "Number:Dimensionless:Percent":
                QuantityType<Dimensionless> percent = new QuantityType<>(Double.valueOf(value), SmartHomeUnits.PERCENT);
                updateState(channelUID, percent);
                break;

            default:
                updateState(channelUID, new DecimalType(value));
        }
        return itemType;
    }

    private void updateSwitchSettingChannel(String setting, ChannelUID channelUID) {
        if ("0".equals(setting)) {
            updateState(channelUID, OnOffType.OFF);
        } else {
            updateState(channelUID, OnOffType.ON);
        }
    }

    private void updateTimeChannel(String timeString, ChannelUID channelUID) {
        String newTime = String.format("%04d", Integer.parseInt(timeString));
        newTime = new StringBuilder(newTime).insert(newTime.length() - 2, ":").toString();
        updateState(channelUID, new StringType(newTime));
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

    private void updateRefreshRequests() {
        for (Channel channel : getThing().getChannels()) {
            if (this.isLinked(channel.getUID())) {
                for (Request request : heatPumpConfiguration) {
                    for (RecordDefinition record : request.getRecordDefinitions()) {
                        if (channel.getUID().toString().endsWith(record.getChannelid())
                                && !heatPumpRefresh.contains(request)) {
                            heatPumpRefresh.add(request);
                            // there is still a channel link in the thing which will require updates
                            logger.info(String.format("Request %02X added to refresh scheduler.",
                                    request.getRequestByte()));
                            break;
                        }
                    }
                }
            }
        }
    }

}
