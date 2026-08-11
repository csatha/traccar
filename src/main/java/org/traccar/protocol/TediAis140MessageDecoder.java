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

import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * Parses all five TEDI ITS-IRNS-01 (AIS-140) message types described in the protocol
 * specification:
 * <ul>
 *     <li>LOGIN DATA STRING</li>
 *     <li>GENERAL DATA STRING</li>
 *     <li>HEALTH DATA STRING</li>
 *     <li>OVER THE AIR PARAMETER CHANGE ALERT DATA STRING</li>
 *     <li>BATCH PACKET FORMAT</li>
 * </ul>
 *
 * <p>This class is intentionally transport-agnostic (no Netty {@code Channel}/session
 * caching logic) so that both {@link TediAis140TcpProtocolDecoder} and
 * {@link TediAis140HttpProtocolDecoder} can reuse the exact same parsing code instead of
 * duplicating it. Device-session resolution (which needs the transport-specific
 * {@code Channel}/{@code SocketAddress}) is injected via {@link SessionResolver}.
 *
 * <p><b>Known field-format assumptions - please confirm with TEDI Elite / verify against
 * live traffic before going to production:</b>
 * <ul>
 *     <li>All timestamps in the LOGIN/GENERAL/HEALTH/OTA packets are UTC (the spec document
 *     explicitly says "UTC Time" / "GMT Time" for these fields), so no per-device timezone
 *     conversion is applied.</li>
 *     <li>Latitude/Longitude are plain decimal degrees (e.g. {@code 13.082680}), not
 *     degrees+minutes, even though the spec's field description says "converted in degree
 *     and minutes" - the sample values only make sense as decimal degrees (they match real
 *     Chennai coordinates).</li>
 *     <li>In the GENERAL packet, LAC is hex and Cell ID is decimal (this matches the BATCH
 *     table which explicitly labels Cell ID as decimal).</li>
 *     <li>The GENERAL packet's neighbour-cell block has been observed in two different
 *     shapes: the spec document's own worked example uses 3 fields per neighbour
 *     (id, lac, signal - 4 neighbours = 12 fields), while the real sample data supplied by
 *     the client uses 2 fields per neighbour (id, signal - 4 neighbours = 8 fields). Both
 *     are handled; anything else is preserved as a raw pipe-delimited attribute instead of
 *     being dropped.</li>
 *     <li>LOGIN and HEALTH packets carry no GPS fix, so those positions are stored with
 *     server-received time and {@code valid=false} purely so the battery/status attributes
 *     land in reports; only the LOGIN packet's own lat/lon (if present) is treated as a fix.</li>
 * </ul>
 */
final class TediAis140MessageDecoder {

    @FunctionalInterface
    interface SessionResolver {
        DeviceSession resolve(String imei);
    }

    private static final DateTimeFormatter DATE_TIME_FORMAT_LONG = DateTimeFormatter
            .ofPattern("ddMMyyyyHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME_FORMAT_SHORT = DateTimeFormatter
            .ofPattern("ddMMyyHHmmss").withZone(ZoneOffset.UTC);

    private TediAis140MessageDecoder() {
    }

    static Object decode(String protocolName, SessionResolver resolver, String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message.replace("\r", "").replace("\n", "").trim();
        if (cleaned.isEmpty() || cleaned.charAt(0) != '$') {
            return null;
        }
        try {
            if (cleaned.length() > 1 && cleaned.charAt(1) == ',') {
                String rest = cleaned.substring(2);
                if (rest.startsWith("BTH,")) {
                    return decodeBatch(protocolName, resolver, cleaned);
                } else if (rest.startsWith("101,")) {
                    return decodeHealth(protocolName, resolver, rest);
                } else if (rest.startsWith("PC,")) {
                    return decodeParameterChange(protocolName, resolver, rest);
                } else {
                    return decodeGeneral(protocolName, resolver, rest);
                }
            } else {
                return decodeLogin(protocolName, resolver, cleaned);
            }
        } catch (RuntimeException error) {
            // Malformed / unexpected firmware variant - ignore this message rather than
            // taking down the connection (per protocol handler bounds-checking guidance).
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // LOGIN DATA STRING: $VEHICLE$IMEI$FWVER$PROTOVER$LAT$LATDIR$LON$LONDIR$
    // ---------------------------------------------------------------------
    private static Object decodeLogin(String protocolName, SessionResolver resolver, String sentence) {
        String[] parts = sentence.split("\\$", -1);
        if (parts.length < 9) {
            return null;
        }

        String vehicleNo = parts[1];
        String imei = parts[2];
        String firmwareVersion = parts[3];
        String protocolVersion = parts[4];
        String latitude = parts[5];
        String latHem = parts[6];
        String longitude = parts[7];
        String lonHem = parts[8];

        DeviceSession deviceSession = resolver.resolve(imei);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(protocolName);
        position.setDeviceId(deviceSession.getDeviceId());
        position.set("packetType", "LOGIN");
        position.set("vehicleNumber", vehicleNo);
        position.set(Position.KEY_VERSION_FW, firmwareVersion);
        position.set("protocolVersion", protocolVersion);
        position.setTime(new Date());

        Double lat = parseDouble(latitude);
        Double lon = parseDouble(longitude);
        if (lat != null && lon != null) {
            position.setValid(true);
            position.setLatitude(applyHemisphere(lat, latHem));
            position.setLongitude(applyHemisphere(lon, lonHem));
        } else {
            position.setValid(false);
            position.setLatitude(0);
            position.setLongitude(0);
        }

        return position;
    }

    // ---------------------------------------------------------------------
    // GENERAL DATA STRING: $,<header>,<vendor>,<fw>,<type>,<alertId>,<status>,<imei>,...
    // ---------------------------------------------------------------------
    private static Object decodeGeneral(String protocolName, SessionResolver resolver, String rest) {
        String[] p = rest.split(",", -1);
        if (p.length < 32) {
            return null;
        }

        int i = 0;
        String packetHeader = p[i++];
        String vendorId = p[i++];
        String firmwareVersion = p[i++];
        String packetType = p[i++];
        String alertIdStr = p[i++];
        String packetStatus = p[i++];
        String imei = p[i++];
        String vehicleNo = p[i++];
        String gpsFix = p[i++];
        String date = p[i++];
        String time = p[i++];
        String latitude = p[i++];
        String latHem = p[i++];
        String longitude = p[i++];
        String lonHem = p[i++];
        String speed = p[i++];
        String course = p[i++];
        String satellites = p[i++];
        String altitude = p[i++];
        String pdop = p[i++];
        String hdop = p[i++];
        String operator = p[i++];
        String ignition = p[i++];
        String mainsPower = p[i++];
        String mainsVoltage = p[i++];
        String batteryVoltage = p[i++];
        String sos = p[i++];
        String gsmSignal = p[i++];
        String mcc = p[i++];
        String mnc = p[i++];
        String lac = p[i++];
        String cellId = p[i++];
        // i == 32 here; everything from here up to (but excluding) the trailing '*' token
        // is the neighbour-cell block followed by digital I/O, frame number and checksum.

        DeviceSession deviceSession = resolver.resolve(imei);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(protocolName);
        position.setDeviceId(deviceSession.getDeviceId());

        position.set("packetType", packetType);
        position.set("vehicleNumber", vehicleNo);
        position.set(Position.KEY_VERSION_FW, firmwareVersion);
        position.set("vendorId", vendorId);

        Integer alertId = parseInt(alertIdStr);
        if (alertId != null) {
            position.set("alertId", alertId);
            applyAlarm(position, alertId);
        }
        if ("H".equalsIgnoreCase(packetStatus) || "200".equals(packetHeader)) {
            position.set(Position.KEY_ARCHIVE, true);
        }

        position.setValid("1".equals(gpsFix));
        Date fixTime = parseDateTime(date, time, DATE_TIME_FORMAT_LONG);
        position.setTime(fixTime != null ? fixTime : new Date());

        Double lat = parseDouble(latitude);
        Double lon = parseDouble(longitude);
        position.setLatitude(lat != null ? applyHemisphere(lat, latHem) : 0);
        position.setLongitude(lon != null ? applyHemisphere(lon, lonHem) : 0);

        Double speedValue = parseDouble(speed);
        if (speedValue != null) {
            position.setSpeed(org.traccar.helper.UnitsConverter.knotsFromKph(speedValue));
        }
        Double courseValue = parseDouble(course);
        if (courseValue != null) {
            position.setCourse(courseValue);
        }
        Integer satellitesValue = parseInt(satellites);
        if (satellitesValue != null) {
            position.set(Position.KEY_SATELLITES, satellitesValue);
        }
        Double altitudeValue = parseDouble(altitude);
        if (altitudeValue != null) {
            position.setAltitude(altitudeValue);
        }
        Double pdopValue = parseDouble(pdop);
        if (pdopValue != null) {
            position.set(Position.KEY_PDOP, pdopValue);
        }
        Double hdopValue = parseDouble(hdop);
        if (hdopValue != null) {
            position.set(Position.KEY_HDOP, hdopValue);
        }
        if (operator != null && !operator.isEmpty()) {
            position.set(Position.KEY_OPERATOR, operator);
        }
        position.set(Position.KEY_IGNITION, "1".equals(ignition));
        position.set(Position.KEY_CHARGE, "1".equals(mainsPower));
        Double mainsVoltageValue = parseDouble(mainsVoltage);
        if (mainsVoltageValue != null) {
            position.set(Position.KEY_POWER, mainsVoltageValue);
        }
        Double batteryVoltageValue = parseDouble(batteryVoltage);
        if (batteryVoltageValue != null) {
            position.set(Position.KEY_BATTERY, batteryVoltageValue);
        }
        if ("1".equals(sos)) {
            position.addAlarm(Position.ALARM_SOS);
        }
        Integer gsmSignalValue = parseInt(gsmSignal);
        if (gsmSignalValue != null) {
            position.set(Position.KEY_RSSI, gsmSignalValue);
        }

        Integer mccVal = parseInt(mcc);
        Integer mncVal = parseInt(mnc);
        Integer lacVal = parseHex(lac);
        Long cellIdVal = parseLong(cellId);
        if (mccVal != null && mncVal != null && lacVal != null && cellIdVal != null) {
            position.setNetwork(new Network(CellTower.from(mccVal, mncVal, lacVal, cellIdVal)));
        }

        int tailCount = (p.length - 1) - i; // exclude the trailing '*' token
        String digitalInput = null;
        String digitalOutput = null;
        String frameNumber = null;
        String checksum = null;

        if (tailCount == 12) {
            // 4 neighbours x (id, signal)
            for (int n = 1; n <= 4 && i < p.length; n++) {
                String neighbourId = p[i++];
                String neighbourSignal = i < p.length ? p[i++] : "";
                if (!neighbourId.isEmpty()) {
                    position.set("neighbourCell" + n + "Id", neighbourId);
                }
                if (!neighbourSignal.isEmpty()) {
                    position.set("neighbourCell" + n + "Signal", neighbourSignal);
                }
            }
            digitalInput = p[i++];
            digitalOutput = p[i++];
            frameNumber = p[i++];
            checksum = p[i++];
        } else if (tailCount == 16) {
            // 4 neighbours x (id, lac, signal)
            for (int n = 1; n <= 4 && i < p.length; n++) {
                String neighbourId = p[i++];
                String neighbourLac = i < p.length ? p[i++] : "";
                String neighbourSignal = i < p.length ? p[i++] : "";
                if (!neighbourId.isEmpty()) {
                    position.set("neighbourCell" + n + "Id", neighbourId);
                }
                if (!neighbourLac.isEmpty()) {
                    position.set("neighbourCell" + n + "Lac", neighbourLac);
                }
                if (!neighbourSignal.isEmpty()) {
                    position.set("neighbourCell" + n + "Signal", neighbourSignal);
                }
            }
            digitalInput = p[i++];
            digitalOutput = p[i++];
            frameNumber = p[i++];
            checksum = p[i++];
        } else if (tailCount >= 4) {
            // Unrecognised firmware variant - keep the last 4 fields (which are stable
            // across both known variants) and preserve whatever is in between verbatim.
            int neighbourFieldCount = tailCount - 4;
            if (neighbourFieldCount > 0) {
                StringBuilder raw = new StringBuilder();
                for (int n = 0; n < neighbourFieldCount; n++) {
                    if (n > 0) {
                        raw.append('|');
                    }
                    raw.append(p[i++]);
                }
                position.set("neighbourCellsRaw", raw.toString());
            }
            digitalInput = p[i++];
            digitalOutput = p[i++];
            frameNumber = p[i++];
            checksum = p[i++];
        }

        if (digitalInput != null) {
            Integer input = parseBinary(digitalInput);
            if (input != null) {
                position.set(Position.KEY_INPUT, input);
            }
        }
        if (digitalOutput != null) {
            Integer output = parseBinary(digitalOutput);
            if (output != null) {
                position.set(Position.KEY_OUTPUT, output);
            }
        }
        if (frameNumber != null) {
            Integer frame = parseInt(frameNumber);
            if (frame != null) {
                position.set(Position.KEY_INDEX, frame);
            }
        }
        if (checksum != null && !checksum.isEmpty() && !checksum.equals("*")) {
            position.set("checksum", checksum);
        }

        return position;
    }

    // ---------------------------------------------------------------------
    // HEALTH DATA STRING: $,101,<vendor>,<fw>,<imei>,<batt%>,<lowBattThr>,...
    // ---------------------------------------------------------------------
    private static Object decodeHealth(String protocolName, SessionResolver resolver, String rest) {
        String[] p = rest.split(",", -1);
        if (p.length < 12) {
            return null;
        }

        int i = 0;
        i++; // packet header ("101") - already used for dispatch, skip
        String vendorId = p[i++];
        String firmwareVersion = p[i++];
        String imei = p[i++];
        String batteryPct = p[i++];
        String lowBatteryThreshold = p[i++];
        String memoryPct = p[i++];
        String ignitionInterval = p[i++];
        String normalInterval = p[i++];
        String digitalIo = p[i++];
        String analog1 = p[i++];
        String analog2 = p[i++];

        DeviceSession deviceSession = resolver.resolve(imei);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(protocolName);
        position.setDeviceId(deviceSession.getDeviceId());
        position.set("packetType", "HEALTH");
        position.set(Position.KEY_VERSION_FW, firmwareVersion);
        position.set("vendorId", vendorId);

        Integer battery = parseInt(batteryPct);
        if (battery != null) {
            position.set(Position.KEY_BATTERY_LEVEL, battery);
        }
        Integer lowThreshold = parseInt(lowBatteryThreshold);
        if (lowThreshold != null) {
            position.set("lowBatteryThreshold", lowThreshold);
        }
        Integer memory = parseInt(memoryPct);
        if (memory != null) {
            position.set("memoryUsage", memory);
        }
        Integer ignInterval = parseInt(ignitionInterval);
        if (ignInterval != null) {
            position.set("ignitionReportInterval", ignInterval);
        }
        Integer normInterval = parseInt(normalInterval);
        if (normInterval != null) {
            position.set("normalReportInterval", normInterval);
        }
        Integer digitalIoValue = parseBinary(digitalIo);
        if (digitalIoValue != null) {
            position.set(Position.KEY_INPUT, digitalIoValue);
        }
        Double adc1 = parseDouble(analog1);
        if (adc1 != null) {
            position.set(Position.PREFIX_ADC + 1, adc1);
        }
        Double adc2 = parseDouble(analog2);
        if (adc2 != null) {
            position.set(Position.PREFIX_ADC + 2, adc2);
        }

        // Health packets carry no GPS fix - record with server time so the battery /
        // memory / interval attributes still land against the device.
        position.setTime(new Date());
        position.setValid(false);
        position.setLatitude(0);
        position.setLongitude(0);

        return position;
    }

    // ---------------------------------------------------------------------
    // OVER THE AIR PARAMETER CHANGE ALERT: $,PC,<alertId>,<imei>,<mode>,<src>,...
    // ---------------------------------------------------------------------
    private static Object decodeParameterChange(String protocolName, SessionResolver resolver, String rest) {
        String[] p = rest.split(",", -1);
        if (p.length < 8) {
            return null;
        }

        int i = 0;
        i++; // "PC" literal - already used for dispatch, skip
        String alertIdStr = p[i++];
        String imei = p[i++];
        String mode = p[i++];
        String source = p[i++];
        String date = p[i++];
        String time = p[i++];
        String param = p[i++];

        DeviceSession deviceSession = resolver.resolve(imei);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(protocolName);
        position.setDeviceId(deviceSession.getDeviceId());
        position.set("packetType", "PARAMETER_CHANGE");

        Integer alertId = parseInt(alertIdStr);
        if (alertId != null) {
            position.set("alertId", alertId);
        }
        position.set("commandMode", "1".equals(mode) ? "server" : "sms");
        if (source != null && !source.isEmpty()) {
            position.set("commandSource", source);
        }
        position.set(Position.KEY_RESULT, param);

        Date changeTime = parseDateTime(date, time, DATE_TIME_FORMAT_LONG);
        position.setTime(changeTime != null ? changeTime : new Date());
        position.setValid(false);
        position.setLatitude(0);
        position.setLongitude(0);

        return position;
    }

    // ---------------------------------------------------------------------
    // BATCH PACKET FORMAT: $,BTH,<imei>,<count>,$,<packet1>,$,<packet2>,...$,<packetN>*
    // ---------------------------------------------------------------------
    private static Object decodeBatch(String protocolName, SessionResolver resolver, String cleaned) {
        String[] segments = cleaned.split("\\$", -1);
        if (segments.length < 3) {
            return null;
        }

        // Every sub-packet (including the BTH header) is delimited by "$," not just "$",
        // so after splitting on the bare '$' character each segment still has a leading
        // comma that needs to be stripped before parsing its fields.
        String headerSegment = stripLeadingComma(segments[1]);
        String[] headerFields = headerSegment.split(",", -1);
        if (headerFields.length < 3 || !"BTH".equals(headerFields[0])) {
            return null;
        }
        String imei = headerFields[1];
        Integer batchCount = parseInt(headerFields[2]);

        DeviceSession deviceSession = resolver.resolve(imei);
        if (deviceSession == null) {
            return null;
        }

        List<Position> positions = new LinkedList<>();

        for (int s = 2; s < segments.length; s++) {
            String segment = stripLeadingComma(segments[s]);
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.endsWith("*")) {
                segment = segment.substring(0, segment.length() - 1);
            }
            if (segment.isEmpty()) {
                continue;
            }

            String[] f = segment.split(",", -1);
            if (f.length < 23) {
                continue; // malformed sub-packet - skip it, keep the rest of the batch
            }

            Position position = new Position(protocolName);
            position.setDeviceId(deviceSession.getDeviceId());
            position.set("packetType", "BATCH");
            if (batchCount != null) {
                position.set("batchCount", batchCount);
            }

            Integer alertId = parseInt(f[0]);
            if (alertId != null) {
                position.set("alertId", alertId);
                applyAlarm(position, alertId);
            }
            if ("H".equalsIgnoreCase(f[1])) {
                position.set(Position.KEY_ARCHIVE, true);
            }
            position.setValid("1".equals(f[2]));

            Date time = parseDateTime(f[3], f[4], DATE_TIME_FORMAT_SHORT);
            position.setTime(time != null ? time : new Date());

            Double lat = parseDouble(f[5]);
            Double lon = parseDouble(f[7]);
            position.setLatitude(lat != null ? applyHemisphere(lat, f[6]) : 0);
            position.setLongitude(lon != null ? applyHemisphere(lon, f[8]) : 0);

            Integer mcc = parseInt(f[9]);
            Integer mnc = parseInt(f[10]);
            Integer lac = parseHex(f[11]);
            Long cellId = parseLong(f[12]);
            if (mcc != null && mnc != null && lac != null && cellId != null) {
                position.setNetwork(new Network(CellTower.from(mcc, mnc, lac, cellId)));
            }

            Double speed = parseDouble(f[13]);
            if (speed != null) {
                position.setSpeed(org.traccar.helper.UnitsConverter.knotsFromKph(speed));
            }
            Double heading = parseDouble(f[14]);
            if (heading != null) {
                position.setCourse(heading);
            }
            Integer satellites = parseInt(f[15]);
            if (satellites != null) {
                position.set(Position.KEY_SATELLITES, satellites);
            }
            Double hdop = parseDouble(f[16]);
            if (hdop != null) {
                position.set(Position.KEY_HDOP, hdop);
            }
            Integer gsmSignal = parseInt(f[17]);
            if (gsmSignal != null) {
                position.set(Position.KEY_RSSI, gsmSignal);
            }
            position.set(Position.KEY_IGNITION, "1".equals(f[18]));
            position.set(Position.KEY_CHARGE, "1".equals(f[19]));
            if (!f[20].isEmpty()) {
                position.set("vehicleMode", f[20]);
            }
            Double altitude = parseDouble(f[21]);
            if (altitude != null) {
                position.setAltitude(altitude);
            }
            if (!f[22].isEmpty()) {
                position.set(Position.KEY_OPERATOR, f[22]);
            }

            positions.add(position);
        }

        return positions.isEmpty() ? null : positions;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void applyAlarm(Position position, int alertId) {
        switch (alertId) {
            case 3:
                position.addAlarm(Position.ALARM_POWER_CUT);
                break;
            case 4:
                position.addAlarm(Position.ALARM_LOW_BATTERY);
                break;
            case 6:
                position.addAlarm(Position.ALARM_POWER_RESTORED);
                break;
            case 9:
            case 16:
                position.addAlarm(Position.ALARM_TAMPERING);
                break;
            case 10:
                position.addAlarm(Position.ALARM_SOS);
                break;
            case 13:
                position.addAlarm(Position.ALARM_BRAKING);
                break;
            case 14:
                position.addAlarm(Position.ALARM_ACCELERATION);
                break;
            case 15:
                position.addAlarm(Position.ALARM_CORNERING);
                break;
            case 17:
                position.addAlarm(Position.ALARM_OVERSPEED);
                break;
            default:
                break;
        }
    }

    private static String stripLeadingComma(String value) {
        if (value != null && value.startsWith(",")) {
            return value.substring(1);
        }
        return value;
    }

    private static double applyHemisphere(double value, String hemisphere) {
        if (hemisphere != null && (hemisphere.equalsIgnoreCase("S") || hemisphere.equalsIgnoreCase("W"))) {
            return -value;
        }
        return value;
    }

    private static Date parseDateTime(String date, String time, DateTimeFormatter formatter) {
        if (date == null || time == null || date.isEmpty() || time.isEmpty()) {
            return null;
        }
        try {
            return Date.from(Instant.from(formatter.parse(date + time)));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Integer parseHex(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim(), 16);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Integer parseBinary(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim(), 2);
        } catch (NumberFormatException error) {
            return null;
        }
    }

}
