package dev.mcrashcraft.namelessforge;

import com.namelessmc.plugin.common.ConfigurationHandler;
import com.namelessmc.plugin.common.logger.AbstractLogger;
import org.slf4j.Logger;
import java.util.function.Supplier;

public final class ForgeLogger extends AbstractLogger {
    private final Logger logger;
    private final ConfigurationHandler config;

    public ForgeLogger(ConfigurationHandler config, Logger logger) {
        super(config);
        this.config = config;
        this.logger = logger;
    }

    private String redact(String text) {
        try {
            String key = config.main().node("api", "key").getString();
            if (key != null && !key.isBlank()) return text.replace(key, "[private API key]");
        } catch (IllegalStateException ignored) { /* Configuration is not loaded yet. */ }
        return text;
    }

    @Override public void info(String text) { logger.info(redact(text)); }
    @Override public void warning(String text) { logger.warn(redact(text)); }
    @Override public void severe(String text) { logger.error(redact(text)); }
    @Override public void info(Supplier<String> text) { if (logger.isInfoEnabled()) info(text.get()); }
    @Override public void warning(Supplier<String> text) { if (logger.isWarnEnabled()) warning(text.get()); }
    @Override public void severe(Supplier<String> text) { if (logger.isErrorEnabled()) severe(text.get()); }
}
