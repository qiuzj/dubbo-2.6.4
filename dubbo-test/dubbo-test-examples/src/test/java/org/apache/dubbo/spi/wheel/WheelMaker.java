package org.apache.dubbo.spi.wheel;

import com.alibaba.dubbo.common.URL;

public interface WheelMaker {
    Wheel makeWheel(URL url);
}