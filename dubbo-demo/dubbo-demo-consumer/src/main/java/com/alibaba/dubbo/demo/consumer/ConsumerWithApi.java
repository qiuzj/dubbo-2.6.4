/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.demo.DemoService;

public class ConsumerWithApi {

    public static void main(String[] args) {
    	// 当前应用配置
    	ApplicationConfig application = new ApplicationConfig();
    	application.setName("yyy");
    	 
    	// 连接注册中心配置
    	RegistryConfig registry = new RegistryConfig();
    	registry.setAddress("224.5.6.7:1234");
//    	registry.setUsername("aaa");
//    	registry.setPassword("bbb");
    	 
    	// 注意：ReferenceConfig为重对象，内部封装了与注册中心的连接，以及与服务提供方的连接
    	 
    	// 引用远程服务
    	ReferenceConfig<DemoService> reference = new ReferenceConfig<DemoService>(); // 此实例很重，封装了与注册中心的连接以及与提供者的连接，请自行缓存，否则可能造成内存和连接泄漏
    	reference.setApplication(application);
    	reference.setRegistry(registry); // 多个注册中心可以用setRegistries()
    	reference.setInterface(DemoService.class);
    	reference.setVersion("1.0.0");
    	 
    	// 和本地bean一样使用DemoService
    	DemoService demoService = reference.get(); // 注意：此代理对象内部封装了所有通讯细节，对象较重，请缓存复用

        while (true) {
            try {
                Thread.sleep(1000);
                String hello = demoService.sayHello("world"); // call remote method
                System.out.println(hello); // get result

            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }


        }

    }
}
