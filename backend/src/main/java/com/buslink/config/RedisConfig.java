package com.buslink.config;

import com.buslink.dto.response.FareRateDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofHours(1));

        JavaType routeStopListType = TypeFactory.createDefaultInstance()
                .constructCollectionType(List.class, RouteStopResponseDTO.class);

        RedisCacheConfiguration routeStopsConfig = defaultConfig.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<List<RouteStopResponseDTO>>(routeStopListType)
                )
        );

        RedisCacheConfiguration fareCalcConfig = defaultConfig.serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(FareRateDTO.class)
                )
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(Map.of(
                        "route-stops", routeStopsConfig,
                        "fare-calc", fareCalcConfig
                ))
                .build();
    }
}
