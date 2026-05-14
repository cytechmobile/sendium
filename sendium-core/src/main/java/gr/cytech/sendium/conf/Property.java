package gr.cytech.sendium.conf;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record Property(String key, String value, String label) {

    public static Comparator<Property> comparatorByKey() {
        return Property::compareKeys;
    }

    public static Comparator<String> comparatorForKeys() {
        return Property::compareKeys;
    }

    public static int compareKeys(Property p1, Property p2) {
        return compareKeys(p1.key, p2.key);
    }

    public static int compareKeys(String k1, String k2) {
        var k1Parts = Strings.nullToEmpty(k1).split("\\.");
        var k2Parts = Strings.nullToEmpty(k2).split("\\.");

        // first try to order by level on param nesting
        if (k1Parts.length != k2Parts.length) {
            return k1Parts.length < k2Parts.length ? -1 : 1;
        }

        // if we have params with different type of nesting, then order them by parent lexicographically
        if (k1Parts.length > 1 && k2Parts.length > 1 && !k1Parts[k1Parts.length - 2].equals(k2Parts[k2Parts.length - 2])) {
            return k1Parts[k1Parts.length - 2].compareTo(k2Parts[k2Parts.length - 2]);
        }

        // give higher priority to specific props
        var priorityProps = List.of("enable", "class", "pause", "username", "password", "host", "port");
        var k1Idx = priorityProps.indexOf(k1Parts[k1Parts.length - 1]);
        var k2Idx = priorityProps.indexOf(k2Parts[k2Parts.length - 1]);
        if (k1Idx > -1 && k2Idx > -1) {
            return k1Idx < k2Idx ? -1 : 1;
        } else if (k1Idx > -1) {
            return -1;
        } else if (k2Idx > -1) {
            return 1;
        }

        return Strings.nullToEmpty(k1).compareTo(Strings.nullToEmpty(k2));
    }

    public static Collection<? extends Collection<String>> sortKeys(Collection<String> keys) {
        Set<String> confs = new TreeSet<>();
        Map<String, Set<String>> restApis = new LinkedHashMap<>();
        Map<String, Set<String>> servlets = new LinkedHashMap<>();
        Map<String, Set<String>> inWorkers = new LinkedHashMap<>();
        Map<String, Set<String>> outWorkers = new TreeMap<>();
        Map<String, Set<String>> resources = new TreeMap<>();
        Map<String, Set<String>> templates = new LinkedHashMap<>();
        for (String key : keys) {
            Map<String, Set<String>> container;
            String prefix;
            if (key.startsWith("outSms.instance.")) {
                prefix = "outSms.instance.";
                container = outWorkers;
            } else if (key.startsWith("inSms.restapi.")) {
                prefix = "inSms.restapi.";
                container = restApis;
            } else if (key.startsWith("inSms.servlet.")) {
                prefix = "inSms.servlet.";
                container = servlets;
            } else if (key.startsWith("inSms.worker.")) {
                prefix = "inSms.worker.";
                container = inWorkers;
            } else if (key.startsWith("res.")) {
                prefix = "res.";
                container = resources;
            } else if (key.startsWith("template.")) {
                prefix = "template.";
                container = templates;
            } else if (key.startsWith("conf.") || key.startsWith("routing") || key.startsWith("smsg.") || key.startsWith("processor.") ||
                    key.startsWith("outSms.default.")) {
                confs.add(key);
                continue;
            } else {
                //ignore everything else
                continue;
            }
            String name = extractInstanceName(prefix, key);
            container.computeIfAbsent(name, k -> new TreeSet<>(Property::compareKeys)).add(key);
        }
        List<Set<String>> result = new ArrayList<>();
        if (keys.contains("outSms.pause")) {
            result.add(Collections.singleton("outSms.pause"));
        }

        result.add(confs);
        result.addAll(restApis.values());
        result.addAll(servlets.values());
        result.addAll(inWorkers.values());
        result.addAll(outWorkers.values());
        result.addAll(resources.values());
        result.addAll(templates.values());

        return result;
    }

    public static String extractInstanceName(String prefix, String key) {
        String instanceAndParamName = key.substring(prefix.length());
        if (!instanceAndParamName.contains(".")) {
            return instanceAndParamName;
        }
        return instanceAndParamName.substring(0, instanceAndParamName.indexOf("."));
    }

    public static List<Property> sortByKey(Map<String, Property> props) {
        return sortKeys(props.keySet()).stream().flatMap(Collection::stream).map(props::get).toList();
    }
}