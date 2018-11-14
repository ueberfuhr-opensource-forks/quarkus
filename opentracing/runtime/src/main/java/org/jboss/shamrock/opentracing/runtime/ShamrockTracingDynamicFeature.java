package org.jboss.shamrock.opentracing.runtime;


import java.util.Optional;
import java.util.logging.Logger;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.CDI;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;

import io.opentracing.Tracer;
import io.opentracing.contrib.jaxrs2.server.OperationNameProvider;
import io.opentracing.contrib.jaxrs2.server.ServerTracingDynamicFeature;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

@Provider
public class ShamrockTracingDynamicFeature implements DynamicFeature {

    private static final Logger logger = Logger.getLogger(io.smallrye.opentracing.SmallRyeTracingDynamicFeature.class.getName());

    private final ServerTracingDynamicFeature delegate;

    public ShamrockTracingDynamicFeature() {
        //Instance<Tracer> tracerInstance = CDI.current().select(Tracer.class);
        Config config = ConfigProvider.getConfig();
        Optional<String> skipPattern = config.getOptionalValue("mp.opentracing.server.skip-pattern", String.class);
        Optional<String> operationNameProvider = config.getOptionalValue("mp.opentracing.server.operation-name-provider", String.class);

        ServerTracingDynamicFeature.Builder builder = new ServerTracingDynamicFeature.Builder(new ShamrockTracer())
                .withOperationNameProvider(OperationNameProvider.ClassNameOperationName.newBuilder())
                .withTraceSerialization(false);
        if (skipPattern.isPresent()) {
            builder.withSkipPattern(skipPattern.get());
        }
        if (operationNameProvider.isPresent()) {
            if ("http-path".equalsIgnoreCase(operationNameProvider.get())) {
                builder.withOperationNameProvider(OperationNameProvider.WildcardOperationName.newBuilder());
            } else if (!"class-method".equalsIgnoreCase(operationNameProvider.get())) {
                logger.warning("Provided operation name does not match http-path or class-method. Using default class-method.");
            }
        }
        this.delegate = builder.build();
    }

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        this.delegate.configure(resourceInfo, context);
    }
}
