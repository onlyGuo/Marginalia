package ink.icoding.marginalia.autoconfigure.config;

import ink.icoding.marginalia.core.service.MarginaliaService;
import ink.icoding.marginalia.autoconfigure.controller.WebController;
import ink.icoding.marginalia.autoconfigure.debugger.DebuggerService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "marginalia", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MarginaliaProperties.class)
public class MarginaliaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MarginaliaService marginaliaService(MarginaliaProperties properties) {
        MarginaliaService service = new MarginaliaService(
                properties.getDataDir(),
                properties.getBasePackage(),
                properties.getSourceDirs()
        );

        if (properties.isAutoScan()) {
            // Scan on startup
            service.scanAndMerge();
        }

        return service;
    }

    @Bean
    @ConditionalOnMissingBean
    public DebuggerService debuggerService(MarginaliaProperties properties) {
        return new DebuggerService();
    }

    @Bean
    @ConditionalOnMissingBean
    public WebController marginaliaWebController(MarginaliaService service,
                                                   DebuggerService debuggerService,
                                                   MarginaliaProperties properties) {
        return new WebController(service, debuggerService, properties);
    }

    @Configuration
    static class MarginaliaWebMvcConfig implements WebMvcConfigurer {

        private final MarginaliaProperties properties;

        MarginaliaWebMvcConfig(MarginaliaProperties properties) {
            this.properties = properties;
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            String prefix = properties.getPrefix();
            if (!prefix.endsWith("/")) prefix += "/";

            registry.addResourceHandler(prefix + "**")
                    .addResourceLocations("classpath:/static/marginalia/");
        }
    }
}
