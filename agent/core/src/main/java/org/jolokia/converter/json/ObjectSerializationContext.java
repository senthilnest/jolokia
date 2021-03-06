package org.jolokia.converter.json;

import java.util.*;

import org.jolokia.request.ValueFaultHandler;

/*
 * Copyright 2009-2011 Roland Huss
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * Context class for holding and counting limits. It can also take care
 * about cycles, i.e. the context knows when an object is visited the second time.
 *
 * @author roland
 */
class ObjectSerializationContext {

    // =============================================================================
    // Context used for detecting call loops and the like
    private static final Set<Class> SIMPLE_TYPES = new HashSet<Class>(Arrays.asList(
            String.class,
            Number.class,
            Long.class,
            Integer.class,
            Boolean.class,
            Date.class
    ));

    private Set   objectsInCallStack = new HashSet();
    private Stack callStack = new Stack();
    private Integer maxDepth;
    private Integer maxCollectionSize;
    private Integer maxObjects;

    private int objectCount = 0;
    private ValueFaultHandler valueFaultHandler;

    /**
     * Constructor for the stack context providing processing options
     *
     * @param pMaxDepth max serialization depth
     * @param pMaxCollectionSize max size of collections before they get truncated
     * @param pMaxObjects maximum number of objects
     * @param pValueFaultHandler handler for dealing with faults
     */
    ObjectSerializationContext(Integer pMaxDepth, Integer pMaxCollectionSize, Integer pMaxObjects, ValueFaultHandler pValueFaultHandler) {
        maxDepth = pMaxDepth;
        maxCollectionSize = pMaxCollectionSize;
        maxObjects = pMaxObjects;
        valueFaultHandler = pValueFaultHandler;
    }

    /**
     * Check, whether a given object has been already seen
     *
     * @param object to check
     * @return true if the object has been already visited
     */
    boolean alreadyVisited(Object object) {
        return objectsInCallStack.contains(object);
    }

    /**
     * Check whether the max depth of the call stack is exceeded
     *
     * @return true if the max depth limit has been reached
     */
    public boolean exceededMaxDepth() {
        return maxDepth != null && objectsInCallStack.size() > maxDepth;
    }

    /**
     * Check whether the number of extracted objects exceeds the number of maximum objects to extract
     *
     * @return true if the number of extracted objects exceeds the maximum number of objects
     */
    public boolean exceededMaxObjects() {
        return maxObjects != null && objectCount > maxObjects;
    }

    /**
     * Configured maximum size of collections
     *
     * @return the maximum collection size
     */
    public Integer getMaxCollectionSize() {
        return maxCollectionSize;
    }

    /**
     * The fault handler used for errors during serialization
     *
     * @return the value fault handler
     */
    public ValueFaultHandler getValueFaultHandler() {
        return valueFaultHandler;
    }

    // =====================================================
    // Tracking methods

    /**
     * Push a new object on the stack
     *
     * @param object to push
     */
    void push(Object object) {
        callStack.push(object);

        if (object != null && !SIMPLE_TYPES.contains(object.getClass())) {
            objectsInCallStack.add(object);
        }
        objectCount++;
    }

    /**
     * Remove an object from top of the call stack
     * @return the object popped
     */
    Object pop() {
        Object ret = callStack.pop();
        if (ret != null && !SIMPLE_TYPES.contains(ret.getClass())) {
            objectsInCallStack.remove(ret);
        }
        return ret;
    }
}
