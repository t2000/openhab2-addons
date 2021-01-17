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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Power;
import javax.measure.quantity.Temperature;

import org.openhab.binding.stiebelheatpump.protocol.DataParser;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition.Type;
import org.openhab.binding.stiebelheatpump.protocol.Request;
import org.openhab.binding.stiebelheatpump.protocol.Requests;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.thing.type.ThingType;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link StiebelHeatPumpHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Peter Kreutzer - Initial contribution
 */
public class StiebelHeatPumpHandler extends BaseThingHandler {

    private static final Duration RETRY_PORT_DELAY = Duration.ofSeconds(10);

    private Logger logger = LoggerFactory.getLogger(StiebelHeatPumpHandler.class);
    private final SerialPortManager serialPortManager;
    private StiebelHeatPumpConfiguration config;
    CommunicationService communicationService;
    boolean communicationInUse = false;

    /** heat pump request definition */
    private Requests heatPumpConfiguration = new Requests();
    private Requests heatPumpSensorConfiguration = new Requests();
    private Requests heatPumpSettingConfiguration = new Requests();
    private Requests heatPumpStatusConfiguration = new Requests();
    private Requests heatPumpSensorStatusRefresh = new Requests();
    private Requests heatPumpSettingRefresh = new Requests();
    private Request versionRequest;
    private Request timeRequest;

    /** cyclic pooling of sensor/status data from heat pump */
    ScheduledFuture<?> refreshSensorStatusJob;
    /** cyclic pooling of setting data from heat pump */
    ScheduledFuture<?> refreshSettingJob;
    /** cyclic update of time in the heat pump */
    ScheduledFuture<?> timeRefreshJob;

    ScheduledFuture<?> retryOpenPortJob;

    private ThingType thingType;

    public StiebelHeatPumpHandler(Thing thing, ThingType thingType, final SerialPortManager serialPortManager) {
        super(thing);
        this.thingType = thingType;
        this.serialPortManager = serialPortManager;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            // refresh is handled with scheduled polling of data
            return;
        }
        logger.debug("Received command {} for channelUID {}", command, channelUID);
        String channelId = channelUID.getId();
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
            logger.debug("Could not get access to heatpump, communication is in use ! Final");
            return;
        }
        communicationInUse = true;
        // communicationService.connect();
        try {
            Map<String, Object> data = new HashMap<>();
            switch (channelUID.getId()) {
                case CHANNEL_SETTIME:
                    data = communicationService.setTime(timeRequest);
                    updateState(channelUID, OnOffType.OFF);
                    break;
                case CHANNEL_DUMPRESPONSE:
                    for (byte requestByte : DEBUGBYTES) {
                        byte[] debugBytes = new byte[] { requestByte };
                        Request request = heatPumpConfiguration.getRequestByByte(debugBytes);
                        if (request == null) {
                            String requestStr = DataParser.bytesToHex(debugBytes);
                            logger.debug("Could not find request for {} in the thingtype definition.", requestStr);
                            request = new Request();
                            request.setRequestByte(debugBytes);
                        }
                        communicationService.dumpResponse(request);
                        Thread.sleep(config.waitingTime);
                    }
                    updateState(channelUID, OnOffType.OFF);
                    break;
                case CHANNEL_REQUESTBYTES:
                    String requestStr = command.toString();
                    byte[] debugBytes = DataParser.hexStringToByteArray(requestStr);
                    logger.debug("Dump responds for request byte {} !", requestStr);
                    String respondStr = communicationService.dumpRequest(debugBytes);
                    updateRespondChannel(respondStr);
                    updateState(channelUID, new StringType(requestStr));
                    break;
                default:
                    // do checks if valid definition is available
                    RecordDefinition updateRecord = heatPumpConfiguration.getRecordDefinitionByChannelId(channelId);
                    if (updateRecord == null) {
                        return;
                    }
                    if (updateRecord.getDataType() != Type.Settings) {
                        logger.warn("The record {} can not be set as it is not a setable value!", channelId);
                        return;
                    }
                    Object value = null;
                    if (command instanceof OnOffType) {
                        // the command come from a switch type , we need to map ON and OFF to 0 and 1 values
                        value = true;
                        if (command.equals(OnOffType.OFF)) {
                            value = false;
                        }
                    }
                    if (command instanceof QuantityType) {
                        QuantityType<?> newQtty = ((QuantityType<?>) command);
                        value = newQtty.doubleValue();
                    }
                    if (command instanceof DecimalType) {
                        value = ((DecimalType) command).doubleValue();
                    }
                    if (command instanceof StringType) {
                        DateTimeFormatter strictTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                                .withResolverStyle(ResolverStyle.STRICT);
                        try {
                            LocalTime time = LocalTime.parse(command.toString(), strictTimeFormatter);
                            value = (short) (time.getHour() * 100 + time.getMinute());
                        } catch (DateTimeParseException e) {
                            logger.info("Time string is not valid ! : {}", e.getMessage());
                        }
                    }
                    data = communicationService.writeData(value, channelId, updateRecord);
                    updateChannels(data);
            }
        } catch (Exception e) {
            logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
        } finally {
            // communicationService.disconnect();
            communicationInUse = false;
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        String channelId = channelUID.getId();
        Request request = heatPumpConfiguration.getRequestByChannelId(channelId);
        if (request == null) {
            logger.debug("Could not find valid record definitionrequest in channel for: {}", channelId);
            return;
        }
        String requestStr = DataParser.bytesToHex(request.getRequestByte(), true);
        logger.debug("Found valid record definition in request {} with ChannelID:{}", requestStr, channelId);
        RecordDefinition record = request.getRecordDefinitionByChannelId(channelId);
        if (record == null) {
            logger.warn("Could not find valid record definition for {},  please verify thing definition.", channelId);
            return;
        }
        Type dataType = record.getDataType();
        if (dataType == RecordDefinition.Type.Settings) {
            if (refreshSettingJob != null) {
                getSettings(request);
            }
            if (!heatPumpSettingRefresh.getRequests().contains(request)) {
                heatPumpSettingRefresh.getRequests().add(request);
                logger.info("Request {} added to setting refresh scheduler.", requestStr);
            }
        }
        if (dataType != RecordDefinition.Type.Settings
                && !heatPumpSensorStatusRefresh.getRequests().contains(request)) {
            heatPumpSensorStatusRefresh.getRequests().add(request);
        }
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        String channelId = channelUID.getId();
        Request request = heatPumpConfiguration.getRequestByChannelId(channelId);
        if (request == null) {
            logger.debug("No Request found for channelid {} !", channelId);
            return;
        }
        String requestStr = DataParser.bytesToHex(request.getRequestByte(), true);
        List<Channel> channels = getThing().getChannels();
        Boolean toBeRemoved = false;
        for (Channel channel : channels) {
            if (this.isLinked(channel.getUID())) {
                String channelSearch = channelUID.getId();
                if (channelSearch.equals(channelId)) {
                    toBeRemoved = true;
                    continue;
                }
                if (request.getRecordDefinitionByChannelId(channelId) != null) {
                    toBeRemoved = false;
                    break;
                }
            }
        }
        if (toBeRemoved) {
            // no channel found which belongs to same request, remove request
            if (heatPumpSettingRefresh.getRequests().remove(request)) {
                logger.debug(
                        "Request {} removed in setting refresh list because no additional channel from request linked",
                        requestStr);
            } else if (heatPumpSensorStatusRefresh.getRequests().remove(request)) {
                logger.debug(
                        "Request {} removed in sensor/status refresh list because no additional channel from request linked",
                        requestStr);
            }
        }
    }

    @Override
    public void initialize() {
        if (heatPumpConfiguration.getRequests().isEmpty()) {
            // get the records from the thing-type configuration file
            String configFile = thingType.getUID().getId();
            ConfigLocator configLocator = new ConfigLocator(configFile + ".xml");
            heatPumpConfiguration.setRequests(configLocator.getRequests());
        }
        categorizeHeatPumpConfiguration();
        updateRefreshRequests();

        this.config = getConfigAs(StiebelHeatPumpConfiguration.class);
        if (!validateConfiguration(config)) {
            return;
        }

        // String availablePorts = serialPortManager.getIdentifiers().map(id -> id.getName())
        // .collect(Collectors.joining(", "));
        //
        // logger.debug(
        // "Initializing stiebel heat pump handler '{}' with configuration: port '{}', baudRate {}, refresh {}.
        // Available ports are : {}",
        // getThing().getUID(), config.port, config.baudRate, config.refresh, availablePorts);

        SerialPortIdentifier portId = serialPortManager.getIdentifier(config.port);
        if (portId == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port is not known!");
            logger.debug("Serial port {} was not found, retrying in {}.", config.port, RETRY_PORT_DELAY);
            retryOpenPortJob = scheduler.schedule(this::initialize, RETRY_PORT_DELAY.getSeconds(), TimeUnit.SECONDS);
            return;
        }

        if (retryOpenPortJob != null) {
            retryOpenPortJob.cancel(true);
            retryOpenPortJob = null;
        }

        communicationService = new CommunicationService(serialPortManager, config.port, config.baudRate,
                config.waitingTime);

        scheduler.schedule(this::getInitialHeatPumpSettings, 0, TimeUnit.SECONDS);
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.HANDLER_CONFIGURATION_PENDING,
                "Waiting for messages from device");
    }

    @Override
    public void dispose() {
        if (refreshSettingJob != null && !refreshSettingJob.isCancelled()) {
            refreshSettingJob.cancel(true);
        }
        refreshSettingJob = null;

        if (refreshSensorStatusJob != null && !refreshSensorStatusJob.isCancelled()) {
            refreshSensorStatusJob.cancel(true);
        }
        refreshSensorStatusJob = null;

        if (timeRefreshJob != null && !timeRefreshJob.isCancelled()) {
            timeRefreshJob.cancel(true);
        }
        timeRefreshJob = null;

        if (retryOpenPortJob != null && !retryOpenPortJob.isCancelled()) {
            retryOpenPortJob.cancel(true);
        }
        retryOpenPortJob = null;

        if (communicationService != null) {
            communicationService.disconnect();
        }
        communicationInUse = false;
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
     * This method pools the heat pump sensor/status data and updates the channels on a scheduler
     * once per refresh time defined in the thing properties
     */
    private void startAutomaticSensorStatusRefresh() {
        refreshSensorStatusJob = scheduler.scheduleWithFixedDelay(() -> {
            Map<String, Object> data = new HashMap<>();
            Instant start = Instant.now();
            if (heatPumpSensorStatusRefresh.getRequests().isEmpty()) {
                logger.debug("nothing to update, sensor/status refresh list is empty");
                return;
            }

            if (communicationInUse) {
                logger.debug("Communication service is in use , skip refresh data task this time.");
                return;
            }
            communicationInUse = true;
            logger.info("Refresh sensor/status data of heat pump.");
            try {
                // communicationService.connect();
                data = communicationService.getRequestData(heatPumpSensorStatusRefresh.getRequests());
            } catch (Exception e) {
                logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            } finally {
                // communicationService.disconnect();
                communicationInUse = false;
            }
            Instant end = Instant.now();
            logger.debug("Sensor/Status refresh took {} seconds.", Duration.between(start, end).getSeconds());
            updateChannels(data);
        }, 10, config.refresh, TimeUnit.SECONDS);
    }

    /**
     * This method pools the heat pump setting data and updates the channels on a scheduler
     */
    private void startAutomaticSettingRefresh() {
        refreshSettingJob = scheduler.scheduleWithFixedDelay(() -> {
            Map<String, Object> data = new HashMap<>();
            Instant start = Instant.now();
            if (heatPumpSettingRefresh.getRequests().isEmpty()) {
                logger.debug("nothing to update, setting refresh list is empty");
                return;
            }

            if (communicationInUse) {
                logger.debug("Communication service is in use , skip refresh data task this time.");
                return;
            }
            communicationInUse = true;
            logger.info("Refresh setting data of heat pump.");
            try {
                // communicationService.connect();
                data = communicationService.getRequestData(heatPumpSettingRefresh.getRequests());
            } catch (Exception e) {
                logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            } finally {
                // communicationService.disconnect();
                communicationInUse = false;
            }
            Instant end = Instant.now();
            logger.debug("Setting refresh took {} seconds.", Duration.between(start, end).getSeconds());
            updateChannels(data);
        }, 2, 3600, TimeUnit.SECONDS);
    }

    /**
     * This method pools the heat pump setting data for one request and updates the channels on a scheduler
     */
    private void getSettings(Request request) {
        refreshSettingJob = scheduler.schedule(() -> {
            int count = 0;
            int maxtry = 10;
            while (count < maxtry && communicationInUse) {
                try {
                    Thread.sleep(2000);

                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                count++;
            }
            if (maxtry == 10) {
                logger.error("Setting could not be read from heatpump.communication is used by other task!");
            }
            Map<String, Object> data = new HashMap<>();
            communicationInUse = true;
            List<Request> requestList = new ArrayList<>();
            requestList.add(request);

            logger.info("Refresh for newly linked setting of heat pump.");
            try {
                // communicationService.connect();
                data = communicationService.getRequestData(requestList);
            } catch (

            Exception e) {
                logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
            } finally {
                // communicationService.disconnect();
                communicationInUse = false;
            }
            updateChannels(data);
        }, 0, TimeUnit.SECONDS);
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
            logger.info("Refresh time of heat pump.");
            try {
                // communicationService.connect();
                Map<String, Object> time = communicationService.setTime(timeRequest);
                updateChannels(time);
            } catch (StiebelHeatPumpException e) {
                logger.debug(e.getMessage());
            } finally {
                // communicationService.disconnect();
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
        }
        // finally {
        // communicationService.disconnect();
        // }

        updateStatus(ThingStatus.ONLINE);
        startTimeRefresh();
        startAutomaticSettingRefresh();
        startAutomaticSensorStatusRefresh();
    }

    /**
     * This method updates the query data to the channels
     *
     * @param data
     *            Map<String, String> of data coming from heat pump
     */
    private void updateChannels(Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            logger.debug("Data {} has value {}", entry.getKey(), entry.getValue());
            String channelId = entry.getKey();
            Channel ch = getThing().getChannel(channelId);
            if (ch == null) {
                logger.debug("For channelid {} no configuration found. Review channel definitions.", channelId);
                continue;
            }
            ChannelUID channelUID = ch.getUID();
            ChannelTypeUID channelTypeUID = ch.getChannelTypeUID();
            String channelType = channelTypeUID.toString();

            if (channelType.equalsIgnoreCase(CHANNELTYPE_TIMESETTING)
                    | channelType.equalsIgnoreCase(CHANNELTYPE_ERRORTIME)) {
                updateTimeChannel(entry.getValue().toString(), channelUID);
                continue;
            }
            if (channelType.equalsIgnoreCase(CHANNELTYPE_ERRORDATE)) {
                updateDateChannel(entry.getValue().toString(), channelUID);
                continue;
            }
            if (channelType.equalsIgnoreCase(CHANNELTYPE_SWITCHSETTING)) {
                updateSwitchSettingChannel((boolean) entry.getValue(), channelUID);
                continue;
            }
            if (channelType.equalsIgnoreCase(CHANNELTYPE_CONTACTSTATUS)) {
                updateContactChannel((boolean) entry.getValue(), channelUID);
                continue;
            }
            if (entry.getValue() instanceof Number) {
                updateStatus((Number) entry.getValue(), channelUID);
            }
            if (entry.getValue() instanceof Boolean) {
                updateSwitchSettingChannel((boolean) entry.getValue(), channelUID);
            }
        }
        LocalDateTime dt = LocalDateTime.now();
        String formattedString = dt.format(DateTimeFormatter.ofPattern(DATE_PATTERN));
        updateState(CHANNEL_LASTUPDATE, new StringType(formattedString));
        updateStatus(ThingStatus.ONLINE);
    }

    private void updateStatus(Number value, ChannelUID channelUID) {
        String itemType = getThing().getChannel(channelUID).getAcceptedItemType();
        if (value instanceof Double) {
            switch (itemType) {
                case "Number:Temperature":
                    QuantityType<Temperature> temperature = new QuantityType<>(value, SIUnits.CELSIUS);
                    updateState(channelUID, temperature);
                    break;
                case "Number:Energy":
                    // TODO: how to make this kW as these are coming from heatpump
                    QuantityType<Power> energy = new QuantityType<>(value, Units.WATT);
                    updateState(channelUID, energy);
                    break;
                case "Number:Dimensionless:Percent":
                    QuantityType<Dimensionless> percent = new QuantityType<>(value, Units.PERCENT);
                    updateState(channelUID, percent);
                    break;
                case "String":
                    updateState(channelUID, new StringType(value.toString()));
                    break;
                default:
                    updateState(channelUID, new DecimalType((Double) value));
            }
            return;
        }
        if (value instanceof Short) {
            updateState(channelUID, new DecimalType((short) value));
        }
        if (value instanceof Integer) {
            updateState(channelUID, new DecimalType((int) value));
        }
    }

    private void updateSwitchSettingChannel(Boolean setting, ChannelUID channelUID) {
        if (Boolean.TRUE.equals(setting)) {
            updateState(channelUID, OnOffType.ON);
        } else {
            updateState(channelUID, OnOffType.OFF);
        }
    }

    private void updateContactChannel(Boolean setting, ChannelUID channelUID) {
        if (Boolean.TRUE.equals(setting)) {
            updateState(channelUID, OpenClosedType.OPEN);
        } else {
            updateState(channelUID, OpenClosedType.CLOSED);
        }
    }

    private void updateTimeChannel(String timeString, ChannelUID channelUID) {
        String newTime = String.format("%04d", Integer.parseInt(timeString));
        newTime = new StringBuilder(newTime).insert(newTime.length() - 2, ":").toString();
        updateState(channelUID, new StringType(newTime));
    }

    private void updateDateChannel(String dateString, ChannelUID channelUID) {
        String newDate = String.format("%04d", Integer.parseInt(dateString));
        newDate = new StringBuilder(newDate).insert(newDate.length() - 2, "-").toString();
        updateState(channelUID, new StringType(newDate));
    }

    private void updateRespondChannel(String responds) {
        for (Channel channel : getThing().getChannels()) {
            ChannelUID channelUID = channel.getUID();
            String channelStr = channelUID.getId();
            if (CHANNEL_RESPONDBYTES.equalsIgnoreCase(channelStr)) {
                updateState(channelUID, new StringType(responds));
                return;
            }
        }
    }

    /**
     * This method categorize the heat pump configuration into setting, sensor
     * and status
     *
     * @return true if heat pump configuration for version could be found and
     *         loaded
     */
    private boolean categorizeHeatPumpConfiguration() {
        for (Request request : heatPumpConfiguration.getRequests()) {
            String requestStr = DataParser.bytesToHex(request.getRequestByte());
            logger.debug("Request : RequestByte -> {}", requestStr);

            if (Arrays.equals(request.getRequestByte(), REQUEST_VERSION)) {
                versionRequest = request;
                logger.debug("set version request : {}", requestStr);
            }
            if (timeRequest == null && Arrays.equals(request.getRequestByte(), REQUEST_TIME)) {
                timeRequest = request;
                logger.debug("set time request : {}", requestStr);
            }

            // group requests in different categories by investigating data type in first record
            RecordDefinition record = request.getRecordDefinitions().get(0);
            switch (record.getDataType()) {
                case Settings:
                    if (!heatPumpSettingConfiguration.getRequests().contains(request)) {
                        heatPumpSettingConfiguration.getRequests().add(request);
                    }
                    break;
                case Status:
                    if (!heatPumpStatusConfiguration.getRequests().contains(request)) {
                        heatPumpStatusConfiguration.getRequests().add(request);
                    }
                    break;
                case Sensor:
                    if (!heatPumpSensorConfiguration.getRequests().contains(request)) {
                        heatPumpSensorConfiguration.getRequests().add(request);
                    }
                    break;
                default:
                    break;
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
            ChannelUID channelUID = channel.getUID();
            String[] parts = channelUID.getId()
                    .split(Pattern.quote(StiebelHeatPumpBindingConstants.CHANNELGROUPSEPERATOR));
            String channelId = parts[parts.length - 1];
            Request request = heatPumpConfiguration.getRequestByChannelId(channelId);
            if (request != null) {
                String requestStr = DataParser.bytesToHex(request.getRequestByte());
                RecordDefinition record = request.getRecordDefinitionByChannelId(channelId);
                if (record == null) {
                    logger.warn("Could not find valid record definition for {},  please verify thing definition.",
                            channelId);
                    return;
                }
                record.setChannelid(channelUID.getId());
                if (!this.isLinked(channelUID)) {
                    continue;
                }
                if (record.getDataType() == Type.Settings && !heatPumpSettingRefresh.getRequests().contains(request)) {
                    heatPumpSettingRefresh.getRequests().add(request);
                    logger.info("Request {} added to setting refresh scheduler.", requestStr);
                }
                if (record.getDataType() != Type.Settings
                        && !heatPumpSensorStatusRefresh.getRequests().contains(request)) {
                    heatPumpSensorStatusRefresh.getRequests().add(request);
                    logger.info("Request {} added to sensor/status refresh scheduler.", requestStr);
                }
            }
        }
    }
}
