/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.stiebelheatpump.internal;

import static org.openhab.binding.stiebelheatpump.StiebelHeatPumpBindingConstants.*;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.eclipse.smarthome.core.thing.type.ChannelGroupTypeRegistry;
import org.eclipse.smarthome.core.thing.type.ChannelTypeRegistry;
import org.eclipse.smarthome.core.thing.type.ThingType;
import org.openhab.binding.stiebelheatpump.handler.StiebelHeatPumpHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link StiebelHeatPumpHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Peter Kreutzer - Initial contribution
 */
@Component(configurationPid = "binding.stiebelheatpump", service = ThingHandlerFactory.class)
public class StiebelHeatPumpHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = new HashSet<ThingTypeUID>() {
        {
            add(THING_TYPE_LWZ206);
            add(THING_TYPE_LWZ236);
            add(THING_TYPE_LWZ419);
            add(THING_TYPE_LWZ509);
            add(THING_TYPE_LWZ539);
            add(THING_TYPE_LWZ739);
            add(THING_TYPE_THZ762);
        }
    };

    private ChannelGroupTypeRegistry channelGroupTypeRegistry;
    private ChannelTypeRegistry channelTypeRegistry;

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        ThingType thingType = getThingTypeByUID(thing.getThingTypeUID());

        if (supportsThingType(thingTypeUID)) {
            return new StiebelHeatPumpHandler(thing, thingType, channelGroupTypeRegistry, channelTypeRegistry);
        }

        return null;
    }

    @Reference
    public void setChannelGroupTypeRegistry(ChannelGroupTypeRegistry channelGroupTypeRegistry) {
        this.channelGroupTypeRegistry = channelGroupTypeRegistry;
    }

    @Reference
    public void setChannelTypeRegistry(ChannelTypeRegistry channelTypeRegistry) {
        this.channelTypeRegistry = channelTypeRegistry;
    }
}
