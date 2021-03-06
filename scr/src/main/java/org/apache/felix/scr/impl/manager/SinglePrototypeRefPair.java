/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.felix.scr.impl.manager;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.felix.scr.impl.inject.ScrComponentContext;
import org.osgi.framework.ServiceReference;

/**
 * @version $Rev$ $Date$
 */
public class SinglePrototypeRefPair<S, T> extends AbstractPrototypeRefPair<S, T>
{
    private final AtomicReference<SimpleImmutableEntry<ScrComponentContext, T>> instance = new AtomicReference<>();

    public SinglePrototypeRefPair( ServiceReference<T> ref )
    {
        super(ref);
    }

    @Override
    public String toString()
    {
        return "[SinglePrototypeRefPair: ref: [" + getRef() + "] service: [" + getServiceObject(null) + "]]";
    }

    @Override
    public T getServiceObject(ScrComponentContext key) {
        return internalGetServiceObject(key, false);
    }

    @Override
    public boolean setServiceObject(ScrComponentContext key, T serviceObject) {
        return instance.compareAndSet(null, new SimpleImmutableEntry<>(key, serviceObject));
    }

    @Override
    protected T remove(ScrComponentContext key) {
        return internalGetServiceObject(key, true);
    }

    private T internalGetServiceObject(ScrComponentContext key, boolean remove) {
        SimpleImmutableEntry<ScrComponentContext, T> entry = instance.get();
        if (entry == null) {
            return null;
        }
        T result = key == null || entry.getKey().equals(key) ? entry.getValue() : null;
        if (remove && result != null) {
            instance.compareAndSet(entry, null);
        }
        return result;
    }

    @Override
    protected Collection<Entry<ScrComponentContext, T>> clearEntries() {
        Map.Entry<ScrComponentContext, T> entry = instance.getAndSet(null);
        return entry == null ? Collections.<Entry<ScrComponentContext, T>>emptyList() : Collections.singleton(entry);
    }

}
