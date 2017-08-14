/*
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
package org.jctools.queues.atomic;

import org.jctools.queues.SpscArrayQueue;
import org.jctools.util.Pow2;
import org.jctools.util.RangeUtil;

import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.jctools.queues.atomic.LinkedAtomicArrayQueueUtil.allocate;
import static org.jctools.queues.atomic.LinkedAtomicArrayQueueUtil.calcElementOffset;
import static org.jctools.queues.atomic.LinkedAtomicArrayQueueUtil.lvElement;

public class SpscGrowableAtomicArrayQueue<E> extends BaseSpscLinkedAtomicArrayQueue<E> {
    private int maxQueueCapacity; // ignored by the unbounded implementation
    private long lookAheadStep;// ignored by the unbounded implementation
    public SpscGrowableAtomicArrayQueue(final int capacity) {
        this(Math.max(8, Pow2.roundToPowerOfTwo(capacity / 8)), capacity);
    }

    public SpscGrowableAtomicArrayQueue(final int chunkSize, final int capacity) {
        RangeUtil.checkGreaterThanOrEqual(capacity, 16, "capacity");
        // minimal chunk size of eight makes sure minimal lookahead step is 2
        RangeUtil.checkGreaterThanOrEqual(chunkSize, 8, "chunkSize");

        maxQueueCapacity = Pow2.roundToPowerOfTwo(capacity);
        int chunkCapacity = Pow2.roundToPowerOfTwo(chunkSize);
        RangeUtil.checkLessThan(chunkCapacity, maxQueueCapacity, "chunkCapacity");

        long mask = chunkCapacity - 1;
        // need extra element to point at next array
        AtomicReferenceArray<E> buffer = allocate(chunkCapacity + 1);
        producerBuffer = buffer;
        producerMask = mask;
        consumerBuffer = buffer;
        consumerMask = mask;
        producerBufferLimit = mask - 1; // we know it's all empty to start with
        adjustLookAheadStep(chunkCapacity);
        soProducerIndex(0L);// serves as a StoreStore barrier to support correct publication
    }

    protected final boolean offerColdPath(final AtomicReferenceArray<E> buffer, final long mask, final E e, final long index,
                                          final int offset) {
        final long lookAheadStep = this.lookAheadStep;
        // normal case, go around the buffer or resize if full (unless we hit max capacity)
        if (lookAheadStep > 0) {
            int lookAheadElementOffset = calcElementOffset(index + lookAheadStep, mask);
            // Try and look ahead a number of elements so we don't have to do this all the time
            if (null == lvElement(buffer, lookAheadElementOffset)) {
                producerBufferLimit = index + lookAheadStep - 1; // joy, there's plenty of room
                writeToQueue(buffer, e, index, offset);
                return true;
            }
            // we're at max capacity, can use up last element
            final int maxCapacity = maxQueueCapacity;
            if (mask + 1 == maxCapacity) {
                if (null == lvElement(buffer, offset)) {
                    writeToQueue(buffer, e, index, offset);
                    return true;
                }
                // we're full and can't grow
                return false;
            }
            // not at max capacity, so must allow extra slot for next buffer pointer
            if (null == lvElement(buffer, calcElementOffset(index + 1, mask))) { // buffer is not full
                writeToQueue(buffer, e, index, offset);
            } else {
                // allocate new buffer of same length
                final AtomicReferenceArray<E> newBuffer = allocate((int) (2*(mask +1) + 1));

                producerBuffer = newBuffer;
                producerMask = newBuffer.length() - 2;

                final int offsetInNew = calcElementOffset(index, producerMask);
                linkOldToNew(index, buffer, offset, newBuffer, offsetInNew, e);
                int newCapacity = (int) (producerMask + 1);
                if (newCapacity == maxCapacity) {
                    long currConsumerIndex = lvConsumerIndex();
                    // use lookAheadStep to store the consumer distance from final buffer
                    this.lookAheadStep = -(index - currConsumerIndex);
                    producerBufferLimit = currConsumerIndex + maxCapacity - 1;
                } else {
                    producerBufferLimit = index + producerMask - 1;
                    adjustLookAheadStep(newCapacity);
                }
            }
            return true;
        }
        // the step is negative (or zero) in the period between allocating the max sized buffer and the
        // consumer starting on it
        else {
            final long prevElementsInOtherBuffers = -lookAheadStep;
            // until the consumer starts using the current buffer we need to check consumer index to
            // verify size
            long currConsumerIndex = lvConsumerIndex();
            int size = (int) (index - currConsumerIndex);
            int maxCapacity = (int) mask+1; // we're on max capacity or we wouldn't be here
            if (size == maxCapacity) {
                // consumer index has not changed since adjusting the lookAhead index, we're full
                return false;
            }
            // if consumerIndex progressed enough so that current size indicates it is on same buffer
            long firstIndexInCurrentBuffer = producerBufferLimit - maxCapacity + prevElementsInOtherBuffers;
            if (currConsumerIndex >= firstIndexInCurrentBuffer) {
                // job done, we've now settled into our final state
                adjustLookAheadStep(maxCapacity);
            }
            // consumer is still on some other buffer
            else {
                // how many elements out of buffer?
                this.lookAheadStep = (int) (currConsumerIndex - firstIndexInCurrentBuffer);
            }
            producerBufferLimit = currConsumerIndex + maxCapacity;
            writeToQueue(buffer, e, index, offset);
            return true;
        }
    }


    private void adjustLookAheadStep(int capacity) {
        lookAheadStep = Math.min(capacity / 4, SpscArrayQueue.MAX_LOOK_AHEAD_STEP);
    }
}
