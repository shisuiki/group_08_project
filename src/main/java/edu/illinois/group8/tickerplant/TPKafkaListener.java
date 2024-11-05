package edu.illinois.group8.tickerplant;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public class TPKafkaListener implements Runnable {

    private final int port;
    private final TPClientHandler clientHandler;
    public static final String INPUT_TOPIC = "market-data";

    public TPKafkaListener(int port, TPClientHandler clientHandler) {
        this.port = port;
        this.clientHandler = clientHandler;
    }
    
    @Override
    public void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + port);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ticker-plant");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(INPUT_TOPIC));

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf("Consumed message: %s%n", record.value());
                    clientHandler.sendDataToClients(record.value());
                }
            }
        }
    }
}
