package gr.cytech.sendium.conf;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collections;
import java.util.OptionalInt;

public class DynamicConfigSourceFactory implements ConfigSourceFactory {
    public static final int ORDINAL = 600;
    private static final Logger logger = LoggerFactory.getLogger(DynamicConfigSourceFactory.class);

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        String filePath = context.getValue("smsg.properties.file.path").getValue();

        if (filePath == null) {
            return Collections.emptyList();
        }

        File file = new File(filePath);
        if (file.exists() && file.isFile()) {
            try {
                return Collections.singletonList(
                        new PropertiesConfigSource(file.toURI().toURL(), ORDINAL));
            } catch (Exception e) {
                logger.error("Failed to load smsg.properties", e);
            }
        }

        return Collections.emptyList();
    }

    @Override
    public OptionalInt getPriority() {
        return OptionalInt.of(ORDINAL);
    }
}
