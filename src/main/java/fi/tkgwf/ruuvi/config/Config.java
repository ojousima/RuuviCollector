package fi.tkgwf.ruuvi.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.log4j.Logger;

public abstract class Config {

    private static final Logger LOG = Logger.getLogger(Config.class);

    private static String influxUrl;
    private static long influxUpdateLimit = 9900;
    private static String operationMode = "normal";
    private static Predicate<String> filterMode = (s) -> true;
    private static final Set<String> FILTER_MACS = new HashSet<>();

    static {
        readConfig();
    }

    private static void readConfig() {
        // Defaults..
        String influxUrlBase = "http://localhost:8086";
        String influxDatabase = "ruuvi";
        String influxUser = "ruuvi";
        String influxPassword = "ruuvi";

        // Read config..
        try {
            File jarLocation = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile();
            File[] configFiles = jarLocation.listFiles(f -> f.isFile() && f.getName().equals("ruuvi-collector.properties"));
            if (configFiles == null || configFiles.length == 0) {
                // look for config files in the parent directory if none found in the current directory, this is useful during development when
                // RuuviCollector can be run from maven target directory directly while the config file sits in the project root
                configFiles = jarLocation.getParentFile().listFiles(f -> f.isFile() && f.getName().equals("ruuvi-collector.properties"));
            }
            if (configFiles != null && configFiles.length > 0) {
                LOG.debug("Config: " + configFiles[0]);
                Properties props = new Properties();
                props.load(new FileInputStream(configFiles[0]));
                Enumeration<?> e = props.propertyNames();
                while (e.hasMoreElements()) {
                    String key = (String) e.nextElement();
                    String value = props.getProperty(key);
                    switch (key) {
                        case "influxUrlBase":
                            influxUrlBase = value;
                            break;
                        case "influxDatabase":
                            influxDatabase = value;
                            break;
                        case "influxUser":
                            influxUser = value;
                            break;
                        case "influxPassword":
                            influxPassword = value;
                            break;
                        case "influxUpdateLimit":
                            try {
                                influxUpdateLimit = Long.parseLong(value);
                            } catch (NumberFormatException ex) {
                                LOG.warn("Malformed number format for influxUpdateLimit: '" + value + '\'');
                            }
                            break;
                        case "operationMode":
                            operationMode = value;
                            break;
                        case "filter.mode":
                            switch (value) {
                                case "blacklist":
                                    filterMode = (s) -> !FILTER_MACS.contains(s);
                                    break;
                                case "whitelist":
                                    filterMode = FILTER_MACS::contains;
                            }
                            break;
                        case "filter.macs":
                            Arrays.stream(value.split(","))
                                    .map(String::trim)
                                    .filter(s -> s.length() == 12)
                                    .forEach(FILTER_MACS::add);
                            break;
                    }
                }
            }
        } catch (URISyntaxException | IOException ex) {
            LOG.warn("Failed to read configuration, using default values...", ex);
        }

        influxUrl = String.format("%s/write?db=%s&u=%s&p=%s", influxUrlBase, influxDatabase, influxUser, influxPassword);
    }

    public static boolean isDryrunMode() {
        return operationMode.equals("dryrun");
    }

    public static String getInfluxUrl() {
        return influxUrl;
    }

    public static long getInfluxUpdateLimit() {
        return influxUpdateLimit;
    }

    public static boolean isAllowedMAC(String mac) {
        return filterMode.test(mac);
    }
}
