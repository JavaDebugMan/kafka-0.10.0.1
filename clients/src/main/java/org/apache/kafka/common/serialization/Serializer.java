/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.apache.kafka.common.serialization;

import java.io.Closeable;
import java.util.Map;

/**
 * @param <T> Type to be serialized from.
 *            <p>
 *            A class that implements this interface is expected to have a constructor with no parameter.
 *            <p>
 *            AVRO序列化方式
 */
public interface Serializer<T> extends Closeable {

    /**
     * 在执行序列化操作之前的配置
     * <p>
     * Configure this class.
     *
     * @param configs configs in key/value pairs
     * @param isKey   whether is for key or value
     */
    public void configure(Map<String, ?> configs, boolean isKey);

    /**
     * 真正序列化成字节数组
     *
     * @param topic topic associated with data
     * @param data  typed data
     * @return serialized bytes
     */
    public byte[] serialize(String topic, T data);


    /**
     * Close this serializer.
     * This method has to be idempotent if the serializer is used in KafkaProducer because it might be called
     * multiple times.
     */
    @Override
    public void close();
}
