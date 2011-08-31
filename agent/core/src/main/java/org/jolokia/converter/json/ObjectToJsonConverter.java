package org.jolokia.converter.json;


import java.lang.reflect.InvocationTargetException;
import java.util.*;

import javax.management.AttributeNotFoundException;

import org.jolokia.converter.object.StringToObjectConverter;
import org.jolokia.request.JmxRequest;
import org.jolokia.request.ValueFaultHandler;
import org.jolokia.util.*;
import org.json.simple.JSONObject;

import static org.jolokia.util.ConfigKey.*;

/*
 *  Copyright 2009-2010 Roland Huss
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
 * A converter which convert attribute and return values
 * into a JSON representation. It uses certain handlers for this which
 * are registered programatically in the constructor.
 *
 * Each handler gets a reference to this converter object so that it
 * can use it for a recursive solution of nested objects.
 *
 * @author roland
 * @since Apr 19, 2009
 */
public final class ObjectToJsonConverter {

    // List of dedicated handlers used for delegation in serialization/deserializatin
    private List<Extractor> handlers;

    private ArrayExtractor arrayExtractor;

    // Thread-Local set in order to prevent infinite recursions
    private ThreadLocal<StackContext> stackContextLocal = new ThreadLocal<StackContext>();

    // Used for converting string to objects when setting attributes
    private StringToObjectConverter stringToObjectConverter;

    private Integer hardMaxDepth,hardMaxCollectionSize,hardMaxObjects;

    // Definition of simplifiers
    private static final String SIMPLIFIERS_DEFAULT_DEF = "META-INF/simplifiers-default";
    private static final String SIMPLIFIERS_DEF = "META-INF/simplifiers";

    /**
     * New object-to-json converter
     *
     * @param pStringToObjectConverter used when setting values
     * @param pConfig configuration for setting limits
     * @param pSimplifyHandlers a bunch of simplifiers used for mangling the conversion result
     */
    public ObjectToJsonConverter(StringToObjectConverter pStringToObjectConverter,
                                 Map<ConfigKey,String> pConfig, Extractor... pSimplifyHandlers) {
        initLimits(pConfig);

        handlers = new ArrayList<Extractor>();

        // TabularDataExtractor must be before MapExtractor
        // since TabularDataSupport isa Map
        handlers.add(new TabularDataExtractor());
        handlers.add(new CompositeDataExtractor());

        // Collection handlers
        handlers.add(new ListExtractor());
        handlers.add(new MapExtractor());

        // Special, well known objects
        addSimplifiers(handlers,pSimplifyHandlers);

        // Special date handling
        handlers.add(new DateExtractor());

        // Must be last in handlers, used default algorithm
        handlers.add(new BeanExtractor());

        arrayExtractor = new ArrayExtractor();

        stringToObjectConverter = pStringToObjectConverter;
    }


    /**
     * Convert the return value to a JSON object.
     *
     * @param pValue the value to convert
     * @param pRequest the original request
     * @param pUseValueWithPath if set, use the path given within the request to extract the inner value.
     *        Otherwise, use the path directly
     * @return the converted value
     * @throws AttributeNotFoundException if within an path an attribute could not be found
     */
    public JSONObject convertToJson(Object pValue, JmxRequest pRequest, boolean pUseValueWithPath)
            throws AttributeNotFoundException {
        Stack<String> extraStack = pUseValueWithPath ? PathUtil.reversePath(pRequest.getPathParts()) : new Stack<String>();

        Object jsonResult = extractObjectWithContext(pRequest, pValue, extraStack, true);

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("value",jsonResult);
        jsonObject.put("request",pRequest.toJSON());
        return jsonObject;
    }


    /**
     * Handle a value which means to dive into the internal of a complex object
     * (if <code>pExtraArgs</code> is not null) and/or to convert
     * it to JSON (if <code>pJsonify</code> is true).
     *
     * @param pRequest request from which various processing
     *        parameters (like maxDepth, maxCollectionSize and maxObjects) are taken and put
     *        into context in order to influence the object traversal.
     * @param pValue value to extract from
     * @param pExtraArgs stack used for diving in to the value
     * @param pJsonify whether the result should be returned as an JSON object
     * @return extracted value, either natively or as JSON
     * @throws AttributeNotFoundException if during traversal an attribute is not found as specified in the stack
     */
    public Object extractObjectWithContext(JmxRequest pRequest, Object pValue, Stack<String> pExtraArgs, boolean pJsonify)
            throws AttributeNotFoundException {
        Object jsonResult;
        setupContext(pRequest);
        try {
            jsonResult = extractObject(pValue, pExtraArgs, pJsonify);
        } finally {
            clearContext();
        }
        return jsonResult;
    }

    /**
     * Related to {@link #extractObjectWithContext(JmxRequest, Object, Stack, boolean)} except that
     * it does not setup a context. This method is used from the
     * various extractors for recursively continuing the extraction
     *
     * @param pValue value to extract from
     * @param pExtraArgs stack for diving into the object
     * @param pJsonify whether a JSON representation {@link JSONObject}
     * @return extracted object either in native format or as {@link JSONObject}
     * @throws AttributeNotFoundException if an attribute is not found during traversal
     */
    public Object extractObject(Object pValue, Stack<String> pExtraArgs, boolean pJsonify)
            throws AttributeNotFoundException {
        StackContext stackContext = stackContextLocal.get();
        String limitReached = checkForLimits(pValue,stackContext);
        Stack<String> pathStack = pExtraArgs != null ? pExtraArgs : new Stack<String>();
        if (limitReached != null) {
            return limitReached;
        }
        try {
            stackContext.push(pValue);
            stackContext.incObjectCount();

            if (pValue == null) {
                return null;
            }

            if (pValue.getClass().isArray()) {
                // Special handling for arrays
                return arrayExtractor.extractObject(this,pValue,pathStack,pJsonify);
            }
            return callHandler(pValue, pathStack, pJsonify);
        } finally {
            stackContext.pop();
        }
    }

    /**
     * Set an value of an inner object
     *
     * @param pInner the inner object
     * @param pAttribute the attribute to set
     * @param pValue the value to set
     * @return the old value
     * @throws IllegalAccessException if the reflection code fails during setting of the value
     * @throws InvocationTargetException reflection error
     */
    public Object setObjectValue(Object pInner, String pAttribute, Object pValue)
            throws IllegalAccessException, InvocationTargetException {

        // Call various handlers depending on the type of the inner object, as is extract Object

        Class clazz = pInner.getClass();
        if (clazz.isArray()) {
            return arrayExtractor.setObjectValue(stringToObjectConverter,pInner,pAttribute,pValue);
        }
        Extractor handler = getExtractor(clazz);

        if (handler != null) {
            return handler.setObjectValue(stringToObjectConverter,pInner,pAttribute,pValue);
        } else {
            throw new IllegalStateException(
                    "Internal error: No handler found for class " + clazz + " for setting object value." +
                    " (object: " + pInner + ", attribute: " + pAttribute + ", value: " + pValue + ")");
        }
    }

    // =================================================================================

    /**
     * Get the length of an extracted collection, but not larger than the configured limit.
     *
     * @param originalLength the orginal length
     * @return the original length if is smaller than then the configured maximum length. Otherwise the
     *         maximum length is returned.
     */
    int getCollectionLength(int originalLength) {
        ObjectToJsonConverter.StackContext ctx = stackContextLocal.get();
        Integer maxSize = ctx.getMaxCollectionSize();
        if (maxSize != null && originalLength > maxSize) {
            return maxSize;
        } else {
            return originalLength;
        }
    }

    /**
     * Get the fault handler used for dealing with exceptions during value extraction.
     *
     * @return the fault handler
     */
    public ValueFaultHandler getValueFaultHandler() {
        ObjectToJsonConverter.StackContext ctx = stackContextLocal.get();
        return ctx.getValueFaultHandler();
    }

    /**
     * Check whether the number of extracted objects exceeds the number of maximum objects to extract
     *
     * @return true if the number of extracted objects exceeds the maximum number of objects
     */
    boolean exceededMaxObjects() {
        ObjectToJsonConverter.StackContext ctx = stackContextLocal.get();
        return ctx.getMaxObjects() != null && ctx.getObjectCount() > ctx.getMaxObjects();
    }

    /**
     * Clear the context used for counting objects and limits
     */
    void clearContext() {
        stackContextLocal.remove();
    }

    /**
     * Setup the context with hard limits
     */
    void setupContext() {
        setupContext(null);
    }

    /**
     * Setup the context with the limits given in the request or with the default limits if not. In all cases,
     * hard limits as defined in the servlet configuration are never exceeded.
     *
     * @param pRequest request from which to extract the limit parameters
     */
    void setupContext(JmxRequest pRequest) {
        if (pRequest != null) {
            Integer maxDepth = getLimit(pRequest.getProcessingConfigAsInt(ConfigKey.MAX_DEPTH),hardMaxDepth);
            Integer maxCollectionSize = getLimit(pRequest.getProcessingConfigAsInt(ConfigKey.MAX_COLLECTION_SIZE),hardMaxCollectionSize);
            Integer maxObjects = getLimit(pRequest.getProcessingConfigAsInt(ConfigKey.MAX_OBJECTS),hardMaxObjects);

            setupContext(maxDepth, maxCollectionSize, maxObjects, pRequest.getValueFaultHandler());
        } else {
            // Use defaults:
            setupContext(hardMaxDepth,hardMaxCollectionSize,hardMaxObjects,JmxRequest.THROWING_VALUE_FAULT_HANDLER);
        }
    }

    /**
     * Setup context with limits
     *
     * @param pMaxDepth maximum serialization level
     * @param pMaxCollectionSize maximum collection size
     * @param pMaxObjects maximum number of objects to serialize
     * @param pValueFaultHandler a value fault handler used during extraction
     */
    void setupContext(Integer pMaxDepth, Integer pMaxCollectionSize, Integer pMaxObjects,
                      ValueFaultHandler pValueFaultHandler) {
        StackContext stackContext = new StackContext(pMaxDepth,pMaxCollectionSize,pMaxObjects,pValueFaultHandler);
        stackContextLocal.set(stackContext);
    }

    // =================================================================================

    // Get the extractor for a certain class
    private Extractor getExtractor(Class pClazz) {
        for (Extractor handler : handlers) {
            if (handler.canSetValue() && handler.getType() != null && handler.getType().isAssignableFrom(pClazz)) {
                return handler;
            }
        }
        return null;
    }


    private void initLimits(Map<ConfigKey, String> pConfig) {
        // Max traversal depth
        if (pConfig != null) {
            hardMaxDepth = getNullSaveIntLimit(MAX_DEPTH.getValue(pConfig));

            // Max size of collections
            hardMaxCollectionSize = getNullSaveIntLimit(MAX_COLLECTION_SIZE.getValue(pConfig));

            // Maximum of overal objects returned by one traversal.
            hardMaxObjects = getNullSaveIntLimit(MAX_OBJECTS.getValue(pConfig));
        } else {
            hardMaxDepth = getNullSaveIntLimit(MAX_DEPTH.getDefaultValue());
            hardMaxCollectionSize = getNullSaveIntLimit(MAX_COLLECTION_SIZE.getDefaultValue());
            hardMaxObjects = getNullSaveIntLimit(MAX_OBJECTS.getDefaultValue());
        }
    }

    private Integer getNullSaveIntLimit(String pValue) {
        Integer ret = pValue != null ? Integer.parseInt(pValue) : null;
        // "0" is interpreted as no limit
        return (ret != null && ret == 0) ? null : ret;
    }


    private String checkForLimits(Object pValue, StackContext pStackContext) {
        Integer maxDepth = pStackContext.getMaxDepth();
        if (maxDepth != null && pStackContext.size() > maxDepth) {
            // We use its string representation
            return pValue.toString();
        }
        if (pValue != null && pStackContext.alreadyVisited(pValue)) {
            return "[Reference " + pValue.getClass().getName() + "@" + Integer.toHexString(pValue.hashCode()) + "]";
        }
        if (exceededMaxObjects()) {
            return "[Object limit exceeded]";
        }
        return null;
    }

    private Object callHandler(Object pValue, Stack<String> pExtraArgs, boolean pJsonify)
            throws AttributeNotFoundException {
        Class pClazz = pValue.getClass();
        for (Extractor handler : handlers) {
            if (handler.getType() != null && handler.getType().isAssignableFrom(pClazz)) {
                return handler.extractObject(this,pValue,pExtraArgs,pJsonify);
            }
        }
        throw new IllegalStateException(
                "Internal error: No handler found for class " + pClazz +
                    " (object: " + pValue + ", extraArgs: " + pExtraArgs + ")");
    }



    // Used for testing only. Hence final and package local
    ThreadLocal<StackContext> getStackContextLocal() {
        return stackContextLocal;
    }


    private Integer getLimit(Integer pReqValue, Integer pHardLimit) {
        if (pReqValue == null) {
            return pHardLimit;
        }
        if (pHardLimit != null) {
            return pReqValue > pHardLimit ? pHardLimit : pReqValue;
        } else {
            return pReqValue;
        }
    }


    // Simplifiers are added either explicitely or by reflection from a subpackage
    private void addSimplifiers(List<Extractor> pHandlers, Extractor[] pSimplifyHandlers) {
        if (pSimplifyHandlers != null && pSimplifyHandlers.length > 0) {
            pHandlers.addAll(Arrays.asList(pSimplifyHandlers));
        } else {
            // Add all
            pHandlers.addAll(ServiceObjectFactory.<Extractor>createServiceObjects(SIMPLIFIERS_DEFAULT_DEF, SIMPLIFIERS_DEF));
        }
    }


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

    /**
     * Context class for holding and counting limits. It can also take care
     * about cycles, i.e. the context knows when an object is visited the second time.
     */
    static class StackContext {

        private Set objectsInCallStack = new HashSet();
        private Stack callStack = new Stack();
        private Integer maxDepth;
        private Integer maxCollectionSize;
        private Integer maxObjects;

        private int objectCount = 0;
        private ValueFaultHandler valueFaultHandler;

        StackContext(Integer pMaxDepth, Integer pMaxCollectionSize, Integer pMaxObjects, ValueFaultHandler pValueFaultHandler) {
            maxDepth = pMaxDepth;
            maxCollectionSize = pMaxCollectionSize;
            maxObjects = pMaxObjects;
            valueFaultHandler = pValueFaultHandler;
        }

        void push(Object object) {
            callStack.push(object);

            if (object != null && !SIMPLE_TYPES.contains(object.getClass())) {
                objectsInCallStack.add(object);
            }
        }

        Object pop() {
            Object ret = callStack.pop();
            if (ret != null && !SIMPLE_TYPES.contains(ret.getClass())) {
                objectsInCallStack.remove(ret);
            }
            return ret;
        }

        boolean alreadyVisited(Object object) {
            return objectsInCallStack.contains(object);
        }

        public int size() {
            return objectsInCallStack.size();
        }

        public void setMaxDepth(Integer pMaxDepth) {
            maxDepth = pMaxDepth;
        }

        public Integer getMaxDepth() {
            return maxDepth;
        }


        public Integer getMaxCollectionSize() {
            return maxCollectionSize;
        }

        public void incObjectCount() {
            objectCount++;
        }

        public int getObjectCount() {
            return objectCount;
        }

        public Integer getMaxObjects() {
            return maxObjects;
        }

        public ValueFaultHandler getValueFaultHandler() {
            return valueFaultHandler;
        }
    }



}
