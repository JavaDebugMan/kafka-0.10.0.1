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
package org.apache.kafka.clients.producer.internals;

import java.util.Iterator;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.utils.CopyOnWriteMap;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class acts as a queue that accumulates records into {@link org.apache.kafka.common.record.MemoryRecords}
 * instances to be sent to the server.
 * <p>
 * The accumulator uses a bounded amount of memory and append calls will block when that memory is exhausted, unless
 * this behavior is explicitly disabled.
 * RecordAccumulator至少有一个业务线程即主线程和Sender线程并发操作,必须是线程安全的
 */
public final class RecordAccumulator {

    private static final Logger log = LoggerFactory.getLogger(RecordAccumulator.class);

    private volatile boolean closed;
    /**
     * 用于flush操作控制计数器
     */
    private final AtomicInteger flushesInProgress;
    /**
     * append操作计数器
     */
    private final AtomicInteger appendsInProgress;
    /**
     * 指定每个RecordBatch底层ByteBuffer的大小
     */
    private final int batchSize;
    /**
     * 压缩类型
     */
    private final CompressionType compression;
    private final long lingerMs;
    private final long retryBackoffMs;
    /**
     * 用于存放消息的 实现ByteBuffer复用的缓存数据结构对象
     */
    private final BufferPool free;
    private final Time time;
    /**
     * 保存了TopicPartition与RecordBatch队列的映射关系,类型是ConcurrentMap,是线程安全的集合,
     * 但其中的Deque是ArrayDeque型,是线程不安全的集合
     * 追加新消息或发送RecordBatch时,需要加锁同步
     * 每个Deque中都保存了发往对应TopicPartition的RecordBatch集合
     */
    private final ConcurrentMap<TopicPartition, Deque<RecordBatch>> batches;
    /**
     * 保存已被写入内存而为被Send线程处理的RecordBatch
     */
    private final IncompleteRecordBatches incomplete;
    // The following variables are only accessed by the sender thread, so we don't need to protect them.
    /**
     * 未发送完成的RecordBatch集合
     * 已发送出去 但是没有收到响应
     */
    private final Set<TopicPartition> muted;
    /**
     * 使用drain()方法批量导出RecordBatch时,为了防止饥饿,使用drainIndex记录上次发送停止时的位置,下次继续从此位置开始发送
     */
    private int drainIndex;

    /**
     * Create a new record accumulator
     *
     * @param batchSize      The size to use when allocating {@link org.apache.kafka.common.record.MemoryRecords} instances
     * @param totalSize      The maximum memory the record accumulator can use.
     * @param compression    The compression codec for the records
     * @param lingerMs       An artificial delay time to add before declaring a records instance that isn't full ready for
     *                       sending. This allows time for more records to arrive. Setting a non-zero lingerMs will trade off some
     *                       latency for potentially better throughput due to more batching (and hence fewer, larger requests).
     * @param retryBackoffMs An artificial delay time to retry the produce request upon receiving an error. This avoids
     *                       exhausting all retries in a short period of time.
     * @param metrics        The metrics
     * @param time           The time instance to use
     */
    public RecordAccumulator(int batchSize,
                             long totalSize,
                             CompressionType compression,
                             long lingerMs,
                             long retryBackoffMs,
                             Metrics metrics,
                             Time time) {
        this.drainIndex = 0;
        this.closed = false;
        this.flushesInProgress = new AtomicInteger(0);
        this.appendsInProgress = new AtomicInteger(0);
        this.batchSize = batchSize;
        this.compression = compression;
        this.lingerMs = lingerMs;
        this.retryBackoffMs = retryBackoffMs;
        this.batches = new CopyOnWriteMap<>();
        String metricGrpName = "producer-metrics";
        this.free = new BufferPool(totalSize, batchSize, metrics, time, metricGrpName);
        this.incomplete = new IncompleteRecordBatches();
        this.muted = new HashSet<>();
        this.time = time;
        registerMetrics(metrics, metricGrpName);
    }

    private void registerMetrics(Metrics metrics, String metricGrpName) {
        MetricName metricName = metrics.metricName("waiting-threads", metricGrpName, "The number of user threads blocked waiting for buffer memory to enqueue their records");
        Measurable waitingThreads = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.queued();
            }
        };
        metrics.addMetric(metricName, waitingThreads);

        metricName = metrics.metricName("buffer-total-bytes", metricGrpName, "The maximum amount of buffer memory the client can use (whether or not it is currently used).");
        Measurable totalBytes = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.totalMemory();
            }
        };
        metrics.addMetric(metricName, totalBytes);

        metricName = metrics.metricName("buffer-available-bytes", metricGrpName, "The total amount of buffer memory that is not being used (either unallocated or in the free list).");
        Measurable availableBytes = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.availableMemory();
            }
        };
        metrics.addMetric(metricName, availableBytes);

        Sensor bufferExhaustedRecordSensor = metrics.sensor("buffer-exhausted-records");
        metricName = metrics.metricName("buffer-exhausted-rate", metricGrpName, "The average per-second number of record sends that are dropped due to buffer exhaustion");
        bufferExhaustedRecordSensor.add(metricName, new Rate());
    }

    /**
     * append()方法主要逻辑:
     * * 1:首先在batches集合中查找TopicPartition对应的Deque,若查不到,则创建新的Deque,并添加到batched集合中
     * * 2:对Deque加锁(使用synchronized关键字加锁)
     * * 3:调用tryAppend()方法,尝试向Deque中最后一个RecordBatch追加Record
     * * 4:synchronized块结束,自动解锁
     * * 5:追加成功,则返回RecordAppendResult(其中封装了ProduceRequestResult)
     * * 6:追加失败,尝试从BufferPool中申请新的ByteBuffer
     * * 7:对Deque加锁(使用synchronized关键字加锁),再次尝试第3步
     * * 8:追加成功,则返回;失败,则使用第5步得到的ByteBuffer创建RecordBatch
     * * 9:将Record追加到新的RecordBatch中,并将新建的RecordBatch添加到对应的Deque尾部
     * * 10:将新建的RecordBatch追加到incomplete集合
     * * 11:synchronized块结束,自动解锁
     * * 12:返回RecordAppendResult,RecordAppendResult会中的字段为作为唤醒Sender线程的条件
     * <p>
     * 第2步和第6步对Deque加synchronized锁然后重试的原因,这里的Deque使用的事ArrayDeque(非线程安全) 所以需要加锁同步
     * 为什么分多个synchronized块而不一个完成的synchronized块中完成呢?
     * 主要是因为在向BufferPool申请新ByteBuffer的时候,可能会导致阻塞,假设在一个synchronized完成所有追加操作
     * 有可能出现:线程1发送的消息比较大,需要向BufferPool申请新空间,而此时BufferPool空间不足,线程1在BufferPool上等待,而他此时任然持有对应Deque的锁
     * 线程2发送的消息较小,Deque最后一个RecordBatch剩余空间足够,但是由于线程1未释放Deque的锁,所以也需要一起等待
     * 若线程2这样的线程较多就会造成很多不必要的线程阻塞降低了吞吐量 这里体现了"减少锁的持有时间"
     * <p>
     * 第二次加锁重试后 是为了防止多个线程并发向BufferPool申请空间后,造成内部碎片
     * <p>
     * <p>
     * <p>
     * Add a record to the accumulator, return the append result
     * <p>
     * The append result will contain the future metadata, and flag for whether the appended batch is full or a new batch is created
     * <p>
     *
     * @param tp             The topic/partition to which this record is being sent
     * @param timestamp      The timestamp of the record
     * @param key            The key for the record
     * @param value          The value for the record
     * @param callback       The user-supplied callback to execute when the request is complete
     * @param maxTimeToBlock The maximum time in milliseconds to block for buffer memory to be available
     */
    public RecordAppendResult append(TopicPartition tp,
                                     long timestamp,
                                     byte[] key,
                                     byte[] value,
                                     Callback callback,
                                     long maxTimeToBlock) throws InterruptedException {
        // We keep track of the number of appending thread to make sure we do not miss batches in
        // abortIncompleteBatches().
        //统计正在向RecordAccumulator中追加数据的线程数
        /**
         * 计数器为为了追踪正在进行的追加操作的线程数 以便当客户端在调用KafkaProducer.close()方法强制关闭发送消息操作时
         * sender调用消息累加器的abortIncompleteBatches()方法 放弃未处理完的请求 释放资源
         */
        appendsInProgress.incrementAndGet();
        try {
            // check if we have an in-progress batch
            //步骤1:查找TopicPartition对应的Deque
            Deque<RecordBatch> dq = getOrCreateDeque(tp);
            synchronized (dq) {//步骤2:对Deque对象加锁(保证了相同TopicPartition的append操作只能顺序执行
                // 当有一个线程正在进行append操作时 与之相同的TopicPartition的客户端就不能进行append操作 必须等待 这样就能保证写入同一个分区的数据在BufferPool是有序写入的)
                //边界检查
                if (closed)
                    throw new IllegalStateException("Cannot send after the producer is closed.");
                //步骤3:向Deque中最后一个RecordBatch追加Record
                RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
                if (appendResult != null)
                    return appendResult;//步骤5:追加成功则直返返回
            }//步骤4:synchronized块结束,自动解锁

            // we don't have an in-progress record batch try to allocate a new batch
            int size = Math.max(this.batchSize, Records.LOG_OVERHEAD + Record.recordSize(key, value));
            log.trace("Allocating a new {} byte message buffer for topic {} partition {}", size, tp.topic(), tp.partition());
            //步骤6:追加失败,从BufferPool申请新空间
            ByteBuffer buffer = free.allocate(size, maxTimeToBlock);
            synchronized (dq) {
                // Need to check if producer is closed again after grabbing the dequeue lock.
                if (closed)//边界检查
                    throw new IllegalStateException("Cannot send after the producer is closed.");
                //步骤7:对Deque枷锁,再次调用tryAppend()方法尝试追加Record
                RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
                if (appendResult != null) {//步骤8:追加成功,则返回
                    // Somebody else found us a batch, return the one we waited for! Hopefully this doesn't happen often...
                    free.deallocate(buffer);//释放步骤7申请的新空间
                    return appendResult;
                }
                MemoryRecords records = MemoryRecords.emptyRecords(buffer, compression, this.batchSize);
                RecordBatch batch = new RecordBatch(tp, records, time.milliseconds());
                //步骤9:在新建的RecordBatch中追加Record,并将其追加到batches集合中
                FutureRecordMetadata future = Utils.notNull(batch.tryAppend(timestamp, key, value, callback, time.milliseconds()));
                //步骤10:将新建的RecordBatch追加到incomplete集合
                dq.addLast(batch);
                incomplete.add(batch);
                //步骤12:返回RecordAppendResult
                return new RecordAppendResult(future, dq.size() > 1 || batch.records.isFull(), true);
            }//步骤11:synchronized块结束,自动解锁
        } finally {
            appendsInProgress.decrementAndGet();
        }
    }

    /**
     * 会查找batches集合中对应队列的最后一个RecordBatch对象,并调用其tryAppend()方法完成消息追加
     * If `RecordBatch.tryAppend` fails (i.e. the record batch is full), close its memory records to release temporary
     * resources (like compression streams buffers).
     */
    private RecordAppendResult tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, Deque<RecordBatch> deque) {
        RecordBatch last = deque.peekLast();
        if (last != null) {
            FutureRecordMetadata future = last.tryAppend(timestamp, key, value, callback, time.milliseconds());
            if (future == null)
                last.records.close();
            else
                return new RecordAppendResult(future, deque.size() > 1 || last.records.isFull(), false);
        }
        return null;
    }

    /**
     * Abort the batches that have been sitting in RecordAccumulator for more than the configured requestTimeout
     * due to metadata being unavailable
     */
    public List<RecordBatch> abortExpiredBatches(int requestTimeout, long now) {
        List<RecordBatch> expiredBatches = new ArrayList<>();
        int count = 0;
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            Deque<RecordBatch> dq = entry.getValue();
            TopicPartition tp = entry.getKey();
            // We only check if the batch should be expired if the partition does not have a batch in flight.
            // This is to prevent later batches from being expired while an earlier batch is still in progress.
            // Note that `muted` is only ever populated if `max.in.flight.request.per.connection=1` so this protection
            // is only active in this case. Otherwise the expiration order is not guaranteed.
            if (!muted.contains(tp)) {
                synchronized (dq) {
                    // iterate over the batches and expire them if they have been in the accumulator for more than requestTimeOut
                    RecordBatch lastBatch = dq.peekLast();
                    Iterator<RecordBatch> batchIterator = dq.iterator();
                    while (batchIterator.hasNext()) {
                        RecordBatch batch = batchIterator.next();
                        boolean isFull = batch != lastBatch || batch.records.isFull();
                        // check if the batch is expired
                        if (batch.maybeExpire(requestTimeout, retryBackoffMs, now, this.lingerMs, isFull)) {
                            expiredBatches.add(batch);
                            count++;
                            batchIterator.remove();
                            deallocate(batch);
                        } else {
                            // Stop at the first batch that has not expired.
                            break;
                        }
                    }
                }
            }
        }
        if (!expiredBatches.isEmpty())
            log.trace("Expired {} batches in accumulator", count);

        return expiredBatches;
    }

    /**
     * Re-enqueue the given record batch in the accumulator to retry
     */
    public void reenqueue(RecordBatch batch, long now) {
        batch.attempts++;
        batch.lastAttemptMs = now;
        batch.lastAppendTime = now;
        batch.setRetry();
        Deque<RecordBatch> deque = getOrCreateDeque(batch.topicPartition);
        synchronized (deque) {
            deque.addFirst(batch);
        }
    }

    /**
     * 在客户端将消息发送给客户端之前,会调用RecordAccumulator.ready()方法获取集群中符合发送消息条件的节点集合
     * * 这些条件是站在RecordAccumulator的角度对集群中的Node进行塞选的,具体的条件如下:
     * * <p>
     * * 1):Deque中有多个RecordBatch或是第一个RecordBatch已经满了
     * * 2):是否超时了
     * * 3):是否有其他线程在等待BufferPool释放空间(即BufferPool的空间耗尽了)
     * * 4):是否有线程正在等待flush操作完成
     * * 5):Sender线程准备关闭
     * * <p>
     * * 方法主要逻辑:
     * * 遍历batches集合中每个分区,首先查找当前分区所在Leader副本所在的Node,如果满足上述5个条件,则将此Node信息发送到readyNodes集合中
     * * 编译完成后返回ReadyCheckResult对象,其中记录了满足发送条件的Node的集合,在遍历过程中是否有找不到Leader副本的分区(也可认为是Metadata中当前的元数据过时了)
     * * ,下次调用ready()方法进行检查的时间间隔
     * * <p>
     * * RecordAccumulator.ready()方法得到readyNodes集合后,此集合还需要经过NetworkClient的过滤,才能得到最终的发送消息的Node集合
     * Get a list of nodes whose partitions are ready to be sent, and the earliest time at which any non-sendable
     * partition will be ready; Also return the flag for whether there are any unknown leaders for the accumulated
     * partition batches.
     * <p>
     * A destination node is ready to send data if:
     * <ol>
     * <li>There is at least one partition that is not backing off its send
     * <li><b>and</b> those partitions are not muted (to prevent reordering if
     * {@value org.apache.kafka.clients.producer.ProducerConfig#MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION}
     * is set to one)</li>
     * <li><b>and <i>any</i></b> of the following are true</li>
     * <ul>
     * <li>The record set is full</li>
     * <li>The record set has sat in the accumulator for at least lingerMs milliseconds</li>
     * <li>The accumulator is out of memory and threads are blocking waiting for data (in this case all partitions
     * are immediately considered ready).</li>
     * <li>The accumulator has been closed</li>
     * </ul>
     * </ol>
     */
    public ReadyCheckResult ready(Cluster cluster, long nowMs) {
        /**
         * 1:创建一个Set<Node>类型的readyNodes集合,该集合保存TopicPartition分区对应的Leader节点信息
         * 定义一个用于记录下次执行延迟等待时间的nextReadyCheckDelayMs变量 默认值是Long.MAX_VALUE
         * 根据BufferPool中维护的Deque<Condition>类型的双端队列waiters长度是否大于0来设置标志位exhausted的值
         * 该值用来标识是否将Leader节点添加到readyNodes集合 waiters是用来记录在申请分配空间时由于可分配内存不足而进行条件阻塞的Contition
         */
        //用来记录可以向哪些Node节点发送消息
        Set<Node> readyNodes = new HashSet<>();
        //记录下次需要调用ready()方法的时间间隔
        long nextReadyCheckDelayMs = Long.MAX_VALUE;
        //根据Metadata元数据中是否有找不到Leader副本的Topic
        boolean unknownLeadersExist = false;
        //是否有线程在阻塞等待BufferPool释放空间
        boolean exhausted = this.free.queued() > 0;
        //下面遍历batches,对其中每个分区的Leader副本所在的Node都进行判断
        /**
         * 2:遍历RecordAccumulator对象的batches 就batches中保存的每个分区TopicPartition对象以及该分区对应的双端队列deque分别进行如下处理
         * a):从Cluster元数据信息中查找该分区的leader副本对应的节点 如果leader副本节点不存在 说明该主题对应的metadata还未被加载
         * 将unknownLeadersExist = true
         * b):如果readyNodes集合中找不到该分区Leader副本所对应的节点,同时muted集合中也找不到该分区,则调至c进行处理,否则结束对该分区的处理,继续取batches中保存的下一个分区进行处理
         * c):从deque头部取出第一个RecordBatch 若第一个RecordBatch为空 则返回继续迭代 否则根据规则判断当前分区的leader副本对应的节点是否需要保存到readyNodes集合中
         * 如果不需要保存到readyNodes集合中 则设置nextReadyCheckDelayMs的值 否则将Leader副本对应节点保存到readyNodes集合中 返回继续迭代batches中的元素
         *
         * 这里讲规则定义如下(一下规则输出均为boolean型 同时在这里约定下文描述规则成立即表示规则输出为true)
         *
         *  规则一:判断RecordBatch是否被提交发送过,若满足提交重试次数attempts大于0 同时上次重试时间lastAttemptMs与重试间隔retryBackoffMs（即配置型${retry.backoff.ms}）
         * 大于当前时nowMs，则表示该RecordBatch已经被提交 标识字段backingOff的值标识该规则成立与否
         *
         * 规则二:deque队列长度大于1或是RecordBatch已满 用标志位full接受该规则输出
         *
         * 规则三:若规则一满足即backingOff=true 则设置变量timeToWaitMs为${retry.backoff.ms} 否则timeToWaitMs为${linger.mx}
         *
         * 根据以上规则来确定是否需要将Leader副本对应的节点保存到readyNodes集合中 若规则一不成立即标识当前RecordBatch没有被提交过
         * 那么readyNodes集合中肯定还有该Leader节点 则在判断以下表达式是否成立
         *    boolean sendable = full || expired || exhausted || closed || flushInProgress();
         *    若该表达式也成立 则将Leader添加到readyNodes集合中 其中标志位closed默认值为false close字段用于标识kafkaProducer是否已关闭
         *    在KafkaProducer中调用close()方法进行关闭操作时 RecordAccumulator执行close操作会将标志为close设置为true
         *    flushInprogress()方法当有线程正在等待进行flush操作时返回true 否则设置nextReadyCheckDelayMs值
         *    取timeToWaitMs与waitedTimeMs之差大于0之中较大者 然后继续迭代
         */
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            TopicPartition part = entry.getKey();
            Deque<RecordBatch> deque = entry.getValue();
            //查找分区的Leader副本所在的Node,根据cluster的信息检查Leader,若找不到Leader,肯定不能发送消息
            Node leader = cluster.leaderFor(part);
            if (leader == null) {
                //将没有leader副本的topic添加到集合
                unknownLeadersExist = true;
            } else if (!readyNodes.contains(leader) && !muted.contains(part)) {
                synchronized (deque) {
                    //只取Deque中的第一个RecordBatch
                    RecordBatch batch = deque.peekFirst();
                    if (batch != null) {
                        //通过计算得到下面5个条件
                        //消息没有重发且上次上次发送的时间+避退时间大于当前时间
                        boolean backingOff = batch.attempts > 0 && batch.lastAttemptMs + retryBackoffMs > nowMs;
                        long waitedTimeMs = nowMs - batch.lastAttemptMs;
                        long timeToWaitMs = backingOff ? retryBackoffMs : lingerMs;
                        long timeLeftMs = Math.max(timeToWaitMs - waitedTimeMs, 0);
                        boolean full = deque.size() > 1 || batch.records.isFull();//条件一
                        boolean expired = waitedTimeMs >= timeToWaitMs;//条件二 exhausted条件三  flushInProgress条件四  closed条件五
                        /**
                         *  1):Deque中有多个RecordBatch或是第一个RecordBatch已经满了
                         *  2):是否超时了(超过linger.ms)
                         *  3):是否有其他线程在等待BufferPool释放空间(即BufferPool的空间耗尽了)
                         *  4):是否有线程正在等待flush操作完成
                         *  5):Sender线程准备关闭
                         */
                        boolean sendable = full || expired || exhausted || closed || flushInProgress();
                        if (sendable && !backingOff) {
                            readyNodes.add(leader);
                        } else {
                            // Note that this results in a conservative estimate since an un-sendable partition may have
                            // a leader that will later be found to have sendable data. However, this is good enough
                            // since we'll just wake up and then sleep again for the remaining time.
                            //记录下次需要调用ready()方法检查的时间间隔
                            nextReadyCheckDelayMs = Math.min(timeLeftMs, nextReadyCheckDelayMs);
                        }
                    }
                }
            }
        }
        /**
         * 3:待batches中元素遍历完毕 将前面几步得到的readyNodes nextReadyCheckDelayMs unknownLeadersExist构造成一个
         * ReadyCheckResult对象返回
         */
        return new ReadyCheckResult(readyNodes, nextReadyCheckDelayMs, unknownLeadersExist);
    }

    /**
     * @return Whether there is any unsent record in the accumulator.
     */
    public boolean hasUnsent() {
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            Deque<RecordBatch> deque = entry.getValue();
            synchronized (deque) {
                if (!deque.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * RecordAccumulator.drain()方法会根据RecordAccumulator.ready()方法 Node集合获取要发送的消息,返回Map<Integer, List<RecordBatch>>集合
     * * ,key是NodeId,value是待发送的RecordBatch集合
     * * RecordAccumulator.drain()方法也是由Sender线程调用的.drain()方法的核心是进行映射的转换:
     * * 将RecordAccumulator记录的TopicPartition-->RecordBatch集合的映射,转换成了NodeId--->RecordBatch集合的映射
     * * 为什么需要进行这次转换?
     * * 在网络I/O方面,生产者是面向Node节点发送消息数据,它只建立的Node的连接并发送数据,而并不关心数据属于哪个TopicPartition
     * * 而在调用KafkaProducer的上层业务逻辑,则是按照TopicPartition的方式生产数据,
     * * 它只关心发送到哪个TopicPartition,而不关心这些TopicPartition在哪个Node几点上
     * * <p>
     * * Sender线程每次向每个Node节点至多发送一个ClientRequest请求,其中封装了追加到此Node节点上多个分区的消息
     * * 待请求到达服务端以后,由kafka服务端对其进行解析
     * Drain all the data for the given nodes and collate(核对) them into a list of batches that will fit within the specified
     * size on a per-node basis. This method attempts to avoid choosing the same topic-node over and over.
     *
     * @param cluster The current cluster metadata
     * @param nodes   The list of node to drain
     * @param maxSize The maximum number of bytes to drain
     * @param now     The current unix time in milliseconds
     * @return A list of {@link RecordBatch} for each node specified with total size less than the requested maxSize.
     */
    public Map<Integer, List<RecordBatch>> drain(Cluster cluster,
                                                 Set<Node> nodes,
                                                 int maxSize,
                                                 long now) {
        if (nodes.isEmpty())
            return Collections.emptyMap();
        //转换后的结果
        Map<Integer, List<RecordBatch>> batches = new HashMap<>();
        //遍历指定的ready node集合
        for (Node node : nodes) {
            int size = 0;
            //获取当前Node上的分区集合
            List<PartitionInfo> parts = cluster.partitionsForNode(node.id());
            List<RecordBatch> ready = new ArrayList<>();
            /* to make starvation(饥饿) less likely this loop doesn't start at 0 */
            /**
             * 记录要发送的RecordBatch
             * drainIndex是batches的下标,记录上次发送停止时的位置,下次继续从此位置开始发送,如果一直从索引0的队列开始发送
             * 可能会出现一直只发送前几个分区消息的情况,造成其他分区饥饿
             */
            int start = drainIndex = drainIndex % parts.size();
            do {
                //获取分区的详细情况
                PartitionInfo part = parts.get(drainIndex);
                TopicPartition tp = new TopicPartition(part.topic(), part.partition());
                // Only proceed if the partition has no in-flight batches.
                //当这个TopicPartition中已经有发出去但是还没有收到response的消息时
                if (!muted.contains(tp)) {
                    //获取对应的RecordBatch队列
                    Deque<RecordBatch> deque = getDeque(new TopicPartition(part.topic(), part.partition()));
                    if (deque != null) {
                        synchronized (deque) {
                            //获取队列中第一个RecordBatch
                            RecordBatch first = deque.peekFirst();
                            if (first != null) {
                                boolean backoff = first.attempts > 0 && first.lastAttemptMs + retryBackoffMs > now;
                                // Only drain the batch if it is not during backoff period.
                                if (!backoff) {
                                    //一个batch消息的请求比maxSize还大 这种情况很少 这个batch单独做一个request
                                    if (size + first.records.sizeInBytes() > maxSize && !ready.isEmpty()) {
                                        // there is a rare case that a single batch size is larger than the request size due
                                        // to compression; in this case we will still eventually send this batch in a single
                                        // request
                                        break;//数量已满,结束循环,一般是一个请求的大小
                                    } else {
                                        //从队列中获取一个RecordBatch,并将整个RecordBatch放到ready集合中
                                        //每个PartitionInfo只取一个RecordBatch
                                        RecordBatch batch = deque.pollFirst();
                                        //关闭Compressor几底层输出流,并将MemoryRecords设置为只读
                                        batch.records.close();
                                        size += batch.records.sizeInBytes();
                                        ready.add(batch);
                                        batch.drainedMs = now;
                                    }
                                }
                            }
                        }
                    }
                }
                //更新drainIndex
                this.drainIndex = (this.drainIndex + 1) % parts.size();
            } while (start != drainIndex);
            //记录NodeId与RecordBatch的对应关系
            batches.put(node.id(), ready);
        }
        //上面只从每个队列中取出一个RecordBatch放到ready集合中,这是为了防止饥饿,提高系统可用性
        return batches;
    }

    private Deque<RecordBatch> getDeque(TopicPartition tp) {
        return batches.get(tp);
    }

    /**
     * Get the deque for the given topic-partition, creating it if necessary.
     */
    private Deque<RecordBatch> getOrCreateDeque(TopicPartition tp) {
        Deque<RecordBatch> d = this.batches.get(tp);
        if (d != null)
            return d;
        d = new ArrayDeque<>();
        Deque<RecordBatch> previous = this.batches.putIfAbsent(tp, d);
        if (previous == null)
            return d;
        else
            return previous;
    }

    /**
     * Deallocate the record batch
     */
    public void deallocate(RecordBatch batch) {
        incomplete.remove(batch);
        free.deallocate(batch.records.buffer(), batch.records.initialCapacity());
    }

    /**
     * Are there any threads currently waiting on a flush?
     * <p>
     * package private for test
     */
    boolean flushInProgress() {
        return flushesInProgress.get() > 0;
    }

    /* Visible for testing */
    Map<TopicPartition, Deque<RecordBatch>> batches() {
        return Collections.unmodifiableMap(batches);
    }

    /**
     * Initiate the flushing of data from the accumulator...this makes all requests immediately ready
     */
    public void beginFlush() {
        this.flushesInProgress.getAndIncrement();
    }

    /**
     * Are there any threads currently appending messages?
     */
    private boolean appendsInProgress() {
        return appendsInProgress.get() > 0;
    }

    /**
     * Mark all partitions as ready to send and block until the send is complete
     */
    public void awaitFlushCompletion() throws InterruptedException {
        try {
            for (RecordBatch batch : this.incomplete.all())
                batch.produceFuture.await();
        } finally {
            this.flushesInProgress.decrementAndGet();
        }
    }

    /**
     * This function is only called when sender is closed forcefully. It will fail all the
     * incomplete batches and return.
     */
    public void abortIncompleteBatches() {
        // We need to keep aborting the incomplete batch until no thread is trying to append to
        // 1. Avoid losing batches.
        // 2. Free up memory in case appending threads are blocked on buffer full.
        // This is a tight loop but should be able to get through very quickly.
        do {
            abortBatches();
        } while (appendsInProgress());
        // After this point, no thread will append any messages because they will see the close
        // flag set. We need to do the last abort after no thread was appending in case there was a new
        // batch appended by the last appending thread.
        abortBatches();
        this.batches.clear();
    }

    /**
     * Go through incomplete batches and abort them.
     */
    private void abortBatches() {
        for (RecordBatch batch : incomplete.all()) {
            Deque<RecordBatch> dq = getDeque(batch.topicPartition);
            // Close the batch before aborting
            synchronized (dq) {
                batch.records.close();
                dq.remove(batch);
            }
            batch.done(-1L, Record.NO_TIMESTAMP, new IllegalStateException("Producer is closed forcefully."));
            deallocate(batch);
        }
    }

    public void mutePartition(TopicPartition tp) {
        muted.add(tp);
    }

    public void unmutePartition(TopicPartition tp) {
        muted.remove(tp);
    }

    /**
     * Close this accumulator and force all the record buffers to be drained
     */
    public void close() {
        this.closed = true;
    }

    /*
     * Metadata about a record just appended to the record accumulator
     */
    public final static class RecordAppendResult {
        public final FutureRecordMetadata future;
        public final boolean batchIsFull;
        public final boolean newBatchCreated;

        public RecordAppendResult(FutureRecordMetadata future, boolean batchIsFull, boolean newBatchCreated) {
            this.future = future;
            this.batchIsFull = batchIsFull;
            this.newBatchCreated = newBatchCreated;
        }
    }

    /*
     * The set of nodes that have at least one complete record batch in the accumulator
     */
    public final static class ReadyCheckResult {
        /**
         * 记录分区的Leader节点
         */
        public final Set<Node> readyNodes;
        /**
         * 记录下次执行需要等待的时间
         */
        public final long nextReadyCheckDelayMs;
        /**
         * 是否有没有找到Leader的分区
         */
        public final boolean unknownLeadersExist;

        public ReadyCheckResult(Set<Node> readyNodes, long nextReadyCheckDelayMs, boolean unknownLeadersExist) {
            this.readyNodes = readyNodes;
            this.nextReadyCheckDelayMs = nextReadyCheckDelayMs;
            this.unknownLeadersExist = unknownLeadersExist;
        }
    }

    /*
     * A threadsafe helper class to hold RecordBatches that haven't been ack'd yet
     */
    private final static class IncompleteRecordBatches {
        private final Set<RecordBatch> incomplete;

        public IncompleteRecordBatches() {
            this.incomplete = new HashSet<RecordBatch>();
        }

        public void add(RecordBatch batch) {
            synchronized (incomplete) {
                this.incomplete.add(batch);
            }
        }

        public void remove(RecordBatch batch) {
            synchronized (incomplete) {
                boolean removed = this.incomplete.remove(batch);
                if (!removed)
                    throw new IllegalStateException("Remove from the incomplete set failed. This should be impossible.");
            }
        }

        public Iterable<RecordBatch> all() {
            synchronized (incomplete) {
                return new ArrayList<>(this.incomplete);
            }
        }
    }

}
