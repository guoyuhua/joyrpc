package io.joyrpc.example.boot;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.example.service.AsyncDemoService;
import io.joyrpc.exception.NoAliveProviderException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class BootAsyncClient {

    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "async-client");
        ConfigurableApplicationContext run = SpringApplication.run(BootAsyncClient.class, args);
        AsyncDemoService consumer = run.getBean(AsyncDemoService.class);
        while (true) {
            try {
                System.out.println(consumer.sayHello("helloWold").get());
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ex) {
                }
                if (e instanceof NoAliveProviderException) {
                    System.out.println(e.getMessage());
                } else {
                    e.printStackTrace();
                }
            }
        }
    }
}

