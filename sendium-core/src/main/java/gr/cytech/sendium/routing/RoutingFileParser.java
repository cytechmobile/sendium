package gr.cytech.sendium.routing;

import com.google.common.base.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RoutingFileParser {
    public static final String DEFAULT_ROUTING_TABLE_NAME = "default";

    public static Map<String, RoutingTable> loadAndParse(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            return new HashMap<>();
        }
        List<String> lines = Files.readAllLines(filePath);
        return parseRoutingTable(lines);
    }

    public static Map<String, RoutingTable> parseRoutingTable(List<String> lines) {
        Matcher chainDfn = Pattern.compile("\\[([^\\[\\]:]*)\\]").matcher("");
        Matcher routeDfn = Pattern.compile("([^:]*):([^:]*):([^:]*):(.*)").matcher("");

        Map<String, RoutingTable> routes = new HashMap<>();
        String currentRoutingTable = DEFAULT_ROUTING_TABLE_NAME;
        routes.put(currentRoutingTable, new RoutingTable(currentRoutingTable, RoutingTable.TargetFunction.NORMAL));

        String currentComment = null;
        for (String line : lines) {
            if (Strings.isNullOrEmpty(line)) {
                continue;
            }
            if (line.startsWith("#")) {
                line = line.substring(1);
                if (!Strings.isNullOrEmpty(currentComment)) {
                    currentComment = currentComment + "\n" + line;
                } else {
                    currentComment = line;
                }
            } else if (chainDfn.reset(line).matches()) {
                currentRoutingTable = chainDfn.group(1);
                RoutingTable rt = parseGroupName(currentRoutingTable);
                rt.setLabel(currentComment);
                currentRoutingTable = rt.getName();

                if (!routes.containsKey(currentRoutingTable)) {
                    routes.put(currentRoutingTable, rt);
                } else if (!Strings.isNullOrEmpty(currentComment)) {
                    routes.get(currentRoutingTable).setLabel(currentComment);
                }
                currentComment = null;
            } else if (routeDfn.reset(line).matches()) {
                routes.get(currentRoutingTable).getRules().add(new RoutingRule(line, currentComment));
                currentComment = null;
            }
        }
        return routes;
    }

    public static RoutingTable parseGroupName(String groupName) {
        if (groupName.contains("->function(")) {
            String rtFunc = groupName;
            groupName = groupName.substring(0, groupName.indexOf("->"));
            String fn = rtFunc.substring(
                    rtFunc.indexOf("->function(") + "->function(".length(),
                    rtFunc.lastIndexOf(")"));
            return new RoutingTable(groupName, RoutingTable.TargetFunction.fromName(fn));
        }
        return new RoutingTable(groupName, RoutingTable.TargetFunction.NORMAL);
    }
}
