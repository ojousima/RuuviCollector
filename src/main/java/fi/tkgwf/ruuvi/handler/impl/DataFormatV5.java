package fi.tkgwf.ruuvi.handler.impl;

import fi.tkgwf.ruuvi.utils.RuuviUtils;
import fi.tkgwf.ruuvi.bean.InfluxDBData;
import fi.tkgwf.ruuvi.config.Config;
import fi.tkgwf.ruuvi.handler.BeaconHandler;
import java.util.HashMap;
import java.util.Map;

public class DataFormatV5 implements BeaconHandler {

    // ">" start of packet, HCI_EVENT_PKT,  HCI_EV_LE_META"
    // http://elixir.free-electrons.com/linux/latest/source/include/net/bluetooth/hci.h
    private static final String LE_REPORT_BEGINS = "> 04 3E ";
    //private static final String SENSORTAG_BEGINS = "> 04 3E 2B 02 01 00 01 ";
    // Ascii index, including spaces and start of packet '>'
    private static final int BDADDR_INDEX = 23;
    //27 bytes, manufacturer specific data, manufacturer Ruuvi, format 5
    private static final String DATAFORMAT_V5_HEADER  = "1B FF 99 04 05";
    /**
     * Contains the MAC address as key, and the timestamp of last sent update as
     * value
     */
    private final Map<String, Long> updatedMacs;
    private final long updateLimit = Config.getInfluxUpdateLimit();
    private String latestMac = null;
    private String latestBeginning = null;
    private String latestMid = null;

    public DataFormatV5() {
        updatedMacs = new HashMap<>();
    }

    @Override
    public InfluxDBData read(String rawLine, String mac) {
        // If this is start of new packet
        if(0 == rawLine.indexOf(">")) {
            //clear stored lines
            latestMac = null;
            latestBeginning = null;
            latestMid = null;
        }

        if (latestMac == null && rawLine.contains(LE_REPORT_BEGINS) && rawLine.length() > 40) { // Parse every LE line which might be valid.
            // MAC is in INGQUIRY_INFO_WITH_RSSI->dbaddr, https://stackoverflow.com/questions/37073114/obtain-rssi-with-hcidump
            latestMac = RuuviUtils.getMacFromLine(rawLine.substring(BDADDR_INDEX));
            latestBeginning = rawLine;
        } else if (latestMac != null && latestBeginning != null && latestMid == null) {
          latestMid = rawLine;
        } else if (latestMac != null && latestBeginning != null && latestMid != null) {
            try {
                if (shouldUpdate(latestMac)) {
                    // HCI dump splits data in 3 parts, call handler with all 3.
                    return handleMeasurement(latestMac, latestBeginning, latestMid, rawLine);
                }
            } finally {
                //clear stored lines
                latestMac = null;
                latestBeginning = null;
                latestMid = null;
            }
        }
        return null;
    }

    @Override
    public void reset() {
        latestMac = null;
    }

    private InfluxDBData handleMeasurement(String mac, String firstPart, String secondPart, String thirdPart) {
        String rawLine = firstPart.trim() + ' ' + secondPart.trim() + ' ' + thirdPart.trim();
        byte[] data = null;
        // Data packet starts after header, include format byte in raw data. Return null if header is not found.
        int packetStart = rawLine.indexOf(DATAFORMAT_V5_HEADER) + DATAFORMAT_V5_HEADER.length() - 2;
        if(packetStart > DATAFORMAT_V5_HEADER.length()) {
            data = RuuviUtils.hexToBytes(rawLine.substring(packetStart));
        }
        //Data was parsed, payload 24 + 1 extra byte, format 5
        if (null == data || data.length < 25 || data[0] != 5) {
            return null; // unknown type
        }
        String protocolVersion = String.valueOf(data[0]);

        //cast to short to keep sign
        short raw_t = (short)(data[1] << 8 | data[2] & 0xFF);
        double temperature = raw_t / 200d;

        double humidity = ((data[3] & 0xFF) << 8 | data[4] & 0xFF) / 400d;

        //32 bits, sign is discarded
        int pressure = ((data[5] & 0xFF) << 8 | data[6] & 0xFF) + 50000;

        double accelX = (data[7] << 8 | data[8] & 0xFF) / 1000d;
        double accelY = (data[9] << 8 | data[10] & 0xFF) / 1000d;
        double accelZ = (data[11] << 8 | data[12] & 0xFF) / 1000d;
        double accelTotal = Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);

        int powerInfo = (data[13] & 0xFF) << 8 | data[14] & 0xFF;
        double battery = (powerInfo >> 5) / 1000d + 1.6d;
        int txPower = (powerInfo & 0b11111) * 2 - 40;

        int movementCounter = data[15] & 0xFF;

        int sequenceNumber = (data[16] & 0xFF) << 8 | data[17] & 0xFF;

        byte rssi = (byte)(data[24] & 0xFF);

        InfluxDBData.Builder builder = new InfluxDBData.Builder().mac(mac).protocolVersion(protocolVersion)
                .measurement("temperature").value(temperature)
                .measurement("humidity").value(humidity)
                .measurement("pressure").value(pressure)
                .measurement("acceleration").tag("axis", "x").value(accelX)
                .measurement("acceleration").tag("axis", "y").value(accelY)
                .measurement("acceleration").tag("axis", "z").value(accelZ)
                .measurement("acceleration").tag("axis", "total").value(accelTotal)
                .measurement("batteryVoltage").value(battery)
                .measurement("txPower").value(txPower)
                .measurement("movementCounter").value(movementCounter)
                .measurement("sequenceNumber").value(sequenceNumber)
                .measurement("RSSI").value(rssi);
        return builder.build();
    }

    private boolean shouldUpdate(String mac) {
        if (!Config.isAllowedMAC(mac)) {
            return false;
        }
        Long lastUpdate = updatedMacs.get(mac);
        if (lastUpdate == null || lastUpdate + updateLimit < System.currentTimeMillis()) {
            updatedMacs.put(mac, System.currentTimeMillis());
            return true;
        }
        return false;
    }
}
