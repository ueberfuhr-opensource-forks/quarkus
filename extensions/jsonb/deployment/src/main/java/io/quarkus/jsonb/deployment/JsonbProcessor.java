package io.quarkus.jsonb.deployment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import javax.inject.Singleton;
import javax.json.bind.JsonbConfig;
import javax.json.bind.adapter.JsonbAdapter;
import javax.json.bind.serializer.JsonbDeserializer;
import javax.json.bind.serializer.JsonbSerializer;

import org.eclipse.yasson.JsonBindingProvider;
import org.eclipse.yasson.spi.JsonbComponentInstanceCreator;
import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.substrate.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.substrate.ServiceProviderBuildItem;
import io.quarkus.deployment.builditem.substrate.SubstrateResourceBundleBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.jsonb.JsonbConfigCustomizer;
import io.quarkus.jsonb.JsonbProducer;
import io.quarkus.jsonb.QuarkusJsonbComponentInstanceCreator;
import io.quarkus.jsonb.spi.JsonbDeserializerBuildItem;
import io.quarkus.jsonb.spi.JsonbSerializerBuildItem;

public class JsonbProcessor {

    static final DotName JSONB_ADAPTER_NAME = DotName.createSimple(JsonbAdapter.class.getName());

    @BuildStep
    void build(BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<SubstrateResourceBundleBuildItem> resourceBundle,
            BuildProducer<ServiceProviderBuildItem> serviceProvider,
            BuildProducer<AdditionalBeanBuildItem> additionalBeans) {
        reflectiveClass.produce(new ReflectiveClassBuildItem(false, false,
                JsonBindingProvider.class.getName()));

        resourceBundle.produce(new SubstrateResourceBundleBuildItem("yasson-messages"));

        serviceProvider.produce(new ServiceProviderBuildItem(JsonbComponentInstanceCreator.class.getName(),
                QuarkusJsonbComponentInstanceCreator.class.getName()));

        // this needs to be registered manually since the runtime module is not indexed by Jandex
        additionalBeans.produce(new AdditionalBeanBuildItem(JsonbProducer.class));
    }

    @BuildStep
    void unremovableJsonbAdapters(BuildProducer<UnremovableBeanBuildItem> unremovableBeans,
            BeanArchiveIndexBuildItem beanArchiveIndex) {
        unremovableBeans.produce(new UnremovableBeanBuildItem(new Predicate<BeanInfo>() {

            @Override
            public boolean test(BeanInfo bean) {
                return bean.isClassBean() && bean.hasType(JSONB_ADAPTER_NAME);
            }
        }));
    }

    // Generate a JsonbConfigCustomizer bean that registers each serializer / deserializer with JsonbConfig
    @BuildStep
    void generateCustomizer(BuildProducer<GeneratedBeanBuildItem> generatedBeans,
            List<JsonbSerializerBuildItem> serializers,
            List<JsonbDeserializerBuildItem> deserializers) {

        if (serializers.isEmpty()) {
            return;
        }

        final Set<String> customSerializerClasses = new HashSet<>();
        final Set<String> customDeserializerClasses = new HashSet<>();
        for (JsonbSerializerBuildItem serializer : serializers) {
            customSerializerClasses.addAll(serializer.getSerializerClassNames());
        }
        for (JsonbDeserializerBuildItem deserializer : deserializers) {
            customDeserializerClasses.addAll(deserializer.getDeserializerClassNames());
        }
        if (customSerializerClasses.isEmpty() && customDeserializerClasses.isEmpty()) {
            return;
        }

        ClassOutput classOutput = new ClassOutput() {
            @Override
            public void write(String name, byte[] data) {
                generatedBeans.produce(new GeneratedBeanBuildItem(name, data));
            }
        };

        try (ClassCreator classCreator = ClassCreator.builder().classOutput(classOutput)
                .className("io.quarkus.jsonb.customizer.RegisterSerializersAndDeserializersCustomizer")
                .interfaces(JsonbConfigCustomizer.class.getName())
                .build()) {
            classCreator.addAnnotation(Singleton.class);

            try (MethodCreator customize = classCreator.getMethodCreator("customize", void.class, JsonbConfig.class)) {
                ResultHandle jsonbConfig = customize.getMethodParam(0);
                if (!customSerializerClasses.isEmpty()) {
                    ResultHandle serializersArray = customize.newArray(JsonbSerializer.class,
                            customize.load(customSerializerClasses.size()));
                    int i = 0;
                    for (String customSerializerClass : customSerializerClasses) {
                        customize.writeArrayValue(serializersArray, i,
                                customize.newInstance(MethodDescriptor.ofConstructor(customSerializerClass)));
                        i++;
                    }
                    customize.invokeVirtualMethod(
                            MethodDescriptor.ofMethod(JsonbConfig.class, "withSerializers", JsonbConfig.class,
                                    JsonbSerializer[].class),
                            jsonbConfig, serializersArray);
                }
                if (!customDeserializerClasses.isEmpty()) {
                    ResultHandle deserializersArray = customize.newArray(JsonbDeserializer.class,
                            customize.load(customDeserializerClasses.size()));
                    int i = 0;
                    for (String customDeserializerClass : customDeserializerClasses) {
                        customize.writeArrayValue(deserializersArray, i,
                                customize.newInstance(MethodDescriptor.ofConstructor(customDeserializerClass)));
                        i++;
                    }
                    customize.invokeVirtualMethod(
                            MethodDescriptor.ofMethod(JsonbConfig.class, "withDeserializers", JsonbConfig.class,
                                    JsonbDeserializer[].class),
                            jsonbConfig, deserializersArray);
                }

                customize.returnValue(null);
            }
        }
    }

}
