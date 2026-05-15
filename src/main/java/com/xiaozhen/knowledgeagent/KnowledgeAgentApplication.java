package com.xiaozhen.knowledgeagent;

import com.xiaozhen.knowledgeagent.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class KnowledgeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeAgentApplication.class, args);
    }

}
