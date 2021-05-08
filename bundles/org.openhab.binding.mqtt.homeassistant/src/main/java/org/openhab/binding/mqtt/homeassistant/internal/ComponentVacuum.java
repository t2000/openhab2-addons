/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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
package org.openhab.binding.mqtt.homeassistant.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mqtt.generic.values.DateTimeValue;
import org.openhab.binding.mqtt.generic.values.NumberValue;
import org.openhab.binding.mqtt.generic.values.TextValue;

/**
 * A MQTT vacuum, following the https://www.home-assistant.io/components/vacuum.mqtt/ specification.
 *
 * @author Stefan Triller - Initial contribution
 */
@NonNullByDefault
public class ComponentVacuum extends AbstractComponent<ComponentVacuum.ChannelConfiguration> {
    public static final String vacuumChannelID = "vacuum"; // Randomly chosen channel "ID"

    // public static final String vacuumStateChannelID = "state";
    public static final String vacuumCommandChannelID = "command";
    public static final String vacuumBatteryChannelID = "batteryLevel";
    public static final String vacuumFanSpeedChannelID = "fanSpeed";

    // sensor stats
    public static final String vacuumMainBrushChannelID = "mainBrushUsage";
    public static final String vacuumSideBrushChannelID = "sideBrushUsage";
    public static final String vacuumFilterChannelID = "filter";
    public static final String vacuumSensorChannelID = "sensor";
    public static final String vacuumCurrentCleanTimeChannelID = "currentCleanTime";
    public static final String vacuumCurrentCleanAreaChannelID = "currentCleanArea";
    public static final String vacuumCleanTimeChannelID = "cleanTime";
    public static final String vacuumCleanAreaChannelID = "cleanArea";
    public static final String vacuumCleanCountChannelID = "cleanCount";

    public static final String vacuumLastRunStartChannelID = "lastRunStart";
    public static final String vacuumLastRunEndChannelID = "lastRunEnd";
    public static final String vacuumLastRunDurationChannelID = "lastRunDuration";
    public static final String vacuumLastRunAreaChannelID = "lastRunArea";
    public static final String vacuumLastRunErrorCodeChannelID = "lastRunErrorCode";
    public static final String vacuumLastRunErrorDescriptionChannelID = "lastRunErrorDescription";
    public static final String vacuumLastRunFinishedFlagChannelID = "lastRunFinishedFlag";

    public static final String vacuumBinInTimeChannelID = "binInTime";
    public static final String vacuumLastBinOutChannelID = "lastBinOutTime";
    public static final String vacuumlastBinFullChannelID = "lastBinFullTime";

    public static final String vacuumCustomCommandChannelID = "customCommand";

    // skipping map because its provided by valetudo_mapper
    // public static final String vacuumMapChannelID = "map";

    /**
     * Configuration class for MQTT component
     */
    static class ChannelConfiguration extends BaseChannelConfiguration {
        ChannelConfiguration() {
            super("MQTT Vacuum");
        }

        // protected @Nullable Boolean optimistic;

        protected @Nullable String command_topic;
        protected String state_topic = "";
        protected @Nullable String send_command_topic; // custom_command

        // [start, pause, stop, return_home, battery, status, locate, clean_spot, fan_speed, send_command]
        protected String[] supported_features = new String[] {};
        protected @Nullable String set_fan_speed_topic;
        protected String[] fan_speed_list = new String[] {};

        // protected @Nullable String state_on;
        // protected @Nullable String state_off;
        // protected String payload_on = "ON";
        // protected String payload_off = "OFF";

        protected @Nullable String json_attributes_topic;
        protected @Nullable String json_attributes_template;
    }

    public ComponentVacuum(CFactory.ComponentConfiguration componentConfiguration) {
        super(componentConfiguration, ChannelConfiguration.class);

        // boolean optimistic = channelConfiguration.optimistic != null ? channelConfiguration.optimistic
        // : StringUtils.isBlank(channelConfiguration.state_topic);
        //
        // if (optimistic && StringUtils.isNotBlank(channelConfiguration.state_topic)) {
        // throw new UnsupportedOperationException("Component:Switch does not support forced optimistic mode");
        // }
        //
        // String state_on = channelConfiguration.state_on != null ? channelConfiguration.state_on
        // : channelConfiguration.payload_on;
        // String state_off = channelConfiguration.state_off != null ? channelConfiguration.state_off
        // : channelConfiguration.payload_off;
        //
        // OnOffValue value = new OnOffValue(state_on, state_off, channelConfiguration.payload_on,
        // channelConfiguration.payload_off);

        List<String> features = Arrays.asList(channelConfiguration.supported_features);

        // features = [start, pause, stop, return_home, status, locate, clean_spot, fan_speed, send_command]

        ArrayList<String> possibleCommands = new ArrayList<String>();
        if (features.contains("start")) {
            possibleCommands.add("start");
        }

        if (features.contains("stop")) {
            possibleCommands.add("stop");
        }

        if (features.contains("pause")) {
            possibleCommands.add("pause");
        }

        if (features.contains("return_home")) {
            possibleCommands.add("return_to_base");
        }

        if (features.contains("clean_spot")) { // TODO: custom channel?
            possibleCommands.add("clean_spot");
        }

        if (features.contains("locate")) {
            possibleCommands.add("locate");
        }

        List<String> vacuumStates = List.of("docked", "cleaning", "returning", "paused", "idle", "error");
        possibleCommands.addAll(vacuumStates);

        // TextValue value = new TextValue(
        // // new String[] { "start", "pause", "return_to_base", "stop", "clean_spot", "locate" });
        // // new String[] { "docked", "cleaning", "returning", "paused", "idle", "error" });
        // new String[] { "start", "pause", "return_to_base", "stop", "clean_spot", "locate", "docked", "cleaning",
        // "returning", "paused", "idle", "error" });
        TextValue value = new TextValue(possibleCommands.toArray(new String[0]));
        buildChannel(vacuumCommandChannelID, value, "State/Command", componentConfiguration.getUpdateListener())
                .stateTopic(channelConfiguration.state_topic, "{{value_json.state}}")
                .commandTopic(channelConfiguration.command_topic, true, 1).build();

        if (features.contains("battery")) {
            // build battery level channel (0-100)
            NumberValue batValue = new NumberValue(BigDecimal.ZERO, new BigDecimal(100), new BigDecimal(1), "%");
            buildChannel(vacuumBatteryChannelID, batValue, "Battery Level", componentConfiguration.getUpdateListener())
                    .stateTopic(channelConfiguration.state_topic, "{{value_json.battery_level}}").build();
        }

        if (features.contains("fan_speed")) {
            // build fan speed channel with values from channelConfiguration.fan_speed_list
            TextValue fanValue = new TextValue(channelConfiguration.fan_speed_list);
            buildChannel(vacuumFanSpeedChannelID, fanValue, "Fan speed", componentConfiguration.getUpdateListener())
                    .stateTopic(channelConfiguration.state_topic, "{{value_json.fan_speed}}")
                    .commandTopic(channelConfiguration.set_fan_speed_topic, true, 1).build();
        }

        // TODO: if(features.contains...
        // TODO: custom command topic with zone cleaning

        // not needed: will be discovered as camera due to valetudo_mapper
        // String mapTopic = channelConfiguration.state_topic.replace("state", "map");
        // ImageValue mapImage = new ImageValue();
        // // mapImage.update(data);
        // buildChannel(vacuumMapChannelID, mapImage, "Map", componentConfiguration.getUpdateListener())
        // .stateTopic(mapTopic).build();

        // json_attributes_topic
        // valetudo/staubi/attributes

        // {"mainBrush":"220.6","sideBrush":"120.6","filter":"70.6","sensor":"0.0","currentCleanTime":"0.0","currentCleanArea":"0.0","cleanTime":"79.3","cleanArea":"4439.9","cleanCount":183,"last_run_stats":{"startTime":1613503117000,"endTime":1613503136000,"duration":0,"area":"0.0","errorCode":0,"errorDescription":"No
        // error","finishedFlag":false},"bin_in_time":1000,"last_bin_out":-1,"last_bin_full":-1,"last_loaded_map":null,"state":"docked","valetudo_state":{"id":8,"name":"Charging"}}

        if (features.contains("status")) {
            NumberValue currentCleanTimeValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumCurrentCleanTimeChannelID, currentCleanTimeValue, "Current Cleaning Time",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.currentCleanTime}}")
                            .build();

            NumberValue currentCleanAreaValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumCurrentCleanAreaChannelID, currentCleanAreaValue, "Current Cleaning Area",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.currentCleanArea}}")
                            .build();

            NumberValue cleanTimeValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumCleanTimeChannelID, cleanTimeValue, "Cleaning Time",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.cleanTime}}").build();

            NumberValue cleanAreaValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumCleanAreaChannelID, cleanAreaValue, "Cleaned Area",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.cleanArea}}").build();

            NumberValue cleaCountValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumCleanCountChannelID, cleaCountValue, "Cleaning Counter",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.cleanCount}}")
                            .build();

            // TODO: only if custom valetudo...?
            DateTimeValue binInValue = new DateTimeValue();
            buildChannel(vacuumBinInTimeChannelID, binInValue, "Bin In Time",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.bin_in_time}}")
                            .build();

            DateTimeValue lastBinOutValue = new DateTimeValue();
            buildChannel(vacuumLastBinOutChannelID, lastBinOutValue, "Last Bin Out Time",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.last_bin_out}}")
                            .build();

            DateTimeValue lastBinFullValue = new DateTimeValue();
            buildChannel(vacuumlastBinFullChannelID, lastBinFullValue, "Last Bin Full Time",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.last_bin_full}}")
                            .build();

            // last_run_stats":{"startTime
            DateTimeValue lastStartTime = new DateTimeValue();
            buildChannel(vacuumLastRunStartChannelID, lastStartTime, "Last run start time",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic,
                                    "{{value_json.last_run_stats.startTime}}")
                            .build();

            DateTimeValue lastEndTime = new DateTimeValue();
            buildChannel(vacuumLastRunEndChannelID, lastEndTime, "Last run end time",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic,
                                    "{{value_json.last_run_stats.endTime}}")
                            .build();

            NumberValue lastRunDurationValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumLastRunDurationChannelID, lastRunDurationValue, "Last run duration",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic,
                                    "{{value_json.last_run_stats.duration}}")
                            .build();

            NumberValue lastRunAreaValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumLastRunAreaChannelID, lastRunAreaValue, "Last run area",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic,
                                    "{{value_json.last_run_stats.area}}")
                            .build();

            NumberValue lastRunErrorCodeValue = new NumberValue(null, null, null, null);
            buildChannel(vacuumLastRunErrorCodeChannelID, lastRunErrorCodeValue, "Last run error code",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic,
                                    "{{value_json.last_run_stats.errorCode}}")
                            .build();

            TextValue lastRunErrorDescriptionValue = new TextValue();
            buildChannel(vacuumLastRunErrorDescriptionChannelID, lastRunErrorDescriptionValue,
                    "Last run error description", componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic,
                                    "{{value_json.last_run_stats.errorDescription}}")
                            .build();

            // TODO: false doesnt map to OFF
            // OnOffValue lastRunFinishedFlagValue = new OnOffValue();
            TextValue lastRunFinishedFlagValue = new TextValue();
            buildChannel(vacuumLastRunFinishedFlagChannelID, lastRunFinishedFlagValue, "Last run finished flag",
                    componentConfiguration.getUpdateListener())
                            .stateTopic(channelConfiguration.json_attributes_topic,
                                    "{{value_json.last_run_stats.finishedFlag}}")
                            .build();
        }

        NumberValue mainBrush = new NumberValue(null, null, null, null);
        buildChannel(vacuumMainBrushChannelID, mainBrush, "Main brush usage",
                componentConfiguration.getUpdateListener())
                        .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.mainBrush}}").build();

        NumberValue sideBrush = new NumberValue(null, null, null, null);
        buildChannel(vacuumSideBrushChannelID, sideBrush, "Side brush usage",
                componentConfiguration.getUpdateListener())
                        .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.sideBrush}}").build();

        NumberValue filterValue = new NumberValue(null, null, null, null);
        buildChannel(vacuumFilterChannelID, filterValue, "Filter time", componentConfiguration.getUpdateListener())
                .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.filter}}").build();

        NumberValue sensorValue = new NumberValue(null, null, null, null);
        buildChannel(vacuumSensorChannelID, sensorValue, "Sensor", componentConfiguration.getUpdateListener())
                .stateTopic(channelConfiguration.json_attributes_topic, "{{value_json.sensor}}").build();

        // we have a custom command channel for zone cleanup, etc => create text channel
        if (channelConfiguration.send_command_topic != null) {
            TextValue customCommandValue = new TextValue();
            buildChannel(vacuumCustomCommandChannelID, customCommandValue, "Custom Command",
                    componentConfiguration.getUpdateListener())
                            .commandTopic(channelConfiguration.send_command_topic, true, 1)
                            .stateTopic(channelConfiguration.json_attributes_topic, // TODO: which statetopic here?
                                    "{{value_json.last_run_stats.finishedFlag}}")
                            .build();
        }

    }
}
