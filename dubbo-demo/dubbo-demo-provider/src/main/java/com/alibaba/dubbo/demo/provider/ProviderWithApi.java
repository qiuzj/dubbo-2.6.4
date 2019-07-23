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
package com.alibaba.dubbo.demo.provider;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.demo.DemoService;

public class ProviderWithApi {

    public static void main(String[] args) throws Exception {
    	// 服务实现
    	DemoService demoService = new DemoServiceImpl();
    	 
    	// 当前应用配置
    	ApplicationConfig application = new ApplicationConfig();
    	application.setName("demo-provider");
    	 
    	// 连接注册中心配置
    	RegistryConfig registry = new RegistryConfig();
    	registry.setAddress("224.5.6.7:1234");
//    	registry.setUsername("aaa");
//    	registry.setPassword("bbb");
    	 
    	// 服务提供者协议配置
    	ProtocolConfig protocol = new ProtocolConfig();
    	protocol.setName("dubbo");
    	protocol.setPort(20880);
//    	protocol.setThreads(200);
    	 
    	// 注意：ServiceConfig为重对象，内部封装了与注册中心的连接，以及开启服务端口
    	 
    	// 服务提供者暴露服务配置
    	ServiceConfig<DemoService> service = new ServiceConfig<DemoService>(); // 此实例很重，封装了与注册中心的连接，请自行缓存，否则可能造成内存和连接泄漏
    	service.setApplication(application);
    	service.setRegistry(registry); // 多个注册中心可以用setRegistries()
    	service.setProtocol(protocol); // 多个协议可以用setProtocols()
    	service.setInterface(DemoService.class);
    	service.setRef(demoService);
    	service.setVersion("1.0.0");
    	 
    	// 暴露及注册服务
    	service.export();

//        System.in.read(); // press any key to exit
    }

}
