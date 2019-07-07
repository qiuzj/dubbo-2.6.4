package org.apache.dubbo.spi.wheel;

import com.alibaba.dubbo.common.URL;

public interface CarMaker {
    Car makeCar(URL url);
}