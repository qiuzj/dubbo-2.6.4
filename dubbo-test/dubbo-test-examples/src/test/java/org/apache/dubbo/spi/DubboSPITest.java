package org.apache.dubbo.spi;

import org.junit.Test;

import com.alibaba.dubbo.common.extension.ExtensionLoader;

public class DubboSPITest {

	@Test
	public void sayHello() throws Exception {
		ExtensionLoader<Robot> extensionLoader = ExtensionLoader.getExtensionLoader(Robot.class);
		Robot optimusPrime = extensionLoader.getExtension("optimusPrime");
		optimusPrime.sayHello();
		Robot bumblebee = extensionLoader.getExtension("bumblebee");
		bumblebee.sayHello();
	}
}