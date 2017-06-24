/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.util.Util;

abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    public SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    public SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    protected long nextValue = Sequence.INITIAL_VALUE;
    protected long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 * 
 * 适用于单生产者的场景，由于没有实现任何栅栏，使用多线程的生产者进行操作并不安全。
 * 
 * <p>
 * <p>Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore)
    {
        long nextValue = this.nextValue;

        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            if (doStore)
            {
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n) 
    {
        if (n < 1) //n表示此次生产者期望获取多少个序号，通常是1
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long nextValue = this.nextValue;

        long nextSequence = nextValue + n;  //生产者当前序号值+期望获取的序号数量后达到的序号值
        long wrapPoint = nextSequence - bufferSize; //减掉RingBuffer的总的buffer值，用于判断是否出现‘覆盖’
        long cachedGatingSequence = this.cachedValue;  //从后面代码分析可得：cachedValue就是缓存的消费者中最小序号值，他不是当前‘消费者中最小序号值’，而是上次程序进入到下面的if判定代码段是，被赋值的当时的‘消费者中最小序号值’
        		//这样做的好处在于：在判定是否出现覆盖的时候，不用每次都调用getMininumSequence计算‘消费者中的最小序号值’，从而节约开销。只要确保当生产者的节奏大于了缓存的cachedGateingSequence一个bufferSize时，从新获取一下 getMinimumSequence()即可。

        //(wrapPoint > cachedGatingSequence) ： 当生产者已经超过上一次缓存的‘消费者中最小序号值’（cachedGatingSequence）一个‘Ring’大小（bufferSize），需要重新获取cachedGatingSequence，避免当生产者一直在生产，但是消费者不再消费的情况下，出现‘覆盖’
        //(cachedGatingSequence > nextValue) ： 生产者和消费者均为顺序递增的，且生产者的seq“先于”消费者的seq，注意是‘先于’而不是‘大于’。当nextValue>Long.MAXVALUE时，nextValue+1就会变成负数，wrapPoint也会变成负数，这时候必然会是：cachedGatingSequence > nextValue
        //									 这个变化的过程会持续bufferSize个序号，这个区间，由于getMinimumSequence()得到的虽然是名义上的‘消费者中最小序号值’，但是不代表是走在‘最后面’的消费者
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue) 
        {
        	//将生产者的cursor值更新到主存，以便对所有的消费者线程可见。
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            //生产者停下来，等待消费者消费，知道‘覆盖’现象清除。
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                waitStrategy.signalAllWhenBlocking();
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            this.cachedValue = minSequence;
        }

        this.nextValue = nextSequence;

        return nextSequence;
    }
    

    public long nextInSimple(int n)
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        long nextValue = this.nextValue;

        long nextSequence = nextValue + n;
        long wrapPoint = nextSequence - bufferSize;
        long cachedGatingSequence = this.cachedValue;
        long minSequence;
        
        //1. 生产者此次取号后，会‘覆盖’（wrap）消费者未来得及消费的序号，生产者等待消费者消费，直至不再发生‘覆盖’。
        if(wrapPoint > cachedGatingSequence) {
        	  cursor.setVolatile(nextValue);  // StoreLoad fence

              while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
              {
                  waitStrategy.signalAllWhenBlocking();
                  LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
              }
              //给缓存的消费者最小序号赋予新值，以备下次循环使用
              this.cachedValue = minSequence;
              
        //2. 生产者此次取号后，不会发生‘覆盖’（wrap）
        } else {
        	//2.1 当前一次取号时缓存的‘消费者最小序号’ > 前一次取得序号 时，将当前生产者序号写回主存，并重新计算‘消费者最小序号’
        	if(cachedGatingSequence > nextValue) {
        		cursor.setVolatile(nextValue);  // StoreLoad fence
        		minSequence = Util.getMinimumSequence(gatingSequences, nextValue);      
        		//给缓存的消费者最小序号赋予新值，以备下次循环使用
                this.cachedValue = minSequence;
                
        	//2.2 当前一次取号时缓存的‘消费者最小序号’ <= 前一次取得序号 时，什么都不做
        	} else {
        		//do nothing
        	}
        }
        
        //将生产者的‘’
        this.nextValue = nextSequence;
        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(long sequence)
    {
        cursor.set(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        return sequence <= cursor.get();
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence;
    }
}
