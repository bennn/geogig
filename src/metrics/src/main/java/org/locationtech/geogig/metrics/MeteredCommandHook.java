/* Copyright (c) 2014 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Gabriel Roldan (Boundless) - initial implementation
 */
package org.locationtech.geogig.metrics;

import static org.locationtech.geogig.metrics.MetricsModule.COMMAND_STACK_LOGGER;
import static org.locationtech.geogig.metrics.MetricsModule.METRICS_ENABLED;
import static org.locationtech.geogig.metrics.MetricsModule.METRICS_LOGGER;

import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.locationtech.geogig.api.AbstractGeoGigOp;
import org.locationtech.geogig.api.Platform;
import org.locationtech.geogig.api.hooks.CannotRunGeogigOperationException;
import org.locationtech.geogig.api.hooks.CommandHook;
import org.locationtech.geogig.storage.ConfigDatabase;
import org.locationtech.geogig.storage.ConfigException;
import org.locationtech.geogig.storage.ConfigException.StatusCode;

public class MeteredCommandHook implements CommandHook {

    private static final double toMillisFactor = 1.0 / TimeUnit.MILLISECONDS.toNanos(1L);

    /**
     * @return {@code true}, applies to all ops
     */
    @Override
    public boolean appliesTo(Class<? extends AbstractGeoGigOp<?>> clazz) {
        return true;
    }

    @Override
    public <C extends AbstractGeoGigOp<?>> C pre(C command)
            throws CannotRunGeogigOperationException {
        Boolean enabled;
        if (command.context().repository() == null) {
            return command;
        }
        ConfigDatabase configDb = command.context().configDatabase();
        try {
            enabled = configDb.get(METRICS_ENABLED, Boolean.class).or(Boolean.FALSE);
        } catch (ConfigException e) {
            if (StatusCode.INVALID_LOCATION.equals(e.statusCode)) {
                enabled = Boolean.FALSE;
            } else {
                throw e;
            }
        }
        if (!enabled.booleanValue()) {
            return command;
        }

        final Platform platform = command.context().platform();
        final long startTime = platform.currentTimeMillis();
        final long nanoTime = platform.nanoTime();
        final String name = command.getClass().getSimpleName();
        CallStack stack = CallStack.push(name, startTime, nanoTime);
        command.getClientData().put("metrics.callStack", stack);
        return command;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T post(AbstractGeoGigOp<T> command, @Nullable Object retVal,
            @Nullable RuntimeException exception) throws Exception {

        CallStack stack = (CallStack) command.getClientData().get("metrics.callStack");
        if (stack == null || command.context().repository() == null) {
            return (T) retVal;
        }

        final Platform platform = command.context().platform();
        long endTime = platform.nanoTime();
        boolean success = exception == null;
        stack = CallStack.pop(endTime, success);
        long ellapsed = stack.getEllapsedNanos();

        double millis = endTime * toMillisFactor;
        METRICS_LOGGER.info("{}, {}, {}, {}", stack.getName(), stack.getStartTimeMillis(), millis,
                success);
        if (stack.isRoot()) {
            COMMAND_STACK_LOGGER.info("{}", stack.toString(TimeUnit.MILLISECONDS));
        }

        return (T) retVal;
    }

}
