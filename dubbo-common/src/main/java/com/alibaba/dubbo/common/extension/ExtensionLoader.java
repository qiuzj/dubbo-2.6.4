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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 每个拓展接口对应一个ExtensionLoader.
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    /**
     * 全局Map. 每个SPI接口对应一个ExtensionLoader.
     * {interface com.alibaba.dubbo.common.extension.ExtensionFactory=com.alibaba.dubbo.common.extension.ExtensionLoader[com.alibaba.dubbo.common.extension.ExtensionFactory], 
     * interface org.apache.dubbo.spi.Robot=com.alibaba.dubbo.common.extension.ExtensionLoader[org.apache.dubbo.spi.Robot]}
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();
    /** 
     * 全局Map. Map<拓展实现类的Class对象，拓展实现类的实例对象>.
     * {class org.apache.dubbo.spi.Bumblebee=org.apache.dubbo.spi.Bumblebee@2145433b, 
     * class com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory=com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory@52e677af, 
     * class org.apache.dubbo.spi.OptimusPrime=org.apache.dubbo.spi.OptimusPrime@55a1c291, 
     * class com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory=com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory@35083305}
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================
    /** 拓展SPI接口Class对象 */
    private final Class<?> type;
    /**
     * type为ExtensionFactory时objectFactory为null，其他默认为AdaptiveExtensionFactory.
     * 每个SPI接口，对应一个拓展工厂ExtensionFactory，通过这个工厂，可以获取依赖对象，将对象注入相应的SPI实现类.
     */
    private final ExtensionFactory objectFactory;
    
    /**
     * {class com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory=spring, class com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory=spi}
     * or
     * {class org.apache.dubbo.spi.Bumblebee=bumblebee, class org.apache.dubbo.spi.OptimusPrime=optimusPrime}
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();
    /**
     * 已加载的拓展类缓存. 格式：<拓展类属性名，拓展类Class对象>
     * {spring=class com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory, spi=class com.alibaba.dubbo.common.extension.factory.SpiExtensionFactory}
     * or
     * {optimusPrime=class org.apache.dubbo.spi.OptimusPrime, bumblebee=class org.apache.dubbo.spi.Bumblebee}
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();
    /** 已缓存的激活注解映射对象，格式：<拓展点名称, Activate注解对象>？ */
    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
    /**
     * {optimusPrime=com.alibaba.dubbo.common.utils.Holder@52e677af, bumblebee=com.alibaba.dubbo.common.utils.Holder@482cd91f}
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    
    /** 自适应拓展实例对象缓存：com.alibaba.dubbo.common.extension.factory.AdaptiveExtensionFactory@8e0379d */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    /** 自适应扩展工厂缓存：class com.alibaba.dubbo.common.extension.factory.AdaptiveExtensionFactory */
    private volatile Class<?> cachedAdaptiveClass = null;
    
    /** SPI注解值value.split(",")[0] */
    private String cachedDefaultName;
    
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    private ExtensionLoader(Class<?> type) {
        this.type = type; // SPI接口Class对象
        // type为ExtensionFactory时objectFactory为null，其他默认为AdaptiveExtensionFactory.
        // 首次使用该构造函数时，会初始化type为ExtensionFactory的ExtensionLoader，再初始化当前SPI接口的ExtensionLoader
        // 其他SPI只需要初始化自己的ExtensionLoader
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }
        // 第一次进来EXTENSION_LOADERS集合元素为空，loader为null
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
        	// 每个type对应一个ExtensionLoader
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names. 拓展点名称key
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        // 获取拓展点名称列表，多个以逗号分隔
        String value = url.getParameter(key);
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     * 获取激活的拓展对象列表（Activate激活参数匹配）
     *
     * @param url    url
     * @param values extension point names. 拓展点名称数组
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        // 拓展点名称列表
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        // 如果拓展点名称names不包括"-default"，什么意思？
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            // 加载并缓存拓展类Class对象（如需要）
            getExtensionClasses();
            // 遍历已缓存的激活注解Activate集合
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                String name = entry.getKey(); // 拓展点名称
                Activate activate = entry.getValue(); // 激活注解对象
                // 如果group与注解匹配
                if (isMatchGroup(group, activate.group())) {
                    T ext = getExtension(name); // 根据名称获取拓展对象
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    /**
     * group为null、空字符串，或者groups包含有group，则返回true，否则返回false
     *
     * @param group
     * @param groups
     * @return
     */
    private boolean isMatchGroup(String group, String[] groups) {
        // group为null或空字符串则返回true
        if (group == null || group.length() == 0) {
            return true;
        }
        // groups包含有group，则返回true，否则返回false
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 传入的url参数key是否与activate注解所声明的URL参数key匹配，是则返回true表示激活
     *
     * @param activate
     * @param url
     * @return
     */
    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value(); // 注解声明的URL参数key值数组，匹配时才激活拓展类
        // 没声明匹配条件则默认激活
        if (keys.length == 0) {
            return true;
        }
        // 遍历每一个URL注解key值，然后再遍历入参url的参数key是否与之匹配，匹配则返回true表示激活
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key)) // key相等、或以".$key"结尾
                        && ConfigUtils.isNotEmpty(v)) { // 非null、空字符串、"false"、"0"、"null"或"N/A"
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     * <br>
     * 首先检查缓存，缓存未命中则创建拓展对象
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        if ("true".equals(name)) {
        	// 获取默认的拓展实现类
            return getDefaultExtension();
        }
        // Holder，顾名思义，用于持有目标对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        // 双重检查
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                	// 创建拓展实例
                    instance = createExtension(name);
                    // 设置实例到 holder 中
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
    	// 从缓存中获取自适应拓展
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) { // 缓存未命中
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                        	// 创建自适应拓展对象：com.alibaba.dubbo.common.extension.factory.AdaptiveExtensionFactory@8e0379d
                            instance = createAdaptiveExtension();
                            // 设置自适应拓展到缓存中
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
    	// 从配置文件中加载所有的拓展类，可得到“配置项名称”到“配置类”的映射关系表. 例如：org.apache.dubbo.spi.OptimusPrime
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
            	// 通过反射创建实例，本来就是一个Map实例对象为什么要再多创建一次？
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 向实例中注入依赖
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
            	// 循环创建 Wrapper 实例
                for (Class<?> wrapperClass : wrapperClasses) {
                	// 将当前 instance 作为参数传给 Wrapper 的构造方法，并通过反射创建 Wrapper 实例。
                    // 然后向 Wrapper 实例中注入依赖，最后将 Wrapper 实例再次赋值给 instance 变量
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * Dubbo IOC 是通过 setter 方法注入依赖。
     * Dubbo 首先会通过反射获取到实例的所有方法，然后再遍历方法列表，检测方法名是否具有 setter 方法特征。
     * 若有，则通过 ObjectFactory 获取依赖对象，最后通过反射调用 setter 方法将依赖设置到目标对象中。
     * 
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
            	// 遍历目标类的所有方法
                for (Method method : instance.getClass().getMethods()) {
                	// 检测方法是否以 set 开头，且方法仅有一个参数，且方法访问级别为 public
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                    	// 获取 setter 方法参数类型
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                        	// 获取属性名，比如 setName 方法对应属性名 name
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            // 从 ObjectFactory 中获取依赖对象. pt:字段类型，property:字段名称。通过SPI或Spring获取依赖对象
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                            	// 通过反射调用 setter 方法设置依赖
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    /**
     * 获取所有的拓展类. Map<String, Class<?>>：<拓展类属性名，拓展类Class对象>
     * 
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {
    	// 从缓存中获取已加载的拓展类，格式：<拓展类属性名，拓展类Class对象>
        Map<String, Class<?>> classes = cachedClasses.get();
        // 双重检查
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                	// 加载拓展类
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 一是对 SPI 注解进行解析，二是调用 loadDirectory 方法加载指定文件夹配置文件。
     * 
     * @return
     */
    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
    	// 获取 SPI 注解，这里的 type 变量是在调用 getExtensionLoader 方法时传入的
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
            	// 对 SPI 注解内容进行切分
                String[] names = NAME_SEPARATOR.split(value);
                // 检测 SPI 注解内容是否合法，不合法则抛出异常
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                // 设置默认名称，参考 getDefaultExtension 方法
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }

        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        // 加载指定文件夹下的配置文件，尝试从不同目录去加载扩展
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        loadDirectory(extensionClasses, DUBBO_DIRECTORY);
        loadDirectory(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * 先通过 classLoader 获取所有资源链接，然后再通过 loadResource 方法加载资源
     * 
     * @param extensionClasses
     * @param dir
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir) {
    	// fileName = 文件夹路径 + type全限定名
        String fileName = dir + type.getName();
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            // 根据文件名加载所有的同名文件
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    System.out.println(String.format("[loadDirectory] fileName = dir + type.getName():%s, resourceURL:%s", fileName, resourceURL));
                    // 加载资源
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 用于读取和解析配置文件，并通过反射加载类，最后调用 loadClass 方法进行其他操作
     * 
     * @param extensionClasses
     * @param classLoader
     * @param resourceURL
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                // 按行读取配置内容
                while ((line = reader.readLine()) != null) {
                	System.out.println(String.format("[loadResource] resourceURL:%s, line:%s", resourceURL, line));
                	// 定位 # 字符
                    final int ci = line.indexOf('#');
                    // 截取 # 之前的字符串，# 之后的内容为注释，需要忽略
                    if (ci >= 0) line = line.substring(0, ci);
                    line = line.trim();
                    if (line.length() > 0) {
                    	// 处理非空行、非注释行
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                            	// 以等于号 = 为界，截取键与值
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                            	// 加载类，并通过 loadClass方法对类进行缓存
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * 主要用于操作不同的缓存，比如 cachedAdaptiveClass、cachedWrapperClasses 和 cachedNames 等等。
     * 除此之外，该方法没有其他什么逻辑了。
     * 
     * @param extensionClasses
     * @param resourceURL
     * @param clazz
     * @param name
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }
        // 检测目标类上是否有 Adaptive注解
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            if (cachedAdaptiveClass == null) {
            	// 设置 cachedAdaptiveClass缓存
                cachedAdaptiveClass = clazz;
                System.out.println(String.format("[loadClass clazz.isAnnotationPresent(Adaptive.class)] resourceURL:%s, clazz:%s", resourceURL, clazz.toString()));
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }
        // 检测 clazz 是否是 Wrapper类型
        } else if (isWrapperClass(clazz)) {
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }
            // 存储 clazz 到 cachedWrapperClasses 缓存中
            wrappers.add(clazz);
            System.out.println(String.format("[loadClass isWrapperClass(clazz)] resourceURL:%s, clazz:%s", resourceURL, clazz.toString()));
        // 程序进入此分支，表明 clazz 是一个普通的拓展类
        } else {
        	// 检测 clazz 是否有默认的构造方法，如果没有，则抛出异常
            clazz.getConstructor();
            if (name == null || name.length() == 0) {
            	// 如果 name 为空，则尝试从 Extension 注解中获取 name，或使用小写的类名作为 name
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }
            // 切分 name
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {
                	// 如果类上有 Activate 注解，则使用 names 数组的第一个元素作为键，
                    // 存储 name 到 Activate 注解对象的映射关系
                    cachedActivates.put(names[0], activate);
                    System.out.println(String.format("[loadClass cachedActivates.put(names[0], activate)] resourceURL:%s, names[0]:%s, activate:%s", resourceURL, names[0], activate));
                }
                for (String n : names) {
                    if (!cachedNames.containsKey(clazz)) {
                    	// 存储 Class 到名称的映射关系
                        cachedNames.put(clazz, n);
                        System.out.println(String.format("[loadClass cachedNames.put(clazz, n)] resourceURL:%s, clazz:%s, n:%s", resourceURL, clazz, n));
                    }
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                    	// 存储名称到 Class 的映射关系
                        extensionClasses.put(n, clazz);
                        System.out.println(String.format("[loadClass extensionClasses.put(n, clazz)] resourceURL:%s, n:%s, clazz:%s", resourceURL, n, clazz));
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * <ol>
     * <li>
     * 调用 getAdaptiveExtensionClass 方法获取自适应拓展 Class 对象
     * <li>
	 * 通过反射进行实例化
	 * <li>
	 * 调用 injectExtension 方法向拓展实例中注入依赖
	 * </ol>
	 * 
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
        	/* 
        	 * 获取自适应拓展类，并通过反射实例化。
        	 * 手工编码的自适应拓展中可能存在着一些依赖，而自动生成的 Adaptive 拓展则不会依赖其他类。
        	 * 这里调用 injectExtension 方法的目的是为手工编码的自适应拓展注入依赖。
        	 */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * <pre>
     * 1.调用 getExtensionClasses 获取所有的拓展类
     * 2.检查缓存，若缓存不为空，则返回缓存
     * 3.若缓存为空，则调用 createAdaptiveExtensionClass 创建自适应拓展类
     * </pre>
     * 
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
    	// 通过 SPI 获取所有的拓展类
        getExtensionClasses();
        // 检查缓存，若缓存不为空，则直接返回缓存。在获取实现类的过程中，如果某个实现类被 Adaptive 注解修饰了，那么该类就会被赋值给 cachedAdaptiveClass 变量
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 创建自适应拓展类. 如果所有的实现类均未被 Adaptive 注解修饰，那么执行第三步逻辑，创建自适应拓展类。
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 用于生成自适应拓展类，该方法首先会生成自适应拓展类的源码，然后通过 Compiler 实例（Dubbo 默认使用 javassist 作为编译器）编译源码，得到代理类 Class 实例。
     * 
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
    	// 构建自适应拓展代码
        String code = createAdaptiveExtensionClassCode();
        ClassLoader classLoader = findClassLoader();
        // 获取编译器实现类
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 编译代码，生成 Class
        return compiler.compile(code, classLoader);
    }

    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();
        
        /*
         * 在生成代理类源码之前，首先会通过反射检测接口方法是否包含 Adaptive 注解。
         * 对于要生成自适应拓展的接口，Dubbo 要求该接口至少有一个方法被 Adaptive 注解修饰。
         * 若不满足此条件，就会抛出运行时异常。
         */
        // 通过反射获取所有的方法
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        // 遍历方法列表
        for (Method m : methods) {
        	// 检测方法上是否有 Adaptive 注解
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation)
        	// 若所有的方法上均无 Adaptive 注解，则抛出异常
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");

        /*
         * 通过 Adaptive 注解检测后，即可开始生成代码。
         * 代码生成的顺序与 Java 文件内容顺序一致，首先会生成 package 语句，然后生成 import 语句，紧接着生成类名等代码。
         */
        // 生成 package 代码：package + type 所在包
        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        // 生成 import 代码：import + ExtensionLoader 全限定名
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        // 生成类代码：public class + type简单名称 + $Adaptive + implements + type全限定名 + {
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");
// 以 Dubbo 的 Protocol 接口为例，生成的代码如下：
//        package com.alibaba.dubbo.rpc;
//        import com.alibaba.dubbo.common.extension.ExtensionLoader;
//        public class Protocol$Adaptive implements com.alibaba.dubbo.rpc.Protocol {
//            // 省略方法代码
//        }
        
        // ${生成方法}
        /*
         * 一个方法可以被 Adaptive 注解修饰，也可以不被修饰。这里将未被 Adaptive 注解修饰的方法称为“无 Adaptive 注解方法”
         */
        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            // 如果方法上无 Adaptive 注解，则生成 throw new UnsupportedOperationException(...) 代码
            /*
             * 对于接口方法，我们可以按照需求标注 Adaptive 注解。
             * 以 Protocol 接口为例，该接口的 destroy 和 getDefaultPort 未标注 Adaptive 注解，其他方法均标注了 Adaptive 注解。
             * Dubbo 不会为没有标注 Adaptive 注解的方法生成代理逻辑，对于该种类型的方法，仅会生成一句抛出异常的代码。
             */
            if (adaptiveAnnotation == null) {
            	// 生成的代码格式如下：
                // throw new UnsupportedOperationException(
                //     "method " + 方法签名 + of interface + 全限定接口名 + is not adaptive method!”)
            	// throw new UnsupportedOperationException(
            	//     "method public abstract void com.alibaba.dubbo.rpc.Protocol.destroy() of interface com.alibaba.dubbo.rpc.Protocol is not adaptive method!");
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
            	/*
            	 * 获取 URL 数据。
            	 * 前面说过方法代理逻辑会从 URL 中提取目标拓展的名称，因此代码生成逻辑的一个重要的任务是从方法的参数列表或者其他参数中获取 URL 数据。
            	 * 举例说明一下，我们要为 Protocol 接口的 refer 和 export 方法生成代理逻辑。在运行时，通过反射得到的方法定义大致如下：
            	 * Invoker refer(Class<T> arg0, URL arg1) throws RpcException;
            	 * Exporter export(Invoker<T> arg0) throws RpcException;
            	 * 对于 refer 方法，通过遍历 refer 的参数列表即可获取 URL 数据，这个还比较简单。
            	 * 对于 export 方法，获取 URL 数据则要麻烦一些。export 参数列表中没有 URL 参数，因此需要从 Invoker 参数中获取 URL 数据。
            	 * 获取方式是调用 Invoker 中可返回 URL 的 getter 方法，比如 getUrl。如果 Invoker 中无相关 getter 方法，此时则会抛出异常。
            	 */
            	
                int urlTypeIndex = -1;
                // 遍历参数列表，确定 URL 参数位置
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                // urlTypeIndex != -1，表示参数列表中存在 URL 参数
                if (urlTypeIndex != -1) {
                    // Null Point check
                	// 为 URL 类型参数生成判空代码，格式如下：
                    // if (arg + urlTypeIndex == null) 
                    //     throw new IllegalArgumentException("url == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    // 为 URL 类型参数生成赋值代码，形如 URL url = arg1
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                // 参数列表中不存在 URL 类型参数
                else {
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                   	// 遍历方法的"参数类型列表"
                    for (int i = 0; i < pts.length; ++i) {
                    	// 获取某一类型参数的全部方法
                        Method[] ms = pts[i].getMethods();
                        // 遍历方法列表，寻找可返回 URL 的 getter 方法
                        for (Method m : ms) {
                            String name = m.getName();
                            // 1. 方法名以 get 开头，或方法名大于3个字符
                            // 2. 方法的访问权限为 public
                            // 3. 非静态方法
                            // 4. 方法参数数量为0
                            // 5. 方法返回值类型为 URL
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                // 结束 for (int i = 0; i < pts.length; ++i) 循环
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                    	// 如果所有参数中均不包含可返回 URL 的 getter 方法，则抛出异常
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    // 为可返回 URL 的参数生成判空代码，格式如下：
                    // if (arg + urlTypeIndex == null) 
                    //     throw new IllegalArgumentException("参数全限定名 + argument == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    // 为 getter 方法返回的 URL 生成判空代码，格式如下：
                    // if (argN.getter方法名() == null) 
                    //     throw new IllegalArgumentException(参数全限定名 + argument getUrl() == null);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    // 生成赋值语句，格式如下：
                    // URL全限定名 url = argN.getter方法名()，比如 
                    // com.alibaba.dubbo.common.URL url = invoker.getUrl();
                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                } // did not find method's parameter in URL type
                
//              上面这段代码主要目的是为了获取 URL 数据，并为之生成判空和赋值代码。以 Protocol 的 refer 和 export 方法为例，上面的代码为它们生成如下内容（代码已格式化）：
//                  refer:
//                	if (arg1 == null) 
//                	    throw new IllegalArgumentException("url == null");
//                	com.alibaba.dubbo.common.URL url = arg1;
//
//                	export:
//                	if (arg0 == null) 
//                	    throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument == null");
//                	if (arg0.getUrl() == null) 
//                	    throw new IllegalArgumentException("com.alibaba.dubbo.rpc.Invoker argument getUrl() == null");
//                	com.alibaba.dubbo.common.URL url = arg0.getUrl();

                /*
                 * 获取 Adaptive 注解值。
                 * Adaptive 注解值 value 类型为 String[]，可填写多个值，默认情况下为空数组。
                 * 若 value 为非空数组，直接获取数组内容即可。若 value 为空数组，则需进行额外处理。
                 * 处理过程是将类名转换为字符数组，然后遍历字符数组，并将字符放入 StringBuilder 中。
                 * 若字符为大写字母，则向 StringBuilder 中添加点号，随后将字符变为小写存入 StringBuilder 中。
                 * 比如 LoadBalance 经过处理后，得到 load.balance。
                 */
                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                // value 为空数组
                if (value.length == 0) {
                	// 获取类名，并将类名转换为字符数组
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    // 遍历字节数组
                    for (int i = 0; i < charArray.length; i++) {
                    	// 检测当前字符是否为大写字母
                        if (Character.isUpperCase(charArray[i])) {
                            if (i != 0) {
                            	// 向 sb 中添加点号
                                sb.append(".");
                            }
                            // 将字符变为小写，并添加到 sb 中
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                        	// 添加字符到 sb 中
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                /*
                 * 检测 Invocation 参数。
                 * 此段逻辑是检测方法列表中是否存在 Invocation 类型的参数，若存在，则为其生成判空代码和其他一些代码。
                 */
                boolean hasInvocation = false;
                // 遍历参数类型列表
                for (int i = 0; i < pts.length; ++i) {
                	// 判断当前参数名称是否等于 com.alibaba.dubbo.rpc.Invocation
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                    	// 为 Invocation 类型参数生成判空代码
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        // 生成 getMethodName 方法调用代码，格式为：
                        //    String methodName = argN.getMethodName();
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        // 设置 hasInvocation 为 true
                        hasInvocation = true;
                        break;
                    }
                }
                
                /*
                 * 生成拓展名获取逻辑。
                 * 本段逻辑用于根据 SPI 和 Adaptive 注解值生成“获取拓展名逻辑”，同时生成逻辑也受 Invocation 类型参数影响，综合因素导致本段逻辑相对复杂
                 */

                // 设置默认拓展名，cachedDefaultName 源于 SPI 注解值，默认情况下，
                // SPI 注解值为空串，此时 cachedDefaultName = null。例如拓展类接口注解@SPI("netty")，则cachedDefaultName值为netty
                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                // 遍历 value，这里的 value 是 Adaptive 的注解值，2.2.3.3 节分析过 value 变量的获取过程。
                // 此处循环目的是生成从 URL 中获取拓展名的代码，生成的代码会赋值给 getNameCode 变量。
                // 注意这个循环的遍历顺序是由后向前遍历的。
                for (int i = value.length - 1; i >= 0; --i) {
                	// 当 i 为最后一个元素的坐标时
                    if (i == value.length - 1) {
                    	// 默认拓展名非空
                        if (null != defaultExtName) {
                        	// protocol 是 url 的一部分，可通过 getProtocol 方法获取，其他的则是从
                            // URL 参数中获取。因为获取方式不同，所以这里要判断 value[i] 是否为 protocol
                            if (!"protocol".equals(value[i]))
                            	// hasInvocation 用于标识方法参数列表中是否有 Invocation 类型参数
                                if (hasInvocation)
                                	// 生成的代码功能等价于下面的代码：
                                    //   url.getMethodParameter(methodName, value[i], defaultExtName)
                                    // 以 LoadBalance 接口的 select 方法为例，最终生成的代码如下：
                                    //   url.getMethodParameter(methodName, "loadbalance", "random")
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                	// 生成的代码功能等价于下面的代码：
        	                        //   url.getParameter(value[i], defaultExtName)
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                            	// 生成的代码功能等价于下面的代码：
                                // ( url.getProtocol() == null ? defaultExtName : url.getProtocol() )
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        // 默认拓展名为空
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                	// 生成代码格式同上
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                	// 生成的代码功能等价于下面的代码：
        	                        // url.getParameter(value[i])
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                            	// 生成从 url 中获取协议的代码，比如 "dubbo"
                                getNameCode = "url.getProtocol()";
                        }
                    // 当 i 为非最后一个元素的坐标时
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                            	// 生成代码格式同上
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                            	// 生成的代码功能等价于下面的代码：
        	                    //   url.getParameter(value[i], getNameCode)
        	                    // 以 Transporter 接口的 connect 方法为例，最终生成的代码如下：
        	                    //   url.getParameter("client", url.getParameter("transporter", "netty"))
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                        	// 生成的代码功能等价于下面的代码：
                            //   url.getProtocol() == null ? getNameCode : url.getProtocol()
                            // 以 Protocol 接口的 connect 方法为例，最终生成的代码如下：
                            //   url.getProtocol() == null ? "dubbo" : url.getProtocol()
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                // 生成 extName 赋值代码
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                // 生成 extName 判空代码
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                /*
                 * 生成拓展加载与目标方法调用逻辑。
                 * 本段代码逻辑用于根据拓展名加载拓展实例，并调用拓展实例的目标方法。
                 */
                // 生成拓展获取代码，格式如下：
                // type全限定名 extension = (type全限定名)ExtensionLoader全限定名
                //     .getExtensionLoader(type全限定名.class).getExtension(extName);
                // Tips: 格式化字符串中的 %<s 表示使用前一个转换符所描述的参数，即 type 全限定名
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                // 如果方法返回值类型非 void，则生成 return 语句。
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                // 生成目标方法调用逻辑，格式为：
                // extension.方法名(arg0, arg2, ..., argN);
                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
                
//                以 Protocol 接口举例说明，上面代码生成的内容如下：
//
//                com.alibaba.dubbo.rpc.Protocol extension = (com.alibaba.dubbo.rpc.Protocol) ExtensionLoader
//                    .getExtensionLoader(com.alibaba.dubbo.rpc.Protocol.class).getExtension(extName);
//                return extension.refer(arg0, arg1);
            } // if (adaptiveAnnotation != null)

            /*
             * 生成完整的方法。
             * 本节进行代码生成的收尾工作，主要用于生成方法定义的代码
             */
            // public + 返回值全限定名 + 方法名 + (
            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
            // 添加参数列表代码
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");
            // 添加异常抛出代码
            if (ets.length > 0) {
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
//            以 Protocol 的 refer 方法为例，上面代码生成的内容如下：
//
//            public com.alibaba.dubbo.rpc.Invoker refer(java.lang.Class arg0, com.alibaba.dubbo.common.URL arg1) {
//                // 方法体
//            }
        } // for (Method method : methods) {
        codeBuilder.append("\n}");
        
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}