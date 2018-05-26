/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.zeelos.leshan.kafka.streams;

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.zeelos.leshan.avro.AvroKey;
import io.zeelos.leshan.avro.response.AvroResponseObserve;
import io.zeelos.leshan.kafka.streams.utils.LeshanTimestampExtractor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static io.zeelos.leshan.kafka.streams.utils.Utils.getResource;
import static io.zeelos.leshan.kafka.streams.utils.Utils.sslProperties;

public class SimpleAnalyticsStreamsApp {

    public static void main(String[] args) {
        final String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        final String schemaRegistryUrl = args.length > 1 ? args[1] : "http://localhost:8081";
        final String observationsTopic = args.length > 2 ? args[2] : "iot.server1.observations";
        final String sslConfigFile = args.length > 3 ? args[3] : "client_security.properties";

        final KafkaStreams streams = buildStream(
                bootstrapServers,
                schemaRegistryUrl,
                "/tmp/kafka-streams",
                observationsTopic,
                sslProperties(sslConfigFile));

        streams.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    private static KafkaStreams buildStream(String bootstrapServers, String schemaRegistryUrl, String stateDir, String observationsTopic, Properties sslProps) {
        final Properties config = new Properties();
        // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
        // against which the application is run.
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "leshan-simple-analytics");
        // Where to find Kafka broker(s).
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Where to find the Confluent schema registry instance(s)
        config.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        // Specify default (de)serializers for record keys and for record values.
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);

        config.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Records should be flushed every 10 seconds. This is less than the default
        // in order to keep this example interactive.
        config.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10 * 1000);
        config.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG, LeshanTimestampExtractor.class);

        // set kafka ssl props (if any)
        config.putAll(sslProps);

        final StreamsBuilder builder = new StreamsBuilder();

        // read the source stream
        final KStream<AvroKey, AvroResponseObserve> readings = builder.stream(observationsTopic);

        // aggregate sensor readings by 'endpoint' id
        final KStream<Windowed<AvroKey>, Long> aggregatedReadingsByEP = readings
                .groupByKey()
                .windowedBy(TimeWindows.of(TimeUnit.MINUTES.toMillis(1)))
                .count(Materialized.as("aggr-ep"))
                .toStream();

        aggregatedReadingsByEP.print(Printed.toSysOut());

        // aggregate sensor readings by 'endpoint' id and 'path'
        final KStream<Windowed<AvroKey>, Long> aggregatedReadingByEPAndPath = readings
                // postfix endpoint key with resource path
                .selectKey((key, reading) -> {
                    key.setEp(String.format("%s.%s", key.getEp(),
                            getResource(reading).getPath()));
                    return key;
                })
                .groupByKey()
                .windowedBy(TimeWindows.of(TimeUnit.MINUTES.toMillis(1)))
                .count(Materialized.as("aggr-ep-path"))
                .toStream();

        aggregatedReadingByEPAndPath.print(Printed.toSysOut());

        return new KafkaStreams(builder.build(), new StreamsConfig(config));
    }
}