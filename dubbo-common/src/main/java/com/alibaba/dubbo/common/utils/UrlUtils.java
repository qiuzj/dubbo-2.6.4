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
package com.alibaba.dubbo.common.utils;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UrlUtils {

    /**
     * 解析address，并构造URL对象
     *
     * @param address 地址字符串，可能是以逗号分隔的多个地址
     * @param defaults 默认Map参数集合，用于在address缺少某个URL属性的情况下，进行默认值替代
     * @return
     */
    public static URL parseURL(String address, Map<String, String> defaults) {
        if (address == null || address.length() == 0) {
            return null;
        }
        String url;
        // 如果地址包含"://"，则直接赋给url
        if (address.indexOf("://") >= 0) {
            url = address;
        } else { // 如果地址不包含"://"，则使用逗号分隔adress为多个地址
            String[] addresses = Constants.COMMA_SPLIT_PATTERN.split(address); // 逗号分隔地址
            url = addresses[0]; // 取第一个地址，其他作为备用地址
            // 组装备用地址url += "?backup=url1,url2,url3..."
            if (addresses.length > 1) {
                StringBuilder backup = new StringBuilder();
                for (int i = 1; i < addresses.length; i++) {
                    if (i > 1) {
                        backup.append(",");
                    }
                    backup.append(addresses[i]);
                }
                url += "?" + Constants.BACKUP_KEY + "=" + backup.toString();
            }
        }
        // 获取协议protocol
        String defaultProtocol = defaults == null ? null : defaults.get("protocol");
        if (defaultProtocol == null || defaultProtocol.length() == 0) {
            defaultProtocol = "dubbo";
        }
        String defaultUsername = defaults == null ? null : defaults.get("username");
        String defaultPassword = defaults == null ? null : defaults.get("password");
        int defaultPort = StringUtils.parseInteger(defaults == null ? null : defaults.get("port"));
        String defaultPath = defaults == null ? null : defaults.get("path");
        Map<String, String> defaultParameters = defaults == null ? null : new HashMap<String, String>(defaults);
        // 为何移除这几项？
        if (defaultParameters != null) {
            defaultParameters.remove("protocol");
            defaultParameters.remove("username");
            defaultParameters.remove("password");
            defaultParameters.remove("host");
            defaultParameters.remove("port");
            defaultParameters.remove("path");
        }

        // 解析URL：protocol、username、password、host、port、path、parameters，封装并构造出alibaba的URL对象
        URL u = URL.valueOf(url);
        boolean changed = false;
        String protocol = u.getProtocol();
        String username = u.getUsername();
        String password = u.getPassword();
        String host = u.getHost();
        int port = u.getPort();
        String path = u.getPath();
        Map<String, String> parameters = new HashMap<String, String>(u.getParameters());
        /*
         * 以下主要是为了处理URL中缺少某一项属性，使用默认属性进行设置，最后重新构造出URL对象
         */
        if ((protocol == null || protocol.length() == 0) && defaultProtocol != null && defaultProtocol.length() > 0) {
            changed = true;
            protocol = defaultProtocol;
        }
        if ((username == null || username.length() == 0) && defaultUsername != null && defaultUsername.length() > 0) {
            changed = true;
            username = defaultUsername;
        }
        if ((password == null || password.length() == 0) && defaultPassword != null && defaultPassword.length() > 0) {
            changed = true;
            password = defaultPassword;
        }
        /*if (u.isAnyHost() || u.isLocalHost()) {
            changed = true;
            host = NetUtils.getLocalHost();
        }*/
        if (port <= 0) {
            if (defaultPort > 0) {
                changed = true;
                port = defaultPort;
            } else {
                changed = true;
                port = 9090;
            }
        }
        if (path == null || path.length() == 0) {
            if (defaultPath != null && defaultPath.length() > 0) {
                changed = true;
                path = defaultPath;
            }
        }
        if (defaultParameters != null && defaultParameters.size() > 0) {
            // 遍历传入的defaults集合，如果从address中解析出的URL中，不存在相应属性，则多defaults中获取并设置到URL的参数集合中
            for (Map.Entry<String, String> entry : defaultParameters.entrySet()) {
                String key = entry.getKey();
                String defaultValue = entry.getValue();
                if (defaultValue != null && defaultValue.length() > 0) {
                    String value = parameters.get(key);
                    if (value == null || value.length() == 0) {
                        changed = true;
                        parameters.put(key, defaultValue);
                    }
                }
            }
        }
        // 如果从address中解析出来的参数中，缺少任何一项，则将通过默认设置来赋值，这时changed将为true，需要重新构建URL
        if (changed) {
            u = new URL(protocol, username, password, host, port, path, parameters);
        }
        return u;
    }

    /**
     * 解析所有注册中心地址，并构造相应的URL对象，不存在的属性从defaults中获取并设置到URL对象的parameters对象中，
     * 最后构造出所有注册中心URL对象的集合列表。
     *
     * 解析单个 URL ，将 `defaults` 里的参数，合并到 `address` 中。
     * 合并的逻辑如下：
     * 我们可以把 `address` 认为是 url ；`defaults` 认为是 defaultURL 。
     * 若 url 有不存在的属性时，从 defaultURL 获得对应的属性，设置到 url 中。
     *
     * @param address
     * @param defaults
     * @return
     */
    public static List<URL> parseURLs(String address, Map<String, String> defaults) {
        if (address == null || address.length() == 0) {
            return null;
        }
        // 使用 "|" 或者 ";" 作为分隔符，设置多个注册中心分组。注意，一个注册中心集群是一个分组，而不是多个。
        String[] addresses = Constants.REGISTRY_SPLIT_PATTERN.split(address); // 分割多个注册中心地址
        if (addresses == null || addresses.length == 0) {
            return null; //here won't be empty
        }
        List<URL> registries = new ArrayList<URL>();
        // 解析每个注册中心地址，并构造URL对象添加到注册中心URL列表中
        for (String addr : addresses) {
            registries.add(parseURL(addr, defaults));
        }
        return registries;
    }

    public static Map<String, Map<String, String>> convertRegister(Map<String, Map<String, String>> register) {
        Map<String, Map<String, String>> newRegister = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : register.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, String> serviceUrls = entry.getValue();
            if (!serviceName.contains(":") && !serviceName.contains("/")) {
                for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                    String serviceUrl = entry2.getKey();
                    String serviceQuery = entry2.getValue();
                    Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                    String group = params.get("group");
                    String version = params.get("version");
                    //params.remove("group");
                    //params.remove("version");
                    String name = serviceName;
                    if (group != null && group.length() > 0) {
                        name = group + "/" + name;
                    }
                    if (version != null && version.length() > 0) {
                        name = name + ":" + version;
                    }
                    Map<String, String> newUrls = newRegister.get(name);
                    if (newUrls == null) {
                        newUrls = new HashMap<String, String>();
                        newRegister.put(name, newUrls);
                    }
                    newUrls.put(serviceUrl, StringUtils.toQueryString(params));
                }
            } else {
                newRegister.put(serviceName, serviceUrls);
            }
        }
        return newRegister;
    }

    public static Map<String, String> convertSubscribe(Map<String, String> subscribe) {
        Map<String, String> newSubscribe = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : subscribe.entrySet()) {
            String serviceName = entry.getKey();
            String serviceQuery = entry.getValue();
            if (!serviceName.contains(":") && !serviceName.contains("/")) {
                Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                String group = params.get("group");
                String version = params.get("version");
                //params.remove("group");
                //params.remove("version");
                String name = serviceName;
                if (group != null && group.length() > 0) {
                    name = group + "/" + name;
                }
                if (version != null && version.length() > 0) {
                    name = name + ":" + version;
                }
                newSubscribe.put(name, StringUtils.toQueryString(params));
            } else {
                newSubscribe.put(serviceName, serviceQuery);
            }
        }
        return newSubscribe;
    }

    public static Map<String, Map<String, String>> revertRegister(Map<String, Map<String, String>> register) {
        Map<String, Map<String, String>> newRegister = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : register.entrySet()) {
            String serviceName = entry.getKey();
            Map<String, String> serviceUrls = entry.getValue();
            if (serviceName.contains(":") || serviceName.contains("/")) {
                for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                    String serviceUrl = entry2.getKey();
                    String serviceQuery = entry2.getValue();
                    Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                    String name = serviceName;
                    int i = name.indexOf('/');
                    if (i >= 0) {
                        params.put("group", name.substring(0, i));
                        name = name.substring(i + 1);
                    }
                    i = name.lastIndexOf(':');
                    if (i >= 0) {
                        params.put("version", name.substring(i + 1));
                        name = name.substring(0, i);
                    }
                    Map<String, String> newUrls = newRegister.get(name);
                    if (newUrls == null) {
                        newUrls = new HashMap<String, String>();
                        newRegister.put(name, newUrls);
                    }
                    newUrls.put(serviceUrl, StringUtils.toQueryString(params));
                }
            } else {
                newRegister.put(serviceName, serviceUrls);
            }
        }
        return newRegister;
    }

    public static Map<String, String> revertSubscribe(Map<String, String> subscribe) {
        Map<String, String> newSubscribe = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : subscribe.entrySet()) {
            String serviceName = entry.getKey();
            String serviceQuery = entry.getValue();
            if (serviceName.contains(":") || serviceName.contains("/")) {
                Map<String, String> params = StringUtils.parseQueryString(serviceQuery);
                String name = serviceName;
                int i = name.indexOf('/');
                if (i >= 0) {
                    params.put("group", name.substring(0, i));
                    name = name.substring(i + 1);
                }
                i = name.lastIndexOf(':');
                if (i >= 0) {
                    params.put("version", name.substring(i + 1));
                    name = name.substring(0, i);
                }
                newSubscribe.put(name, StringUtils.toQueryString(params));
            } else {
                newSubscribe.put(serviceName, serviceQuery);
            }
        }
        return newSubscribe;
    }

    public static Map<String, Map<String, String>> revertNotify(Map<String, Map<String, String>> notify) {
        if (notify != null && notify.size() > 0) {
            Map<String, Map<String, String>> newNotify = new HashMap<String, Map<String, String>>();
            for (Map.Entry<String, Map<String, String>> entry : notify.entrySet()) {
                String serviceName = entry.getKey();
                Map<String, String> serviceUrls = entry.getValue();
                if (!serviceName.contains(":") && !serviceName.contains("/")) {
                    if (serviceUrls != null && serviceUrls.size() > 0) {
                        for (Map.Entry<String, String> entry2 : serviceUrls.entrySet()) {
                            String url = entry2.getKey();
                            String query = entry2.getValue();
                            Map<String, String> params = StringUtils.parseQueryString(query);
                            String group = params.get("group");
                            String version = params.get("version");
                            // params.remove("group");
                            // params.remove("version");
                            String name = serviceName;
                            if (group != null && group.length() > 0) {
                                name = group + "/" + name;
                            }
                            if (version != null && version.length() > 0) {
                                name = name + ":" + version;
                            }
                            Map<String, String> newUrls = newNotify.get(name);
                            if (newUrls == null) {
                                newUrls = new HashMap<String, String>();
                                newNotify.put(name, newUrls);
                            }
                            newUrls.put(url, StringUtils.toQueryString(params));
                        }
                    }
                } else {
                    newNotify.put(serviceName, serviceUrls);
                }
            }
            return newNotify;
        }
        return notify;
    }

    //compatible for dubbo-2.0.0
    public static List<String> revertForbid(List<String> forbid, Set<URL> subscribed) {
        if (forbid != null && !forbid.isEmpty()) {
            List<String> newForbid = new ArrayList<String>();
            for (String serviceName : forbid) {
                if (!serviceName.contains(":") && !serviceName.contains("/")) {
                    for (URL url : subscribed) {
                        if (serviceName.equals(url.getServiceInterface())) {
                            newForbid.add(url.getServiceKey());
                            break;
                        }
                    }
                } else {
                    newForbid.add(serviceName);
                }
            }
            return newForbid;
        }
        return forbid;
    }

    public static URL getEmptyUrl(String service, String category) {
        String group = null;
        String version = null;
        int i = service.indexOf('/');
        if (i > 0) {
            group = service.substring(0, i);
            service = service.substring(i + 1);
        }
        i = service.lastIndexOf(':');
        if (i > 0) {
            version = service.substring(i + 1);
            service = service.substring(0, i);
        }
        return URL.valueOf(Constants.EMPTY_PROTOCOL + "://0.0.0.0/" + service + "?"
                + Constants.CATEGORY_KEY + "=" + category
                + (group == null ? "" : "&" + Constants.GROUP_KEY + "=" + group)
                + (version == null ? "" : "&" + Constants.VERSION_KEY + "=" + version));
    }

    public static boolean isMatchCategory(String category, String categories) {
        if (categories == null || categories.length() == 0) {
            return Constants.DEFAULT_CATEGORY.equals(category);
        } else if (categories.contains(Constants.ANY_VALUE)) {
            return true;
        } else if (categories.contains(Constants.REMOVE_VALUE_PREFIX)) {
            return !categories.contains(Constants.REMOVE_VALUE_PREFIX + category);
        } else {
            return categories.contains(category);
        }
    }

    public static boolean isMatch(URL consumerUrl, URL providerUrl) {
        String consumerInterface = consumerUrl.getServiceInterface();
        String providerInterface = providerUrl.getServiceInterface();
        if (!(Constants.ANY_VALUE.equals(consumerInterface) || StringUtils.isEquals(consumerInterface, providerInterface)))
            return false;

        if (!isMatchCategory(providerUrl.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY),
                consumerUrl.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY))) {
            return false;
        }
        if (!providerUrl.getParameter(Constants.ENABLED_KEY, true)
                && !Constants.ANY_VALUE.equals(consumerUrl.getParameter(Constants.ENABLED_KEY))) {
            return false;
        }

        String consumerGroup = consumerUrl.getParameter(Constants.GROUP_KEY);
        String consumerVersion = consumerUrl.getParameter(Constants.VERSION_KEY);
        String consumerClassifier = consumerUrl.getParameter(Constants.CLASSIFIER_KEY, Constants.ANY_VALUE);

        String providerGroup = providerUrl.getParameter(Constants.GROUP_KEY);
        String providerVersion = providerUrl.getParameter(Constants.VERSION_KEY);
        String providerClassifier = providerUrl.getParameter(Constants.CLASSIFIER_KEY, Constants.ANY_VALUE);
        return (Constants.ANY_VALUE.equals(consumerGroup) || StringUtils.isEquals(consumerGroup, providerGroup) || StringUtils.isContains(consumerGroup, providerGroup))
                && (Constants.ANY_VALUE.equals(consumerVersion) || StringUtils.isEquals(consumerVersion, providerVersion))
                && (consumerClassifier == null || Constants.ANY_VALUE.equals(consumerClassifier) || StringUtils.isEquals(consumerClassifier, providerClassifier));
    }

    public static boolean isMatchGlobPattern(String pattern, String value, URL param) {
        if (param != null && pattern.startsWith("$")) {
            pattern = param.getRawParameter(pattern.substring(1));
        }
        return isMatchGlobPattern(pattern, value);
    }

    public static boolean isMatchGlobPattern(String pattern, String value) {
        if ("*".equals(pattern))
            return true;
        if ((pattern == null || pattern.length() == 0)
                && (value == null || value.length() == 0))
            return true;
        if ((pattern == null || pattern.length() == 0)
                || (value == null || value.length() == 0))
            return false;

        int i = pattern.lastIndexOf('*');
        // doesn't find "*"
        if (i == -1) {
            return value.equals(pattern);
        }
        // "*" is at the end
        else if (i == pattern.length() - 1) {
            return value.startsWith(pattern.substring(0, i));
        }
        // "*" is at the beginning
        else if (i == 0) {
            return value.endsWith(pattern.substring(i + 1));
        }
        // "*" is in the middle
        else {
            String prefix = pattern.substring(0, i);
            String suffix = pattern.substring(i + 1);
            return value.startsWith(prefix) && value.endsWith(suffix);
        }
    }

    public static boolean isServiceKeyMatch(URL pattern, URL value) {
        return pattern.getParameter(Constants.INTERFACE_KEY).equals(
                value.getParameter(Constants.INTERFACE_KEY))
                && isItemMatch(pattern.getParameter(Constants.GROUP_KEY),
                value.getParameter(Constants.GROUP_KEY))
                && isItemMatch(pattern.getParameter(Constants.VERSION_KEY),
                value.getParameter(Constants.VERSION_KEY));
    }

    /**
     * Check if the given value matches the given pattern. The pattern supports wildcard "*".
     *
     * @param pattern pattern
     * @param value   value
     * @return true if match otherwise false
     */
    static boolean isItemMatch(String pattern, String value) {
        if (pattern == null) {
            return value == null;
        } else {
            return "*".equals(pattern) || pattern.equals(value);
        }
    }
}