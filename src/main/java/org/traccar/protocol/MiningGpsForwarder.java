/*
 * Copyright 2026 TEDI Elite / Client Customization
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Converts positions decoded on the "Mining" variant ports (see {@link TediAis140MiningTcpProtocol},
 * {@link TediAis140MiningHttpProtocol} and {@link ItsMiningProtocol}) into the JSON payload expected by
 * the external mining vehicle-tracking API and posts it there, in addition to the normal Traccar
 * processing that already happens for the decoded position.
 *
 * <p><b>Field-mapping assumptions - please confirm against the mining API's own documentation before
 * going to production:</b>
 * <ul>
 *     <li>{@code batterystatustype} / {@code mainpowerstatustype} are simple 1/0 flags derived from the
 *     decoded charge/ignition state - the real enum values used by the mining API are unknown.</li>
 *     <li>{@code digitalinput} / {@code digitaloutput} are reconstructed from the integer bitmask Traccar
 *     stores internally, so the binary string length may not match the device's original field width.</li>
 *     <li>{@code rawdata} is the raw sentence/request body as received from the device for this message -
 *     for a BATCH packet (multiple positions from one message) every position in the batch carries the
 *     same raw data, since that is what the device actually sent.</li>
 * </ul>
 */
final class MiningGpsForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(MiningGpsForwarder.class);

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    private static final ObjectMapper LOG_MAPPER = new ObjectMapper();

    // Bound the optional downstream integration so it can never exhaust Traccar/Jersey threads.
    private static final int MAX_CONCURRENT_REQUESTS = 32;
    private static final int MAX_QUEUED_REQUESTS = 10_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    private static final int READ_TIMEOUT_MILLIS = 7_000;

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
    private static final AtomicLong DROPPED_REQUESTS = new AtomicLong();

    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "mining-forwarder-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            MAX_CONCURRENT_REQUESTS,
            MAX_CONCURRENT_REQUESTS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_REQUESTS),
            THREAD_FACTORY,
            new ThreadPoolExecutor.AbortPolicy());

    private MiningGpsForwarder() {
    }

    static void forwardResult(
            Config config, Client client, CacheManager cacheManager, Object decodedResult, String rawData) {
        if (decodedResult instanceof Position position) {
            forwardPosition(config, client, cacheManager, position, rawData);
        } else if (decodedResult instanceof Collection<?> positions) {
            for (Object item : positions) {
                if (item instanceof Position position) {
                    forwardPosition(config, client, cacheManager, position, rawData);
                }
            }
        }
    }

    private static void forwardPosition(
            Config config, Client client, CacheManager cacheManager, Position position, String rawData) {
        if (client == null || cacheManager == null) {
            return;
        }
        String url = config.getString(Keys.MINING_FORWARD_URL);
        if (url == null || url.isBlank()) {
            return;
        }
        Device device = cacheManager.getObject(Device.class, position.getDeviceId());
        if (device == null) {
            return;
        }

        final Map<String, Object> payload;
        try {
            payload = buildPayload(position, device, rawData);
        } catch (RuntimeException error) {
            LOGGER.warn("Mining GPS forward failed to build payload for device " + device.getUniqueId(), error);
            return;
        }

        String deviceUniqueId = device.getUniqueId();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Mining GPS forward payload for device {}: {}", deviceUniqueId, toJson(payload));
        }

        try {
            EXECUTOR.execute(() -> send(client, url, deviceUniqueId, payload));
        } catch (RejectedExecutionException error) {
            long dropped = DROPPED_REQUESTS.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                LOGGER.warn(
                        "Mining GPS forwarding queue full; dropped {} request(s). activeWorkers={}, queuedRequests={}",
                        dropped, EXECUTOR.getActiveCount(), EXECUTOR.getQueue().size());
            }
        }
    }

    private static void send(
            Client client, String url, String deviceUniqueId, Map<String, Object> payload) {
        try (Response response = client.target(url)
                .property(ClientProperties.CONNECT_TIMEOUT, CONNECT_TIMEOUT_MILLIS)
                .property(ClientProperties.READ_TIMEOUT, READ_TIMEOUT_MILLIS)
                .request(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.entity(payload, MediaType.APPLICATION_JSON_TYPE))) {

            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                String body = response.hasEntity() ? response.readEntity(String.class) : "";
                LOGGER.warn("Mining GPS forward failed with HTTP {} for device {}: {}",
                        response.getStatus(), deviceUniqueId, limit(body, 1000));
            } else {
                LOGGER.debug("Mining GPS forward succeeded for device {}", deviceUniqueId);
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Mining GPS forward transport failure for device {}: {}",
                    deviceUniqueId, error.getMessage());
        }
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private static String toJson(Map<String, Object> payload) {
        try {
            return LOG_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            return payload.toString();
        }
    }

    private static Map<String, Object> buildPayload(Position position, Device device, String rawData) {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("latitude", position.getLatitude());
        payload.put("longitude", position.getLongitude());
        payload.put("signalstrength", position.getInteger(Position.KEY_RSSI));
        payload.put("speed", UnitsConverter.kphFromKnots(position.getSpeed()));
        payload.put("odometer", position.getDouble(Position.KEY_ODOMETER) / 1000);
        payload.put("direction", (int) position.getCourse());
        payload.put("isonlinedata", !position.getBoolean(Position.KEY_ARCHIVE));
        payload.put("isignitionon", position.getBoolean(Position.KEY_IGNITION));
        payload.put("gpsdevicets", TIMESTAMP_FORMAT.format(position.getFixTime().toInstant()));
        payload.put("accelerometer", 0);
        payload.put("batterystatustype", position.getBoolean(Position.KEY_CHARGE) ? 1 : 0);
        payload.put("batteryvoltage", position.getDouble(Position.KEY_BATTERY));
        payload.put("extbatteryvoltage", position.getDouble(Position.KEY_POWER));
        payload.put("mainpowerstatustype", position.getBoolean(Position.KEY_CHARGE) ? 1 : 0);
        payload.put("gpsstatus", position.getValid());
        payload.put("packettype", position.getString("packetType", "GPS"));
        payload.put("altitude", position.getAltitude());
        payload.put("noofsatellites", position.getInteger(Position.KEY_SATELLITES));
        payload.put("operatorname", position.getString(Position.KEY_OPERATOR, ""));
        payload.put("isemergency", position.getString(Position.KEY_ALARM, "").contains(Position.ALARM_SOS));
        payload.put("istampered", position.getString(Position.KEY_ALARM, "").contains(Position.ALARM_TAMPERING));
        payload.put("servingcellinfo", formatServingCell(position));
        payload.put("neighbourcellinfo", formatNeighbourCells(position));
        payload.put("digitalinput", toBinaryString(position.getInteger(Position.KEY_INPUT)));
        payload.put("digitaloutput", toBinaryString(position.getInteger(Position.KEY_OUTPUT)));
        payload.put("frameno", position.getInteger(Position.KEY_INDEX));
        payload.put("analoginput1", position.getDouble(Position.PREFIX_ADC + 1));
        payload.put("analoginput2", position.getDouble(Position.PREFIX_ADC + 2));
        payload.put("imeino", device.getUniqueId());
        payload.put("rawdata", rawData != null ? rawData : "");

        return payload;
    }

    private static String formatServingCell(Position position) {
        if (position.getNetwork() != null
                && position.getNetwork().getCellTowers() != null
                && !position.getNetwork().getCellTowers().isEmpty()) {
            CellTower cellTower = position.getNetwork().getCellTowers().iterator().next();
            return cellTower.getMobileCountryCode() + "-" + cellTower.getMobileNetworkCode()
                    + "-" + cellTower.getLocationAreaCode() + "-" + cellTower.getCellId();
        }
        return "";
    }

    private static String formatNeighbourCells(Position position) {
        StringBuilder result = new StringBuilder();
        if (position.getNetwork() != null && position.getNetwork().getCellTowers() != null
                && position.getNetwork().getCellTowers().size() > 1) {
            var towers = position.getNetwork().getCellTowers().iterator();
            towers.next();
            while (towers.hasNext()) {
                CellTower cellTower = towers.next();
                if (!result.isEmpty()) {
                    result.append('|');
                }
                result.append(cellTower.getLocationAreaCode()).append('-').append(cellTower.getCellId());
            }
        }
        for (int i = 1; i <= 4; i++) {
            String id = position.getString("neighbourCell" + i + "Id");
            if (id != null) {
                if (!result.isEmpty()) {
                    result.append('|');
                }
                result.append(id);
            }
        }
        return result.toString();
    }

    private static String toBinaryString(int value) {
        return value > 0 ? Integer.toBinaryString(value) : "0000";
    }

}
