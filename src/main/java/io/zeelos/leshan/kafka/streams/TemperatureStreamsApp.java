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

import static io.zeelos.leshan.kafka.streams.utils.Utils.getResource;
import static io.zeelos.leshan.kafka.streams.utils.Utils.getValue;
import static io.zeelos.leshan.kafka.streams.utils.Utils.sslProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindowedDeserializer;
import org.apache.kafka.streams.kstream.TimeWindowedSerializer;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.zeelos.leshan.avro.AvroKey;
import io.zeelos.leshan.avro.resource.AvroResource;
import io.zeelos.leshan.avro.response.AvroResponseObserve;
import io.zeelos.leshan.kafka.streams.utils.LeshanTimestampExtractor;

public class TemperatureStreamsApp {
    public static void main(String[] args) {
        final String bootstrapServers = args.length > 0 ? args[0] : "localhost:9092";
        final String schemaRegistryUrl = args.length > 1 ? args[1] : "http://localhost:8081";
        final String observationsTopic = args.length > 2 ? args[2] : "iot.server1.observations";
        final String observationsAnalyticTopic = args.length > 3 ? args[3] : "analytics.server1.observations.maxper30sec";
        final String stateDir = args.length > 4 ? args[4] : "/tmp/kafka-streams-leshan";
        final String sslConfigFile = args.length > 5 ? args[5] : "client_security.properties";

        final KafkaStreams streams = buildStream(
                bootstrapServers,
                schemaRegistryUrl,
                stateDir,
                observationsTopic,
                observationsAnalyticTopic,
                sslProperties(sslConfigFile));

        streams.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

    private static KafkaStreams buildStream(String bootstrapServers, String schemaRegistryUrl, String stateDir,
                                            String observationsTopic, String observationsAnalyticTopic,
                                            Properties sslProps) {
        final Properties config = new Properties();
        // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
        // against which the application is run.
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "leshan-maxper30sec-analytic");
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

        final Map<String, String> serdeAvroConfig = Collections.singletonMap(
                AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);

        final SpecificAvroSerde<AvroKey> serdeAvroKey = new SpecificAvroSerde<>();
        serdeAvroKey.configure(serdeAvroConfig, true);
        final SpecificAvroSerde<AvroResponseObserve> serdeAvroResponse = new SpecificAvroSerde<>();
        serdeAvroResponse.configure(serdeAvroConfig, false);

        final TimeWindowedSerializer<AvroKey> windowedSerializer = new TimeWindowedSerializer<>(serdeAvroKey.serializer());
        final TimeWindowedDeserializer<AvroKey> windowedDeserializer = new TimeWindowedDeserializer<>(serdeAvroKey.deserializer());
        final Serde<Windowed<AvroKey>> serdeWindowed = Serdes.serdeFrom(windowedSerializer, windowedDeserializer);
        serdeWindowed.configure(serdeAvroConfig, true);

        final StreamsBuilder builder = new StreamsBuilder();

        // read the source stream
        final KStream<AvroKey, AvroResponseObserve> readings = builder.stream(observationsTopic);

        // calculate max sensor reading by endpoint and path over a period of 30 secs 
        final KStream<Windowed<AvroKey>, AvroResponseObserve> maxReadingByEPAndPath = readings
                .map((key, reading) -> {
                    AvroResource resource = getResource(reading);

                    String path = resource.getPath();
                    // postfix endpoint key with resource path
                    key.setEp(String.format("%s.%s", key.getEp(), path));
                    // postfix resource path with "-max"
                    String maxPath = String.format("%s-%s", path, "max");
                    reading.setPath(maxPath);
                    resource.setPath(maxPath);

                    return new KeyValue<>(key, reading);
                })
                .groupByKey()
                .windowedBy(TimeWindows.of(Duration.ofSeconds((30))))
                .reduce((response1, response2) ->
                                getValue(response1) > getValue(response2) ? response1 : response2
                        , Materialized.as("max"))
                .toStream();

        maxReadingByEPAndPath.to(observationsAnalyticTopic, Produced.with(serdeWindowed, serdeAvroResponse));

        return new KafkaStreams(builder.build(), config);
    }
}
