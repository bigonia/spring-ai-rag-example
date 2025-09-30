package com.zwbd.dbcrawlerv4.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author: wnli
 * @Date: 2025/9/16 9:11
 * @Desc:
 */
@Configuration
public class CommonConfig {

    @Bean
    ExecutorService init(){
        return Executors.newFixedThreadPool(10);
    }

}
